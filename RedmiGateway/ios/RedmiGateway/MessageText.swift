import SwiftUI
import UIKit

/// The text of one SMS bubble: links, phone numbers, addresses and dates are coloured and
/// tappable, and any part of the message can be selected with handles, as in Apple's Messages.
///
/// SwiftUI's Text detects nothing in a plain String, and on iOS its text selection only copies the
/// whole message, so the bubble hosts a non-editable UITextView. Sizing it needs
/// UIViewRepresentable.sizeThatFits (iOS 16); on iOS 15 MessageBubble keeps the plain Text.
///
/// Phone-line links never reach iOS (MessageLinks): a tapped number calls through the Redmi, and
/// its long-press menu offers the Redmi's line and this app's conversation instead of Apple's
/// Phone and Messages on the iPhone's own dead line.
@available(iOS 16.0, *)
struct MessageText: UIViewRepresentable {
    let text: String
    let isIncoming: Bool

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> UITextView {
        // TextKit 1: its sizeThatFits returns the tight width a short bubble hugs.
        let view = UITextView(usingTextLayoutManager: false)
        view.isEditable = false
        // Data detectors only act in a text view that is selectable and not editable.
        view.isSelectable = true
        view.isScrollEnabled = false
        view.backgroundColor = .clear
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.font = UIFont.preferredFont(forTextStyle: .body)
        view.adjustsFontForContentSizeCategory = true
        var detectors: UIDataDetectorTypes = [.link, .phoneNumber, .address, .shipmentTrackingNumber, .flightNumber]
        // Dates: iOS 17 shows "Create Event" out of process. On iOS 16 that action asks this app for
        // calendar access, and Info.plist has no calendar usage text, so iOS would end the app.
        if #available(iOS 17.0, *) {
            detectors.insert(.calendarEvent)
        }
        view.dataDetectorTypes = detectors
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.setContentHuggingPriority(.required, for: .vertical)
        view.delegate = context.coordinator
        return view
    }

    func updateUIView(_ view: UITextView, context: Context) {
        if context.coordinator.styledIncoming != isIncoming {
            context.coordinator.styledIncoming = isIncoming
            view.textColor = isIncoming ? UIColor.label : UIColor.white
            // White handles and highlight on the coloured outgoing bubble, where tint blue vanishes.
            view.tintColor = isIncoming ? nil : UIColor.white
            view.linkTextAttributes = [
                .foregroundColor: isIncoming ? UIColor.link : UIColor.white,
                .underlineStyle: NSUnderlineStyle.single.rawValue
            ]
        }
        // Never reassign unchanged text: that would clear the person's selection and run the
        // detectors again on every update of the conversation.
        if view.text != text {
            view.text = text
        }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        var width = CGFloat.greatestFiniteMagnitude
        if let proposed = proposal.width, proposed.isFinite {
            width = proposed
        }
        let fitted = uiView.sizeThatFits(CGSize(width: width, height: CGFloat.greatestFiniteMagnitude))
        // Short texts hug, long ones wrap at the width the bubble row offers.
        return CGSize(width: min(fitted.width.rounded(.up), width), height: fitted.height.rounded(.up))
    }

    @MainActor
    final class Coordinator: NSObject, UITextViewDelegate {
        /// The style last applied, so updates leave the text view (and its selection) alone.
        var styledIncoming: Bool?

        // MARK: Links, iOS 17 and later

        @available(iOS 17.0, *)
        func textView(_ textView: UITextView, primaryActionFor textItem: UITextItem,
                      defaultAction: UIAction) -> UIAction? {
            guard case .link(let url) = textItem.content, let route = PhoneLink.route(for: url) else {
                return defaultAction
            }
            return UIAction(title: MessageLinks.title(for: route)) { _ in
                Task { @MainActor in MessageLinks.open(route) }
            }
        }

        /// Replaces the system menu of a number, whose Call and Send Message use the iPhone's own
        /// line and Apple's Messages. Other links keep the system menu.
        @available(iOS 17.0, *)
        func textView(_ textView: UITextView, menuConfigurationFor textItem: UITextItem,
                      defaultMenu: UIMenu) -> UITextItem.MenuConfiguration? {
            guard case .link(let url) = textItem.content, let route = PhoneLink.route(for: url) else {
                return UITextItem.MenuConfiguration(menu: defaultMenu)
            }
            guard let number = route.number else {
                GatewayConnection.shared.log("Message link: long-pressed phone link has no usable number; no menu")
                return nil
            }
            return UITextItem.MenuConfiguration(menu: MessageLinks.menu(for: number))
        }

        // MARK: Links before iOS 17 (iOS 17 calls the two methods above instead)

        func textView(_ textView: UITextView, shouldInteractWith url: URL, in characterRange: NSRange,
                      interaction: UITextItemInteraction) -> Bool {
            guard let route = PhoneLink.route(for: url) else { return true }
            // A long press (.presentActions) would offer Call, FaceTime and Send Message on the
            // iPhone's own line; only the tap is answered, through the Redmi.
            if interaction == .invokeDefaultAction {
                MessageLinks.open(route)
            }
            return false
        }

        // MARK: Selection menu

        /// The system menu for the selected part (Copy, Look Up, Translate, Share) plus one step
        /// to copy the whole message, which the old long-press offered.
        func textView(_ textView: UITextView, editMenuForTextIn range: NSRange,
                      suggestedActions: [UIMenuElement]) -> UIMenu? {
            let whole: String = textView.text ?? ""
            var children = suggestedActions
            children.append(UIAction(title: "Mesajın tamamını kopyala", image: UIImage(systemName: "doc.on.doc")) { _ in
                Task { @MainActor in UIPasteboard.general.string = whole }
            })
            return UIMenu(children: children)
        }
    }
}

