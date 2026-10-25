import Contacts
import ContactsUI
import SwiftUI

struct PhoneContact: Identifiable, Equatable {
    let id: String
    let name: String
    let numbers: [String]
    let thumbnail: Data?
}

enum PhoneIdentity {
    /// Comparable key for Turkish and international numbers: +90 532..., 0532... and 532... match.
    static func key(_ number: String) -> String {
        let digits = number.filter { $0.isNumber }
        if digits.isEmpty { return number.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }
        if digits.count == 12 && digits.hasPrefix("90") { return String(digits.suffix(10)) }
        if digits.count == 11 && digits.hasPrefix("0") { return String(digits.suffix(10)) }
        return digits
    }

    /// 0532 123 45 67 style for Turkish mobile numbers; anything else as received.
    static func formatted(_ number: String) -> String {
        let key = self.key(number)
        guard key.count == 10, key.allSatisfy({ $0.isNumber }), key.hasPrefix("5") else { return number }
        let chars = Array(key)
        return "0\(String(chars[0..<3])) \(String(chars[3..<6])) \(String(chars[6..<8])) \(String(chars[8..<10]))"
    }
}

struct FavoriteEntry: Codable, Identifiable, Equatable {
    let number: String
    let name: String?
    var id: String { PhoneIdentity.key(number) }
}

/// The iPhone's own contacts, read on the iPhone only; nothing is sent to the Redmi.
@MainActor
final class PhoneContactDirectory: ObservableObject {
    static let shared = PhoneContactDirectory()

    @Published private(set) var contacts: [PhoneContact] = []
    @Published private(set) var authorization = CNContactStore.authorizationStatus(for: .contacts)
    @Published private(set) var favorites: [FavoriteEntry] = []

    private var byKey: [String: PhoneContact] = [:]
    private let store = CNContactStore()
    private var hasLoaded = false
    private let kFavorites = "favorites_v2"
    private let kLegacyFavorites = "favorite_numbers"

    private init() {
        loadFavorites()
    }

    /// Loads contacts, asking for access the first time. Only called from visible UI.
    func ensureLoaded() {
        guard !hasLoaded else { return }
        authorization = CNContactStore.authorizationStatus(for: .contacts)
        switch authorization {
        case .notDetermined:
            store.requestAccess(for: .contacts) { _, _ in
                Task { @MainActor in
                    let directory = PhoneContactDirectory.shared
                    directory.authorization = CNContactStore.authorizationStatus(for: .contacts)
                    directory.fetch()
                }
            }
        case .denied, .restricted:
            break
        default:
            fetch()
        }
    }

    func reload() {
        hasLoaded = false
        ensureLoaded()
    }

    var canRead: Bool {
        authorization != .denied && authorization != .restricted && authorization != .notDetermined
    }

    func contact(for number: String) -> PhoneContact? { byKey[PhoneIdentity.key(number)] }

