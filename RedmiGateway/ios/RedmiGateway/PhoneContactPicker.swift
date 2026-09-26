import SwiftUI
import Contacts
import ContactsUI

struct PhoneContact: Identifiable {
    let id: String
    let name: String
    let numbers: [String]
}

enum PhoneIdentity {
    static func key(_ number: String) -> String {
        let digits = number.filter { $0.isNumber }
        if digits.isEmpty { return number.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }
        if digits.count == 12 && digits.hasPrefix("90") { return String(digits.suffix(10)) }
        if digits.count == 11 && digits.hasPrefix("0") { return String(digits.suffix(10)) }
        return digits
    }
}

@MainActor
final class PhoneContactDirectory: ObservableObject {
    static let shared = PhoneContactDirectory()

    @Published private(set) var contacts: [PhoneContact] = []
    @Published private(set) var authorization = CNContactStore.authorizationStatus(for: .contacts)
    private var namesByNumber: [String: String] = [:]
    private let store = CNContactStore()
    private var hasLoaded = false

    func ensureLoaded() {
        guard !hasLoaded else { return }
        authorization = CNContactStore.authorizationStatus(for: .contacts)
        switch authorization {
        case .authorized:
            fetchContacts()
        case .notDetermined:
            store.requestAccess(for: .contacts) { [weak self] granted, _ in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    self.authorization = CNContactStore.authorizationStatus(for: .contacts)
                    if granted { self.fetchContacts() }
                }
            }
        default:
            if #available(iOS 18.0, *), authorization == .limited {
                fetchContacts()
            }
        }
    }

    func reload() {
        hasLoaded = false
        ensureLoaded()
    }

    func name(for number: String) -> String? {
        namesByNumber[PhoneIdentity.key(number)]
    }

    func displayName(for number: String) -> String {
        name(for: number) ?? number
    }

    private func fetchContacts() {
        hasLoaded = true
        let store = self.store
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let keys: [CNKeyDescriptor] = [
                CNContactFormatter.descriptorForRequiredKeys(for: .fullName),
                CNContactPhoneNumbersKey as CNKeyDescriptor
            ]
            let request = CNContactFetchRequest(keysToFetch: keys)
            var entries: [PhoneContact] = []
            do {
                try store.enumerateContacts(with: request) { contact, _ in
                    let numbers = Array(Set(contact.phoneNumbers.map { $0.value.stringValue }))
                        .filter { !PhoneIdentity.key($0).isEmpty }
                    guard !numbers.isEmpty else { return }
                    let name = CNContactFormatter.string(from: contact, style: .fullName)
                        ?? numbers.first ?? "Bilinmeyen Kişi"
                    entries.append(PhoneContact(id: contact.identifier, name: name, numbers: numbers))
                }
            } catch {
                entries = []
            }
            entries.sort { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.contacts = entries
                self.namesByNumber = Dictionary(
                    entries.flatMap { entry in entry.numbers.map { (PhoneIdentity.key($0), entry.name) } },
                    uniquingKeysWith: { first, _ in first }
                )
            }
        }
    }
}

/// Uses Apple's contact picker so only the selected phone number leaves Contacts.
struct PhoneContactPicker: UIViewControllerRepresentable {
    let onSelect: (String) -> Void

    func makeUIViewController(context: Context) -> CNContactPickerViewController {
        let picker = CNContactPickerViewController()
        picker.delegate = context.coordinator
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
            if let number = contact.phoneNumbers.first?.value.stringValue {
                onSelect(number)
            }
        }

        func contactPicker(_ picker: CNContactPickerViewController, didSelect contactProperty: CNContactProperty) {
            if let number = contactProperty.value as? CNPhoneNumber {
                onSelect(number.stringValue)
            }
        }
    }
}