/// What a phone-line link in a message does: the Redmi's line and this app's conversations.
///
/// The data detectors hand a detected number over as a tel: link. Addresses, dates, flight and
/// tracking numbers come with the detectors' own links and keep the system's action; re-reading
/// their text as a phone number would turn an all-digit cargo tracking number into a real call.
@MainActor
enum MessageLinks {
    static func title(for route: PhoneLink.Route) -> String {
        switch route {
        case .call: return "Redmi hattından ara"
        case .message: return "Mesaj gönder"
        case .unusable: return "Numara aranamıyor"
        }
    }

    static func open(_ route: PhoneLink.Route) {
        switch route {
        case .call(let number):
            GatewayConnection.shared.log("Message link: number tapped; calling through the Redmi")
            InAppDial.start(number)
        case .message(let number):
            GatewayConnection.shared.log("Message link: sms link tapped; opening the conversation here")
            AppRouter.shared.pending = .messages(number)
        case .unusable:
            GatewayConnection.shared.log("Message link: phone link without a usable number; kept from iOS")
        }
    }

    /// Long-press on a number: Messages' own menu, with the Redmi's line in place of the iPhone's.
    static func menu(for number: String) -> UIMenu {
        var actions: [UIMenuElement] = [
            UIAction(title: "Redmi hattından ara", image: UIImage(systemName: "phone")) { _ in
                Task { @MainActor in MessageLinks.open(.call(number)) }
            },
            UIAction(title: "Mesaj gönder", image: UIImage(systemName: "message")) { _ in
                Task { @MainActor in MessageLinks.open(.message(number)) }
            }
        ]
        // FaceTime runs over the Redmi's hotspot, not over a phone line, so it stays on offer.
        if !number.contains("*"), !number.contains("#") {
            if let video = URL(string: "facetime://\(number)") {
                actions.append(UIAction(title: "FaceTime", image: UIImage(systemName: "video")) { _ in
                    Task { @MainActor in UIApplication.shared.open(video, options: [:], completionHandler: nil) }
                })
            }
            if let audio = URL(string: "facetime-audio://\(number)") {
                actions.append(UIAction(title: "FaceTime sesli", image: UIImage(systemName: "phone.circle")) { _ in
                    Task { @MainActor in UIApplication.shared.open(audio, options: [:], completionHandler: nil) }
                })
            }
        }
        actions.append(UIAction(title: "Kopyala", image: UIImage(systemName: "doc.on.doc")) { _ in
            Task { @MainActor in UIPasteboard.general.string = number }
        })
        return UIMenu(title: PhoneIdentity.formatted(number), children: actions)
    }
}
