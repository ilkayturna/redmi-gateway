import Contacts
import ContactsUI
import SwiftUI

struct PhoneContact: Identifiable, Equatable {
    let id: String
    let name: String
    let numbers: [String]
    let thumbnail: Data?
    /// Contacts' label for each number ("cep", "ev", "iş"…), in the order of `numbers`.
    var labels: [String] = []

    /// The number's label as Contacts shows it, or "Telefon" when it has none.
    func label(for number: String) -> String {
        guard let index = numbers.firstIndex(of: number), index < labels.count, !labels[index].isEmpty else {
            return "Telefon"
        }
        return labels[index]
    }
}

enum PhoneIdentity {
    /// Comparable key for Turkish and international numbers: +90 532..., 0532... and 532... match.
    ///
    /// The Redmi mirrors this rule exactly for its notification threads (wave 1 contract, section 7),
    /// so it changes only together with the Redmi's copy.
    static func key(_ number: String) -> String {
        let digits = number.filter { $0.isNumber }
        if digits.isEmpty { return number.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }
        if digits.count == 12 && digits.hasPrefix("90") { return String(digits.suffix(10)) }
        if digits.count == 11 && digits.hasPrefix("0") { return String(digits.suffix(10)) }
        return digits
    }

    /// key(), plus the key of the same number written with "00" in place of "+": a contact saved
    /// as 0090 532... is the caller +90 532.... Only contact matching uses it; key() stays as the
    /// Redmi knows it.
    static func matchKeys(_ number: String) -> [String] {
        let primary = key(number)
        let digits = number.filter { $0.isNumber }
        guard digits.hasPrefix("00"), digits.count > 4 else { return [primary] }
        let plusForm = key("+" + String(digits.dropFirst(2)))
        return plusForm == primary ? [primary] : [primary, plusForm]
    }

    /// How a number is shown: Turkish numbers as 0532 123 45 67, 0212 345 67 89, 0850 222 00 00
    /// and 444 1 444; an international number written as one run of digits as +44 207 946 0958.
    /// Anything else (short codes, sender names, numbers already spaced) stays as received.
    static func formatted(_ number: String) -> String {
        let key = self.key(number)
        guard !key.isEmpty, key.allSatisfy({ $0.isASCII && $0.isNumber }) else { return number }
        if key.count == 10, let first = key.first, "23458".contains(first) {
            return grouped("0" + key, sizes: [4, 3, 2, 2])
        }
        if key.count == 7, key.hasPrefix("444") {
            return grouped(key, sizes: [3, 1, 3])
        }
        return international(number) ?? number
    }

    /// The keypad's number while it is typed, grouped the way formatted() shows the whole number.
    /// Codes with * or # and foreign numbers stay as typed.
    static func typing(_ raw: String) -> String {
        let allDigits = !raw.isEmpty && raw.allSatisfy { $0.isASCII && $0.isNumber }
        if allDigits, raw.hasPrefix("0"), !raw.hasPrefix("00"), raw.count <= 11 {
            return grouped(raw, sizes: [4, 3, 2, 2])
        }
        if allDigits, raw.hasPrefix("5"), raw.count <= 10 {
            return grouped(raw, sizes: [3, 3, 2, 2])
        }
        if raw.hasPrefix("+90") {
            let rest = String(raw.dropFirst(3))
            if !rest.isEmpty, rest.count <= 10, rest.allSatisfy({ $0.isASCII && $0.isNumber }) {
                return "+90 " + grouped(rest, sizes: [3, 3, 2, 2])
            }
        }
        return raw
    }

    private static func grouped(_ digits: String, sizes: [Int]) -> String {
        var parts: [String] = []
        var rest = Substring(digits)
        for size in sizes where !rest.isEmpty {
            parts.append(String(rest.prefix(size)))
            rest = rest.dropFirst(size)
        }
        if !rest.isEmpty { parts.append(String(rest)) }
        return parts.joined(separator: " ")
    }