    /// Contacts whose name contains the query, or one of whose numbers contains its digits.
    func matching(_ query: String) -> [PhoneContact] {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return contacts }
        let digits = text.filter { $0.isNumber }
        return contacts.filter { contact in
            contact.name.localizedCaseInsensitiveContains(text)
                || (!digits.isEmpty && contact.numbers.contains { $0.filter { $0.isNumber }.contains(digits) })
        }
    }
    func name(for number: String) -> String? { contact(for: number)?.name }

    func displayName(for number: String) -> String {
        if number.isEmpty { return "Gizli numara" }
        return name(for: number) ?? PhoneIdentity.formatted(number)
    }

    private func fetch() {
        hasLoaded = true
        let store = self.store
        DispatchQueue.global(qos: .userInitiated).async {
            let keys: [CNKeyDescriptor] = [
                CNContactFormatter.descriptorForRequiredKeys(for: .fullName),
                CNContactPhoneNumbersKey as CNKeyDescriptor,
                CNContactThumbnailImageDataKey as CNKeyDescriptor
            ]
            let request = CNContactFetchRequest(keysToFetch: keys)
            request.sortOrder = .userDefault
            var entries: [PhoneContact] = []
            try? store.enumerateContacts(with: request) { contact, _ in
                var seen = Set<String>()
                let numbers = contact.phoneNumbers.map { $0.value.stringValue }
                    .filter { !PhoneIdentity.key($0).isEmpty && seen.insert(PhoneIdentity.key($0)).inserted }
                guard !numbers.isEmpty else { return }
                let name = CNContactFormatter.string(from: contact, style: .fullName) ?? numbers[0]
                entries.append(PhoneContact(id: contact.identifier, name: name, numbers: numbers,
                                            thumbnail: contact.thumbnailImageData))
            }
            entries.sort { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
            DispatchQueue.main.async {
                let directory = PhoneContactDirectory.shared
                directory.contacts = entries
                var index: [String: PhoneContact] = [:]
                for entry in entries {
                    for number in entry.numbers where index[PhoneIdentity.key(number)] == nil {
                        index[PhoneIdentity.key(number)] = entry
                    }
                }
                directory.byKey = index
            }
        }
    }

    // MARK: - Favorites

    func isFavorite(_ number: String) -> Bool {
        favorites.contains { $0.id == PhoneIdentity.key(number) }
    }

    func toggleFavorite(number: String, name: String?) {
        if isFavorite(number) {
            favorites.removeAll { $0.id == PhoneIdentity.key(number) }
        } else {
            favorites.append(FavoriteEntry(number: number, name: name))
        }
        saveFavorites()
    }

    func removeFavorites(at offsets: IndexSet) {
        favorites.remove(atOffsets: offsets)
        saveFavorites()
    }

    func moveFavorites(from source: IndexSet, to destination: Int) {
        favorites.move(fromOffsets: source, toOffset: destination)
        saveFavorites()
    }

    private func loadFavorites() {
        let defaults = UserDefaults.standard
        if let data = defaults.data(forKey: kFavorites),
           let list = try? JSONDecoder().decode([FavoriteEntry].self, from: data) {
            favorites = list
        } else if let legacy = defaults.string(forKey: kLegacyFavorites),
                  let numbers = try? JSONDecoder().decode([String].self, from: Data(legacy.utf8)) {
            favorites = numbers.map { FavoriteEntry(number: $0, name: nil) }
            saveFavorites()
        }
    }

    private func saveFavorites() {
        if let data = try? JSONEncoder().encode(favorites) {
            UserDefaults.standard.set(data, forKey: kFavorites)
        }
    }
}

/// Contact photo, or initials on a tinted circle.
struct ContactAvatar: View {
    let name: String
    let thumbnail: Data?
    var size: CGFloat = 40

    var body: some View {
        Group {
            if let thumbnail, let image = UIImage(data: thumbnail) {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                ZStack {
                    Circle().fill(Color(UIColor.systemGray4))
                    if initials.isEmpty {
                        Image(systemName: "person.fill")
                            .font(.system(size: size * 0.45))
                            .foregroundColor(.white)
                    } else {
                        Text(initials)
                            .font(.system(size: size * 0.4, weight: .semibold, design: .rounded))
                            .foregroundColor(.white)
                    }
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }

    private var initials: String {
        let letters = name.split(separator: " ").prefix(2).compactMap { $0.first }.filter { $0.isLetter }
        return String(letters).uppercased()
    }
}

/// Apple's contact picker, so only the chosen number leaves Contacts.
struct PhoneContactPicker: UIViewControllerRepresentable {
    let onSelect: (String) -> Void

    func makeUIViewController(context: Context) -> CNContactPickerViewController {
        let picker = Self.makePicker()
        picker.delegate = context.coordinator
        return picker
    }

    static func makePicker() -> CNContactPickerViewController {
        let picker = CNContactPickerViewController()
        picker.displayedPropertyKeys = [CNContactPhoneNumbersKey]
        picker.predicateForEnablingContact = NSPredicate(format: "phoneNumbers.@count > 0")
        picker.predicateForSelectionOfProperty = NSPredicate(format: "key == %@", CNContactPhoneNumbersKey)
        return picker
    }

    func updateUIViewController(_ uiViewController: CNContactPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onSelect: onSelect) }

    final class Coordinator: NSObject, CNContactPickerDelegate {
        let onSelect: (String) -> Void

        init(onSelect: @escaping (String) -> Void) {
            self.onSelect = onSelect
        }

        func contactPicker(_ picker: CNContactPickerViewController, didSelect contact: CNContact) {
            if let number = contact.phoneNumbers.first?.value.stringValue { onSelect(number) }
        }

        func contactPicker(_ picker: CNContactPickerViewController, didSelect contactProperty: CNContactProperty) {
            if let number = contactProperty.value as? CNPhoneNumber { onSelect(number.stringValue) }
        }
    }
}
