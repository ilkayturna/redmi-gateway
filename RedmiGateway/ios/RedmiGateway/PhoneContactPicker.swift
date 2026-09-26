import SwiftUI
import ContactsUI

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