    /// +CC followed by groups of three, for a number written as +4420... or 004420....
    private static func international(_ number: String) -> String? {
        let trimmed = number.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.allSatisfy({ $0 == "+" || ($0.isASCII && $0.isNumber) }) else { return nil }
        let digits = trimmed.filter { $0.isNumber }
        let national: String
        if trimmed.hasPrefix("+") {
            national = digits
        } else if digits.hasPrefix("00") {
            national = String(digits.dropFirst(2))
        } else {
            return nil
        }
        guard (8...15).contains(national.count) else { return nil }
        let codeLength = countryCodeLength(national)
        let code = String(national.prefix(codeLength))
        var parts: [String] = []
        var rest = national.dropFirst(codeLength)
        while rest.count > 4 {
            parts.append(String(rest.prefix(3)))
            rest = rest.dropFirst(3)
        }
        if !rest.isEmpty { parts.append(String(rest)) }
        return "+\(code) " + parts.joined(separator: " ")
    }

    /// The ITU country codes of two digits; 1 and 7 have one, every other code has three.
    private static let twoDigitCountryCodes: Set<String> = [
        "20", "27", "30", "31", "32", "33", "34", "36", "39", "40", "41", "43", "44", "45", "46", "47", "48", "49",
        "51", "52", "53", "54", "55", "56", "57", "58", "60", "61", "62", "63", "64", "65", "66",
        "81", "82", "84", "86", "90", "91", "92", "93", "94", "95", "98"
    ]

    private static func countryCodeLength(_ digits: String) -> Int {
        guard let first = digits.first else { return 0 }
        if first == "1" || first == "7" { return 1 }
        return twoDigitCountryCodes.contains(String(digits.prefix(2))) ? 2 : 3
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
    private var refetchScheduled = false
    private let kFavorites = "favorites_v2"
    private let kLegacyFavorites = "favorite_numbers"

    private init() {
        loadFavorites()
        // A contact added or renamed in Apple's Contacts app shows at once in Recents, Messages and
        // the call screen, as in Phone and Messages; it used to wait for the app to be killed.
        NotificationCenter.default.addObserver(forName: .CNContactStoreDidChange, object: nil, queue: .main) { _ in
            Task { @MainActor in PhoneContactDirectory.shared.contactsChanged() }
        }
        // A background launch leaves the list unread (no tab has appeared); it is read once the app
        // is on screen, where the full read costs nothing visible.
        NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { _ in
            Task { @MainActor in PhoneContactDirectory.shared.loadIfAuthorized() }
        }
    }

    /// Whether contacts can be read without asking: never ask from the background, where the
    /// prompt would wait unseen.
    nonisolated static var canReadWithoutPrompt: Bool {
        let status = CNContactStore.authorizationStatus(for: .contacts)
        if status == .authorized { return true }
        if #available(iOS 18.0, *) { return status == .limited }
        return false
    }

    /// Loads contacts when access was already granted; never asks.
    func loadIfAuthorized() {
        guard !hasLoaded, Self.canReadWithoutPrompt else { return }
        authorization = CNContactStore.authorizationStatus(for: .contacts)
        fetch()
    }

    /// Several changes often come together (an iCloud sync); one read after them is enough.
    private func contactsChanged() {
        guard hasLoaded, !refetchScheduled else { return }
        refetchScheduled = true
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            let directory = PhoneContactDirectory.shared
            directory.refetchScheduled = false
            directory.authorization = CNContactStore.authorizationStatus(for: .contacts)
            guard directory.canRead else {
                GatewayConnection.shared.log("Contacts changed, but access is no longer granted; not read again")
                return
            }
            directory.fetch()
        }
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

    func contact(for number: String) -> PhoneContact? {
        for key in PhoneIdentity.matchKeys(number) {
            if let contact = byKey[key] { return contact }
        }
        return nil
    }

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

    /// Numbers a notification has to look up itself because the list is not loaded: after a
    /// background relaunch (Bluetooth, a push) no tab has appeared, and the full read with photos
    /// is left for the foreground. Empty once the list is loaded, and without granted access.
    func numbersNeedingLookup(_ numbers: [String]) -> [String] {
        guard byKey.isEmpty, Self.canReadWithoutPrompt else { return [] }
        return Array(Set(numbers.filter { !$0.isEmpty }))
    }

    /// Names for the given numbers straight from the contact store, one query per number. Called
    /// off the main thread before a notification is posted.
    nonisolated static func lookUpNames(_ numbers: [String]) -> [String: String] {
        guard !numbers.isEmpty else { return [:] }
        let store = CNContactStore()
        let keys = [CNContactFormatter.descriptorForRequiredKeys(for: .fullName)]
        var names: [String: String] = [:]
        for number in numbers {
            let predicate = CNContact.predicateForContacts(matching: CNPhoneNumber(stringValue: number))
            guard let match = (try? store.unifiedContacts(matching: predicate, keysToFetch: keys))?.first,
                  let name = CNContactFormatter.string(from: match, style: .fullName), !name.isEmpty else { continue }
            names[number] = name
        }
        return names
    }

    /// One number's name while the list is not loaded yet (the Redmi's resolve_contact).
    func lookUpName(_ number: String) -> String? {
        let pending = numbersNeedingLookup([number])
        guard !pending.isEmpty else { return nil }
        return Self.lookUpNames(pending)[number]
    }

    /// The first contact whose number starts with what the keypad shows. Typed as 0532..., 532...
    /// or +90532..., it matches a number saved in any of those forms.
    func suggestion(forTyped typed: String) -> (name: String, number: String)? {
        let digits = typed.filter { $0.isASCII && $0.isNumber }
        guard digits.count >= 3 else { return nil }
        for contact in contacts {
            for number in contact.numbers {
                let key = PhoneIdentity.key(number)
                let all = number.filter { $0.isASCII && $0.isNumber }
                if all.hasPrefix(digits) || key.hasPrefix(digits) || ("0" + key).hasPrefix(digits) {
                    return (contact.name, number)
                }
            }
        }
        return nil
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
                var numbers: [String] = []
                var labels: [String] = []
                for labeled in contact.phoneNumbers {
                    let number = labeled.value.stringValue
                    let key = PhoneIdentity.key(number)
                    guard !key.isEmpty, seen.insert(key).inserted else { continue }
                    numbers.append(number)
                    labels.append(labeled.label.map { CNLabeledValue<CNPhoneNumber>.localizedString(forLabel: $0) } ?? "")
                }
                guard !numbers.isEmpty else { return }
                let name = CNContactFormatter.string(from: contact, style: .fullName) ?? numbers[0]
                entries.append(PhoneContact(id: contact.identifier, name: name, numbers: numbers,
                                            thumbnail: contact.thumbnailImageData, labels: labels))
            }
            entries.sort { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
            DispatchQueue.main.async {
                let directory = PhoneContactDirectory.shared
                directory.contacts = entries
                var index: [String: PhoneContact] = [:]
                for entry in entries {
                    for number in entry.numbers {
                        for key in PhoneIdentity.matchKeys(number) where index[key] == nil {
                            index[key] = entry
                        }
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

/// Apple's new-contact card with the number filled in, for a number from Recents that is not a
/// contact yet. Saving changes the contact store, and the directory reads it again by itself.
struct NewContactView: UIViewControllerRepresentable {
    let number: String
    let onDone: () -> Void

    func makeUIViewController(context: Context) -> UINavigationController {
        let contact = CNMutableContact()
        contact.phoneNumbers = [CNLabeledValue(label: CNLabelPhoneNumberMobile, value: CNPhoneNumber(stringValue: number))]
        let controller = CNContactViewController(forNewContact: contact)
        controller.contactStore = CNContactStore()
        controller.delegate = context.coordinator
        return UINavigationController(rootViewController: controller)
    }

    func updateUIViewController(_ uiViewController: UINavigationController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onDone: onDone) }

    final class Coordinator: NSObject, CNContactViewControllerDelegate {
        let onDone: () -> Void

        init(onDone: @escaping () -> Void) {
            self.onDone = onDone
        }

        func contactViewController(_ viewController: CNContactViewController, didCompleteWith contact: CNContact?) {
            onDone()
        }
    }
}
