## MSG-01 [iPhone app Messages UI (conversation list, conversation view, message bubbles, compose). iOS deployment target is 15.0 (RedmiGateway/ios/RedmiGateway.xcodeproj/project.pbxproj:254,281,297,319). The only user runs iOS 27.0 on an iPhone 15 Pro Max (docs/IOS-COKME-20261029.md:3). The bubble fix needs iOS 16+ APIs (UIViewRepresentable.sizeThatFits) and iOS 17+ APIs (UITextItem delegate). Either raise the target to 16.0, or keep the current Text as the `#available` else-branch.] high ios real=True fix_ok=True
**SMS bubble text is a plain SwiftUI Text, so links, numbers, e-mails, addresses and dates are never detected, coloured or tappable**

Impact: A URL in an SMS (bank link, cargo tracking, Turkcell campaign) is shown as black or white text. Tapping it does nothing, so the owner has to retype it in Safari.

Evidence: MessagesView.swift:280 renders `Text(message.body)`. A String argument picks the verbatim `Text<S: StringProtocol>` initializer, which never parses Markdown and never runs data detectors. SwiftUI Text only shows a link when it gets an AttributedString with `.link` runs, and none is built anywhere: a grep of RedmiGateway/ios/RedmiGateway for NSDataDetector, AttributedString, openURL, UITextView and dataDetectorTypes finds nothing. The colour is forced by MessagesView.swift:281 (`.primary` / `.white`). (RedmiGateway/ios/RedmiGateway/MessagesView.swift:280)

Native: Apple Messages underlines and colours URLs, phone numbers, e-mails, addresses, dates and tracking numbers. Tap opens them; long-press shows a preview and actions.

Fix: Replace MessagesView.swift:280-288 with a UIViewRepresentable `MessageText(text:isIncoming:)`. Keep the bubble padding and background in SwiftUI.

makeUIView:
  let v = UITextView(usingTextLayoutManager: false) // iOS 16+, TextKit 1 for predictable sizing; plain UITextView() on 15
  v.isEditable = false; v.isSelectable = true   // data detectors only fire when not editable AND selectable
  v.isScrollEnabled = false
  v.backgroundColor = .clear
  v.textContainerInset = .zero; v.textContainer.lineFragmentPadding = 0
  v.dataDetectorTypes = [.link, .phoneNumber, .address, .calendarEvent, .shipmentTrackingNumber, .flightNumber] // .link also yields mailto:
  v.font = .preferredFont(forTextStyle: .body); v.adjustsFontForContentSizeCategory = true
  v.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
  v.setContentHuggingPriority(.required, for: .vertical)
  v.delegate = context.coordinator   // see MSG-03

updateUIView:
  context.coordinator.parent = self
  v.textColor = isIncoming ? .label : .white
  v.linkTextAttributes = [.foregroundColor: isIncoming ? UIColor.link : UIColor.white, .underlineStyle: NSUnderlineStyle.single.rawValue]
  if v.text != text { v.text = text }  // never reassign unchanged text: it would wipe the person's selection and re-run detection on every composer keystroke

@available(iOS 16.0, *) sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
  let w = proposal.width ?? .greatestFiniteMagnitude
  let s = uiView.sizeThatFits(CGSize(width: w, height: .greatestFiniteMagnitude))
  return CGSize(width: min(ceil(s.width), w), height: ceil(s.height))  // short texts hug, long ones wrap
}

Bubble: MessageText(...).fixedSize(horizontal: false, vertical: true).padding(.horizontal, 13).padding(.vertical, 8).background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(bubbleColor)). Drop `.textSelection`, and do not add a SwiftUI .contextMenu on the bubble because it competes with UITextView's long-press.

On iOS 15, either raise IPHONEOS_DEPLOYMENT_TARGET to 16.0 or keep the current Text in the else branch of `if #available(iOS 16, *)`. A cheaper alternative (NSDataDetector to AttributedString `.link`, plus `.environment(\.openURL, OpenURLAction{...})`) would fix links only, not range selection (MSG-02).

Verifier: Confirmed. MessagesView.swift:280 is `Text(message.body)` with a String variable, so the verbatim StringProtocol initializer is used: no Markdown and no data detection. Line 281 forces `.primary`/`.white`. A grep of RedmiGateway/ios finds no NSDataDetector, AttributedString, openURL, UITextView, dataDetectorTypes or UIViewRepresentable, and no other code path renders SMS bodies. This is exactly the owner's complaint that links are not blue and cannot be opened. The deployment target is iOS 15.0 (project.pbxproj:254,281,297,319), so an iOS 16 gate or a target bump is needed, as the finding says.

Better fix: 

API notes: The API names are correct: UITextView(usingTextLayoutManager:) is iOS 16+, UIViewRepresentable.sizeThatFits(_:uiView:context:) is iOS 16+, and dataDetectorTypes .link/.phoneNumber/.address/.calendarEvent/.shipmentTrackingNumber/.flightNumber exist. Links only respond when isEditable=false and isSelectable=true. Two amendments are needed. (1) `.calendarEvent`: on iOS 16 the 'Create Event' action asks for calendar access, and Info.plist:1-71 has no NSCalendarsUsageDescription, so the app is killed by TCC. Either add NSCalendarsUsageDescription (iOS 17+ runs EKEventEditViewController out of process without a prompt) or leave out .calendarEvent. `.address` 'Add to Contacts' is covered by the existing NSContactsUsageDescription (Info.plist:30). (2) Set `v.tintColor` per bubble (for example .white on outgoing). Otherwise the selection highlight and handles are tint blue on the accentColor-blue outgoing bubble (MessagesView.swift:297) and cannot be seen. Keep the iOS 15 else-branch on the current Text. The cheaper NSDataDetector to AttributedString(.link) path is correct for iOS 15 (OpenURLAction is iOS 15+), but as stated it does not give range selection.

## MSG-02 [iPhone app Messages UI (conversation list, conversation view, message bubbles, compose). iOS deployment target is 15.0 (RedmiGateway/ios/RedmiGateway.xcodeproj/project.pbxproj:254,281,297,319). The only user runs iOS 27.0 on an iPhone 15 Pro Max (docs/IOS-COKME-20261029.md:3). The bubble fix needs iOS 16+ APIs (UIViewRepresentable.sizeThatFits) and iOS 17+ APIs (UITextItem delegate). Either raise the target to 16.0, or keep the current Text as the `#available` else-branch.] high ios real=True fix_ok=True
**Long-press can only copy the whole message: .textSelection(.enabled) on an iOS Text has no range selection**

Impact: The owner cannot select a word, a number or a code inside an SMS. Long-press offers Copy and Share for the entire body, so pasting an OTP or an IBAN means pasting the whole message and deleting text by hand.

Evidence: MessagesView.swift:288 applies `.textSelection(.enabled)` to the SwiftUI Text from line 280. On iOS, SwiftUI's textSelection on Text gives a context menu that copies or shares the entire string. There are no selection handles and no word or character range; range selection exists only on macOS. No UITextView or other selectable UIKit text exists anywhere in the iOS sources (grep found none). (RedmiGateway/ios/RedmiGateway/MessagesView.swift:288)

Native: In Apple Messages the user can select part of a message (word or range with handles) and copy just that.

Fix: Use the same `MessageText` UITextView from MSG-01. isSelectable=true gives long-press word selection with drag handles, double-tap to select a word, and the system edit menu (Copy, Look Up, Translate, Share).

To keep a one-step 'copy all', add to the coordinator:
@available(iOS 16.0, *) func textView(_ tv: UITextView, editMenuForTextIn range: NSRange, suggestedActions: [UIMenuElement]) -> UIMenu? { UIMenu(children: suggestedActions + [UIAction(title: "Mesajın tamamını kopyala", image: UIImage(systemName: "doc.on.doc")) { _ in UIPasteboard.general.string = tv.text }]) }

Selection must survive re-renders. Only assign `text` when it changed (see MSG-01). Also move the composer into its own View with its own @State draft (see MSG-08), so typing does not call updateUIView on every visible bubble.

Verifier: Confirmed. MessagesView.swift:288 applies `.textSelection(.enabled)` to a SwiftUI Text. On iOS this gives only a long-press Copy/Share of the whole string, with no handles and no partial range. No UIKit selectable text view exists anywhere in the iOS sources (grep: no UITextView or UIViewRepresentable). This is the owner's second complaint, that he cannot select part of a message character by character.

Better fix: 

API notes: `textView(_:editMenuForTextIn:suggestedActions:) -> UIMenu?` is a real UITextViewDelegate method on iOS 16+, and appending a 'copy all' UIAction to suggestedActions is valid. The menu only appears once there is a selection: long-press selects a word, and the menu shows then. 'Only assign text when changed' is required. Otherwise every HistoryStore publish, and every draft keystroke (draft is @State at MessagesView.swift:148), re-runs updateUIView and clears the selection. Also apply MSG-01's tintColor amendment so the selection is visible on outgoing bubbles.

## MSG-03 [iPhone app Messages UI (conversation list, conversation view, message bubbles, compose). iOS deployment target is 15.0 (RedmiGateway/ios/RedmiGateway.xcodeproj/project.pbxproj:254,281,297,319). The only user runs iOS 27.0 on an iPhone 15 Pro Max (docs/IOS-COKME-20261029.md:3). The bubble fix needs iOS 16+ APIs (UIViewRepresentable.sizeThatFits) and iOS 17+ APIs (UITextItem delegate). Either raise the target to 16.0, or keep the current Text as the `#available` else-branch.] high ios real=True fix_ok=True
**Once numbers are detected, a tap would go to the iPhone's own dead cellular Phone app unless the delegate routes tel:/sms: to the gateway**

Impact: If MSG-01 only turns on dataDetectorTypes, tapping '0532 123 45 67' or '444 0 532' in an SMS hands a tel: URL to iOS. iOS opens Apple's Phone app on an iPhone with a blocked IMEI, and the call fails. 'Send Message' in the long-press menu opens Apple Messages, which also cannot send SMS.

Evidence: The app declares no URL scheme: Info.plist:1-71 has no CFBundleURLTypes, so iOS cannot hand tel: back to this app. The only path that places a call through the Redmi is InAppDial.start (ContentView.swift:273-288) → CallKitManager.startOutgoingCall (CallKitManager.swift:907-923). The tel:// system dialer is deliberately reserved for emergency numbers (CallKitManager.swift:915-918). startOutgoingCall only strips `[\s().-]` and requires `^[0-9*#+]{2,20}$` (CallKitManager.swift:910-913). A data-detector tel URL carries percent-encoded spaces (`tel:0532%20123%2045%2067`), so it must be decoded before being passed in. (RedmiGateway/ios/RedmiGateway/ContentView.swift:278)

Native: On a working iPhone, tapping a number in Messages calls through the phone line, and long-press shows Call / Send Message / FaceTime / Add to Contacts / Copy. Here 'the phone line' is the Redmi, so those actions must use the gateway.

Fix: Give MessageText a Coordinator: NSObject, UITextViewDelegate.

iOS 17+ tap:
@available(iOS 17.0, *) func textView(_ tv: UITextView, primaryActionFor item: UITextItem, defaultAction: UIAction) -> UIAction? {
  guard case .link(let url) = item.content, let n = PhoneLink.number(from: url) else { return defaultAction } // http, mailto, maps, calendar keep the system action
  if url.scheme?.lowercased() == "sms" { return UIAction(title: "Mesaj") { _ in AppRouter.shared.pending = .messages(n) } }
  return UIAction(title: "Ara") { _ in InAppDial.start(n) }
}

iOS 17+ long-press:
@available(iOS 17.0, *) func textView(_ tv: UITextView, menuConfigurationFor item: UITextItem, defaultMenu: UIMenu) -> UITextItem.MenuConfiguration? {
  guard case .link(let url) = item.content, let n = PhoneLink.number(from: url) else { return .init(menu: defaultMenu) }
  return .init(menu: UIMenu(title: PhoneIdentity.formatted(n), children: [
    UIAction(title: "Redmi hattından ara", image: UIImage(systemName: "phone")) { _ in InAppDial.start(n) },
    UIAction(title: "Mesaj gönder", image: UIImage(systemName: "message")) { _ in AppRouter.shared.pending = .messages(n) },
    UIAction(title: "Kopyala", image: UIImage(systemName: "doc.on.doc")) { _ in UIPasteboard.general.string = n }]))
}
This replaces the system menu, whose Call, FaceTime and Send Message all use the dead line or Apple Messages.

iOS 15-16 fallback (not called on 17+ once the UITextItem methods exist):
func textView(_ tv: UITextView, shouldInteractWith url: URL, in r: NSRange, interaction: UITextItemInteraction) -> Bool {
  guard let n = PhoneLink.number(from: url) else { return true }
  if interaction == .invokeDefaultAction { InAppDial.start(n) }
  return false
}

enum PhoneLink { static func number(from url: URL) -> String? {
  guard let s = url.scheme?.lowercased(), ["tel", "telprompt", "sms"].contains(s) else { return nil }
  let raw = (url.absoluteString.removingPercentEncoding ?? url.absoluteString).dropFirst(s.count + 1)
  let n = raw.filter { "0123456789+*#".contains($0) }
  return n.count >= 2 ? n : nil } }

InAppDial.start already sends emergency numbers to tel:// and waits for the link otherwise (ContentView.swift:278-287). AppRouter.pending = .messages(n) already opens the right thread (ContentView.swift:38-45, MessagesView.swift:88-97).

Verifier: Valid as a requirement of the MSG-01 fix, not a defect that happens today: no number is tappable yet. The evidence holds. Info.plist:1-71 has no CFBundleURLTypes. The only path that places a call through the Redmi is InAppDial.start (ContentView.swift:278-287), which leads to CallKitManager.startOutgoingCall (CallKitManager.swift:907-976). The only tel:// open is for emergency numbers (CallKitManager.swift:915-918). startOutgoingCall strips `[\s().-]` and requires `^[0-9*#+]{2,20}$` (CallKitManager.swift:910-913), so a percent-encoded tel: URL string would be refused. With dataDetectorTypes .phoneNumber and no delegate, a tap or long-press would hand tel: to Apple's Phone app on an iPhone that has no service.

Better fix: 

API notes: The signatures are right. `textView(_:primaryActionFor:defaultAction:) -> UIAction?` and `textView(_:menuConfigurationFor:defaultMenu:) -> UITextItem.MenuConfiguration?` exist on iOS 17+. UITextItem.content `.link(URL)` and `UITextItem.MenuConfiguration(menu:)` are correct. Once the new methods are implemented, iOS 17 does not call the deprecated `textView(_:shouldInteractWith:in:interaction:)`, so it is a valid iOS 15/16 fallback; returning false for .presentActions removes the system sheet there. Routing to InAppDial.start and AppRouter.shared.pending = .messages(n) works (ContentView.swift:38-45, MessagesView.swift:88-97). Corrections: (a) FaceTime does not use the dead cellular line; it works over the Redmi hotspot. Replacing the whole menu drops FaceTime and 'Add to Contacts', so consider adding a 'Kişilere ekle' item. (b) PhoneLink's digit filter would also pick up digits from an `sms:...&body=` query. Data detectors never produce sms: URLs, so drop that branch or cut at '?'/'&' first. (c) The Coordinator and UIAction closures touch @MainActor types (AppRouter at Notifier.swift:11-15, InAppDial at ContentView.swift:272), so mark the Coordinator @MainActor.

## MSG-04 [iPhone app Messages UI (conversation list, conversation view, message bubbles, compose). iOS deployment target is 15.0 (RedmiGateway/ios/RedmiGateway.xcodeproj/project.pbxproj:254,281,297,319). The only user runs iOS 27.0 on an iPhone 15 Pro Max (docs/IOS-COKME-20261029.md:3). The bubble fix needs iOS 16+ APIs (UIViewRepresentable.sizeThatFits) and iOS 17+ APIs (UITextItem delegate). Either raise the target to 16.0, or keep the current Text as the `#available` else-branch.] medium ios real=True fix_ok=True
**One-time codes: nothing detects them and nothing lets the owner copy just the code; Apple's Security Code AutoFill cannot see these SMS**

Impact: Every bank or 2FA code now arrives through this app, because the iPhone has no SIM. iOS keyboard AutoFill only reads codes from Apple Messages, so it never offers them. With MSG-02 the owner cannot even copy only the digits, so each login means switching apps, copying the whole SMS and deleting text.

Evidence: Notifier.add(sms:) (Notifier.swift:76-89) posts the raw body (line 81) with no categoryIdentifier and no actions. MessageBubble (MessagesView.swift:272-318) has no code affordance. Copy is whole-message only (MessagesView.swift:288). (RedmiGateway/ios/RedmiGateway/MessagesView.swift:280)

Native: Apple Messages + QuickType suggest 'From Messages: 123456' above the keyboard. The code is one tap away, and iOS 17+ can delete it after use.

Fix: Add an `OneTimeCode.detect(body)` helper: a 4-8 digit (or 4-8 alphanumeric) token near keywords such as kod|code|şifre|parola|doğrulama|OTP|onay, case- and diacritic-insensitive, and ignoring numbers that look like phone numbers or amounts.
1) Under an incoming bubble that has a code, show a small 'Kodu kopyala · 123456' capsule that writes UIPasteboard.general.string and gives a success haptic.
2) In Notifier, register a UNNotificationCategory 'sms-otp' with a 'Kodu kopyala' UNNotificationAction (no .foreground). In didReceive (Notifier.swift:151-164), copy the code; check on device that the background pasteboard write is accepted. Also put the code in the notification subtitle.
MSG-02 alone already makes the code selectable.

Verifier: Valid as a gap against Apple Messages, not a crash bug. Notifier.add(sms:) (Notifier.swift:76-89) posts the raw body with no categoryIdentifier and no actions. MessageBubble (MessagesView.swift:272-318) has no code affordance. Copy is whole-message only (MessagesView.swift:288). UITextView data detectors have no public one-time-code type, so MSG-01 alone will not mark codes, and iOS AutoFill only takes codes from Apple Messages and Mail. MSG-02 already lets the owner select just the digits; this finding adds Apple-style 'copy code' convenience on top.

Better fix: 

API notes: Writing UIPasteboard from a background (non-.foreground) notification action is not reliable, so the 'check on device' caveat is essential. If it fails, use `.foreground` on that action and copy on launch, or show the code prominently in the notification title or subtitle. The Redmi's APNs alert payload (ApnsSender.kt:98-103, called from PushNotifier.kt:101) has no `category` key, so the pushed banner shown while away will have no 'Kodu kopyala' action until the local copy replaces it. Add aps.category to that payload. When smsContentInPush is off, the push body is just 'Yeni mesaj' (PushNotifier.kt:147-148), so no code can be extracted from it. The detector must exclude IBAN/TR..., amounts and 10-11 digit phone numbers, as proposed.

## MSG-05 [iPhone app Messages UI (conversation list, conversation view, message bubbles, compose). iOS deployment target is 15.0 (RedmiGateway/ios/RedmiGateway.xcodeproj/project.pbxproj:254,281,297,319). The only user runs iOS 27.0 on an iPhone 15 Pro Max (docs/IOS-COKME-20261029.md:3). The bubble fix needs iOS 16+ APIs (UIViewRepresentable.sizeThatFits) and iOS 17+ APIs (UITextItem delegate). Either raise the target to 16.0, or keep the current Text as the `#available` else-branch.] medium ios real=True fix_ok=True
**Open conversation is tied to the raw address string of the thread's newest message; when the number format flips, the view can pop or re-push and the half-typed draft is lost**

Impact: Speculative, derived from code and not reproduced on device. The owner starts a thread from a contact ('0532 123 45 67') and the other side replies, or the Redmi's journal copy replaces the pending one. The conversation can then jump back to the list or re-push, and whatever he was typing disappears.

Evidence: MessageThread.address is the last message's raw address (MessagesView.swift:24). Rows navigate with `NavigationLink(tag: thread.address, selection: $openAddress)` (MessagesView.swift:49). A second hidden link is active only while no thread has exactly that address string (MessagesView.swift:60-65). openThread stores the raw string (MessagesView.swift:94-97). The three formats differ: the pending copy keeps the address as typed (GatewayConnection.swift:1308); the Redmi sends to and journals the stripped form (android .../sms/SmsSender.kt:60 normalizeRecipient: '0532 123 45 67' becomes '05321234567'); incoming rows carry the network's '+90…'. HistoryStore matches them by PhoneIdentity.key (HistoryStore.swift:200-204), so the thread's last address string changes while the selection keeps the old one. That deactivates one link and activates the other. The draft is @State of the pushed ConversationView (MessagesView.swift:148), so a re-push drops it. (RedmiGateway/ios/RedmiGateway/MessagesView.swift:49)

Native: Apple Messages keeps the conversation on screen and keeps the draft when new messages arrive. Drafts also persist per conversation.

Fix: Identify the open conversation by PhoneIdentity.key, not by the address string. Use `NavigationLink(tag: thread.id, selection: $openKey)` and key the hidden link by key too. On iOS 16+ the better option is `NavigationStack(path: $path)` with `.navigationDestination(for: String.self) { key in ConversationView(key: key) }`. ConversationView should derive its reply address from the newest message of that key, falling back to the address it was opened with. Keep drafts per key, for example in HistoryStore or a small DraftStore, so they survive navigation the way Apple keeps per-conversation drafts. Verify on device: open a thread from a contact, send, receive a reply, and check that the view stays put and the draft survives.

Verifier: The code path is real; the outcome is not reproduced. MessageThread.address is the newest record's raw address (MessagesView.swift:24). Rows use `NavigationLink(tag: thread.address, selection: $openAddress)` (MessagesView.swift:49), and a hidden link is active only while no thread has that exact string (MessagesView.swift:60-65). The formats really do diverge. The pending copy keeps the typed recipient (GatewayConnection.swift:1308, HistoryStore.swift:222-226). The Redmi sends to normalizeRecipient's stripped form (SmsSender.kt:60,64; the test at SmsSendProblemTest.kt:42 turns '0555 123 45 67' into '05551234567'). The journal reads the provider ADDRESS (SmsJournal.kt:196,219). The journal entry replaces the pending copy by key (HistoryStore.swift:199-204), so thread.address changes from '0532 123 45 67' to '05321234567' while openAddress keeps the old string. That swaps the row link for the hidden link and creates a new ConversationView, which loses @State draft (MessagesView.swift:148). This happens a few seconds after a send, exactly when the owner may be typing the next message.

Better fix: 

API notes: Keying the row link by thread.id (= PhoneIdentity.key) fixes the case that loses the draft: the journal replacement and later replies no longer change the tag. The same destination identity then receives the new address, so @State survives. One swap remains with key-based tags. In a brand-new conversation the hidden link (MessagesView.swift:60) is active until the first pending copy creates a thread with that key, and then the row link takes over. Draft is already '' at that point (MessagesView.swift:263-264), but a pop/push glitch can still happen. To remove it completely, use NavigationStack(path:) with .navigationDestination(for: String.self) on iOS 16+, or on iOS 15 route every open through one programmatic link driven by openKey. A per-key draft store is a good addition.

## MSG-06 [iPhone app Messages UI (conversation list, conversation view, message bubbles, compose). iOS deployment target is 15.0 (RedmiGateway/ios/RedmiGateway.xcodeproj/project.pbxproj:254,281,297,319). The only user runs iOS 27.0 on an iPhone 15 Pro Max (docs/IOS-COKME-20261029.md:3). The bubble fix needs iOS 16+ APIs (UIViewRepresentable.sizeThatFits) and iOS 17+ APIs (UITextItem delegate). Either raise the target to 16.0, or keep the current Text as the `#available` else-branch.] medium ios real=True fix_ok=True
**Composer is a single-line TextField: no newline, and long text scrolls sideways out of view**

Impact: When writing anything longer than one line, the owner cannot see the start of his message and cannot add a line break before sending.

Evidence: MessagesView.swift:226 uses `TextField("Mesaj", text: $draft)` with no `axis:` and no lineLimit. A one-axis TextField never wraps and never accepts return as a newline. The maximum length is 1600 characters (MessagesView.swift:150). (RedmiGateway/ios/RedmiGateway/MessagesView.swift:226)

Native: Apple Messages' input grows to about 5-6 lines and then scrolls inside, and return inserts a newline.

Fix: On iOS 16+ use `TextField("Mesaj", text: $draft, axis: .vertical).lineLimit(1...6)` (UITextView-backed on 15). Keep the send button bottom-aligned, as it already is with HStack(alignment: .bottom) at line 225. Pair this with MSG-08: move the composer into its own View with its own draft state.

Verifier: Confirmed. MessagesView.swift:226 is `TextField("Mesaj", text: $draft)` with no axis. A one-axis TextField is single-line, scrolls horizontally and cannot insert a newline. The 1600-character limit is at MessagesView.swift:150. Apple Messages grows the field vertically and allows line breaks.

Better fix: 

API notes: `TextField(_:text:axis:)` and `.lineLimit(1...6)` (ClosedRange) are iOS 16+. With the iOS 15.0 deployment target (project.pbxproj:254), gate them with `if #available(iOS 16, *)`. On iOS 15, keep the current TextField or use a TextEditor; the phrase 'UITextView-backed on 15' is misleading. With axis .vertical, Return inserts a newline, which matches Messages. send() trims only leading and trailing whitespace (MessagesView.swift:261), so interior newlines are kept.

## MSG-07 [iPhone app Messages UI (conversation list, conversation view, message bubbles, compose). iOS deployment target is 15.0 (RedmiGateway/ios/RedmiGateway.xcodeproj/project.pbxproj:254,281,297,319). The only user runs iOS 27.0 on an iPhone 15 Pro Max (docs/IOS-COKME-20261029.md:3). The bubble fix needs iOS 16+ APIs (UIViewRepresentable.sizeThatFits) and iOS 17+ APIs (UITextItem delegate). Either raise the target to 16.0, or keep the current Text as the `#available` else-branch.] low ios real=True fix_ok=True
**SMS part counter is shown too late and counts wrongly (ASCII taken as GSM-7, grapheme count used instead of UTF-16)**

Impact: A 100-character Turkish message with 'ş', 'ğ' or 'ı' goes out as 2 paid SMS with no warning, because the counter only appears after 140 characters. Messages with emoji or with ö/ü/Ç are mis-estimated.

Evidence: The counter only shows when `draft.count > 140` (MessagesView.swift:237-241), but non-GSM text splits at 70 characters (line 251). The `gsm` check is `unicodeScalars.allSatisfy { $0.isASCII }` (line 250). That is wrong both ways: ö, ü, Ç, é, à and £ are in GSM 03.38 but not ASCII, while ` is ASCII but not GSM, and [ ] { } ~ \\ | ^ take two septets. `draft.count` counts Characters, while UCS-2 parts count UTF-16 units (a single emoji is 2 or more). The real split is done by Android divideMessage (android .../sms/SmsSender.kt:73), which may also use carrier-enabled Turkish shift tables. (RedmiGateway/ios/RedmiGateway/MessagesView.swift:237)

Native: iOS Settings > Messages > Character Count shows count and parts for SMS.

Fix: Show the counter as soon as the text needs more than one part, or whenever the text uses UCS-2. Compute with the GSM 03.38 basic and extension tables and `draft.utf16.count` for UCS-2. For exactness, ask the Redmi for SmsMessage.calculateLength over the existing link. Show it Apple-style, for example '2 SMS · 98/134'.

Verifier: Confirmed, low severity. The counter only appears when draft.count > 140 (MessagesView.swift:237-241), yet non-ASCII text splits at 70/67 (MessagesView.swift:251). The 'gsm' test is ASCII-only (MessagesView.swift:250). That is wrong for GSM 03.38 letters such as ö, ü, Ö, Ü, Ç, é, à and £, for ASCII characters outside GSM such as `, and for extension characters that cost two septets ([ ] { } ~ \\ | ^ €). Lengths use grapheme count, but UCS-2 parts count UTF-16 units. The real split is Android divideMessage (SmsSender.kt:73). Whether the carrier enables the Turkish shift tables there is not verified, so it is speculative.

Better fix: 

API notes: `SmsMessage.calculateLength(CharSequence, boolean)` is a public static Android API returning [msgCount, codeUnitCount, codeUnitsRemaining, codeUnitSize]. Asking the Redmi is the only exact answer, but debounce it. A related mismatch: iOS limits by graphemes (MessagesView.swift:257 `text.count <= 1600`), while the Redmi rejects by UTF-16 length (SmsSender.kt:124 `message.length > MAX_LENGTH`). An emoji-heavy draft the iOS button allows can therefore come back failed with message_too_long. Use `draft.utf16.count` for the limit too. Apple Messages shows a count only when its Character Count setting is on, so this is a cost warning rather than strict parity.

## MSG-08 [iPhone app Messages UI (conversation list, conversation view, message bubbles, compose). iOS deployment target is 15.0 (RedmiGateway/ios/RedmiGateway.xcodeproj/project.pbxproj:254,281,297,319). The only user runs iOS 27.0 on an iPhone 15 Pro Max (docs/IOS-COKME-20261029.md:3). The bubble fix needs iOS 16+ APIs (UIViewRepresentable.sizeThatFits) and iOS 17+ APIs (UITextItem delegate). Either raise the target to 16.0, or keep the current Text as the `#available` else-branch.] low ios real=True fix_ok=True
**Every keystroke re-filters the whole history once per visible bubble; the thread list rescans all messages per thread while searching**

Impact: Not measured. With a long history (up to 3000 SMS), typing in the composer or the search field can lag. The UITextView bubbles from MSG-01 would make each re-render costlier.

Evidence: `messages` is a computed filter over all of history.messages (HistoryStore.swift:74 caps it at 3000) that calls PhoneIdentity.key (a string filter, PhoneContactPicker.swift:14-20) for every record. It is evaluated at MessagesView.swift:165, again inside every row closure at :166 (`messages[index - 1]`), and at :180 and :268. `draft` is @State of the same view (MessagesView.swift:148), so each keystroke re-evaluates body. `threads` (MessagesView.swift:20-35) regroups all messages and runs three times per render (lines 48, 60, 96). With a query, each thread rescans every message (line 32). (RedmiGateway/ios/RedmiGateway/MessagesView.swift:166)

Native: Apple Messages keeps typing and search responsive regardless of history size.

Fix: Evaluate once per body (`let items = messages`), precompute the day-separator flags, or have HistoryStore keep a [key: [SmsRecord]] index updated in finishMessages. Move the composer into its own struct with its own draft (or a per-key draft store) so typing does not invalidate the bubble list. Compute `threads` once per render.

Verifier: Real as code-level inefficiency; impact not measured. `messages` (MessagesView.swift:156-158) filters all of history.messages (cap 3000, HistoryStore.swift:74) with PhoneIdentity.key, a Character filter (PhoneContactPicker.swift:14-20). It is evaluated at :165, inside every visible row closure at :166 (`messages[index - 1]`), and at :180. Typing updates @State draft (:148), which re-runs ConversationView.body. Search is worse: for each thread, line 32 rescans every message and recomputes PhoneIdentity.key, roughly threads × messages work per keystroke. One overstatement in the evidence: `threads` runs at most twice per render (:48 and :60, and :60 only while openAddress != nil). Line :96 runs only in openThread.

Better fix: 

API notes: Hoisting `let items = messages` and precomputing separator flags is plain Swift and safe. A [key: [SmsRecord]] index rebuilt in HistoryStore.finishMessages (HistoryStore.swift:216-220) and after addPending/removeMessage would also serve isUnread and unreadCount, which currently rescan all messages. Moving the composer and its draft into a child View is the most effective change, because typing then stops invalidating the bubble list.

## MSG-09 [iPhone app Messages UI (conversation list, conversation view, message bubbles, compose). iOS deployment target is 15.0 (RedmiGateway/ios/RedmiGateway.xcodeproj/project.pbxproj:254,281,297,319). The only user runs iOS 27.0 on an iPhone 15 Pro Max (docs/IOS-COKME-20261029.md:3). The bubble fix needs iOS 16+ APIs (UIViewRepresentable.sizeThatFits) and iOS 17+ APIs (UITextItem delegate). Either raise the target to 16.0, or keep the current Text as the `#available` else-branch.] low ios real=True fix_ok=True
**Send is blocked, not queued, while the link is down; the link is closed by design 25 s after backgrounding**

Impact: When the owner opens the app from an SMS notification and replies quickly, the send button can be grey ('Redmi'ye bağlanınca gönderilebilir') until the link comes back. Apple would just send.

Evidence: canSend requires connection.isAuthenticated (MessagesView.swift:255-258), and the hint is at MessagesView.swift:219-224. sendSms marks the copy failed at once when not authenticated (GatewayConnection.swift:1309-1312). The link is closed on purpose after backgrounding, and calls were already changed to wait for it (ContentView.swift:266-288). (RedmiGateway/ios/RedmiGateway/MessagesView.swift:257)

Native: Apple Messages accepts the message immediately and sends it when it can.

Fix: Let the button send whenever the text is valid. Keep the pending copy in `.sending`, reconnect, and transmit after authentication. The retry path already does this kind of wait (GatewayConnection.swift:1354-1359, reconnectNow plus the sync hook) under the existing 90 s timeout (GatewayConnection.swift:1300).

Verifier: Confirmed. canSend requires connection.isAuthenticated (MessagesView.swift:255-258), with the hint at :219-224. sendSms marks the copy failed at once when not authenticated (GatewayConnection.swift:1309-1312). The link is closed on purpose 25 s after backgrounding (GatewayConnection.swift:96; comment at ContentView.swift:267-271), and calls already wait for it (InAppDial, ContentView.swift:278-287; dialFromSystem, CallKitManager.swift:705-712). The link reconnects on foreground (GatewayConnection.swift:1437,1715), so the button is grey for a few seconds after opening the app from a notification.

Better fix: 

API notes: A brand-new send has never been transmitted, so it needs no journal check. Transmitting directly when authentication completes is simpler than reusing smsRetriesAfterSync (GatewayConnection.swift:1351,1367-1383), although reusing it also works (the journal will not contain the message, so it is sent after the 3 s grace). Call reconnectNow when queuing. Keep the 90 s smsResultTimeout (GatewayConnection.swift:1300). expirePendingSends (HistoryStore.swift:268-283) already turns copies stranded by suspension into failed. Change the hint text to something like 'Bağlanınca gönderilecek'.

## MSG-10 [iPhone app Messages UI (conversation list, conversation view, message bubbles, compose). iOS deployment target is 15.0 (RedmiGateway/ios/RedmiGateway.xcodeproj/project.pbxproj:254,281,297,319). The only user runs iOS 27.0 on an iPhone 15 Pro Max (docs/IOS-COKME-20261029.md:3). The bubble fix needs iOS 16+ APIs (UIViewRepresentable.sizeThatFits) and iOS 17+ APIs (UITextItem delegate). Either raise the target to 16.0, or keep the current Text as the `#available` else-branch.] low ios real=True fix_ok=True
**No way to delete a conversation or a message (including a failed one)**

Impact: Spam and old OTP threads stay in the list forever, and a failed bubble can only be retried, never removed.

Evidence: The thread List (MessagesView.swift:48-54) has no .swipeActions or .onDelete; Favorites has them (ContentView.swift:327-332). MessageBubble (MessagesView.swift:272-318) has no menu. HistoryStore.removeMessage (HistoryStore.swift:285-288) is only called from retrySms (GatewayConnection.swift:1346). The protocol has no delete message: a grep for delete_sms or deleteSms in RedmiGateway finds nothing. (RedmiGateway/ios/RedmiGateway/MessagesView.swift:48)

Native: Apple Messages offers swipe-to-delete on threads and delete on individual messages.

Fix: Add a trailing swipe 'Sil' on thread rows, plus 'Sil' in the bubble's edit menu (MSG-02 editMenuForTextIn) and on failed bubbles. Store a persisted tombstone set of gatewayIds in HistoryStore so upsertSms (HistoryStore.swift:187-214) does not resurrect them when the Redmi republishes a status change. Deleting on the Redmi provider is likely not possible for a non-default SMS app (speculative), so keep it iPhone-local.

Verifier: Confirmed. The thread List (MessagesView.swift:48-54) has no swipeActions or onDelete; Favorites has them (ContentView.swift:327-332). MessageBubble (MessagesView.swift:272-318) has no menu. HistoryStore.removeMessage (HistoryStore.swift:285-288) is only called from retrySms (GatewayConnection.swift:1346). No delete message exists in either app (grep finds no delete_sms or deleteSms).

Better fix: 

API notes: Tombstones are needed. The Redmi journal re-publishes a row with a new seq whenever box or read changes (SmsJournal.kt:209-219, 236-243), and upsertSms appends it again (HistoryStore.swift:187-210). Thread delete should also clear threadSeen and the thread's delivered notifications (Notifier.clearConversation, Notifier.swift:114-123), and tombstones must be dropped on reset() (HistoryStore.swift:107-117). Keeping deletes iPhone-local is right: a non-default SMS app cannot write or delete the provider (OP_WRITE_SMS), and foundation.e.message holds the SMS role.

## MSG-11 [iPhone app Messages UI (conversation list, conversation view, message bubbles, compose). iOS deployment target is 15.0 (RedmiGateway/ios/RedmiGateway.xcodeproj/project.pbxproj:254,281,297,319). The only user runs iOS 27.0 on an iPhone 15 Pro Max (docs/IOS-COKME-20261029.md:3). The bubble fix needs iOS 16+ APIs (UIViewRepresentable.sizeThatFits) and iOS 17+ APIs (UITextItem delegate). Either raise the target to 16.0, or keep the current Text as the `#available` else-branch.] low both real=True fix_ok=True
**Read state is iPhone-local; the Redmi never learns a message was read**

Impact: Reading on the iPhone does not change anything on the Redmi. The ROM's SMS app (role holder foundation.e.message, per the device facts) keeps its unread state and notifications for every SMS. This is pocket noise outside the iPhone UI, and a fix belongs with the Android notification work.

Evidence: markThreadSeen only updates threadSeen locally (HistoryStore.swift:298-301). isUnread combines the Redmi's `read` flag with the local threadSeen (HistoryStore.swift:290-294). No mark-read message exists in either app: a grep for mark_read, markRead or sms_read finds only the journal's read column (android .../journal/SmsJournal.kt:45,168,240). (RedmiGateway/ios/RedmiGateway/HistoryStore.swift:298)

Native: On a single phone, reading a message clears it everywhere.

Fix: Low priority. Optionally send a 'sms_seen' {key, until} message so the Redmi can clear its own SMS notifications. Writing READ=1 into the provider likely needs the SMS role, which the gateway does not hold (speculative). The real Redmi-noise fix is on the Android side, by silencing foundation.e.message notifications.

Verifier: Real in code, low and speculative in impact. markThreadSeen only writes local threadSeen (HistoryStore.swift:298-301). isUnread mixes the Redmi's read flag with local state (HistoryStore.swift:290-294). No mark-read message exists in either app (grep finds no mark_read, markRead, sms_read or sms_seen). The impact claim that foundation.e.message keeps notifying and leaves SMS unread is plausible for the SMS role holder, but it is not among the measured device facts, which list only the Telecom missed-call notification.

Better fix: 

API notes: The gateway has no NotificationListenerService (grep finds none), so it cannot cancel foundation.e.message notifications today. That would need a listener granted through Settings or `cmd notification allow_listener`. Writing READ=1 needs the SMS role (OP_WRITE_SMS), which is correctly marked as likely impossible. The practical noise fix is to turn off foundation.e.message notifications on the Redmi, which belongs to the Android area. Low priority, as stated.

## MSG-12 [iPhone app Messages UI (conversation list, conversation view, message bubbles, compose). iOS deployment target is 15.0 (RedmiGateway/ios/RedmiGateway.xcodeproj/project.pbxproj:254,281,297,319). The only user runs iOS 27.0 on an iPhone 15 Pro Max (docs/IOS-COKME-20261029.md:3). The bubble fix needs iOS 16+ APIs (UIViewRepresentable.sizeThatFits) and iOS 17+ APIs (UITextItem delegate). Either raise the target to 16.0, or keep the current Text as the `#available` else-branch.] low ios real=True fix_ok=True
**A conversation left open in a background tab may mark new messages seen without the owner looking (speculative)**

Impact: Possible: a new SMS in a conversation that was left open in the Messages tab while the owner uses another tab gets no unread dot or tab badge. The banner still shows.

Evidence: `.onChange(of: messages.count)` calls history.markThreadSeen(key) (MessagesView.swift:180-183) with no check that the view is on screen. onDisappear only clears openThreadKey (MessagesView.swift:211-214). TabView keeps the Messages tab's NavigationView and its pushed ConversationView alive (ContentView.swift:89-93). Whether SwiftUI fires onChange for the hidden tab was not verified on device. (RedmiGateway/ios/RedmiGateway/MessagesView.swift:182)

Native: Apple Messages counts a message as read only when its conversation is actually on screen.

Fix: Only mark the thread seen when `history.openThreadKey == key` and scenePhase == .active. Set and clear openThreadKey on scenePhase changes as well.

Verifier: Low severity, speculative. `.onChange(of: messages.count)` calls markThreadSeen(key) unconditionally (MessagesView.swift:180-183). onDisappear only clears openThreadKey (:211-214). Backgrounding the app does not fire onDisappear, and TabView keeps the Messages NavigationView alive (ContentView.swift:89-93). If SwiftUI evaluates that onChange while the tab is hidden or the scene is in the background (not verified on device), a new SMS is marked seen. The unread dot, tab badge (ContentView.swift:92) and app-icon badge (Notifier.swift:132-134) then undercount, while the banner still shows.

Better fix: 

API notes: `@Environment(\.scenePhase)` is available in views on iOS 14+. Guarding with `history.openThreadKey == key && scenePhase == .active` is correct, because onDisappear on a tab switch already clears openThreadKey. Also call markThreadSeen and clearConversation when scenePhase returns to .active while the conversation is on screen. Otherwise messages that arrived in the background stay unread after the owner is looking at them.

## MSG-13 [iPhone app Messages UI (conversation list, conversation view, message bubbles, compose). iOS deployment target is 15.0 (RedmiGateway/ios/RedmiGateway.xcodeproj/project.pbxproj:254,281,297,319). The only user runs iOS 27.0 on an iPhone 15 Pro Max (docs/IOS-COKME-20261029.md:3). The bubble fix needs iOS 16+ APIs (UIViewRepresentable.sizeThatFits) and iOS 17+ APIs (UITextItem delegate). Either raise the target to 16.0, or keep the current Text as the `#available` else-branch.] low ios real=True fix_ok=True
**Scroll-to-newest and keyboard behaviour are fragile (speculative)**

Impact: Possible: a long conversation may open short of the newest bubble. When the keyboard opens, the last messages scroll out of view, and there is no swipe-down to dismiss the keyboard.

Evidence: scrollTo runs synchronously in onAppear of a ScrollView/LazyVStack during the push (MessagesView.swift:179, 267-269), which is a known-unreliable pattern with lazily sized rows. It gets worse once bubbles size themselves via UITextView. Nothing re-anchors to the bottom on keyboard show, and there is no .scrollDismissesKeyboard. The ScrollView is at MessagesView.swift:162-184. (RedmiGateway/ios/RedmiGateway/MessagesView.swift:179)

Native: Apple Messages opens at the newest message, stays pinned to the bottom when the keyboard rises, and dismisses the keyboard interactively.

Fix: On iOS 17+ use `.defaultScrollAnchor(.bottom)`. Otherwise defer scrollTo with DispatchQueue.main.async. Re-scroll to the last id when the composer gains focus (@FocusState) or the keyboard appears. Add `.scrollDismissesKeyboard(.interactively)` (iOS 16+).

Verifier: Low severity, partly speculative. scrollTo runs synchronously in the ScrollView's onAppear (MessagesView.swift:179, 267-269) over a LazyVStack (:164). With lazily sized rows it can land short of the end; not reproduced here. The keyboard part follows from the code: nothing re-scrolls on focus or keyboard show, and there is no scrollDismissesKeyboard (grep finds none). When keyboard avoidance shrinks the ScrollView, the newest bubbles end up below the visible area.

Better fix: 

API notes: `.defaultScrollAnchor(.bottom)` is iOS 17+, `.scrollDismissesKeyboard(.interactively)` is iOS 16+ (the target is 15.0, so gate both), and @FocusState is iOS 15+ (already used in SettingsView.swift:10). A DispatchQueue.main.async-deferred scrollTo is the usual iOS 15 fallback.

## MSG-14 [iPhone app Messages UI (conversation list, conversation view, message bubbles, compose). iOS deployment target is 15.0 (RedmiGateway/ios/RedmiGateway.xcodeproj/project.pbxproj:254,281,297,319). The only user runs iOS 27.0 on an iPhone 15 Pro Max (docs/IOS-COKME-20261029.md:3). The bubble fix needs iOS 16+ APIs (UIViewRepresentable.sizeThatFits) and iOS 17+ APIs (UITextItem delegate). Either raise the target to 16.0, or keep the current Text as the `#available` else-branch.] low ios real=True fix_ok=True
**Visual parity gaps: day separators, per-bubble timestamps, emoji-only messages, SMS bubble colour**

Impact: The conversation looks busier and less familiar than Apple Messages: a time under every bubble, '29 Eki 2026' even for today, small emoji, and iMessage-blue bubbles for SMS.

Evidence: The day separator is date-only `.abbreviated` (MessagesView.swift:167), although RelativeDay already exists (ContentView.swift:484-496). Every sent or received bubble gets its own time line (MessagesView.swift:312-315). Emoji-only bodies use the normal bubble (MessagesView.swift:280-287). Outgoing bubbles use Color.accentColor (MessagesView.swift:297), and there is no AccentColor asset, so they are system blue. (RedmiGateway/ios/RedmiGateway/MessagesView.swift:167)

Native: Apple shows time headers on gaps, large emoji for emoji-only messages, and green SMS bubbles.

Fix: Use separator text like Apple's 'Bugün 14:32', 'Dün 09:10', weekday plus time within 7 days, and date plus time beyond that. Insert a separator after gaps over about 1 hour, not only at day changes. Drop the per-bubble time except for the status footers (sending/failed). Render 1-3 emoji-only messages at about 3x size without a bubble. Optionally use systemGreen for outgoing SMS bubbles.

Verifier: Confirmed, cosmetic and low severity. The separator uses date-only `.abbreviated` (MessagesView.swift:167), which shows '29 Eki 2026' even for today; RelativeDay exists at ContentView.swift:484-496 but omits 'Bugün' and the time. Every sent or received bubble shows its own time (MessagesView.swift:312-315). Emoji-only bodies use the normal bubble (:280-287). Outgoing bubbles use Color.accentColor (:297), and Assets.xcassets contains only AppIcon.appiconset, so they are system blue. Apple uses green for SMS.

Better fix: 

API notes: Plain SwiftUI changes. If per-bubble times are dropped, keep times reachable: Apple reveals them with a left swipe, and the proposed gap-based separators cover most of this. RelativeDay.text would need a 'Bugün HH:mm' / 'Dün HH:mm' variant for separators. The unread dot (MessagesView.swift:125) also uses accentColor, so changing the global accent would affect it; colour only the outgoing bubble (for example Color(.systemGreen)).

## MSG-15 [iPhone app Messages UI (conversation list, conversation view, message bubbles, compose). iOS deployment target is 15.0 (RedmiGateway/ios/RedmiGateway.xcodeproj/project.pbxproj:254,281,297,319). The only user runs iOS 27.0 on an iPhone 15 Pro Max (docs/IOS-COKME-20261029.md:3). The bubble fix needs iOS 16+ APIs (UIViewRepresentable.sizeThatFits) and iOS 17+ APIs (UITextItem delegate). Either raise the target to 16.0, or keep the current Text as the `#available` else-branch.] low ios real=True fix_ok=True
**Call button shows for alphanumeric senders and does nothing visible**

Impact: Tapping the phone icon in a 'TURKCELL' or bank-sender thread does nothing visible.

Evidence: canReply hides only the composer for senders with fewer than 3 digits (MessagesView.swift:154, 186-194). The toolbar phone button is unconditional (MessagesView.swift:198-205). With the link up, startOutgoingCall rejects the name at CallKitManager.swift:911-913 and writes lastCallError, which only the keypad displays (ContentView.swift:686-692). (RedmiGateway/ios/RedmiGateway/MessagesView.swift:200)

Native: Apple shows no call action for short-code or alphanumeric senders.

Fix: Show the toolbar call button only when canReply is true.

Verifier: Confirmed. canReply hides only the composer (MessagesView.swift:154, 186-194); the toolbar phone button is unconditional (:198-205). With the link up, InAppDial.start (ContentView.swift:278-283) calls startOutgoingCall, which rejects 'TURKCELL' at CallKitManager.swift:911-913 and sets lastCallError. That error shows only on the keypad (ContentView.swift:686-692) or the call screen (CallScreen.swift:76,354), which is not presented. With the link down, dialFromSystem queues the name and forces a reconnect (CallKitManager.swift:705-711), and the queued dial fails silently later (CallKitManager.swift:621-623).

Better fix: 

API notes: Better gate: canReply counts at least 3 digits anywhere, so a sender like 'BANK123' still shows the button and the composer, while both the iOS dial check (`^[0-9*#+]{2,20}$` after stripping, CallKitManager.swift:910-911) and the Redmi SMS check (`^\\+?[0-9]{3,15}$`, SmsSender.kt:122) refuse it. Derive one `isDialable` from the stripped address matching `^\\+?[0-9]{3,15}$`, and use it for both the toolbar button and the composer.

## MSG-16 [iPhone app Messages UI (conversation list, conversation view, message bubbles, compose). iOS deployment target is 15.0 (RedmiGateway/ios/RedmiGateway.xcodeproj/project.pbxproj:254,281,297,319). The only user runs iOS 27.0 on an iPhone 15 Pro Max (docs/IOS-COKME-20261029.md:3). The bubble fix needs iOS 16+ APIs (UIViewRepresentable.sizeThatFits) and iOS 17+ APIs (UITextItem delegate). Either raise the target to 16.0, or keep the current Text as the `#available` else-branch.] low ios real=True fix_ok=True
**SMS notifications have no actions: no inline reply, no mark as read**

Impact: The owner has to open the app to answer a quick SMS, while Apple Messages lets him reply from the banner or lock screen.

Evidence: Notifier.add(sms:) sets title, body, sound, thread, userInfo and badge but no categoryIdentifier (Notifier.swift:79-86). No UNNotificationCategory is registered anywhere (a grep for categoryIdentifier and UNNotificationCategory finds nothing). didReceive only routes taps (Notifier.swift:151-164). (RedmiGateway/ios/RedmiGateway/Notifier.swift:79)

Native: Apple Messages offers inline Reply and Mark as Read from the notification.

Fix: Register a category 'sms' with UNTextInputNotificationAction('Yanıtla') and UNNotificationAction('Okundu'). In didReceive, mark the thread seen. For a reply, take a background task, reconnect, and call sendSms. This depends on the link waking within the background budget, so test on device before shipping. It can share the category work with MSG-04.

Verifier: Confirmed. Notifier.add(sms:) sets title, body, sound, thread, userInfo and badge, but no categoryIdentifier (Notifier.swift:79-86). No UNNotificationCategory is registered (grep finds no categoryIdentifier or UNNotificationCategory). didReceive only routes taps (Notifier.swift:151-164). There is also a related gap: the Redmi's APNs SMS alert carries only `aps` (ApnsSender.kt:98-103), with no `open` or `address` keys. A pushed banner that the local copy has not yet replaced (away mode) therefore opens the app without routing to the thread, because didReceive requires info["open"] == "sms" (Notifier.swift:153-158).

Better fix: 

API notes: Use center.setNotificationCategories at launch with UNNotificationCategory(identifier: "sms", actions: [UNTextInputNotificationAction(identifier:title:options:textInputButtonTitle:textInputPlaceholder:), UNNotificationAction(identifier:title:options:)], intentIdentifiers: []). Read the reply via `(response as? UNTextInputNotificationResponse)?.userText`. Call completionHandler only after the send settles or a UIApplication.beginBackgroundTask expires; until MSG-09 lands, sendSms fails immediately when unauthenticated (GatewayConnection.swift:1309-1312). Add `"category":"sms"` inside aps, plus top-level `open`/`address`, to ApnsSender.sendAlert (called from PushNotifier.kt:101), so pushed banners get the actions and tap routing too. Consider `.authenticationRequired` on the reply action. Test on device, as stated.

## CALL-01 [iPhone app calling UI: keypad, Recents, contacts, favourites, in-call screen, missed calls, voicemail, tel: links from other apps, Siri and CallKit intents. Read-only review of RedmiGateway/ios, plus the Android missed-call path named in the device facts.] high ios real=True fix_ok=False
**Phone numbers tapped in other apps (Safari, Mail, Contacts, Maps) always go to the iPhone's dead cellular radio**

Impact: Every phone number the owner taps outside this app opens Apple's Phone app and fails, because the iPhone has no cellular service. He has to copy the number, open Telefon Geçidi, long-press the keypad and paste it.

Evidence: Info.plist:1-71 has no CFBundleURLTypes, so the app declares no URL scheme of any kind, and there is no onOpenURL anywhere (grep for openURL/onOpenURL finds only Settings links). RedmiGateway.entitlements:7-8 holds only aps-environment, with no default-calling-app entitlement. project.pbxproj:138-152 has a single application target: no Share or Action extension and no App Intents. The only way in from outside is INStartCallIntent from Apple's Recents (ContentView.swift:47-56, Info.plist:44-48). (C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\ios\RedmiGateway\Info.plist:44)

Native: In Apple's Phone app, tapping a number anywhere in iOS starts the call. Since iOS 18.2 the person can also pick a different default calling app (Settings > Apps > Default Apps > Calling), and iOS then sends tel: links and the Contacts call button to that app.

Fix: 1) Apply to Apple for the Default Calling App entitlement (Apple's doc 'Preparing your app to be the default calling app' names com.apple.developer.calling-app; confirm the current name and eligibility). Add tel and telprompt to CFBundleURLTypes. Handle them with .onOpenURL at the WindowGroup: parse the number and call InAppDial.start(number), which already waits for the link. Then the owner can pick 'Telefon Geçidi' as his default calling app. 2) Fallbacks that need no Apple approval: a custom scheme (telefongecidi://call?n=...); an AppIntent / App Shortcut 'Redmi ile ara' with a phone-number parameter, usable from Shortcuts, the Action button and Siri; and a Share/Action extension that takes selected text, pulls out numbers with NSDataDetector(.phoneNumber) and opens the app to dial. Speculative part: entitlement availability and eligibility are Apple's decision.

Verifier: Confirmed. Info.plist:1-71 has no CFBundleURLTypes. RedmiGateway.entitlements:7-8 holds only aps-environment. project.pbxproj:138-152 has one application target. The only outside entry points are the two onContinueUserActivity handlers (ContentView.swift:47-56), backed by NSUserActivityTypes (Info.plist:44-48). Today a tel: link in Safari, Mail or Maps cannot reach the app. This is a gap rather than a bug, and closing it fully depends on Apple granting the default-calling-app entitlement.

Better fix: 1) Default calling app: keep the plan. Apply for the entitlement, declare tel/telprompt in CFBundleURLTypes, add .onOpenURL on ContentView and call InAppDial.start(number). One addition: the emergency path calls UIApplication.shared.open(tel://112) (CallKitManager.swift:915-918). Before shipping, confirm on device that iOS still sends emergency tel: URLs to Apple's Phone app once this app is the default. If it does not, the call loops back into this app, so 112 must be handled differently. 2) Drop the Share/Action extension. Extensions cannot use UIApplication.shared.open, and there is no supported way for one to open its containing app. Instead, add an AppIntent 'Redmi ile ara' with openAppWhenRun = true and a String phone-number parameter (iOS 16+, wrapped in @available because the deployment target is 15.0, project.pbxproj:254). Offer it as an App Shortcut. The owner can then build a Share Sheet shortcut that takes text, runs 'Get Phone Numbers from Input' and passes the number to this intent. The custom scheme telefongecidi://call?n= is fine as a further fallback.

API notes: IPHONEOS_DEPLOYMENT_TARGET = 15.0 (project.pbxproj:254/281/297/319). AppIntents and App Shortcuts are iOS 16+. Default calling apps arrived in iOS 18.2; the entitlement name com.apple.developer.calling-app and Apple's exact requirements (tel/telprompt handling) are unverified here, as the finding itself notes.

## CALL-02 [iPhone app calling UI: keypad, Recents, contacts, favourites, in-call screen, missed calls, voicemail, tel: links from other apps, Siri and CallKit intents. Read-only review of RedmiGateway/ios, plus the Android missed-call path named in the device facts.] high ios real=True fix_ok=False
**Numbers and links inside SMS bubbles are plain text; once they become tappable, tel: must be handled in-app**

Impact: Matches the owner's complaint: links and phone numbers in messages are not blue and cannot be tapped, and he cannot select part of a message to copy. If someone fixes the links naively, a tapped number in an SMS (bank, courier, OTP call-back) goes to the dead cellular radio instead of the Redmi.

Evidence: MessagesView.swift:280 renders the body as Text(message.body) with no data detection. MessagesView.swift:288 uses .textSelection(.enabled), which on iOS only offers whole-text Copy/Share, not part of the text. No OpenURLAction or openURL environment override exists in the app (grep), so any future tel: link would leave the app. Related: the thread's call button (MessagesView.swift:198-205) shows even for alphanumeric senders that canReply already rejects (MessagesView.swift:153-154). Tapping it gives InAppDial.start('TURKCELL') -> dialFromSystem -> startOutgoingCall, which rejects it at CallKitManager.swift:910-913. That error only shows on the keypad, so the tap looks dead. (C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\ios\RedmiGateway\MessagesView.swift:280)

Native: Apple Messages detects links, phone numbers, addresses and dates. Tapping a number calls it. Long-pressing a number shows Call / Send Message / Add to Contacts / Copy. Any part of a message can be selected and copied.

Fix: Render each bubble with a UIViewRepresentable UITextView: isEditable=false, isSelectable=true, isScrollEnabled=false, dataDetectorTypes=[.link,.phoneNumber,.address,.calendarEvent], and textContainerInset sized to the bubble. This gives tappable links and partial selection at the same time. In UITextViewDelegate textView(_:primaryActionFor:defaultAction:) (iOS 16+) or shouldInteractWith URL, route tel:/telprompt: to InAppDial.start(number) and sms: to the in-app thread (AppRouter .messages). Let http(s) open normally. Also set .environment(\.openURL, OpenURLAction { url in tel/sms -> in-app, .handled }) on ContentView as a global catch. Hide the toolbar phone button when !canReply.

Verifier: Confirmed, and it is exactly what the owner reported. MessagesView.swift:280 renders Text(message.body) from a plain String, so no links or numbers are detected. MessagesView.swift:288 uses .textSelection(.enabled), which on iOS only offers Copy/Share for the whole text, not part of it. The app has no OpenURLAction override (grep). The side point also holds: the call button (MessagesView.swift:198-205) shows even when canReply is false (MessagesView.swift:154). Tapping it for 'TURKCELL' fails the number regex (CallKitManager.swift:910-913), and that error is shown only on the keypad (ContentView.swift:686) and the call screen (CallScreen.swift:76).

Better fix: The UITextView approach is right; four details need fixing. (1) textView(_:primaryActionFor:defaultAction:) is iOS 17+, not 16+. The deployment target is 15.0, so on iOS 15-16 implement textView(_:shouldInteractWith:in:interaction:): for tel:/telprompt: return false and call InAppDial.start; for sms: return false and route with AppRouter .messages. (2) A long press on a detected number shows a system menu with Call, FaceTime and Send Message, and those go to Apple's apps. On iOS 17+, implement textView(_:menuConfigurationFor:defaultMenu:) to swap them for in-app 'Redmi ile ara' and 'Mesaj gönder' while keeping Copy and Add to Contacts. On iOS 15-16, returning false for .presentActions suppresses that sheet. (3) .environment(\.openURL) does not catch UITextView taps, because UIKit opens those URLs itself. It only covers SwiftUI Text and Link, so it is not a global catch for the bubbles. (4) Sizing: UIViewRepresentable.sizeThatFits is iOS 16+. On iOS 15, use a UITextView subclass whose intrinsicContentSize is measured at the bubble's max width. Set isScrollEnabled=false, textContainerInset=.zero, lineFragmentPadding=0 and adjustsFontForContentSizeCategory with the .body font. Set linkTextAttributes to white underlined in outgoing bubbles and the system link colour in incoming ones. Remove .textSelection. Hide the toolbar call button when !canReply.

API notes: UITextView dataDetectorTypes .link/.phoneNumber/.address/.calendarEvent and shouldInteractWith URL are iOS 10+ (deprecated in 17). UITextItem primaryActionFor and menuConfigurationFor are iOS 17+. Deployment target is 15.0 (project.pbxproj:254).

## CALL-03 [iPhone app calling UI: keypad, Recents, contacts, favourites, in-call screen, missed calls, voicemail, tel: links from other apps, Siri and CallKit intents. Read-only review of RedmiGateway/ios, plus the Android missed-call path named in the device facts.] high both real=True fix_ok=True
**Redmi shows missed-call notifications from Telecom (the 'previous phone app') and its own, and re-posts them after every reboot**

Impact: Matches the owner's complaint: the Redmi in his pocket keeps 'Cevapsız çağrılar - 4 cevapsız çağrı' from the system Telecom service, which looks like an old phone app. It also has the app's own extra missed-call row, and all of them return after a reboot, even though he already saw the calls on the iPhone.

Evidence: Device facts: notification com.android.server.telecom tag MissedCallNotifierImpl, channel TelecomMissedCalls, with 4 re-posted after a reboot. AndroidManifest.xml:61-95 and 105-165 have no receiver for android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION. Telecom posts its own missed-call notification unless the default dialer has a receiver for that action. Nothing in android/ calls TelecomManager.cancelMissedCallsNotification() (grep finds no hits), so the CallLog NEW=1 flag on missed calls is never cleared, and Telecom re-reads those rows at boot. On top of that, CallUiNotifier.kt:218-266 posts a second 'Cevapsız Arama' row, on a quiet channel when paired (231-243). Its 'Geri Ara' action (246-258) opens ACTION_DIAL on the Redmi, which is useless in a pocket. On the iPhone side, HistoryStore.markMissedSeen (HistoryStore.swift:162-166) is local only. No message tells the Redmi the missed calls were seen; GatewayConnection has no such message type (see the 'type' list at GatewayConnection.swift:1113-1317). (C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\android\app\src\main\AndroidManifest.xml:105)

Native: With Apple's Phone app, a missed call appears once, only on the phone the person uses, and disappears everywhere once seen. It does not come back after a restart.

Fix: Android: 1) Declare a receiver for TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION in the dialer app. Telecom then hands missed-call notifications to the default dialer and stops posting its own. In onReceive, post nothing while an iPhone is paired (PairingStore.pairedClient() != null). 2) Once the iPhone has the missed call (journal delivered or 'missed_seen' received), call telecomManager.cancelMissedCallsNotification(). The default dialer may call it, and Telecom then marks those CallLog rows NEW=0/IS_READ=1, so a reboot re-posts nothing. Also call it once at boot after the service starts, so the 4 notifications there now are cleared. 3) Skip CallUiNotifier.postMissedCallNotification entirely when paired. iOS: in markMissedSeen, send {type:'missed_seen', upToLogId}. This is a protocol addition, so add it to docs/PROTOCOL.md and make older Redmis ignore it.

Verifier: Confirmed by code and by today's device facts. The manifest's receivers (AndroidManifest.xml:105-165) include none for android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION. AOSP/Lineage Telecom's MissedCallNotifierImpl posts its own 'missed calls' notification unless the default dialer has a receiver for that action; that matches the measured MissedCallNotifierImpl/TelecomMissedCalls notification. No code calls cancelMissedCallsNotification (grep finds nothing), so the CallLog NEW flag is never cleared and Telecom re-posts at boot. CallUiNotifier.kt:218-266 posts a second, app-owned row, quiet when paired (231-243), with a 'Geri Ara' ACTION_DIAL action (246-258). Clearing the flag is safe for the journal: CallJournal.importNow only imports _ID > last (CallJournal.kt:175), so marking rows read does not republish anything. On iOS, markMissedSeen (HistoryStore.swift:162-166) is local only.

Better fix: Simpler and fully sufficient: declare a manifest receiver for TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION (exported; Telecom delivers it with the READ_PHONE_STATE receiver permission, which the app holds at AndroidManifest.xml:12). In onReceive, when PairingStore.pairedClient() != null, post nothing and call getSystemService(TelecomManager).cancelMissedCallsNotification(). Allowed for the default dialer, this marks the missed rows NEW=0/IS_READ=1, so the boot reload finds nothing. Do this at once rather than waiting for a 'missed_seen' message: the iPhone already gets every missed call through the journal (by _ID, not the NEW flag) and APNs. That makes the protocol addition optional. Telecom also sends this action with EXTRA_NOTIFICATION_COUNT = 0 on a clear, so treat count 0 as 'remove'. When unpaired, either let the receiver post the row or keep CallUiNotifier's, but not both, or you get duplicates. Also skip CallUiNotifier.postMissedCallNotification when paired. Call cancelMissedCallsNotification once when the service starts, to clear the 4 rows there now.

API notes: TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION and cancelMissedCallsNotification() (API 23+). cancelMissedCallsNotification requires MODIFY_PHONE_STATE or the default-dialer role; the app holds DIALER. The broadcast is sent with an explicit package, so the Android 8 implicit-broadcast limits do not apply. Telecom-side behaviour is from AOSP MissedCallNotifierImpl and was not re-verified on this /e/OS build.

## CALL-04 [iPhone app calling UI: keypad, Recents, contacts, favourites, in-call screen, missed calls, voicemail, tel: links from other apps, Siri and CallKit intents. Read-only review of RedmiGateway/ios, plus the Android missed-call path named in the device facts.] high ios real=True fix_ok=True
**Keypad call button is disabled while the link reconnects, which also blocks 112**

Impact: When the app is opened, the link is usually still asleep: it is closed on purpose 25 s after backgrounding. A number typed on the keypad then cannot be called; the green button is dimmed. Emergency numbers (112, 155, 156...) are blocked the same way, although the code is designed to send them to the iPhone's own radio, which needs no Redmi.

Evidence: ContentView.swift:744-746: canCall = connection.isAuthenticated && digits >= 2. ContentView.swift:715 applies .disabled(!canCall). ContentView.swift:748-750: call() goes straight to CallKitManager.startOutgoingCall and skips InAppDial.start. Favorites, Recents and Contacts all use InAppDial.start (ContentView.swift:273-288), which waits for the link and lets emergency numbers through. startOutgoingCall handles emergency numbers before the auth check (CallKitManager.swift:915-918), but the disabled button never reaches it. (C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\ios\RedmiGateway\ContentView.swift:744)

Native: Apple's keypad call button is always active once digits are entered. Emergency numbers can always be dialled, even with no SIM or service.

Fix: canCall = digits >= 2 (drop isAuthenticated), and always allow numbers in the emergency set. call() -> InAppDial.start(number), so a not-yet-ready link waits as it does for every other entry point. Move the emergency set to one shared static (now duplicated in ContentView.swift:276 and CallKitManager.swift:16).

Verifier: Confirmed. canCall requires connection.isAuthenticated (ContentView.swift:744-746), and the button is .disabled(!canCall) (ContentView.swift:715). call() goes straight to startOutgoingCall (ContentView.swift:748-750). The emergency branch there (CallKitManager.swift:915-918) runs before the auth check (920-923), but a disabled button never reaches it. The link really is down after a background stay: backgroundGrace = 25 s (GatewayConnection.swift:96). InAppDial.start (ContentView.swift:278-287) already waits for the link and sends emergency numbers straight through.

Better fix: As proposed: canCall = digits >= 2 and call() -> InAppDial.start(number). Keep a single shared emergency set; the two copies are ContentView.swift:276 and CallKitManager.swift:16. This depends on CALL-05: without visible pending feedback, a keypad call made while the link is down can be dropped silently.

API notes: 

## CALL-05 [iPhone app calling UI: keypad, Recents, contacts, favourites, in-call screen, missed calls, voicemail, tel: links from other apps, Siri and CallKit intents. Read-only review of RedmiGateway/ios, plus the Android missed-call path named in the device facts.] medium ios real=True fix_ok=True
**A call started before the link is up is silently dropped after 20 s, with nothing on screen**

Impact: Tapping a contact, favourite or Recents row, or a call in Apple's Phone Recents, shows no call screen while the link comes up. If the link takes longer than 20 s (away mode over Tailscale, slow Wi-Fi), the call never happens and no message says so. The tap looks dead.

Evidence: CallKitManager.swift:703-712: dialFromSystem only stores pendingDial and calls reconnectNow. CallKitManager.swift:621-624: when the link comes back, the dial runs only if it is less than 20 s old; otherwise it is thrown away with no lastCallError and no log. InAppDial (ContentView.swift:278-287) and both INStartCallIntent handlers (ContentView.swift:47-56) use this path. The only feedback is the generic banner 'Redmi aranıyor…' (ContentView.swift:169-178). (C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\ios\RedmiGateway\CallKitManager.swift:618)

Native: In Apple's Phone app the call screen appears at once ('arıyor…') with an End button. If the call cannot be placed, the screen says 'Arama Başarısız'.

Fix: Show the call at once: start the CXStartCallAction (state 'pending', as startOutgoingCall already does for audio gating) or show an in-app 'Arama bekliyor: <name> · Vazgeç' bar. Send dial when isAuthenticated becomes true. When the window runs out, end the CallKit call with .failed and set lastCallError ('Redmi'ye ulaşılamadı, arama yapılmadı'), shown on the call screen and as a toast in the current tab. Make the window longer over a remote link (isRemoteLink). Log both the expiry and the cancel.

Verifier: Confirmed. dialFromSystem only stores pendingDial and calls reconnectNow (CallKitManager.swift:703-712). handleGatewayLinkRestored dials only if the stored dial is under 20 s old (CallKitManager.swift:621-624). Otherwise it drops it with no lastCallError and no log. A second tap silently replaces the first. The only feedback is the generic link banner (ContentView.swift:169-178). InAppDial (ContentView.swift:285-286) and both intent handlers (ContentView.swift:47-56) go through this path.

Better fix: Both options work. Note on the CallKit-first option: sendDial refuses when not authenticated (GatewayConnection.swift:1264-1267), and startOutgoingCall sends the dial in the CXTransaction completion (CallKitManager.swift:960-975). So the dial must be deferred until isAuthenticated, and dialRefusal/pendingOutgoing (CallKitManager.swift:996-998) must let a held pending call through. The in-app 'Arama bekliyor: <ad> · Vazgeç' bar changes less of the call path and is safer. Either way: on expiry set lastCallError, show it outside the keypad (a toast or banner in the current tab), and log both the expiry and the cancel.

API notes: 

## CALL-06 [iPhone app calling UI: keypad, Recents, contacts, favourites, in-call screen, missed calls, voicemail, tel: links from other apps, Siri and CallKit intents. Read-only review of RedmiGateway/ios, plus the Android missed-call path named in the device facts.] medium ios real=True fix_ok=True
**Opening Recents does not clear missed-call notifications or the app icon badge**

Impact: After the owner looks at his missed calls, the red number stays on the app icon and the missed-call banners stay in Notification Center. They only go away at a later sync or when cleared by hand.

Evidence: HistoryStore.swift:162-166: markMissedSeen only moves missedSeenAt. It neither calls Notifier.updateBadge() nor removes the delivered notifications. Notifier has a removal helper only for SMS threads (clearConversation, Notifier.swift:113-123). Missed calls are posted with threadIdentifier 'missed' (Notifier.swift:99) and nothing ever removes them. updateBadge runs only from finishCatchUp (GatewayConnection.swift:1538) and when a message thread closes (MessagesView.swift:213). Also, markMissedSeen runs only on RecentsView.onAppear (ContentView.swift:413-416), so a missed call that arrives while Recents is on screen keeps the tab badge. (C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\ios\RedmiGateway\HistoryStore.swift:162)

Native: Opening Recents in Apple's Phone app clears the missed-call badge on the icon and on the Recents tab, and removes the missed-call notifications.

Fix: Add Notifier.clearMissedCalls(): getDeliveredNotifications, filter threadIdentifier == 'missed' (and pushed alerts with thread-id 'missed'), call removeDeliveredNotifications, then updateBadge(). Call it from markMissedSeen. Also mark missed calls seen when calls change while the Recents tab is selected and the scene is active. Send 'missed_seen' to the Redmi (see CALL-03).

Verifier: Confirmed. markMissedSeen (HistoryStore.swift:162-166) only moves missedSeenAt. Delivered missed-call notifications ('missed' thread, Notifier.swift:99) are never removed; only SMS threads have a removal helper (Notifier.swift:113-123). The icon badge is set at post time (Notifier.swift:101). It is refreshed only by finishCatchUp (GatewayConnection.swift:1538), clearConversation (Notifier.swift:120) and thread close (MessagesView.swift:213). markMissedSeen runs only in RecentsView.onAppear (ContentView.swift:413-416).

Better fix: As proposed. The Redmi's pushed missed-call alerts also use thread-id 'missed' (PushNotifier.kt:111, ApnsSender.kt:102), so filtering on threadIdentifier == 'missed' covers both. updateBadge does nothing below iOS 16 (Notifier.swift:127-129). If iOS 15 matters, add a fallback (UIApplication.shared.applicationIconBadgeNumber).

API notes: UNUserNotificationCenter.setBadgeCount is iOS 16+.

## CALL-07 [iPhone app calling UI: keypad, Recents, contacts, favourites, in-call screen, missed calls, voicemail, tel: links from other apps, Siri and CallKit intents. Read-only review of RedmiGateway/ios, plus the Android missed-call path named in the device facts.] medium both real=True fix_ok=True
**Missed-call notifications have no Call Back / Message actions, and tapping the Redmi's pushed alert opens no particular screen**

Impact: From a missed-call banner the owner cannot call back or text without opening the app and finding the row. When the alert came through Apple push and the app had been swiped away, tapping it just opens the app on whatever tab was last used.

Evidence: Notifier.swift:92-105 sets no categoryIdentifier, and no UNNotificationCategory is registered anywhere (grep). userInfo carries only ['open':'recents'] (Notifier.swift:100), without the number. didReceive (Notifier.swift:151-164) routes only by userInfo['open']. The Redmi's APNs alert (ApnsSender.kt:98-104, sent by PushNotifier.kt:105-113) has only alert/sound/thread-id: no category and no 'open' or number key. A tap on it reaches didReceive with open == nil and routes nowhere. (C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\ios\RedmiGateway\Notifier.swift:92)

Native: Apple's missed-call notification can be long-pressed for 'Geri Ara' and 'Mesaj', and a tap opens Recents.

Fix: Register a UNNotificationCategory 'missed_call' with actions 'Geri ara' and 'Mesaj gönder', both with options .foreground: iOS refuses CXStartCallAction from the background (see CallKitManager.swift:547-549). Set content.categoryIdentifier = 'missed_call' and userInfo['number']. In didReceive, switch on actionIdentifier: callback -> InAppDial.start(number); message -> AppRouter .messages(number); default -> .recents. On Android, add "category":"missed_call" to aps and top-level keys open=recents and number to the payload in ApnsSender.sendAlert, and the same for SMS (open=sms, address), so pushed alerts route and show actions even when the app is not running.

Verifier: Confirmed. No categoryIdentifier is set (Notifier.swift:92-105) and no UNNotificationCategory is registered anywhere. userInfo holds only ['open':'recents'] (Notifier.swift:100). didReceive routes only on userInfo['open'] (Notifier.swift:151-164). The Redmi's APNs alert has only alert/sound/thread-id (ApnsSender.kt:98-104; missed call at PushNotifier.kt:111, SMS at PushNotifier.kt:101), so tapping a pushed alert routes nowhere.

Better fix: Register categories in Notifier.init with center.setNotificationCategories. For pushed alerts, a fallback needs no new plaintext data. The request identifier is the apns-collapse-id ('call-<logId>' at PushNotifier.kt:111, 'sms-<id>' at PushNotifier.kt:101), so when userInfo['open'] is nil, route on the identifier prefix and look the number or address up in HistoryStore after sync. Adding 'number'/'address' as plaintext top-level APNs keys sends phone numbers through Apple even when the SMS privacy switch hides the body (PushNotifier.kt:100). Either use the identifier or seal them as the VoIP push does (PushNotifier.kt:88-89). Adding "category":"missed_call" inside aps is fine. Speculative, low severity: on a cold launch, AppRouter.pending may be set before ContentView exists. Its .onChange (ContentView.swift:38-45) does not fire for the initial value, so also read router.pending in onAppear.

API notes: Actions with UNNotificationActionOptions.foreground are needed, because CallKit refuses CXStartCallAction from the background (CallKitManager.swift:547-551).

## CALL-08 [iPhone app calling UI: keypad, Recents, contacts, favourites, in-call screen, missed calls, voicemail, tel: links from other apps, Siri and CallKit intents. Read-only review of RedmiGateway/ios, plus the Android missed-call path named in the device facts.] medium ios real=True fix_ok=True
**Contacts are read only when a tab is on screen and never refreshed, so names are missing or stale**

Impact: After iOS relaunches the app in the background (Bluetooth, VoIP push, silent push, region wake), missed-call and SMS banners show a bare number instead of the contact's name. A contact the owner adds or renames in Apple's Contacts app does not show in Recents, Messages or the call screen until the app is killed and reopened.

Evidence: PhoneContactPicker.swift:56-58: ensureLoaded is documented as 'Only called from visible UI' and runs once (the hasLoaded guard). Its only callers are view onAppear handlers (ContentView.swift:357, 414, 564, 737; CallScreen.swift:337; MessagesView.swift:82, 384) and resolve_contact (GatewayConnection.swift:670). AppDelegate (RedmiGatewayApp.swift:6-31) never loads contacts. fetch() is asynchronous (PhoneContactPicker.swift:104-138), so even a call made right before Notifier.add would miss. Notifier uses displayName for titles and bodies (Notifier.swift:80, 97), which falls back to the number when byKey is empty (PhoneContactPicker.swift:97-102). There is no CNContactStoreDidChange observer (grep), and reload() is called only from the permission button. (C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\ios\RedmiGateway\PhoneContactPicker.swift:56)

Native: Apple's Phone and Messages always show the current contact name, in notifications too, and pick up edits immediately.

Fix: In AppDelegate, call ensureLoaded() when authorizationStatus is already .authorized or .limited; never prompt from the background. Observe .CNContactStoreDidChange and refetch. Before posting a notification, if byKey is empty, look the number up directly: CNContactStore().unifiedContacts(matching: CNContact.predicateForContacts(matching: CNPhoneNumber(stringValue:)), keysToFetch:) on a background queue, then post. Keep CNLabeledValue labels in PhoneContact (cep/ev/iş) instead of the fixed 'Telefon' at ContentView.swift:594.

Verifier: Confirmed. ensureLoaded is guarded by hasLoaded and runs only from view onAppear handlers (ContentView.swift:357, 414, 564, 737; CallScreen.swift:337; MessagesView.swift:82, 384) and from resolve_contact (GatewayConnection.swift:670). AppDelegate (RedmiGatewayApp.swift:6-31) never loads contacts. fetch() is asynchronous (PhoneContactPicker.swift:104-138). Notifier titles and bodies use displayName (Notifier.swift:80, 97), which falls back to the number (PhoneContactPicker.swift:99-102). There is no CNContactStoreDidChange observer, and reload() is called only from the permission buttons (ContentView.swift:535, CallScreen.swift:423).

Better fix: As proposed, with three caveats. (1) CNAuthorizationStatus.limited exists only on iOS 18+, so switch with #available; the deployment target is 15.0. (2) A background wake (VoIP push, BLE restoration) has a tight budget, and enumerating every contact with thumbnails (PhoneContactPicker.swift:108-112) costs memory and time there. In the background, do the single-number lookup (CNContact.predicateForContacts(matching: CNPhoneNumber)) before posting and leave the full fetch for the foreground. (3) Never call ensureLoaded while the status is .notDetermined from the background; resolve_contact already does this (GatewayConnection.swift:670).

API notes: CNContact.predicateForContacts(matching: CNPhoneNumber) is iOS 11+. Notification name: Notification.Name.CNContactStoreDidChange.

## CALL-09 [iPhone app calling UI: keypad, Recents, contacts, favourites, in-call screen, missed calls, voicemail, tel: links from other apps, Siri and CallKit intents. Read-only review of RedmiGateway/ios, plus the Android missed-call path named in the device facts.] medium ios real=True fix_ok=False
**Siri cannot place calls through the app, and Siri's redial / call-back requests are ignored**

Impact: 'Hey Siri, Ahmet'i ara' or 'son arayanı geri ara' goes to the dead cellular radio or does nothing. Even 'Telefon Geçidi ile ara' cannot work. Calls from Apple's Recents work only when the intent carries a plain number.

Evidence: There is no Intents extension (one target, project.pbxproj:138-152), no INIntentsSupported key in Info.plist (1-71), no com.apple.developer.siri entitlement (RedmiGateway.entitlements:7-8) and no application(_:handlerFor:). ContentView.swift:47-56 reads only intent.contacts?.first?.personHandle?.value. An INStartCallIntent that has only recordTypeForRedialing (.lastOutgoing/.lastMissed), or a person without a handle value, is silently dropped. No INInteraction is ever donated (grep), so iOS never learns that this app is how the owner calls a contact. A hidden-number call is put in Apple's Recents with CXHandle(.generic, 'Gizli numara') (CallKitManager.swift:851-853, 862). Tapping it there sends 'Gizli numara' to startOutgoingCall, which fails its number check (CallKitManager.swift:910-913), and the error appears only on the keypad. (C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\ios\RedmiGateway\ContentView.swift:47)

Native: Apple's Phone app handles 'call X', 'redial' and 'call back my last missed call' through Siri, and Siri learns which app the person uses for each contact.

Fix: Add the Siri capability and INIntentsSupported = [INStartCallIntent, INStartAudioCallIntent]. Implement application(_:handlerFor:) returning an INStartCallIntentHandling object: resolveContacts against CNContactStore (disambiguate numbers), and handle recordTypeForRedialing using HistoryStore (last outgoing / last missed). Return .continueInApp so the existing onContinueUserActivity path dials. Extend that handler with the same redial fallback. After every call, donate INInteraction(intent: INStartCallIntent(callRecordFilter:..., contacts:[INPerson(personHandle: INPersonHandle(value:number,type:.phoneNumber), ...)]), response: nil) with direction set. In the intent handler, ignore hidden-number handles (not a dialable number) and say so, instead of failing silently.

Verifier: Confirmed. One target (project.pbxproj:138-152). No INIntentsSupported key and no Siri entitlement (Info.plist:1-71, RedmiGateway.entitlements:7-8). No application(_:handlerFor:) and no INInteraction donation (grep). onContinueUserActivity reads only contacts?.first?.personHandle?.value (ContentView.swift:47-56). A hidden number is reported with the generic handle 'Gizli numara' (CallKitManager.swift:851-853, 862). Redialling it from Apple's Recents fails the regex (CallKitManager.swift:910-913), and the error shows only on the keypad (ContentView.swift:686).

Better fix: The plan is incomplete. SiriKit also needs NSSiriUsageDescription in Info.plist and a call to INPreferences.requestSiriAuthorization from the foreground. Without that authorization Siri does not route the intent to the app, and calling the request without the key crashes. The Siri capability must also be on the App ID and in the provisioning profile the release pipeline installs (see the comment at RedmiGateway.entitlements:5-6). Keep the rest: INIntentsSupported = [INStartCallIntent, INStartAudioCallIntent] in the app's Info.plist, application(_:handlerFor:) (iOS 14+ in-app handling) returning .continueInApp, redial through HistoryStore, and INInteraction donations. For hidden numbers, either skip them in the handler with a message, or report remoteHandle = nil with localizedCallerName 'Gizli numara' so Apple's Recents has nothing to dial (needs a device check).

API notes: In-app intent handling via application(_:handlerFor:) is iOS 14+. INStartCallIntent.recordTypeForRedialing is iOS 13+. INStartCallIntentResponseCode.continueInApp exists.

## CALL-10 [iPhone app calling UI: keypad, Recents, contacts, favourites, in-call screen, missed calls, voicemail, tel: links from other apps, Siri and CallKit intents. Read-only review of RedmiGateway/ios, plus the Android missed-call path named in the device facts.] medium both real=True fix_ok=True
**No way to reach voicemail: no voicemail entry, and long-pressing 1 does nothing**

Impact: The owner cannot listen to messages left on the Redmi SIM's carrier voicemail without knowing and typing the carrier's voicemail number.

Evidence: Tabs are favorites/recents/contacts/keypad/messages (ContentView.swift:4-6, 71-93), with no voicemail entry. On the keypad, onLongPress acts only for '0' (ContentView.swift:700-702), so long-pressing 1 does nothing. Recents can show a 'voicemail' type (ContentView.swift:470), but nothing leads to it. The Android side never reads the SIM's voicemail number: no getVoiceMailNumber anywhere in android/ (grep). (C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\ios\RedmiGateway\ContentView.swift:700)

Native: In Apple's Phone app, long-pressing 1 on the keypad calls voicemail, and there is a Voicemail tab (visual voicemail, or 'Call Voicemail' where the carrier offers no visual voicemail).

Fix: Android: read TelephonyManager.getVoiceMailNumber() for the active SIM slot (priv-app, default dialer) and send it in hello/state as 'voicemailNumber' (additive field). iOS: long-press 1 -> InAppDial.start(voicemailNumber), with a short explanation if it is empty. Add a 'Sesli mesaj' row (Recents header or Favorites) that does the same. Speculative: some Turkish SIMs may return an empty voicemail number. If so, offer a setting to type it in.

Verifier: Confirmed as a missing feature. The tabs (ContentView.swift:4-6, 71-93) have no voicemail entry. The keypad long-press acts only for '0' (ContentView.swift:700-702), so holding 1 just types '1'. 'voicemail' appears only as a Recents label (ContentView.swift:470). The Android side never reads the voicemail number: VOICEMAIL_TYPE appears only in CallJournal.kt:267, and there is no getVoiceMailNumber call.

Better fix: As proposed. Use TelephonyManager.createForSubscriptionId(subId of SIM slot 2).voiceMailNumber; it needs READ_PHONE_STATE, which the app holds (AndroidManifest.xml:12). An alternative that works even when the SIM returns no number: a new 'dial_voicemail' message that makes the Redmi call TelecomManager.placeCall(Uri.fromParts(PhoneAccount.SCHEME_VOICEMAIL, "", null), extras), so Telecom resolves the carrier's voicemail itself. This is a protocol addition; older Redmis must ignore it. Also, the iOS dial path accepts only digits (CallKitManager.swift:911), so voicemail cannot go through the existing dial message without a number.

API notes: Lower priority than the owner's explicit complaints.

## CALL-11 [iPhone app calling UI: keypad, Recents, contacts, favourites, in-call screen, missed calls, voicemail, tel: links from other apps, Siri and CallKit intents. Read-only review of RedmiGateway/ios, plus the Android missed-call path named in the device facts.] medium ios real=True fix_ok=True
**Recents rows have no details view, add-to-contacts, copy, delete or grouping**

Impact: An unknown number that called cannot be saved as a contact or copied from Recents. Rows cannot be deleted. Five calls from the same person fill five rows. Tapping a row places a call at once, with no safe way to look at it first.

Evidence: ContentView.swift:383-399: each CallRecord is its own row, with onTapGesture -> InAppDial.start and only a leading 'Mesaj' swipe action. There is no trailing delete swipe, no Edit/Clear, no info button and no contextMenu. RecentRow (ContentView.swift:420-482) has no detail navigation. There is no CNContactViewController(forNewContact:) or 'add to existing contact' anywhere (grep for ContactsUI shows only the picker, PhoneContactPicker.swift:220-257). The keypad likewise has no 'Numara ekle'. (C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\ios\RedmiGateway\ContentView.swift:383)

Native: In Apple's Phone app, Recents groups consecutive calls ('Ahmet (3)'). Each row has an (i) button that opens a details card (each call's time, direction and duration, plus Call / Message / Create New Contact / Add to Existing Contact / Share / Block). Rows can be deleted with a swipe, or via Edit > Clear. Long-pressing a number copies it. The keypad offers 'Add Number' as soon as digits are typed.

Fix: Group consecutive rows with the same PhoneIdentity.key and the same missed/not-missed kind, showing a count. Add a trailing info button that pushes CallDetailView: the calls for that number with answeredOn (iPhone'da/Redmi'de), Ara/Mesaj buttons, CNContactViewController(forNewContact:) for unknown numbers, add-to-existing via the contact picker followed by CNContactViewController(for:), and Kopyala. Add a trailing 'Sil' swipe plus Edit > 'Tümünü temizle', backed by a local hidden-logId set in HistoryStore, because the journal comes from the Redmi and a resync must not bring rows back. Add 'Numara ekle' under the keypad number when it matches no contact.

Verifier: Confirmed as a gap. Each CallRecord is its own row with tap-to-call and only a leading 'Mesaj' swipe (ContentView.swift:383-399). There is no trailing delete, no Edit, no info button and no contextMenu. RecentRow (ContentView.swift:420-482) has no detail view. There is no CNContactViewController anywhere (grep); ContactsUI is used only for the picker (PhoneContactPicker.swift:220-257). Tap-to-call itself matches Apple's Recents; what is missing is Apple's (i) detail button and the add-to-contact and copy actions.

Better fix: As proposed. Rows deleted locally must stay hidden through a resync, because applyCallSync and upsertCall re-add them by logId (HistoryStore.swift:138-146). So the hidden-logId set has to be checked in upsertCall. A quick, cheap step first: add a contextMenu with 'Numarayı kopyala' and 'Mesaj gönder', as ContactDetailView already does (ContentView.swift:609-619).

API notes: CNContactViewController(forNewContact:) and CNContactViewController(for:) are iOS 9+.

## CALL-12 [iPhone app calling UI: keypad, Recents, contacts, favourites, in-call screen, missed calls, voicemail, tel: links from other apps, Siri and CallKit intents. Read-only review of RedmiGateway/ios, plus the Android missed-call path named in the device facts.] medium ios real=True fix_ok=False
**In-call Mute and Speaker buttons go out of step with the system call screen**

Impact: If the owner mutes on the lock-screen call UI or the Dynamic Island, or audio moves to AirPods or the car, the in-app screen still shows 'Sessiz' off or 'Hoparlör' on. He can believe the other side hears him when it does not, or the reverse.

Evidence: CallScreen.swift:8-9: muted and speaker are @State, read from AudioEngine only in onAppear (CallScreen.swift:21-25). AudioEngine is a plain final class, not an ObservableObject (AudioEngine.swift:11). The CXSetMutedCallAction handler (CallKitManager.swift:1479-1482) sets AudioEngine.isMuted, which the view never observes. isSpeakerEnabled changes only through setSpeakerEnabled (AudioEngine.swift:212-219), and the route-change observer (AudioEngine.swift:113-125) never updates it. CallScreen's own toggle sends CXSetMutedCallAction from its local value (CallScreen.swift:156-160), so after a system-side mute the first in-app tap sets muted=true again and appears to do nothing. (C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\ios\RedmiGateway\CallScreen.swift:8)

Native: Apple's in-call screen, the lock-screen UI and Control Center always show the same mute state and the actual audio output.

Fix: Add a small @MainActor ObservableObject (for example CallControlsState) with @Published muted and outputRoute. Set muted from the CXSetMutedCallAction handler. Set outputRoute from AVAudioSession.routeChangeNotification (currentRoute.outputs.first?.portType). CallScreen reads both instead of @State, and the speaker button shows on when the route is .builtInSpeaker. This is display only; leave the audio graph logic unchanged.

Verifier: Confirmed. muted and speaker are @State, read only in onAppear (CallScreen.swift:8-9, 21-25), and the fullScreenCover does not re-run onAppear when the app returns from the background. CXSetMutedCallAction sets AudioEngine.isMuted (CallKitManager.swift:1479-1482), but AudioEngine is not observable (AudioEngine.swift:11). isSpeakerEnabled changes only in setSpeakerEnabled (AudioEngine.swift:212-219), and the route observer (AudioEngine.swift:113-125) never updates it. One correction to the evidence: after a system mute, the first in-app tap sets muted=true, which matches the real state. The defect is the stale display before that tap. There is also a functional problem: after a route change, recovery rebuilds the engine with preservingControls (AudioEngine.swift:258), then re-applies overrideOutputAudioPort(.speaker) when isSpeakerEnabled (AudioEngine.swift:380). AirPods or the car connecting during a speaker call can therefore be pulled back to the speaker.

Better fix: Do more than the display. Publish muted from the CXSetMutedCallAction handler and the output route from routeChangeNotification, as proposed. Also, in the route-change observer, set isSpeakerEnabled = false whenever currentRoute.outputs.first?.portType != .builtInSpeaker. Otherwise the rebuild at AudioEngine.swift:380 forces the speaker back over a headset the user just chose. The speaker button then reads on only when the route is .builtInSpeaker. Verify on device with AirPods connecting during a speaker call.

API notes: AVAudioSession.routeChangeNotification; AVAudioSession.Port.builtInSpeaker/.bluetoothHFP/.carAudio.

## CALL-13 [iPhone app calling UI: keypad, Recents, contacts, favourites, in-call screen, missed calls, voicemail, tel: links from other apps, Siri and CallKit intents. Read-only review of RedmiGateway/ios, plus the Android missed-call path named in the device facts.] low ios real=True fix_ok=True
**In-app Decline and End skip CallKit, so a declined call is logged as missed in Apple's Recents**

Impact: Declining from the in-app ringing screen leaves the system ring and banner going until the Redmi confirms. The call then shows in Apple's Phone Recents as a red missed call, although the app's own Recents says 'Reddedildi'.

Evidence: CallScreen.swift:115-117: Reddet calls connection.sendReject directly. CallScreen.swift:200-202: Bitir calls sendHangup directly. Yet answer goes through CallKit (answerFromApp, CallKitManager.swift:466-476). The CallKit call ends only when the Redmi's 'disconnected' arrives. That code treats a call that was ringing and not answered here as missed and ends it with .unanswered (CallKitManager.swift:192-196). The provider's own end handler already sends reject/hangup and records endedCallIds (CallKitManager.swift:1427-1459), so a CXEndCallAction would cover both. (C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\ios\RedmiGateway\CallScreen.swift:115)

Native: In Apple's Phone app, declining stops the ring at once and Recents records the call as declined, not missed.

Fix: Add CallKitManager.endFromApp(callId:), the mirror of answerFromApp. If uuidByCallId[callId] is presented, request a CXEndCallAction (the provider sends reject or hangup); otherwise fall back to sendReject/sendHangup. Use it for Reddet, Bitir and the OtherCallCard end button (CallScreen.swift:62-65).

Verifier: Confirmed. Reddet calls connection.sendReject (CallScreen.swift:115-117), Bitir calls sendHangup (CallScreen.swift:200-202) and the other-call end calls sendHangup (CallScreen.swift:62-65), while Answer goes through CallKit (CallKitManager.swift:466-476). The CallKit call ends only on the Redmi's 'disconnected'. 'disconnecting' is ignored (CallKitManager.swift:114), so the previous state is still 'ringing' and the call is ended with .unanswered (CallKitManager.swift:192-196). The provider's CXEndCallAction handler already sends reject or hangup and records endedCallIds (CallKitManager.swift:1427-1459). The system ring continuing until the Redmi round trip is certain. That Apple's Recents shows it as a red missed call is likely but not verified on a device, so it is low confidence.

Better fix: As proposed: endFromApp(callId:) mirroring answerFromApp, with fallback to sendReject/sendHangup when there is no CallKit call (for example pickupAfterAnswer, or calls carried only on the Redmi).

API notes: CXEndCallAction via CXCallController.request.

## CALL-14 [iPhone app calling UI: keypad, Recents, contacts, favourites, in-call screen, missed calls, voicemail, tel: links from other apps, Siri and CallKit intents. Read-only review of RedmiGateway/ios, plus the Android missed-call path named in the device facts.] low ios real=True fix_ok=False
**In-call audio has only a Speaker toggle; there is no route picker for AirPods, Bluetooth or the car**

Impact: With AirPods or car Bluetooth connected, the owner cannot switch the call between iPhone, speaker and headset from the app's call screen. He has to find the route control in Control Center.

Evidence: CallScreen.swift:165-169: the only output control is a speaker toggle using AudioEngine.setSpeakerEnabled, which calls overrideOutputAudioPort(.speaker/.none) (AudioEngine.swift:212-219). There is no AVRoutePickerView anywhere (grep). (C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\ios\RedmiGateway\CallScreen.swift:165)

Native: When a Bluetooth output is available, Apple's call screen turns 'Hoparlör' into an 'Ses' button that opens the route list (iPhone / Hoparlör / AirPods / car).

Fix: When AVAudioSession.sharedInstance().availableInputs contains a .bluetoothHFP/.carAudio port, show an AVRoutePickerView (UIViewRepresentable, prioritizesVideoDevices=false) labelled 'Ses' in place of the speaker toggle; keep the toggle otherwise. This touches the audio route, so verify on device that a route change still goes through the existing requestRecovery path (AudioEngine.swift:118-120) without dropping the call.

Verifier: Confirmed. The only output control is the speaker toggle (CallScreen.swift:165-169) using overrideOutputAudioPort (AudioEngine.swift:212-219), and there is no AVRoutePickerView (grep). The session allows Bluetooth HFP/A2DP (AudioEngine.swift:201-203), so a headset can be connected, but the app offers no way to choose between routes.

Better fix: Same as proposed, plus the speaker interaction. start() re-applies overrideOutputAudioPort(.speaker) after every rebuild when isSpeakerEnabled (AudioEngine.swift:380). Route changes trigger rebuilds (AudioEngine.swift:118-120 -> CallKitManager.recoverCallAudio, CallKitManager.swift:375-394). So picking AirPods in the picker while on speaker can be undone by the next rebuild. Clear isSpeakerEnabled whenever the current output is not .builtInSpeaker (see CALL-12) before adding the picker. If AVRoutePickerView turns out unreliable in a .voiceChat session on device, build an 'Ses' menu from availableInputs with setPreferredInput, plus overrideOutputAudioPort for the speaker. Verify that the call survives each route switch.

API notes: AVRoutePickerView is iOS 11+, and prioritizesVideoDevices is iOS 13+. Behaviour in a .playAndRecord/.voiceChat CallKit session needs a device check.

## CALL-15 [iPhone app calling UI: keypad, Recents, contacts, favourites, in-call screen, missed calls, voicemail, tel: links from other apps, Siri and CallKit intents. Read-only review of RedmiGateway/ios, plus the Android missed-call path named in the device facts.] low ios real=True fix_ok=True
**Keypad details: no redial on empty, no contact suggestions, long-press 0 likely adds '+0', no key tones, no formatting**

Impact: Small daily differences from Apple's keypad: tapping call on an empty field does nothing, there are no name suggestions while typing, holding 0 probably gives '+0' instead of '+', there is no key-tone feedback, and the number shows as a run of digits.

Evidence: ContentView.swift:744-746 disables the button below 2 digits, so there is no 'fill in the last dialled number'. matchName is an exact key match only (ContentView.swift:740-742). KeypadButton (ContentView.swift:760-776) is a Button with .simultaneousGesture(LongPressGesture); SwiftUI still fires the Button action on touch-up after a long press, so long-pressing 0 appends '+' (line 701) and then '0' (line 699). That last point is not verified on a device. Taps give only a haptic (ContentView.swift:761) and no tone. The number is shown raw (ContentView.swift:667). The DTMF sheet (CallScreen.swift:579-582) also gives no local tone. (C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\ios\RedmiGateway\ContentView.swift:753)

Native: On Apple's keypad, tapping call with an empty field brings back the last dialled number. iOS 18 suggests matching contacts as you type. Holding 0 gives '+' only. Keys play DTMF tones (silent in silent mode). The number is formatted as you type.

Fix: Empty field plus call tap: fill in HistoryStore's latest outgoing number. Suggest contacts by digit prefix or T9 match in the matchName row, and tap a suggestion to fill the field. Replace the simultaneous gesture with a press-tracking gesture where a long press marks the touch consumed so the tap is skipped. Play AudioServicesPlaySystemSound(1200 + digit) for key tones (1210 for *, 1211 for #). Format the display with the same formatter as CALL-16.

Verifier: Mostly confirmed; low severity. The call button is disabled below 2 digits (ContentView.swift:744-746), so there is no redial on an empty field. matchName is an exact key match (ContentView.swift:740-742). A tap gives only a haptic (ContentView.swift:761), with no tone in the keypad or the DTMF sheet (CallScreen.swift:579-582). The number shows raw (ContentView.swift:667). The '+0' on a long press of 0 follows from Button plus simultaneousGesture(LongPressGesture) (ContentView.swift:776, 699-701), but it is not verified on a device, so plausible only.

Better fix: As proposed. Simplest guard for the long press: set a 'consumed' flag in onLongPress and skip the next Button action when it is set. Sound IDs 1200-1211 are undocumented system DTMF sounds and follow the silent switch. In the in-call DTMF sheet, play the local tone only as feedback; voice processing should keep it out of the uplink, but check this on device.

API notes: AudioServicesPlaySystemSound (AudioToolbox).

## CALL-16 [iPhone app calling UI: keypad, Recents, contacts, favourites, in-call screen, missed calls, voicemail, tel: links from other apps, Siri and CallKit intents. Read-only review of RedmiGateway/ios, plus the Android missed-call path named in the device facts.] low ios real=True fix_ok=True
**Number formatting and matching cover only Turkish mobile numbers**

Impact: Landlines (0212..., 0850...), short codes and foreign numbers show as unformatted digits in Recents, Favorites and the call screen. A number saved as 0090... never matches the same caller's +90... number, so its name is missing.

Evidence: PhoneIdentity.formatted formats only 10-digit keys starting with 5 and returns everything else as received (PhoneContactPicker.swift:22-28). PhoneIdentity.key normalizes only the '90' + 10-digit and '0' + 10-digit forms (PhoneContactPicker.swift:14-20); the '00' international prefix is not turned into '+'. (C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\ios\RedmiGateway\PhoneContactPicker.swift:14)

Native: Apple's Phone app formats every number for its region (0212 345 67 89, 0850 222 00 00, +49 151 2345 6789) and matches contacts whatever prefix form was saved.

Fix: In key(), turn a leading '00' into a country-code prefix before the existing rules. In formatted(), add Turkish landline and 0850/0800/0444 patterns, plus a +CC fallback grouping. For full coverage, consider PhoneNumberKit, a pinned dependency (decision for the owner).

Verifier: Confirmed. key() normalizes only 12-digit '90…' and 11-digit '0…' numbers (PhoneContactPicker.swift:14-20). A contact saved as 0090 532… becomes the 14-digit key '00905…', which never matches '5…'. formatted() formats only 10-digit keys starting with 5 (PhoneContactPicker.swift:22-28), so landlines, 0850 numbers and foreign numbers show as received. Cosmetic, except for the name mismatch.

Better fix: As proposed: in key(), strip a leading '00' before the existing rules ('0090532…' -> '90532…' -> '532…'). This key is also used for SMS thread grouping (MessagesView.swift:21) and favourites (PhoneContactPicker.swift:34). Changing it can merge threads or favourites stored under the old key, which is the intended effect, but check any persisted keys (HistoryStore threadSeen).

API notes: 

## CALL-17 [iPhone app calling UI: keypad, Recents, contacts, favourites, in-call screen, missed calls, voicemail, tel: links from other apps, Siri and CallKit intents. Read-only review of RedmiGateway/ios, plus the Android missed-call path named in the device facts.] low ios real=True fix_ok=True
**CallKit provider has no icon, so the system call screen shows a blank app button**

Impact: On the lock-screen call UI, the button that opens the app has no Telefon Geçidi glyph, which is less clear than other VoIP apps.

Evidence: CallKitManager.swift:88-95 sets localizedName, video, groups, handle types and includesCallsInRecents, but not iconTemplateImageData. (C:\Users\ilkay.turna\source\repos\redmi-gateway\RedmiGateway\ios\RedmiGateway\CallKitManager.swift:88)

Native: Apple's own call screen and well-made CallKit apps show a recognizable glyph on the app button.

Fix: Add a 40x40 pt single-colour template PNG to the asset catalog and set config.iconTemplateImageData = UIImage(named:)?.pngData(). This is cosmetic.

Verifier: Confirmed. CXProviderConfiguration sets supportsVideo, the call limits, supportedHandleTypes and includesCallsInRecents, but not iconTemplateImageData (CallKitManager.swift:88-95). Cosmetic.

Better fix: As proposed: a square 40 pt template PNG, from whose alpha channel the system makes the white mask, then config.iconTemplateImageData = UIImage(named:)?.pngData().

API notes: CXProviderConfiguration.iconTemplateImageData, iOS 10+.

## N1 [iPhone notifications from the app (SMS, missed calls, badge, APNs fallback, duplicates, actions, sounds, gateway alerts) plus the Redmi-side missed-call and SMS notification noise] high android real=True fix_ok=False
**Redmi posts Android Telecom's own missed-call notification ("Cevapsız çağrılar") and the app posts a second row for the same call**

Impact: Every missed call leaves a notification from what looks like the old phone app on the Redmi, which may sound, buzz or light the screen in the pocket. After a reboot all the old missed calls come back at once (4 seen today). The owner never looks at the Redmi, so these rows just pile up.

Evidence: Device facts: an active notification from com.android.server.telecom (tag MissedCallNotifierImpl, channel TelecomMissedCalls) says '4 cevapsız çağrı', and Telecom re-posted 4 of them after a reboot. In AOSP Telecom, MissedCallNotifierImpl hands the missed-call notification to the default dialer only when that package has a receiver for android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION; otherwise Telecom posts its own. AndroidManifest.xml:105-165 declares no such receiver; the only call receiver is CallActionReceiver (105-107). Nothing in the app calls TelecomManager.cancelMissedCallsNotification() (grep finds no hits). Only READ_CALL_LOG is declared (AndroidManifest.xml:15), so missed rows stay NEW=1 / IS_READ=0, and Telecom reloads them on every boot. On top of that, CallUiNotifier.kt:74-75 and 218-265 post the app's own 'Cevapsız Arama' row for the same call. When an iPhone is paired, 231-235 only switches that row to the quiet channel; it is still posted and is never cancelled once the iPhone has the call. (RedmiGateway/android/app/src/main/AndroidManifest.xml:105)

Native: On a normal phone, only the dialer the user looks at shows a missed call. Here the iPhone is that dialer, so the Redmi should show nothing.

Fix: (1) Add an exported receiver, e.g. .telecom.MissedCallsReceiver, with an intent-filter for android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION. Its onReceive only logs EXTRA_NOTIFICATION_COUNT. Telecom then delegates to the dialer and stops posting its own row. (2) Call runCatching { getSystemService(TelecomManager::class.java).cancelMissedCallsNotification() } in two places: at service start, next to CallJournal.get(this).start() (GatewayService.kt:169), and after a missed-call journal entry has been delivered to the iPhone (GatewayWebSocketServer.kt:608 and 765) or pushed. The default dialer is allowed to make this call. Telecom then marks the missed call-log rows read, which clears the 4 current rows and stops the re-post after reboot. CallJournal imports only new _IDs (CallJournal.kt:175), so marking rows read does not re-publish entries. (3) In CallUiNotifier.postMissedCallNotification, return early when PairingStore.pairedClient() != null; the iPhone already gets the call through the journal and APNs. Verify on device with `dumpsys notification` after an IVR 532 test missed call and after a reboot.

Verifier: Confirmed. The manifest has no receiver for android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION. The only call receiver is CallActionReceiver, which is not exported (AndroidManifest.xml:105-107). Nothing calls cancelMissedCallsNotification (a grep finds no hits), and only READ_CALL_LOG is declared (AndroidManifest.xml:15). The device facts show Telecom's own MissedCallNotifierImpl row on the TelecomMissedCalls channel. That matches AOSP, where Telecom posts its own row unless the default dialer package resolves that broadcast. The app also posts its own 'Cevapsız Arama' row (CallUiNotifier.kt:74-75, 218-265). When paired, that row is quiet (231-243) but nothing cancels it. The only cancel in the file is for NOTIFICATION_ID 4301 (CallUiNotifier.kt:46, 85); the missed row uses id 4400+ (221) and goes away only through setAutoCancel on tap (240). This is what the owner reports ('önceki telefon appinden bildirim').

Better fix: (1) The receiver is the real fix, and it is correct. Add <receiver android:name=".telecom.MissedCallsReceiver" android:exported="true"> with an intent-filter for android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION, and have onReceive only log. Telecom sends this broadcast with READ_PHONE_STATE as the required permission, and the app already holds it (AndroidManifest.xml:12). Telecom then delegates both live missed calls and the reload after reboot, so it no longer posts its own row. (2) The reasoning for step 2 is wrong once (1) ships. In AOSP, MissedCallNotifierImpl.clearMissedCalls skips markMissedCallsAsRead when the dialer manages the notification. cancelMissedCallNotification also sends a count=0 broadcast to the dialer and returns without cancelling Telecom's own row. So with the receiver present, cancelMissedCallsNotification neither marks the rows read nor removes the 4 rows already posted. Those 4 disappear at the next reboot, because the reload is delegated, or with one manual swipe. Drop the claim that rows get marked read. If the call-log rows should be NEW=0/IS_READ=1, the app must declare WRITE_CALL_LOG (the dialer role can grant it) and update CallLog.Calls itself for type=MISSED rows after the iPhone has received them. (3) Skipping the app's own row when PairingStore.pairedClient() != null (PairingStore.kt:38) is fine. A stricter version: post it only if the entry was not delivered to the iPhone (PushNotifier.deliveredCallSeq) or pushed. Verify with `dumpsys notification` after an IVR 532 missed call and after a reboot.

API notes: AOSP Telecom MissedCallNotifierImpl.shouldManageNotificationThroughDefaultDialer checks the default dialer package with queryBroadcastReceiversAsUser on Intent(ACTION_SHOW_MISSED_CALLS_NOTIFICATION).setPackage(dialer). TelecomManager.cancelMissedCallsNotification() needs MODIFY_PHONE_STATE or the default dialer. This was not checked against the /e/OS Telecom source; LineageOS keeps the AOSP logic. The app's journal imports only new _IDs (CallJournal.kt:173-175), so changing read flags does not re-publish entries.

## N2 [iPhone notifications from the app (SMS, missed calls, badge, APNs fallback, duplicates, actions, sounds, gateway alerts) plus the Redmi-side missed-call and SMS notification noise] high both real=True fix_ok=True
**The same SMS or missed call alerts twice: tapping or swiping away a pushed notification makes the app re-post it with a banner and sound**

Impact: Away from home, the owner taps the 'new SMS' banner. The app opens and immediately plays a second sound and shows a second banner for the message they just opened. Any pushed banner they swiped away sounds again at the next sync, up to 24 h later. After a long offline period, the catch-up plays one sound per message.

Evidence: The local copy is muted only when 'sms-<id>' is still listed in getDeliveredNotifications (Notifier.swift:55-61, 76-88, quiet at 86); missed calls work the same way (92-104). Tapping a notification removes it from Notification Center. willEnterForeground then runs reconnectNow and requestSync (GatewayConnection.swift:1437-1446). applySmsSync returns the message as new, because HistoryStore never recorded the pushed one (HistoryStore.swift:188 'known', guard at 211-212; calls 147-148). Notifier then posts it with .default sound (Notifier.swift:82), and willPresent returns [.banner, .list, .sound] (Notifier.swift:146). Even the 'quiet' replacement shows a fresh banner in the foreground, because willPresent ignores interruptionLevel. Every record in a catch-up batch gets .default sound (Notifier.swift:76-88). (RedmiGateway/ios/RedmiGateway/Notifier.swift:86)

Native: Apple Messages and Phone alert once per message or call and never re-announce an item the user has already opened or dismissed.

Fix: Keep a persisted set of notification identifiers already handled. In didReceive (Notifier.swift:151), insert response.notification.request.identifier; for an 'sms-…' identifier, also mark that thread seen. In add(sms:) and add(missed:), skip identifiers in the set. In willPresent, return [.list] (no banner, no sound) when content.interruptionLevel == .passive. In a catch-up batch, give sound only to the newest record and post the rest quiet. Full fix: add a Notification Service Extension (aps mutable-content:1) that writes every delivered 'sms-…'/'call-…' id into an App Group set. Then pushes the owner swiped away are known too, and the app never re-alerts anything iOS already showed.

Verifier: Confirmed by code path. The local copy is muted only when its identifier is still among the delivered notifications (Notifier.swift:57-59, 86, 102). Tapping or swiping a pushed banner removes it. willEnterForeground then calls reconnectNow and requestSync (GatewayConnection.swift:1437-1446). Authentication also calls requestSync (GatewayConnection.swift:896). The pushed message was never stored, so it is not 'known' (HistoryStore.swift:188, 211-212) and is returned as news (GatewayConnection.swift:634). It is then posted with .default sound (Notifier.swift:82). willPresent returns [.banner, .list, .sound] unless that thread is open (Notifier.swift:143-146), and pushed taps never set openThreadKey (see N3). Every record in a batch gets .default sound (Notifier.swift:76-88).

Better fix: The proposed fix works, with two corrections. (a) An 'sms-<gatewayId>' identifier carries no address, so didReceive cannot mark the thread seen from the identifier alone. It needs the address from userInfo, which pushes carry only after N3, or a lookup in HistoryStore by gatewayId after the sync. (b) A 'handled' set filled in didReceive covers taps only. Swiped pushes need the NSE, or a UNNotificationCategory with .customDismissAction so the dismissal reaches didReceive with UNNotificationDismissActionIdentifier. A cheaper option needs no NSE: the Redmi knows which entries it pushed (PushNotifier.kt:98-101, 109-111). It can add 'pushed': true to sync items, and iOS then never re-posts a pushed entry unless it is still delivered, in which case it replaces it quietly. Ship it with N12, because APNs returns 200 even when an expiration-0 push is later dropped. Returning [.list] for .passive in willPresent is fine (deployment target is iOS 15, project.pbxproj:254).

API notes: UNNotificationContent.interruptionLevel is iOS 15+. For a push sent with apns-collapse-id, iOS uses the collapse id as request.identifier, so 'sms-<id>' matches the local identifier. The claim that a .passive replacement still shows a foreground banner is plausible but was not tested.

## N3 [iPhone notifications from the app (SMS, missed calls, badge, APNs fallback, duplicates, actions, sounds, gateway alerts) plus the Redmi-side missed-call and SMS notification noise] medium both real=True fix_ok=True
**Tapping a pushed SMS does not open the conversation, and a pushed missed call does not open Recents**

Impact: Away from home, which is the only time pushes are used, tapping a message notification lands on whatever tab was last open. The owner has to look for the conversation by hand.

Evidence: ApnsSender.sendAlert builds a payload with only aps.alert, sound and thread-id (ApnsSender.kt:98-104). PushNotifier passes no custom keys (PushNotifier.kt:101, 111). Notifier.didReceive routes only when userInfo['open'] == 'sms' together with an 'address', or 'open' == 'recents' (Notifier.swift:153-161). Local notifications set these keys (84, 100); pushed ones never do. (RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/push/ApnsSender.kt:98)

Native: Tapping a Messages notification opens that conversation; tapping a missed-call notification opens Phone > Recents.

Fix: Give sendAlert an extra JSONObject that is merged at the top level, outside aps. For an SMS, pass {"open":"sms","address":entry.address,"gid":entry.id}; for a missed call, pass {"open":"recents","number":entry.number}. The number already appears in the title when the Redmi has no contact for it (PushNotifier.kt:99), so this exposes nothing new. Once an NSE exists, seal these fields with the existing push key, as the VoIP payload already does (PushNotifier.kt:84-89).

Verifier: Confirmed. sendAlert builds only aps.alert, sound and thread-id (ApnsSender.kt:98-104). PushNotifier passes no extra keys (PushNotifier.kt:101, 111). didReceive routes only on userInfo['open'] (Notifier.swift:153-161), and only local notifications set it (Notifier.swift:84, 100).

Better fix: Merge the extra keys at the top level, outside aps, as proposed. One correction: this does expose something new. When the Redmi resolves a contact name, the title is the name (PushNotifier.kt:99, 110), and adding 'address'/'number' puts the phone number into Apple's payload. No NSE is needed to avoid that. didReceive runs inside the app, which holds the pairing key, so send {"open":"sms","p":<BridgeCrypto.seal(derive(key,"push"), {address,gid}, "phonebridge-push")>} the way the VoIP payload does (PushNotifier.kt:84-89), and open it in didReceive with BridgeCrypto.open (VoipPush.swift:39-42). Leave 'open' in plaintext.

API notes: Custom top-level keys appear in response.notification.request.content.userInfo next to 'aps'.

## N4 [iPhone notifications from the app (SMS, missed calls, badge, APNs fallback, duplicates, actions, sounds, gateway alerts) plus the Redmi-side missed-call and SMS notification noise] medium ios real=True fix_ok=True
**No Reply or Mark as Read on SMS notifications, and no Call Back or Message on missed-call notifications**

Impact: To answer an SMS or return a missed call, the owner has to open the app and find the thread or number. They cannot reply from the lock screen or the banner the way they could in Messages and Phone.

Evidence: There is no UNNotificationCategory, categoryIdentifier or UNTextInputNotificationAction anywhere in the iOS target (grep). Content is built without a category (Notifier.swift:79-87, 95-103), and the APNs aps payload has no 'category' (ApnsSender.kt:99-102). The missed-call userInfo holds only 'open':'recents' (Notifier.swift:100), so a call-back action would have no number to dial. sendSms fails at once when the link is not authenticated (GatewayConnection.swift:1306-1313), which is the normal state in the background, because enterBackground closes the link after the grace period. (RedmiGateway/ios/RedmiGateway/Notifier.swift:79)

Native: A long-press on a Messages notification gives an inline reply field. A missed-call notification offers Call Back and Message.

Fix: Register two categories in Notifier.init with center.setNotificationCategories. Category 'sms': UNTextInputNotificationAction('reply', 'Yanıtla', textInputButtonTitle 'Gönder') plus UNNotificationAction('read', 'Okundu Olarak İşaretle'). Category 'missed': 'callback' 'Geri Ara' and 'message' 'Mesaj Gönder', both with .foreground. Set content.categoryIdentifier on local notifications, put 'category' in ApnsSender.sendAlert, and add 'number' to the missed-call userInfo. In didReceive: for 'reply', begin a background task, call GatewayConnection.wakeFromBeacon(), wait for isAuthenticated with the bounded loop from RedmiGatewayApp.swift:60-68, then sendSms. Call completionHandler after the send result or about 25 s. If the send fails, post a local 'Gönderilemedi' notification in the same thread. For 'read', call markThreadSeen, clearConversation and updateBadge. For 'callback', call CallKitManager.shared.dialFromSystem(number) (CallKitManager.swift:705). For 'message', route to .messages(number).

Verifier: Confirmed. A grep of the iOS target finds no UNNotificationCategory, categoryIdentifier or UNTextInputNotificationAction. Content has no category (Notifier.swift:79-87, 95-103), and the aps payload has none either (ApnsSender.kt:99-102). The missed-call userInfo holds only 'open' (Notifier.swift:100). sendSms fails at once when the link is not authenticated (GatewayConnection.swift:1306-1313). The bounded wait loop exists at RedmiGatewayApp.swift:60-68, and dialFromSystem at CallKitManager.swift:705.

Better fix: The plan is sound. Use UNTextInputNotificationAction(identifier:title:options:textInputButtonTitle:textInputPlaceholder:) with no .foreground option, so iOS runs the reply in the background, and call completionHandler only once. Register the categories in Notifier.init, which runs at launch (RedmiGatewayApp.swift:13). For pushes, 'category' goes inside aps. The address/number for reply and call-back must reach pushed notifications too (see N3). A reply sent near the end of the background window may be left pending. expirePendingSends will then mark it failed at the next launch (HistoryStore.swift:268-283), and a late result still settles it. That is acceptable, but tell the owner through the N11 notification.

API notes: UNNotificationAction/UNTextInputNotificationAction are available on iOS 10+, and the icon variants on iOS 15+. A background action gets roughly 30 s of execution time.

## N5 [iPhone notifications from the app (SMS, missed calls, badge, APNs fallback, duplicates, actions, sounds, gateway alerts) plus the Redmi-side missed-call and SMS notification noise] medium both real=True fix_ok=True
**Notifications show a raw number instead of the contact name after a background relaunch; pushed notifications use the Redmi's address book**

Impact: A message or missed call from a saved contact appears as '0532 123 45 67' (local) or '+90532…' (push) instead of the name. This happens most often exactly when the phone is locked in a pocket.

Evidence: PhoneContactDirectory loads contacts only from visible UI ('Only called from visible UI', PhoneContactPicker.swift:56-57; callers ContentView.swift:357, 414, 564, 737, MessagesView.swift:82, 384, CallScreen.swift:337), and the load runs asynchronously (PhoneContactPicker.swift:104-107). When iOS relaunches the app in the background for a Bluetooth event (RedmiGatewayApp.swift:8-13), byKey is empty. displayName then falls back to PhoneIdentity.formatted (PhoneContactPicker.swift:99-102), which feeds the title (Notifier.swift:80) and the missed-call body (Notifier.swift:97). Pushed notifications take the name from the Redmi's ContactsContract (PushNotifier.kt:99, 110, 115-124), but the owner's contacts live on the iPhone ('nothing is sent to the Redmi', PhoneContactPicker.swift:37). (RedmiGateway/ios/RedmiGateway/PhoneContactPicker.swift:57)

Native: Messages and Phone always title the notification with the contact's name.

Fix: In Notifier.add, resolve the name synchronously when contacts are authorized and the directory is not loaded yet: CNContactStore().unifiedContacts(matching: CNContact.predicateForContacts(matching: CNPhoneNumber(stringValue: number)), keysToFetch: [CNContactFormatter.descriptorForRequiredKeys(for: .fullName)]). Also call ensureLoaded() from AppDelegate when the status is already .authorized; this never shows a prompt. For pushes, add a Notification Service Extension with mutable-content:1 that rewrites the title from a name map the app keeps in an App Group. The same NSE would also allow sealing the SMS body, which currently crosses Apple's servers in plain text (PushNotifier.kt:136-148).

Verifier: Partly confirmed. The local path is real: ensureLoaded runs only from views' onAppear (ContentView.swift:357, 414, 564, 737; MessagesView.swift:82, 384; CallScreen.swift:337) and the resolve_contact handler (GatewayConnection.swift:670), which the finding missed. The fetch is asynchronous (PhoneContactPicker.swift:104-137), and displayName falls back to the formatted number (PhoneContactPicker.swift:99-102). After a background relaunch (RedmiGatewayApp.swift:8-13) with no scene shown, nothing loads contacts before Notifier.add. The push half is overstated. The Redmi ships 701 seed contacts (assets/seed_contacts.vcf), which ContactResolver.loadSeedContacts (called at RedmiGatewayApp.kt:42) writes into ContactsContract through syncToSystemContacts (ContactResolver.kt:141, 148-226). PushNotifier.contactName therefore usually finds a name. Only contacts that exist on the iPhone alone are missed. Severity is low to medium and conditional on iOS having terminated the app.

Better fix: On iOS, the synchronous CNContactStore.unifiedContacts(matching: CNContact.predicateForContacts(matching: CNPhoneNumber(stringValue:)), keysToFetch:) fallback in Notifier.add is correct (iOS 11+). Calling ensureLoaded() from the AppDelegate when the status is .authorized is also fine. For pushes, an NSE is not needed: make PushNotifier.contactName try ContactResolver.getCached(number) first. That cache holds names the iPhone returned through resolve_contact (GatewayWebSocketServer.kt:494). Then fall back to ContactsContract.

API notes: CNContact.predicateForContacts(matching: CNPhoneNumber) is iOS 11+. It needs contacts authorization, which already exists, and the device unlocked since boot.

## N6 [iPhone notifications from the app (SMS, missed calls, badge, APNs fallback, duplicates, actions, sounds, gateway alerts) plus the Redmi-side missed-call and SMS notification noise] medium both real=True fix_ok=False
**Redmi problems (no vendor fix, not default dialer, low battery, gateway unreachable) never reach the iPhone; the only warning is a high-importance notification on the pocketed Redmi**

Impact: After an /e/OS update removes the audio fix, or the Redmi's battery runs down, or Bluetooth has been gone for an hour, the iPhone says nothing. The owner finds out from a failed call or from missing SMS. Meanwhile the warning that does exist may buzz the Redmi in the pocket.

Evidence: HealthReporter.publish posts 'iPhone sesi çalışmaz' on the Redmi itself (HealthReporter.kt:61-90) on a channel created with IMPORTANCE_HIGH (HealthReporter.kt:92-101, line 96). It is called from GatewayService.reportHealth (GatewayService.kt:285-291). The protocol to the iPhone has no health or battery field: HelloMessage (GatewayMessage.swift:18-27) and BLECallState (BLECentralManager.swift:5-16). The Android sources never read the Redmi's own battery level (no BatteryManager use); the 'battery' entry in STATUS_KEYS (GatewayWebSocketServer.kt:66-70) is the iPhone's battery. iOS posts only SMS and missed-call notifications (Notifier.swift:55-69). (RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/service/HealthReporter.kt:61)

Native: 

Fix: Add 'health': [codes] and 'battery': {level, charging} to hello/state messages, and a compact flag to the BLE state characteristic. On iOS, add Notifier.postGatewayProblem(code) with threadIdentifier 'gateway' and a fixed identifier per code, so a repeat replaces the old notification instead of stacking. Use .timeSensitive for audio-critical problems; this needs the time-sensitive entitlement. Post one notification when the Redmi battery reaches 20 % and one at 10 %. Post 'Redmi'ye ulaşılamıyor' when BLE has been disconnected for more than about 15 min (the suspended app receives didDisconnectPeripheral) and remove it on reconnect. In away mode, the Redmi sends the same alert through APNs with collapse-id 'gateway-<code>'. When an iPhone is paired, move the HealthReporter channel to IMPORTANCE_LOW.

Verifier: Confirmed as a gap. HealthReporter.publish posts 'iPhone sesi çalışmaz' on the Redmi (HealthReporter.kt:61-90) through an IMPORTANCE_HIGH channel (HealthReporter.kt:93-96). HelloMessage (GatewayMessage.swift:18-32), StateResponseMessage (GatewayMessage.swift:98-100) and BLECallState (BLECentralManager.swift:5-16) carry no health or Redmi battery field. No Android code reads BatteryManager (grep). 'battery' in STATUS_KEYS is the iPhone's own status (GatewayWebSocketServer.kt:66-70). The iPhone sees vendorFix/defaultDialer only on demand, through remote diagnostics in Settings (SettingsView.swift:257; DiagnosticsReport.kt:83, 92). Notifier posts only SMS and missed calls.

Better fix: Most of the plan holds; three parts need changes. (a) A suspended iOS app cannot run a 15-minute timer. In didDisconnectPeripheral, schedule a UNNotificationRequest with UNTimeIntervalNotificationTrigger(timeInterval: 900, repeats: false) and identifier 'gateway-unreachable', and remove it on reconnect with removePendingNotificationRequests and removeDeliveredNotifications. (b) The app cannot simply 'move' HealthReporter's channel to LOW. createNotificationChannel can only lower importance if the user has not changed the channel. Use a second quiet channel and pick one based on PairingStore.pairedClient(), as CallUiNotifier does (CallUiNotifier.kt:234-235, 296-303). (c) Do not grow the sealed BLE state blob. Add health and battery to hello/get_state over the WebSocket. For BLE, bump a 'healthSeq' the same way callsSeq/smsSeq trigger a catch-up. Read the Redmi battery from the sticky ACTION_BATTERY_CHANGED intent or BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY). .timeSensitive needs the com.apple.developer.usernotifications.time-sensitive entitlement and a provisioning profile that includes that capability (scripts/ensure_push_profile.py).

API notes: 

## N7 [iPhone notifications from the app (SMS, missed calls, badge, APNs fallback, duplicates, actions, sounds, gateway alerts) plus the Redmi-side missed-call and SMS notification noise] medium ios real=True fix_ok=True
**Missed-call notifications and the badge are not cleared when Recents is opened**

Impact: After the owner looks at Recents, the old 'Cevapsız arama' banners stay on the lock screen and in Notification Center. The app icon badge keeps counting calls already seen until some unrelated sync happens.

Evidence: RecentsView.onAppear only calls history.markMissedSeen() (ContentView.swift:413-416). markMissedSeen (HistoryStore.swift:162-166) neither calls Notifier.updateBadge nor removes delivered notifications. clearConversation removes only SMS threads (Notifier.swift:114-123). The badge is otherwise refreshed only in finishCatchUp (GatewayConnection.swift:1538) and when a conversation is closed (MessagesView.swift:211-214). (RedmiGateway/ios/RedmiGateway/ContentView.swift:415)

Native: Opening Phone > Recents clears the missed-call notifications and the Phone badge.

Fix: Add Notifier.clearMissed(): remove delivered notifications whose threadIdentifier == 'missed', then call updateBadge(). Call it from RecentsView.onAppear, and also on didBecomeActive while the Recents tab is selected, because onAppear does not fire again for a tab that is already on screen.

Verifier: Confirmed. RecentsView.onAppear calls only ensureLoaded and markMissedSeen (ContentView.swift:413-416). markMissedSeen only moves missedSeenAt (HistoryStore.swift:162-166). updateBadge is called only from finishCatchUp (GatewayConnection.swift:1538) and the conversation view (MessagesView.swift:209, 213), and clearConversation matches only 'sms-' threads (Notifier.swift:114-117). Missed-call notifications, local and pushed, use thread 'missed' (Notifier.swift:99; PushNotifier.kt:111) and are never removed. Minor: a sync that finishes after the Recents onAppear does refresh the badge.

Better fix: Add Notifier.clearMissed(): getDeliveredNotifications, filter threadIdentifier == 'missed', removeDeliveredNotifications, then updateBadge(). Call it from RecentsView.onAppear and on didBecomeActive while selectedTabRaw == recents (ContentView.swift:12).

API notes: 

## N8 [iPhone notifications from the app (SMS, missed calls, badge, APNs fallback, duplicates, actions, sounds, gateway alerts) plus the Redmi-side missed-call and SMS notification noise] medium both real=True fix_ok=True
**Pushed SMS and missed-call notifications never change the app badge**

Impact: While away, the icon badge stays at whatever it was when the owner left, however many messages and missed calls arrive. It does not work as an 'unread' signal the way Messages and Phone badges do.

Evidence: The APNs aps payload has no 'badge' (ApnsSender.kt:99-102). Local notifications set content.badge (Notifier.swift:85, 101), but pushes are the only path while away (PushNotifier.kt:98, 109). In the foreground, willPresent leaves out .badge (Notifier.swift:146), and live 'sms' / 'call_log' messages do not call updateBadge (GatewayConnection.swift:642-645). (RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/push/ApnsSender.kt:99)

Native: Messages and Phone badges count unread messages and missed calls as they arrive.

Fix: Have the iPhone report its badge count in client_status or with each sync. The Redmi keeps 'last reported badge + number pushed since then' and puts it in aps.badge. With an NSE, the extension can instead read the count from the App Group, add 1, and set content.badge. Also call updateBadge() after the live 'sms' and 'call_log' handlers.

Verifier: Confirmed. The aps payload has no 'badge' (ApnsSender.kt:99-102). Local notifications set content.badge (Notifier.swift:85, 101), but willPresent omits .badge (Notifier.swift:146), so a foreground post does not apply it. The live 'sms' and 'call_log' handlers do not call updateBadge (GatewayConnection.swift:642-645).

Better fix: Either report the count in client_status and have the Redmi send last count + pushed since, or use an NSE. Both are valid. The cheapest local part: add .badge to the willPresent options, or call updateBadge() after the live handlers. aps.badge sets an absolute number, so the Redmi must reset its 'pushed since' counter whenever the iPhone reports a new count.

API notes: setBadgeCount is iOS 16+ and is already guarded (Notifier.swift:127).

## N9 [iPhone notifications from the app (SMS, missed calls, badge, APNs fallback, duplicates, actions, sounds, gateway alerts) plus the Redmi-side missed-call and SMS notification noise] medium ios real=True fix_ok=False
**Bank and OTP codes cannot be copied from the notification, and iOS AutoFill never sees them**

Impact: For every bank or e-Devlet code, the owner has to open the app, find the message and select the digits by hand. Selecting part of a message does not work in this app either, as the owner reports. In Messages, the code appeared above the keyboard.

Evidence: The SMS notification is plain title and body with no action (Notifier.swift:79-87). UIPasteboard appears only in number-copy buttons (ContentView.swift:616, 679) and the report copy (SettingsView.swift:265). iOS security-code AutoFill reads codes only from Messages (and Mail), never from a third-party app's notifications, so relayed codes are invisible to it. That part is a platform limit. (RedmiGateway/ios/RedmiGateway/Notifier.swift:81)

Native: Messages offers 'Copy Code' and fills the code into the keyboard's AutoFill bar.

Fix: Detect a 4-8 digit code near words such as kod, şifre, code, OTP or doğrulama in the body. For those messages, use category 'otp' with UNNotificationAction('copy', 'Kodu Kopyala (123456)'), which sets UIPasteboard.general.string. Run the action in the background; if the device refuses the background pasteboard write, use .foreground. Also show the code in content.subtitle so it can be read straight from the lock screen.

Verifier: Real as a missing convenience, low to medium severity. SMS notifications have no actions (Notifier.swift:79-87). UIPasteboard is used only for numbers and the report (ContentView.swift:616, 679; SettingsView.swift:265). iOS security-code AutoFill reads only Messages and Mail, which is a platform limit. The owner's actual complaint about partial selection and links is in-app. MessagesView.swift:288 uses SwiftUI .textSelection(.enabled), which on iOS offers only whole-text copy and no data detectors. That belongs to the messages-UI area, not notifications.

Better fix: An action title cannot carry the code. UNNotificationCategory actions are registered ahead of time with fixed titles, so 'Kodu Kopyala (123456)' is impossible for pushes and racy for local posts. Use a fixed 'Kodu Kopyala' action and put the code in content.subtitle. Writing UIPasteboard from a background action is unreliable, so give the action the .foreground option and copy in didReceive, or open the conversation with a copy button. For pushes, the Redmi has to detect the code (Kotlin) and set aps.category 'otp' plus the code, sealed as in N3. The bigger win for the owner: replace .textSelection with a UITextView wrapper (isEditable=false, isSelectable=true, dataDetectorTypes=[.link,.phoneNumber]). That gives range selection and tappable blue links like Messages.

API notes: 

## N10 [iPhone notifications from the app (SMS, missed calls, badge, APNs fallback, duplicates, actions, sounds, gateway alerts) plus the Redmi-side missed-call and SMS notification noise] low android real=True fix_ok=True
**Pushed SMS thread-id breaks for alphanumeric and foreign senders: all bank and operator SMS stack in one 'sms-' group and stay after the conversation is read**

Impact: While away, AKBANK, Turkcell, e-Devlet and courier SMS all group under one stack. Opening the conversation does not clear them from Notification Center.

Evidence: smsPushThreadId keeps only digits (PushNotifier.kt:156-157), so every alphanumeric sender becomes 'sms-', and a +49… number becomes its last 10 digits. iOS uses PhoneIdentity.key (PhoneContactPicker.swift:14-20): uppercase text for alphanumeric senders and all digits for non-TR numbers, used in the thread id at Notifier.swift:83. clearConversation (Notifier.swift:114-117) and the open-thread banner suppression (Notifier.swift:143) both match on that thread id. (RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/push/PushNotifier.kt:156)

Native: 

Fix: Port PhoneIdentity.key exactly into smsPushThreadId: no digits → trimmed uppercase; 12 digits starting with 90, or 11 starting with 0 → last 10 digits; otherwise all digits. Add one shared set of test vectors that both the Kotlin and Swift unit tests check.

Verifier: Confirmed, low severity. smsPushThreadId keeps only digits (PushNotifier.kt:156-157), so every alphanumeric sender becomes 'sms-', and a +49 number becomes its last 10 digits. iOS uses PhoneIdentity.key (PhoneContactPicker.swift:14-20) for the thread (Notifier.swift:83) and for clearing and suppression (Notifier.swift:115-117, 143). This self-heals partly: once the app syncs, the local copy replaces the pushed notification under the same 'sms-<id>' identifier with the correct thread (Notifier.swift:78, 86-87). So the mismatch lasts until the next sync.

Better fix: Port PhoneIdentity.key exactly as proposed: digits empty → trim().uppercase(); 12 digits starting with '90' → last 10; 11 digits starting with '0' → last 10; otherwise all digits. Kotlin uppercase() and Swift uppercased() are both locale-free. Do not reuse ContactResolver.normalize, which differs (0090 rule and the Turkish-only last-10 rule, ContactResolver.kt:38-58). Add shared test vectors.

API notes: 

## N11 [iPhone notifications from the app (SMS, missed calls, badge, APNs fallback, duplicates, actions, sounds, gateway alerts) plus the Redmi-side missed-call and SMS notification noise] low ios real=True fix_ok=True
**No notification when an SMS sent from the iPhone fails**

Impact: The owner sends a message and leaves the app. If the Redmi rejects it or the 90 s timeout expires, nothing tells them; the failure shows only inside the thread.

Evidence: Failures only change history status: completePending(success:false) at GatewayConnection.swift:659, 1310 and 1331, and expirePendingSends at launch (HistoryStore.swift:268-283). No Notifier call follows any of them. The timeout is 90 s (GatewayConnection.swift:1300), long enough that the app is usually off screen when it expires. (RedmiGateway/ios/RedmiGateway/GatewayConnection.swift:1331)

Native: Messages posts a 'Not Delivered' alert and puts a red exclamation mark on the message.

Fix: Add Notifier.postSendFailed(record): identifier 'fail-<requestId>', the recipient's thread id, body 'Mesaj gönderilemedi', posted only when the app is not active. Tapping it opens the conversation, where 'Tekrar dene' is shown.

Verifier: Confirmed. Send failures only change the history status: GatewayConnection.swift:1310 (not authenticated), 659 (Redmi result), 1331 (90 s timeout, smsResultTimeout at 1300), and expirePendingSends at launch (HistoryStore.swift:268-283). No Notifier call follows any of them. Apple Messages notifies when a send fails.

Better fix: Post 'fail-<requestId>' only when UIApplication.shared.applicationState != .active. Use the recipient's 'sms-<key>' thread and userInfo open=sms with the address. Remove the notification when a late success settles the same requestId, because completePending can turn failed into sent (HistoryStore.swift:234-238).

API notes: 

## N12 [iPhone notifications from the app (SMS, missed calls, badge, APNs fallback, duplicates, actions, sounds, gateway alerts) plus the Redmi-side missed-call and SMS notification noise] low android real=True fix_ok=True
**SMS and missed-call pushes use apns-expiration 0, so a brief iPhone offline moment drops them**

Impact: If the iPhone is offline for a moment when an SMS arrives (hotspot restart, network switch; it has no cellular), the alert is lost. The owner learns about the message only the next time the app syncs, and not at all if that is more than 24 h later.

Evidence: post() sets apns-expiration 0 for every push type (ApnsSender.kt:189-190), including sendAlert (98-104). With 0, APNs tries once and stores nothing. The local fallback ignores entries older than 24 h (HistoryStore.swift:211-212, 147-148). Whether this happens in practice depends on network gaps, which were not measured. (RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/push/ApnsSender.kt:190)

Native: 

Fix: Set the expiration per push type: 0 for VoIP and background pushes, now + 3600 s for SMS and missed-call alerts (keep the collapse-id). Ship this together with the N2 dedup, so a late push does not alert again after the local copy.

Verifier: The code is as described: post() sets apns-expiration 0 for every push type (ApnsSender.kt:189-190), sendAlert included (ApnsSender.kt:98-104). This is a deliberate choice ('Never deliver late', line 189). The impact is speculative, as the finding says: no network gap was measured. If a push is dropped, the next sync posts the message locally, but only within 24 h (HistoryStore.swift:211-212), and while away a sync happens only when the app is opened. Low severity.

Better fix: Use per-type expiration as proposed: alerts get now+3600 with the collapse-id kept, VoIP and background stay 0. Note that APNs store-and-forward keeps only the most recent notification per app and device. After an offline period, only the last alert arrives, and the rest still depend on the next sync. Ship it with the N2 dedup.

API notes: 

## N13 [iPhone notifications from the app (SMS, missed calls, badge, APNs fallback, duplicates, actions, sounds, gateway alerts) plus the Redmi-side missed-call and SMS notification noise] low ios real=True fix_ok=True
**Not a communication notification: no sender photo, and Focus 'allowed people' cannot let a contact's SMS through**

Impact: Banners show the app icon instead of the contact's photo. Under Sleep or Work Focus, SMS from people the owner allowed are held back unless the whole app is allowed.

Evidence: NSUserActivityTypes lists only call intents (Info.plist:44-48). The entitlements file has only aps-environment (RedmiGateway.entitlements:4-9). The code never donates an INSendMessageIntent (grep), and content is never passed through updating(from:) (Notifier.swift:79-87). Contact thumbnails are already loaded (PhoneContactPicker.swift:111). (RedmiGateway/ios/RedmiGateway/RedmiGateway.entitlements:7)

Native: Messages notifications show the sender's photo and respect Focus allowed-people lists.

Fix: Add the com.apple.developer.usernotifications.communication entitlement and INSendMessageIntent to NSUserActivityTypes. In Notifier.add: build an INPerson (phone-number handle, displayName, INImage from the thumbnail), build an INSendMessageIntent with conversationIdentifier = thread key, serviceName 'SMS' and that person as sender, donate an incoming INInteraction, then set content = try content.updating(from: intent). Do the same in the NSE for pushes.

Verifier: Confirmed as a gap, low severity. NSUserActivityTypes lists only call intents (Info.plist:44-48), and the entitlements contain only aps-environment (RedmiGateway.entitlements:7-8). The code never donates an INSendMessageIntent and never calls updating(from:) (grep). Contact thumbnails are already fetched (PhoneContactPicker.swift:111). The deployment target is iOS 15.0 (project.pbxproj:254), which is enough for the API.

Better fix: As proposed, with these details. UNNotificationContent.updating(from:) throws and returns an immutable UNNotificationContent; post that object and set any category and userInfo before the call. INPerson needs personHandle INPersonHandle(value:type:.phoneNumber) so Focus can match the iPhone contact. The com.apple.developer.usernotifications.communication capability must be added to the App ID and to the profile that scripts/ensure_push_profile.py installs. Otherwise the signed build fails or the entitlement is ignored. Pushes need the NSE.

API notes: 

## N14 [iPhone notifications from the app (SMS, missed calls, badge, APNs fallback, duplicates, actions, sounds, gateway alerts) plus the Redmi-side missed-call and SMS notification noise] low android real=False fix_ok=True
**The ROM's Message app, as SMS role holder, probably posts its own SMS notifications on the Redmi (inferred, not measured)**

Impact: If it does, every incoming SMS sounds or lights the Redmi in the pocket and leaves a growing pile of unread notifications that the iPhone never clears.

Evidence: Device fact: the SMS role holder is foundation.e.message. The gateway only listens to SMS_RECEIVED (AndroidManifest.xml:145-152) and mirrors the provider that the role holder fills (SmsJournal.kt:18-24). No code refers to foundation.e.message or ROLE_SMS, and nothing marks messages read on the Redmi after the iPhone reads them. The device facts list no active foundation.e.message notification, so this is speculative. (RedmiGateway/android/app/src/main/AndroidManifest.xml:145)

Native: 

Fix: Check first: send a test SMS, then run `dumpsys notification --noredact` and look for foundation.e.message. If it posts, the cheap fix for the 4-month window is a one-time setting: Ayarlar › Uygulamalar › Mesaj › Bildirimler off. Add it to the setup checklist on the Redmi status screen. Taking over the SMS role is a large change and not recommended now.

Verifier: Not shown. The code side is accurate: the gateway only listens to SMS_RECEIVED (AndroidManifest.xml:145-152), and no code refers to foundation.e.message. But the device facts list no active foundation.e.message notification, and the owner's complaint is about missed calls only. A role holder posting its own SMS notification is likely, but it is unmeasured. Treat this as a check to run, not a defect.

Better fix: Run the check first: send a test SMS, then run `dumpsys notification --noredact | grep foundation.e.message`. If it posts, the right fix for this window is the one-time Settings › Apps › Message › Notifications off, added to the Redmi setup checklist. The gateway cannot mark SMS read in the provider without holding the SMS role, so nothing in code clears that pile.

API notes: 

## quiet-1 [Redmi in the pocket: things that ring, vibrate, light the screen or post notifications on the Redmi (missed calls, SMS, ringer, the app's own channels, other system sources)] high android real=True fix_ok=True
**No SHOW_MISSED_CALLS_NOTIFICATION receiver, so Telecom posts its own missed-call notification, it piles up, and it comes back after every reboot**

Impact: Every missed call buzzes and lights the Redmi in the pocket (TelecomMissedCalls: 'Cevapsız çağrılar - 4 cevapsız çağrı'). The count only grows because nothing ever clears it, and after each reboot Telecom posts all of them again at once. The iPhone already shows the missed call through CallJournal and APNs, so the Redmi row is pure noise.

Evidence: The manifest receivers are CallActionReceiver (AndroidManifest.xml:105-107), KnownWifiReceiver (110-113), LabModeReceiver (116-119), SmsReceiver (145-152) and BootReceiver (155-165). None of them filters android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION. A grep of src/main finds no cancelMissedCallsNotification, CallLog.Calls.NEW or IS_READ anywhere. The manifest declares READ_CALL_LOG (line 15) but not WRITE_CALL_LOG. CallJournal only queries CallLog (CallJournal.kt:172-178) and never writes it. The Recents badge in PhoneActivity uses a private pref (PhoneActivity.kt:504-513) and clears nothing in Telecom. Platform rule (AOSP MissedCallNotifierImpl, not verified on /e/OS): showMissedCallNotification first asks shouldManageNotificationThroughDefaultDialer(), which runs queryBroadcastReceivers for ACTION_SHOW_MISSED_CALLS_NOTIFICATION with setPackage(defaultDialer). If a receiver exists, Telecom sends that broadcast instead, guarded by READ_PHONE_STATE (the app has it, line 12), with EXTRA_NOTIFICATION_COUNT, EXTRA_NOTIFICATION_PHONE_NUMBER, EXTRA_CLEAR_MISSED_CALLS_INTENT and, when count==1, EXTRA_CALL_BACK_INTENT, and it posts nothing itself. Otherwise Telecom posts on its own TelecomMissedCalls channel (AOSP: IMPORTANCE_DEFAULT with vibration and lights). At boot, reloadFromDatabase() re-reads the CallLog rows with type=MISSED and new=1/unread and shows each one again, which is the 4-at-once re-post seen on the device. clearMissedCalls() sits behind TelecomManager.cancelMissedCallsNotification(), which accepts MODIFY_PHONE_STATE or the default dialer, and the app has both (AndroidManifest.xml:25-26 plus the role). It cancels the row, but it marks the CallLog rows read only while the dialer does NOT handle the broadcast. Once the receiver exists, marking them read becomes the dialer's job. (RedmiGateway/android/app/src/main/AndroidManifest.xml:105)

Native: Stock Android dialers (AOSP Dialer, Google Phone) register this receiver and own the missed-call notification. It clears once the call log has been seen and does not come back after a reboot. On the iPhone, a missed call alerts once and clears when Recents is opened.

Fix: (1) Manifest: add <uses-permission android:name="android.permission.WRITE_CALL_LOG"/> and <receiver android:name=".telecom.MissedCallsReceiver" android:exported="true"><intent-filter><action android:name="android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION"/></intent-filter></receiver>. exported must be explicit at targetSdk 34, and the sender is system_server. (2) Handler: read count = getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0) and number = getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER), but use them only as a trigger: CallJournal.get(ctx).scheduleImport(0). Do not forward the extras to the iPhone. The journal dedups by log_id (CallJournal.kt:76-77, 193), so boot replays create no duplicate iPhone alerts, and PushNotifier.kt:105-112 already sends the APNs alert. When paired, post nothing. When unpaired and count>0, post the app's own MISSED_CHANNEL row. When count==0, cancel the app's rows. Log the count without the number in DiagnosticsLog. (3) Clearing: on the journal worker, once a missed entry is delivered (deliveredCallSeq is set at GatewayWebSocketServer.kt:608/765, or after sendAlert in PushNotifier.kt:111), run contentResolver.update(CallLog.Calls.CONTENT_URI, ContentValues{NEW=0, IS_READ=1}, "type=? AND (new=1 OR is_read=0)", arrayOf(MISSED_TYPE)) and then telecom.cancelMissedCallsNotification(). That resets Telecom's counter and triggers a count=0 broadcast. Do not cancel again on count==0, or it loops. Marking the rows read does not affect the journal import, which keys on _ID/DATE (CallJournal.kt:175). Also run this once at service start to clear the 4 stale rows. (4) WRITE_CALL_LOG is a runtime permission. The dialer role should grant it; if it does not, run `adb shell pm grant com.ilkay.redmigateway android.permission.WRITE_CALL_LOG` once, and guard the code with checkSelfPermission. Interim step, before the receiver ships: calling cancelMissedCallsNotification() after delivery already clears Telecom's row and lets Telecom mark the rows read, which stops the reboot re-post. Each new missed call would still buzz once.

Verifier: Confirmed in code and on the device. AndroidManifest.xml:105-165 has no receiver for android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION. src/main has no cancelMissedCallsNotification, CallLog.Calls.NEW or IS_READ (grep is empty). WRITE_CALL_LOG is not declared (manifest lines 5-44 have READ_CALL_LOG at 15 only). CallJournal only reads CallLog (CallJournal.kt:172-178). The PhoneActivity badge uses a private pref only (PhoneActivity.kt:504-513). With nothing handling the broadcast and nothing ever calling clearMissedCalls, AOSP MissedCallNotifierImpl posts on its own TelecomMissedCalls channel and replays every new=1 MISSED row at boot. That matches the device fact ('4 cevapsız çağrı', 4 re-posts after reboot). One side finding: scripts/watch_and_configure.py:37, scripts/autonomous_deployer.py:117 and docs/POST_UNLOCK_RESTORE.md:59 already run `pm grant ... WRITE_CALL_LOG`. Today that grant must fail because the manifest does not request the permission.

Better fix: The proposal is correct. Three simplifications. (a) The receiver alone silences Telecom: once queryBroadcastReceivers finds it, Telecom posts nothing, and boot replays come to the app as count=1..N broadcasts, which the app ignores while paired. WRITE_CALL_LOG plus marking rows read only keeps the CallLog NEW flags and Telecom's counter clean. (b) The clearing does not need to wait for iPhone delivery, because CallJournal keys on _ID > last log_id (CallJournal.kt:168-176) and never reads NEW/IS_READ. In the receiver, on count>0: scheduleImport, mark MISSED rows NEW=0/IS_READ=1 (guarded by checkSelfPermission(WRITE_CALL_LOG)), then call telecom.cancelMissedCallsNotification(). On count==0, only cancel the app's own rows and never call cancel again (it would loop). (c) The CallLog update fires CallJournal's ContentObserver (CallJournal.kt:70-72, 109), which is a harmless no-op import. scheduleImport(0) from the receiver is redundant with that observer but harmless. Keep the interim step (cancelMissedCallsNotification() with no receiver, which makes Telecom mark the rows read itself) as the zero-permission first step. It also clears the 4 stale rows when run once at service start.

API notes: TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION, EXTRA_NOTIFICATION_COUNT, EXTRA_NOTIFICATION_PHONE_NUMBER, EXTRA_CLEAR_MISSED_CALLS_INTENT and EXTRA_CALL_BACK_INTENT are API 23+. cancelMissedCallsNotification() requires MODIFY_PHONE_STATE or the default dialer, and the app is the dialer. Telecom sends the broadcast with receiver permission READ_PHONE_STATE (granted by the dialer role). The receiver needs android:exported="true" at targetSdk 34 (build.gradle.kts:13). Writing CallLog NEW/IS_READ needs WRITE_CALL_LOG; the DIALER role's phone permission set should grant it once declared, with pm grant as the fallback. AOSP behaviour is not verified on /e/OS, but LineageOS Telecom is AOSP.

## quiet-2 [Redmi in the pocket: things that ring, vibrate, light the screen or post notifications on the Redmi (missed calls, SMS, ringer, the app's own channels, other system sources)] high android real=True fix_ok=True
**Volume and power keys cannot silence a ringing Redmi: GatewayInCallService does not implement onSilenceRinger**

Impact: When the iPhone does not confirm a call in time and the Redmi falls back to ringing in the pocket, the owner presses a volume or power key through the fabric and nothing happens. The Redmi keeps ringing and vibrating until the caller gives up or he takes it out and taps the screen. The owner asked for exactly this behaviour, and the request is quoted in RedmiRinger.kt:127-129.

Evidence: The manifest declares IN_CALL_SERVICE_RINGING (AndroidManifest.xml:129-132), so Telecom plays no ringtone and RedmiRinger plays its own (RedmiRinger.kt:148-185). RedmiRinger.silence() (RedmiRinger.kt:132-143) has one caller, CallActivity.onKeyDown for volume keys (CallActivity.kt:188-195). GatewayInCallService overrides onCreate, onAvailableCallEndpointsChanged, onCallEndpointChanged, onDestroy, onBind, onUnbind, onCallAdded and onCallRemoved (GatewayInCallService.kt:196-250), but not onSilenceRinger; a grep confirms it appears nowhere. Platform (AOSP PhoneWindowManager): a volume key-down while TelecomManager.isRinging() calls telecomManager.silenceRinger() and clears ACTION_PASS_TO_USER, so the key never reaches CallActivity. A power key-down while ringing also calls silenceRinger(). Telecom forwards the request to each bound InCallService.onSilenceRinger(). The default implementation does nothing, and Telecom's own Ringer.silence() stops nothing because Telecom is not ringing. Not yet observed on the device: no diagnostics line 'Redmi ringer silenced by the user' is expected after a key press. (RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/telecom/GatewayInCallService.kt:17)

Native: On any Android phone, and with the iPhone's side button, one press of volume or power while a call rings mutes the ringer at once and leaves the call ringing.

Fix: In GatewayInCallService add `override fun onSilenceRinger() { super.onSilenceRinger(); RedmiRinger.silenceFromTelecom() }`. Make silenceFromTelecom the same as silence() but without the TelecomManager.silenceRinger() call at RedmiRinger.kt:140-142. That call would re-enter onSilenceRinger in a loop, so keep it only on the CallActivity key path or remove it. Also remove or flag the telecom.silenceRinger() in IncomingCallPresenter.kt:63-65. Telecom does not ring here anyway, and after the fix that call would land in onSilenceRinger and log 'silenced by the user' for every call the iPhone shows. Verify: let the Redmi ring with the iPhone away, press volume-down with the screen off, and check for RINGER_OFF or 'Redmi ringer silenced by the user' in the diagnostics log.

Verifier: Confirmed. GatewayInCallService.kt:196-250 overrides onCreate, the endpoint callbacks, onDestroy, onBind, onUnbind, onCallAdded and onCallRemoved, but not onSilenceRinger (grep finds it nowhere). RedmiRinger.silence() (RedmiRinger.kt:132-143) has one caller, CallActivity.onKeyDown (CallActivity.kt:188-195). Because of IN_CALL_SERVICE_RINGING (AndroidManifest.xml:129-132), Telecom does not ring, and RedmiRinger plays the ringtone and vibration itself (RedmiRinger.kt:148-185). In AOSP PhoneWindowManager, a volume key-down while TelecomManager.isRinging() (Telecom's hasRingingOrSimulatedRingingCall, independent of who plays audio) calls telecomManager.silenceRinger() and clears ACTION_PASS_TO_USER. The key therefore never reaches CallActivity, and that path is effectively dead while Telecom shows RINGING. Power key-down while ringing also calls silenceRinger(). TelecomServiceImpl.silenceRinger stops Telecom's own Ringer, which is idle here, and forwards the request to InCallService.onSilenceRinger(), whose default does nothing. Nothing stops RedmiRinger.

Better fix: The proposal is correct as written: override onSilenceRinger() and route it to a silenceFromTelecom() that stops the sound and removes ringNow, without calling TelecomManager.silenceRinger(). Otherwise RedmiRinger.kt:140-142 re-enters onSilenceRinger in an endless async loop through handler.post. Removing the redundant silenceRinger() in IncomingCallPresenter.kt:61-65 is also right. After the fix it would arrive as onSilenceRinger and be logged as 'silenced by the user' for every call the iPhone acks, which corrupts the very diagnostics line RedmiRinger.kt:124-131 was added for. Expected side effect: a key press during the 3/5 s wait window also cancels the pending fallback ring (removeCallbacks(ringNow)). That matches the owner's intent.

API notes: InCallService.onSilenceRinger() is API 23+. TelecomManager.silenceRinger() is allowed for the default dialer or MODIFY_PHONE_STATE. PhoneWindowManager handles volume keys while ringing even with the screen off (non-interactive).

## quiet-3 [Redmi in the pocket: things that ring, vibrate, light the screen or post notifications on the Redmi (missed calls, SMS, ringer, the app's own channels, other system sources)] medium android real=True fix_ok=True
**Every incoming SMS also notifies on the Redmi because foundation.e.message holds the SMS role; the gateway only listens**

Impact: Each SMS buzzes or lights the Redmi in the pocket while the iPhone shows the same message. Reading it on the iPhone never clears the Redmi notification.

Evidence: The gateway receives SMS through the non-role SMS_RECEIVED broadcast (AndroidManifest.xml:145-152; SmsReceiver.kt:19-47). SmsJournal mirrors the provider through a ContentObserver on Telephony.Sms.CONTENT_URI (SmsJournal.kt:110-118). The provider is filled by the default SMS app, and if no app stores a message within 5 s the journal keeps a synthetic copy (SmsJournal.kt:129-134, 162-172). Only Telephony.Sms is read (SmsJournal.kt:194-200), so MMS never reaches the iPhone. The manifest has no SMS_DELIVER, WAP_PUSH_DELIVER, SENDTO or RESPOND_VIA_MESSAGE components, so the app cannot hold the SMS role today. The protocol has no iPhone-to-Redmi mark-read message (dispatch in GatewayWebSocketServer.kt:376-431), and writing READ to the provider needs the SMS role (WRITE_SMS app-op). Device fact: SMS role = foundation.e.message. (RedmiGateway/android/app/src/main/AndroidManifest.xml:145)

Native: Apple Messages alerts once per message on the device in use, and the alert clears when the thread is read.

Fix: Recommended now (no code): the owner turns off notifications once for Mesajlar (foundation.e.message) under Settings > Apps > Notifications. If the master switch is greyed out, he turns off or silences its message channels instead. The app still writes the provider in its own SMS_DELIVER handling, so SmsJournal, the iPhone sync and APNs (PushNotifier.kt:93-103) keep working. Risks: MMS and flash/class-0 messages also go quiet and still never reach the iPhone; an /e/OS update or app reset can turn the notifications back on; failed-send notices from that app are hidden (the iPhone gets sms_send_result anyway). Option B, a NotificationListenerService in the gateway that cancels foundation.e.message rows (and Telecom missed-call or voicemail rows). Risks: notification access must be granted (Settings, or `adb shell cmd notification allow_listener com.ilkay.redmigateway/<svc>`, no root needed). The cancel arrives after NotificationManagerService has already started the sound or vibration and the ambient display, so each message still blips: this cleans the shade but does not make the Redmi quiet. It also adds a service that is always bound. Only a NotificationAssistantService can lower importance before posting, and that is speculative. Option C, the gateway takes the SMS role. It then needs an SMS_DELIVER receiver (BROADCAST_SMS), a WAP_PUSH_DELIVER receiver (BROADCAST_WAP_PUSH, application/vnd.wap.mms-message), a SENDTO activity for sms/smsto/mms/mmsto and a RESPOND_VIA_MESSAGE service. It must itself write every incoming SMS to Telephony.Sms.Inbox, where a bug loses the message from the Redmi store and the journal fallback only covers the iPhone. It must download and persist MMS (SmsManager.downloadMultimediaMessage plus Telephony.Mms) or lose them, and write the sent, failed and delivery status rows. It also takes over class-0 and carrier WAP push. The benefit is one notifier and mark-read sync. That is too much risk for a 4-month horizon; use option A.

Verifier: Tied to a device fact and to code. The SMS role holder is foundation.e.message, so it receives SMS_DELIVER, stores the message and posts its own notification. The gateway only listens to the non-abortable SMS_RECEIVED (AndroidManifest.xml:145-152; SmsReceiver.kt:19-47). It mirrors the provider by ContentObserver (SmsJournal.kt:110-118) and falls back to a synthetic copy after 5 s (SmsJournal.kt:129-134, 162-172). Only Telephony.Sms is read (SmsJournal.kt:194-200), so MMS is not mirrored. The manifest has no SMS_DELIVER, WAP_PUSH_DELIVER, SENDTO or RESPOND_VIA_MESSAGE components, so the app cannot hold the SMS role. The dispatch at GatewayWebSocketServer.kt:376-431 has no mark-read command (grep for mark_read/markRead finds nothing). Each SMS therefore notifies on the Redmi, and reading it on the iPhone cannot clear that notification.

Better fix: Option A (owner turns off or silences Mesajlar notifications) is the right call. One wording fix: in 'The app still writes the provider in its own SMS_DELIVER handling', the app is foundation.e.message, not the gateway. Muting notifications does not stop its SMS_DELIVER storage, so SmsJournal, sync and the APNs fallback keep working. PushNotifier only pushes unread inbox rows (PushNotifier.kt:93-103, 133), and they stay unread because nobody opens the Redmi app. A gentler variant: set its message channels to Silent rather than off, so the shade still collects them with no sound, vibration or ambient wake. Option B is correctly described as cleanup only, since sound and vibration fire before a listener cancel. Option C is correctly rejected for this horizon.

API notes: AOSP NotificationManagerService NON_BLOCKABLE_DEFAULT_ROLES covers ROLE_DIALER and ROLE_EMERGENCY, not ROLE_SMS, so the SMS app's notifications should be blockable (not verified on /e/OS). `cmd notification allow_listener <component>` works from the adb shell without root. SMS_RECEIVED cannot be aborted on KitKat and later.

## quiet-4 [Redmi in the pocket: things that ring, vibrate, light the screen or post notifications on the Redmi (missed calls, SMS, ringer, the app's own channels, other system sources)] medium android real=True fix_ok=True
**The health warning posts on an IMPORTANCE_HIGH channel with default sound and vibration at every service start, and never reaches the iPhone**

Impact: After every OTA, which always wipes the priv-app install and the /vendor fix, and after every boot or service restart until the repair, the Redmi beeps and vibrates with a heads-up 'iPhone sesi çalışmaz' in the pocket. The owner never reads it, and the iPhone, which he does read, says nothing, so he first learns about it on a call with no audio.

Evidence: The channel is created at IMPORTANCE_HIGH without setSound(null) or enableVibration(false) (HealthReporter.kt:92-101). The notification uses PRIORITY_HIGH and setAutoCancel(true) (HealthReporter.kt:79-89). setOnlyAlertOnce only suppresses re-alerts while the row is still showing, so it alerts again after it is dismissed or after a reboot. reportHealth() runs on every ACTION_START (GatewayService.kt:128-135, 285-292). A null START_STICKY restart intent also maps to ACTION_START (GatewayService.kt:124, 139), as do BootReceiver for boot and MY_PACKAGE_REPLACED (BootReceiver.kt:16-25) and PhoneActivity.startBridge (PhoneActivity.kt:1399-1401). The iPhone sees health problems only when the owner requests diagnostics by hand (GatewayWebSocketServer.kt:432; iOS GatewayConnection.swift:663-667 stores raw JSON). stateMessage carries no health data (GatewayWebSocketServer.kt:633-641). For comparison, the other channels are fine: redmi_gateway_channel is IMPORTANCE_LOW (GatewayService.kt:263-276); cellular_calls is HIGH but silent and used only while the Redmi itself rings (CallUiNotifier.kt:127, 143, 270-277); cellular_calls_quiet is LOW, silent and without vibration (CallUiNotifier.kt:280-289). (RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/service/HealthReporter.kt:92)

Native: Problems on a paired accessory surface on the iPhone (for example a Watch or AirPods alert), not as a sound from a device in a bag.

Fix: Move the alert to a new silent channel id (for example redmi_gateway_health_v2, IMPORTANCE_LOW, or DEFAULT with setSound(null, null) and enableVibration(false)) and call deleteNotificationChannel("redmi_gateway_health"), because code cannot change the sound or raise the importance of an existing channel. Send the audio-critical problem codes and titles to the iPhone, in the hello/state message and once as an APNs alert per problem set (for example 'Redmi: iPhone sesi çalışmaz – onarım gerekli'). Possibly relevant and unverified on this ROM: AOSP locks the notification importance of the ROLE_DIALER holder (NON_BLOCKABLE_DEFAULT_ROLES), so the owner may not be able to silence this channel in Settings, and the code has to do it.

Verifier: Confirmed. HealthReporter.createChannel builds 'redmi_gateway_health' at IMPORTANCE_HIGH with the default sound and vibration (HealthReporter.kt:92-101). The notification is PRIORITY_HIGH with setAutoCancel(true) and setOnlyAlertOnce(true) (HealthReporter.kt:79-89), so it alerts again after it is dismissed or after a reboot. publish() runs from reportHealth() on every ACTION_START (GatewayService.kt:128-135, 285-292). A null sticky-restart intent maps to ACTION_START (GatewayService.kt:124), as do BootReceiver (BootReceiver.kt:16-25), MainActivity.kt:407 and PhoneActivity.kt:1401. The iPhone sees problems only through a manual diagnostics request: the codes are in DiagnosticsReport.kt:63/104, sent at GatewayWebSocketServer.kt:432, requested only by GatewayConnection.swift:1385-1388 and stored as raw text at GatewayConnection.swift:663-667. The hello message (GatewayWebSocketServer.kt:137-148) and stateMessage (633-641) carry no health data. It fires only while an audio-critical problem exists (HealthReporter.kt:63-67; HealthCheck.kt:46-93). The 'noise' is therefore an alert about a real fault, but it reaches the wrong device, and the device the owner actually reads says nothing.

Better fix: Forwarding is the essential half. Put the audio-critical codes and titles in hello (and/or a 'health' message on connect), and send one ApnsSender.sendAlert per new problem set; that path already exists (PushNotifier.kt:111) and needs no iOS change for the alert itself. Only after that, quiet the Redmi channel. A new id plus deleteNotificationChannel("redmi_gateway_health") is the robust way. createNotificationChannel on the same id can also lower the importance in place, but only if the user never touched the channel. If it were silenced first, a broken setup would be invisible on both phones.

API notes: Code cannot change an existing channel's sound or vibration, and can lower its importance only while getUserLockedFields()==0. That makes the new channel id the reliable route. The dialer role holder's channel importance may be locked against user changes (NON_BLOCKABLE_DEFAULT_ROLES); this is speculative on /e/OS.

## quiet-5 [Redmi in the pocket: things that ring, vibrate, light the screen or post notifications on the Redmi (missed calls, SMS, ringer, the app's own channels, other system sources)] low android real=True fix_ok=True
**The app posts its own 'Cevapsız Arama' row for calls declined on the iPhone, duplicating Telecom's row, and never clears them**

Impact: The Redmi shade collects a 'missed call' for every call the owner declined on the iPhone, next to Telecom's own missed-call row. These rows are silent while paired but never go away. If the pairing is lost they move to an alerting channel with default sound.

Evidence: wasMissed() means rang and never active (CallUiNotifier.kt:68-80, 344-345). It ignores the disconnect cause. A decline from the iPhone goes through CallManager.reject, which calls call.reject(REJECT_REASON_DECLINED) (CallManager.kt:919-939). Telecom logs that as REJECTED, and CallJournal maps REJECTED to 'declined' (CallJournal.kt:265), so the iPhone rightly does not alert. Each row gets its own id (4400+hash) with only setAutoCancel and is never cancelled (CallUiNotifier.kt:218-266). The paired channel is LOW and silent (CallUiNotifier.kt:296-303). Unpaired, the row goes to missed_calls at IMPORTANCE_DEFAULT with default sound (CallUiNotifier.kt:234-235, 290-295). (RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/telecom/CallUiNotifier.kt:344)

Native: A declined call is not shown as a missed call on Android or on the iPhone.

Fix: Remove this path and make MissedCallsReceiver (quiet-1) the single source, because it follows Telecom's own MISSED decision. At minimum, check call.details.disconnectCause.code == DisconnectCause.MISSED before posting, skip posting entirely while paired, and cancel the app's rows when Telecom sends count==0.

Verifier: Confirmed, low severity. CallUiNotifier decides 'missed' only from 'rang and never became active' (CallUiNotifier.kt:68-77, 344-345), ignoring the disconnect cause. An iPhone decline goes through GatewayWebSocketServer.kt:381 -> CallManager.reject -> call.reject(REJECT_REASON_DECLINED) (CallManager.kt:919-939), then finishCallSession reports 'disconnected' (CallManager.kt:737-743). The ringing number recorded at CallUiNotifier.kt:55-61 then produces a 'Cevapsız Arama' row. Each row gets its own id, 4400+hash (CallUiNotifier.kt:221), has only setAutoCancel (240), and nothing in src/main cancels it. While paired it sits on missed_calls_quiet (LOW, silent: 296-303), which means clutter but no buzz. While unpaired it uses missed_calls at IMPORTANCE_DEFAULT with the default sound (234-235, 290-295). Telecom logs the decline as REJECTED and CallJournal maps that to 'declined' (CallJournal.kt:265), so the Redmi row is the only place that calls it missed.

Better fix: Remove postMissedCallNotification and let the quiet-1 MissedCallsReceiver be the single source, since it follows Telecom's own MISSED decision. If the code path is kept, CallUiNotifier receives only string states (CallManager.CallListener), so checking DisconnectCause.MISSED means CallManager has to expose call.details.disconnectCause.code at disconnect time. Skipping the post entirely while PairingStore.pairedClient()!=null is the cheap interim.

API notes: DisconnectCause.MISSED and DisconnectCause.REJECTED are API 23+. Call.REJECT_REASON_DECLINED is API 30.

## quiet-6 [Redmi in the pocket: things that ring, vibrate, light the screen or post notifications on the Redmi (missed calls, SMS, ringer, the app's own channels, other system sources)] low android real=True fix_ok=True
**Item 3, ringing: the Redmi is silent while the iPhone rings, with a 3 s or 5 s fallback ring that lights the screen. Two code comments are stale.**

Impact: Normally only the iPhone rings. If ringing_ack does not arrive within 3 s (linked or Bluetooth) or 5 s (Apple push only), the Redmi rings and vibrates, posts a full-screen CallStyle row, and CallActivity turns the screen on with Answer and Reject exposed in the pocket. The 5 s push window being too short when the iPhone has to reconnect over Tailscale is a guess, not measured.

Evidence: IN_CALL_SERVICE_RINGING=true (AndroidManifest.xml:129-132). AOSP Telecom checks this meta-data on the default dialer package and skips its own ringtone, vibration and call-waiting tone. quietWhenIPhoneRings defaults to true (IncomingCallPresenter.kt:30-33). RedmiRinger.onIncoming waits 3000 or 5000 ms (RedmiRinger.kt:37-40, 56-72, 228-233) and stays silent when the ack arrives (RedmiRinger.kt:74-80; IncomingCallPresenter.kt:56-60). An ack whose callId does not match is ignored with only a log line (IncomingCallPresenter.kt:46-55). While waiting, the ringing row sits on the LOW quiet channel (CallUiNotifier.kt:123-148). On the fallback, start() posts the full-screen row on the HIGH channel (CallUiNotifier.kt:152-159; RedmiRinger.kt:154), and CallActivity sets SHOW_WHEN_LOCKED, TURN_SCREEN_ON and KEEP_SCREEN_ON (CallActivity.kt:92-100; AndroidManifest.xml:97-103). The APNs VoIP push leaves 1.5 s after ringing starts (PushNotifier.kt:65-71). Stale comments: 'Telecom continues to own the ringtone' (CallUiNotifier.kt:20) and 'Android Telecom rings the handset' (CallUiNotifier.kt:275). (RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/telecom/RedmiRinger.kt:56)

Native: With call forwarding to another device (iPhone Continuity), the other device rings softly or not at all and its screen stays off.

Fix: Keep the rule that a call is never silent on both phones. Optional: when IPhoneReachability is PUSH, make the first fallback seconds vibration-only with no full-screen intent, and escalate to ringtone plus call screen only if the call is still unanswered. Record 'ringing_ack arrived after Redmi rang (ms)' so the 5 s window can be tuned from real data. Fix the two stale comments. The volume and power silencing gap is quiet-2.

Verifier: Only the two stale comments are a code defect (trivial). CallUiNotifier.kt:20 says 'Telecom continues to own the ringtone', and CallUiNotifier.kt:275 says 'Android Telecom rings the handset'. Both contradict IN_CALL_SERVICE_RINGING (AndroidManifest.xml:129-132) and RedmiRinger (RedmiRinger.kt:22-29, 148-185). The rest is intended behaviour and is described accurately. quietWhenIPhoneRings defaults to true (IncomingCallPresenter.kt:30-33). The wait is 3 s nearby or 5 s push-only (RedmiRinger.kt:37-40, 228-233). An ack cancels the ring (RedmiRinger.kt:74-80), and a non-matching ack is only logged (IncomingCallPresenter.kt:46-55). The quiet channel is used while waiting (CallUiNotifier.kt:123-148). On fallback the full-screen CallStyle goes on the HIGH channel (CallUiNotifier.kt:152-159; RedmiRinger.kt:154), and CallActivity turns the screen on (CallActivity.kt:92-100). The fallback follows the documented rule 'A call is never silent on both phones' (RedmiRinger.kt:29). The claim that the 5 s window is too short is unmeasured, as the finding itself says.

Better fix: Fix the two comments. Logging ack latency relative to RINGER start is cheap and is the right way to tune WAIT_PUSH_MS. The vibration-only-first escalation is a product decision for the owner because it delays the audible ring. Keep it optional.

API notes: No platform API is involved beyond what is already used.

## quiet-7 [Redmi in the pocket: things that ring, vibrate, light the screen or post notifications on the Redmi (missed calls, SMS, ringer, the app's own channels, other system sources)] low android real=True fix_ok=True
**No ACTION_SHOW_VOICEMAIL_NOTIFICATION receiver, so a carrier voicemail indicator would notify on the Redmi only**

Impact: If Turkcell sends a voicemail waiting indicator (MWI) to this SIM, com.android.phone posts an alerting 'Yeni sesli mesaj' row in the pocket and the iPhone never learns about it. Whether this carrier sends MWI to this line is unknown; this is speculative.

Evidence: There is no receiver for android.telephony.action.SHOW_VOICEMAIL_NOTIFICATION in AndroidManifest.xml:105-165. Platform (AOSP Telephony NotificationMgr.updateMwi): the phone process hands the voicemail notification to the default dialer only when that dialer has a receiver for TelephonyManager.ACTION_SHOW_VOICEMAIL_NOTIFICATION; otherwise it posts its own. (RedmiGateway/android/app/src/main/AndroidManifest.xml:105)

Native: The iPhone shows a voicemail badge or alert in the Phone app.

Fix: Add a receiver for android.telephony.action.SHOW_VOICEMAIL_NOTIFICATION (exported=true), reusing the quiet-1 pattern. Read EXTRA_NOTIFICATION_COUNT and EXTRA_VOICEMAIL_NUMBER, ignore alerts when EXTRA_IS_REFRESH is true, post nothing on the Redmi while paired, and send the count to the iPhone in the state message or as one APNs alert.

Verifier: The code gap is real: AndroidManifest.xml:105-165 has no receiver for android.telephony.action.SHOW_VOICEMAIL_NOTIFICATION. In AOSP Telephony NotificationMgr.updateMwi, the phone process sends this to the default dialer only when that package has a receiver for it, and otherwise posts its own voicemail notification. The trigger is speculative: no device fact shows that Turkcell sends MWI to this SIM. Low severity.

Better fix: Same pattern as quiet-1: an exported receiver for TelephonyManager.ACTION_SHOW_VOICEMAIL_NOTIFICATION. On EXTRA_NOTIFICATION_COUNT==0, clear. On a refresh (EXTRA_IS_REFRESH=true), do not alert. While paired, post nothing on the Redmi and forward the count to the iPhone once. Low priority; it can share the quiet-1 change.

API notes: TelephonyManager.ACTION_SHOW_VOICEMAIL_NOTIFICATION, EXTRA_NOTIFICATION_COUNT and EXTRA_VOICEMAIL_NUMBER are API 26. EXTRA_IS_REFRESH is API 28. Telephony sends it with receiver permission READ_PHONE_STATE (held: AndroidManifest.xml:12).

## quiet-8 [Redmi in the pocket: things that ring, vibrate, light the screen or post notifications on the Redmi (missed calls, SMS, ringer, the app's own channels, other system sources)] low android real=True fix_ok=True
**The Redmi battery level never reaches the iPhone, so the low-battery warning sounds in the pocket**

Impact: The gateway's battery decides whether calls, SMS and the hotspot keep working. Its only low-battery signal is SystemUI's 'Pil zayıf' row, which plays a sound when power sounds are on. Nobody hears it in the pocket, and the iPhone does not know the level. Whether the sound plays depends on a ROM setting, so this part is speculative.

Evidence: stateMessage carries only call state (GatewayWebSocketServer.kt:633-641). The 'battery' key in STATUS_KEYS is the iPhone's own battery in its client_status (GatewayWebSocketServer.kt:67-70; iOS GatewayConnection.swift:1751). Nothing in src/main reads ACTION_BATTERY_CHANGED or BatteryManager. (RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/server/GatewayWebSocketServer.kt:633)

Native: The iPhone battery widget shows the level of paired accessories, and they warn on the iPhone.

Fix: Add the Redmi battery percentage and charging state to the state message, sent on change in 5% steps, plus one APNs alert at 20% and 10% while the iPhone is away. Owner, once: turn off charging and low-battery sounds and vibration on the Redmi.

Verifier: The visibility gap is real but it is a missing feature, low severity. stateMessage carries call state only (GatewayWebSocketServer.kt:633-641), and hello has no battery field (137-148). 'battery' in STATUS_KEYS (GatewayWebSocketServer.kt:67-70) is the iPhone's own value from client_status (452-459). Nothing in src/main reads BatteryManager or ACTION_BATTERY_CHANGED (grep finds only MainActivity.kt:334's battery-optimisation exemption). The 'low-battery sound in the pocket' part is SystemUI behaviour that depends on a ROM setting. It is speculative, and forwarding the level does not stop it; only the owner-side setting does.

Better fix: Register ACTION_BATTERY_CHANGED at runtime in GatewayService; it is sticky and cannot be declared in the manifest. Send the level and charging state in hello and on 5% steps, plus one APNs alert at 20% and 10% via ApnsSender.sendAlert. For the noise, the owner turns off Settings > Sound > charging sounds and vibration, which on AOSP is Settings.Global power_sounds_enabled / charging_vibration_enabled. That this also gates the low-battery sound is likely but unverified on /e/OS.

API notes: Intent.ACTION_BATTERY_CHANGED is runtime-only; use BatteryManager.EXTRA_LEVEL/EXTRA_SCALE/EXTRA_STATUS, or BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY). New hello/state keys need iOS parsing to be shown.

## quiet-9 [Redmi in the pocket: things that ring, vibrate, light the screen or post notifications on the Redmi (missed calls, SMS, ringer, the app's own channels, other system sources)] low android real=False fix_ok=True
**Other system sources not handled by the app: developer/USB notifications, SIM Toolkit popups, cell broadcast**

Impact: Each wireless-debugging connect posts an 'android' DEVELOPER_IMPORTANT notification (device fact), and the open connection also drains the battery. Turkcell STK display-text popups and cell-broadcast alerts, if any arrive, sound only on the Redmi. The iPhone has no cellular service and receives no emergency alerts at all. The STK and cell-broadcast parts are speculative; no code is involved.

Evidence: No code path in src/main handles these. The developer notification comes from the system 'android' package and is ongoing, so a NotificationListenerService cannot cancel it. STK (com.android.stk) and com.android.cellbroadcastreceiver are separate system apps with their own channels and dialogs. (RedmiGateway/android/app/src/main/AndroidManifest.xml:105)

Native: On the iPhone, emergency alerts arrive over its own cellular connection, which this iPhone does not have.

Fix: Process: batch all adb reads into one wireless session and turn wireless debugging off afterwards (it also drains the battery). Owner: in the Redmi's Wireless emergency alerts settings, keep extreme and severe alerts on for safety and turn off test or other optional categories. Optional later: a NotificationListenerService that forwards cell-broadcast text to the iPhone, which would also close the iPhone's emergency-alert gap. Do not disable the STK app, because some SIM functions depend on it.

Verifier: No app code defect. The DEVELOPER_IMPORTANT notification is a by-product of the engineers' wireless-adb sessions, not of the gateway. It is ongoing and comes from the system 'android' package, so the app could not cancel it even with a NotificationListenerService (listener cancels honour FLAG_ONGOING_EVENT/FLAG_NO_CLEAR). STK and cell-broadcast noise are speculative, with no device fact and no code path involved. Nothing in AndroidManifest.xml:105-165 or src/main could be changed to address them.

Better fix: Keep it as process advice: batch adb work into one wireless session and turn wireless debugging off afterwards (also a known battery drain). Leave extreme and severe emergency-alert categories on, and do not disable STK. Forwarding cell broadcasts to the iPhone is a separate feature idea, not a fix here.

API notes: NotificationListenerService.cancelNotification cannot remove ongoing or no-clear notifications. Receiving SMS_CB_RECEIVED directly requires the signature|privileged RECEIVE_EMERGENCY_BROADCAST.

## CONS-1 [Consistency between the two phones as the owner experiences it on the iPhone (SMS/call history sync, read/seen state, notifications, contact names, timestamps, link gaps)] high both real=True fix_ok=True
**Missed calls seen on the iPhone stay 'new' on the Redmi: Telecom keeps its own missed-call notification and re-posts it after every reboot**

Impact: The Redmi in the pocket shows its own missed-call notifications from the ROM's Telecom service ('4 cevapsız çağrı') and the gateway adds one more row per call. Seeing the calls on the iPhone clears none of them, and Telecom posts them all again after a Redmi reboot. This is the owner's complaint that the Redmi shows missed calls from the old phone app.

Evidence: Device fact: an active notification from com.android.server.telecom (tag MissedCallNotifierImpl, channel TelecomMissedCalls) says '4 cevapsız çağrı', and Telecom posted 4 again at once after a reboot. Code: (1) The gateway holds the DIALER role but the manifest has no receiver for android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION (AndroidManifest.xml:105-165 lists only CallActionReceiver, KnownWifiReceiver, LabModeReceiver, SmsReceiver and BootReceiver). Telecom hands the missed-call notification to the default dialer only when that dialer has such a receiver, so it posts its own. (2) Nothing calls TelecomManager.cancelMissedCallsNotification() (grep over android/app/src finds no match), so the CallLog NEW/IS_READ flags never reset, and that is what Telecom reloads at boot. (3) The iPhone's 'seen' is local only: HistoryStore.swift:162-166 markMissedSeen sets missedSeenAt and nothing else, and the Redmi's command switch has no seen or read command (GatewayWebSocketServer.kt:376-498). (4) The gateway posts its own missed-call row too, under a per-call id 4400+hash that is never cancelled (CallUiNotifier.kt:218-266, quiet channel at 234-243). So each missed call leaves two Redmi notifications, and neither is ever cleared. (RedmiGateway/android/app/src/main/AndroidManifest.xml:145)

Native: On a single iPhone, opening Recents clears the missed-call notification and the count everywhere; a call seen once is never announced again after a restart.

Fix: 1) Add an exported receiver, e.g. telecom.MissedCallsReceiver, with an intent-filter for android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION. Telecom (MissedCallNotifierImpl) then sends the gateway a broadcast for live misses and for the boot-time reload, instead of posting on TelecomMissedCalls. While an iPhone is paired the receiver posts nothing, because the iPhone already notifies through the journal and APNs. 2) Add a protocol command {type:'missed_seen'}, sent from HistoryStore.markMissedSeen and when a missed-call notification is opened on the iPhone. The Redmi handles it with getSystemService(TelecomManager::class.java).cancelMissedCallsNotification(), which the default dialer may call. Telecom then marks the CallLog missed rows read (NEW=0, IS_READ=1) itself, so nothing comes back after a reboot. The same handler cancels the gateway's 4400+ rows. 3) Stop CallUiNotifier.postMissedCallNotification while PairingStore.pairedClient()!=null.

Verifier: Confirmed in the code. The manifest has no SHOW_MISSED_CALLS_NOTIFICATION receiver. Its only receivers are CallActionReceiver (AndroidManifest.xml:105-107), KnownWifiReceiver (110-113), LabModeReceiver (116-119), SmsReceiver (145-152) and BootReceiver (155-165). Telecom therefore keeps posting its own TelecomMissedCalls notification, which matches the device fact. No source file calls cancelMissedCallsNotification or writes CallLog NEW/IS_READ: a grep for SHOW_MISSED_CALLS|cancelMissedCalls|IS_READ|Calls.NEW finds nothing under android/app/src/main, and there is no WRITE_CALL_LOG permission. markMissedSeen only moves missedSeenAt (HistoryStore.swift:162-166). The dispatch switch has no seen or read command (GatewayWebSocketServer.kt:378-498). CallUiNotifier also posts its own row with id 4400+hash (CallUiNotifier.kt:221, 235-261). While paired that row goes to the IMPORTANCE_LOW channel (234, 296-303), but it is cancelled only by a tap, through setAutoCancel (240), and nothing else in the code cancels it. Severity: high, because this is the owner's own complaint.

Better fix: 

API notes: TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION ("android.telecom.action.SHOW_MISSED_CALLS_NOTIFICATION") is public API. Telecom's MissedCallNotifierImpl checks whether the default dialer package has a receiver for it. If it has one, Telecom sends the broadcast with EXTRA_NOTIFICATION_COUNT, EXTRA_NOTIFICATION_PHONE_NUMBER, EXTRA_CALL_BACK_INTENT and EXTRA_CLEAR_MISSED_CALLS_INTENT, and posts nothing itself. This covers the boot-time reload too. A count of 0 means 'clear'. Declare the receiver exported=true. Telecom sends it explicitly to the dialer package, so manifest receivers work on Android 8 and later. TelecomManager.cancelMissedCallsNotification() requires the default dialer role or MODIFY_PHONE_STATE. The gateway holds the dialer role, and a non-root /e/OS user build does not make MODIFY_PHONE_STATE reliable. Telecom then marks missed rows NEW=0, IS_READ=1, so the boot reload finds nothing. Side effect checked: CallJournal imports only ids above its watermark (CallJournal.kt:168-177), so marking rows read does not republish any journal entries. One refinement: once the receiver exists, do all missed-call UI in it. When unpaired, post one aggregated row from EXTRA_NOTIFICATION_COUNT. Remove CallUiNotifier.postMissedCallNotification entirely rather than only while paired; otherwise an unpaired Redmi shows two rows per miss. The missed_seen handler should also cancel any leftover 4400+ ids.

## CONS-2 [Consistency between the two phones as the owner experiences it on the iPhone (SMS/call history sync, read/seen state, notifications, contact names, timestamps, link gaps)] medium both real=True fix_ok=False
**SMS read state only goes one way: reading on the iPhone never marks the Redmi's copy read, and the journal's 'read' flag is effectively always false**

Impact: Every SMS stays unread on the Redmi forever. The ROM's messaging app is the SMS role holder, so its unread state, and very likely its notifications and badge, keep piling up in the pocket. The 'read' field the iPhone receives carries no information.

Evidence: The device facts say the SMS role holder is foundation.e.message, not the gateway. The gateway declares only RECEIVE_SMS, READ_SMS and SEND_SMS (AndroidManifest.xml:31-33), so it cannot write Telephony.Sms.READ/SEEN. No command accepts a read or seen report from the iPhone (GatewayWebSocketServer.kt:376-498). The iPhone keeps read state locally in threadSeen (HistoryStore.swift:290-301, MessagesView.swift:180-183 and 206-210), and nothing is sent back. The journal mirrors the provider's READ column (SmsJournal.kt:194-197, 216), which only changes if someone opens the Redmi's SMS app. So the iPhone relies only on local threadSeen, and PushNotifier.isPushableSms (PushNotifier.kt:133) sees every inbox row as unread. Speculative and not measured today: that foundation.e.message posts a notification for each SMS. That is its default as role holder. (RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/server/GatewayWebSocketServer.kt:378)

Native: In Apple Messages, opening a conversation marks it read and clears its notifications and badge, and no other device keeps nagging about it.

Fix: Structural fix: make the gateway the SMS role holder (RoleManager.ROLE_SMS). That needs an SMS_DELIVER receiver that writes the inbox row, WAP_PUSH_DELIVER for MMS, a RESPOND_VIA_MESSAGE service and a SENDTO activity. foundation.e.message then stops notifying, and the gateway can write READ/SEEN. Then add {type:'sms_read', ids:[gatewayIds]}, sent from ConversationView when a thread is shown. The Redmi updates Telephony.Sms READ=1,SEEN=1 for those _IDs, and the journal re-publishes read=true, so a reinstalled iPhone or a second view stays consistent. Interim step without the role: switch off foundation.e.message notifications on the Redmi once (app notification settings), and still send sms_read so the protocol is ready.

Verifier: The code path is as described. The gateway declares only RECEIVE_SMS, READ_SMS and SEND_SMS (AndroidManifest.xml:31-33) and is not the SMS role holder (device fact), so it cannot write Telephony.Sms READ/SEEN. No read command exists (GatewayWebSocketServer.kt:378-498). The journal mirrors the provider's READ column (SmsJournal.kt:196-197, 216). The iPhone keeps read state only locally (HistoryStore.swift:290-301, MessagesView.swift:180-183, 206-210). However, the harm on the Redmi is speculative. Nobody has measured whether foundation.e.message posts or keeps notifications or a badge, and the device facts list only Telecom and developer notifications. isPushableSms treating every inbox row as unread (PushNotifier.kt:133) is the intended behaviour, not a defect. Severity: low to medium.

Better fix: Keep foundation.e.message as the SMS role holder. Fix the pocket noise first: on the Redmi, turn off foundation.e.message notifications or its channels once (a manual device setting, or an adb 'cmd notification' / appops step done by the owner). Then measure whether that app was posting anything at all. Add {type:'sms_read', ids:[...]} only after confirming on this device that `appops set com.ilkay.redmigateway WRITE_SMS allow` lets a non-default app update Telephony.Sms READ/SEEN. If it does not, leave read state iPhone-local. Do not take ROLE_SMS unless the gateway also implements SMS_DELIVER persistence, sent-message persistence and full MMS download and storage.

API notes: Taking RoleManager.ROLE_SMS has large side effects that the proposal leaves out. (1) The default SMS app must insert every received SMS into the provider itself (SMS_DELIVER). SmsJournal mirrors the provider, so any slip loses messages. (2) For apps holding the role, the platform no longer writes sent messages to the provider (SmsApplication.shouldWriteMessageForPackage returns false for the default app). SmsSender.sendTextMessage (SmsSender.kt:84-88) would then leave no 'sent' rows, which breaks HistoryStore.upsertSms pending replacement and the retry-after-journal logic (GatewayConnection.swift:1362-1383). (3) MMS: the role holder must download MMS itself with SmsManager.downloadMultimediaMessage and write it to Telephony.Mms. Otherwise MMS are lost even on the Redmi, because foundation.e.message stops downloading them. (4) It needs a RESPOND_VIA_MESSAGE service protected by SEND_RESPOND_VIA_MESSAGE and a SENDTO activity for sms/smsto/mms/mmsto. Without the role, an sms_read command cannot write READ, because the provider blocks writes from non-default apps through AppOps WRITE_SMS.

## CONS-3 [Consistency between the two phones as the owner experiences it on the iPhone (SMS/call history sync, read/seen state, notifications, contact names, timestamps, link gaps)] medium ios real=True fix_ok=False
**After an iPhone restart the app can wipe its own history state: history.json is unreadable before the first unlock, the store starts empty, and that empty store is saved over the file after the unlock**

Impact: After an iPhone reboot, SMS that were unread and missed calls not yet seen silently become read. Failed or pending outgoing SMS bubbles ('Gönderilemedi · Tekrar dene') disappear for good, because they exist only on the iPhone. Then the whole journal downloads again from sequence 0.

Evidence: At launch, RedmiGatewayApp.swift:24 calls GatewayConnection.start(), and its first history access is expirePendingSends at GatewayConnection.swift:209, which runs HistoryStore.init. The init returns empty on any read or decode failure (HistoryStore.swift:81-83). The file is written with .completeFileProtectionUntilFirstUserAuthentication (HistoryStore.swift:323), so it cannot be read before the first unlock. The app is known to launch before the first unlock: the Keychain .locked branch at GatewayConnection.swift:179-183, the 'Pairing locked until the first unlock' log at 207, and the protectedDataDidBecomeAvailable handler at 1449-1455. After the unlock, reloadPairingIfLocked calls history.bind (GatewayConnection.swift:281). bind sees gatewayId==nil, sets gatewayId, readBaseline=Date() and missedSeenAt=Date(), and calls scheduleSave (HistoryStore.swift:97-105). saveNow then overwrites the real history.json with empty messages and calls and smsSeq/callsSeq=0 (HistoryStore.swift:314-325). The next catch-up re-syncs everything, but under the new readBaseline nothing counts as unread (HistoryStore.swift:290-294). A Snapshot decode failure after a future schema change takes the same path. (RedmiGateway/ios/RedmiGateway/HistoryStore.swift:82)

Native: Apple Messages and Phone keep unread and missed state across a reboot; a restart never makes an unread message look read.

Fix: Make loading explicit. In init, if !UIApplication.shared.isProtectedDataAvailable or the read fails with anything other than file-not-found, set loaded=false and keep the snapshot unread. Have saveNow() and bind() do nothing while loaded==false. On protectedDataDidBecomeAvailable, reload first and then let reloadPairingIfLocked call bind. Never treat 'could not read' as 'no history'. Give new Snapshot fields defaults (decodeIfPresent) so an app update cannot wipe the file either.

Verifier: Confirmed and high severity. The code itself documents that the app is relaunched before the first unlock (BridgeCrypto.swift:186-189, PairingKeychain .locked at 196, GatewayConnection.swift:179-183, 207). start() runs at launch (RedmiGatewayApp.swift:24), and its first history access is at GatewayConnection.swift:209. HistoryStore.init treats any read or decode error as an empty store (HistoryStore.swift:82-83). The file uses .completeFileProtectionUntilFirstUserAuthentication (323), so it cannot be read in that window. After the unlock, reloadPairingIfLocked calls history.bind (GatewayConnection.swift:281). With gatewayId==nil, bind resets readBaseline and missedSeenAt and schedules a save (HistoryStore.swift:97-105). saveNow then overwrites history.json with the empty snapshot and smsSeq/callsSeq=0 (314-324). Pending and failed outgoing bubbles exist only in that file and are lost. A decode failure after a schema change takes the same path.

Better fix: Make loading tri-state. In init, try Data(contentsOf:). If it fails with CocoaError.fileReadNoSuchFile (ENOENT), the store is genuinely empty: set loaded=true. For any other read error, such as the data-protection permission error (NSFileReadNoPermissionError) before the first unlock, set loaded=false and keep nothing in memory. Add loadIfNeeded(), which retries the read, and call it first in bind(), the apply*/upsert entry points, expirePendingSends and reloadPairingIfLocked, rather than only from the protectedData notification. saveNow() must never write while loaded==false. On a decode failure, move the file aside (history.bad-<timestamp>.json) and then start fresh, so one schema problem does not block saving forever. Give every future Snapshot, SmsRecord and CallRecord field Optional or decodeIfPresent handling.

API notes: Gating on UIApplication.isProtectedDataAvailable is wrong for this protection class. It reports false whenever the device is locked, including after the first unlock, when a file with completeUntilFirstUserAuthentication is readable. The app's normal state is a background Bluetooth wake while the iPhone is locked in a pocket, so that gate would refuse to load history in exactly that case. Also, protectedDataDidBecomeAvailable is not delivered to a suspended app, which the code itself notes at GatewayConnection.swift:264-267, so reloading only from that notification is not enough. Synthesized Decodable ignores property default values. New fields need to be Optional or have a custom init(from:) that uses decodeIfPresent.

## CONS-4 [Consistency between the two phones as the owner experiences it on the iPhone (SMS/call history sync, read/seen state, notifications, contact names, timestamps, link gaps)] medium both real=True fix_ok=True
**Duplicate alert: an SMS or missed call already announced by the Redmi's Apple push is announced again, with sound, when the app later syncs**

Impact: Away from home the owner gets the push and reads or dismisses it. Later, when the app syncs (Bluetooth wake at home, or opening the app from that notification), the same SMS or missed call rings and bannners again. Opening the app from a missed-call push to Recents plays a second 'Cevapsız arama' banner while the owner is looking at Recents.

Evidence: The Redmi sends an APNs alert when no connected iPhone has the entry within 8 s (PushNotifier.kt:93-113). The iPhone treats every entry it has not stored yet as news if it is under 24 h old (HistoryStore.swift:147-149 for calls, 211-213 for SMS). Notifier quiets the copy only if the pushed banner is still among the delivered notifications (Notifier.swift:55-69, 76-89, 92-105). Tapping or swiping a notification removes it from that list, so the copy goes out with .default sound. willPresent suppresses only an SMS thread that is currently open (Notifier.swift:138-149), never missed calls while Recents is on screen. (RedmiGateway/ios/RedmiGateway/Notifier.swift:86)

Native: iOS announces each message and missed call exactly once; once it has been seen or dismissed it does not sound again.

Fix: Have the Redmi record what it pushed. Persist the pushed journal ids or seqs in PushNotifier and include "pushed":true in SmsJournal.Entry.toJson and CallJournal.Entry.toJson. On the iPhone, an entry marked pushed is stored without a new alert, or at most posted with interruptionLevel .passive and no sound. Also suppress missed-call banners in willPresent while Recents is the visible tab, the same way the open SMS thread is handled.

Verifier: Confirmed. The Redmi pushes an alert when deliveredSmsSeq or deliveredCallSeq is below the entry's seq after 8 s (PushNotifier.kt:97-112), using collapse ids sms-<id> and call-<logId>. When the iPhone later syncs, upsertSms and upsertCall return any entry not yet stored as news if it is under 24 h old (HistoryStore.swift:147-149, 211-213). Notifier quiets the copy only if that identifier is still delivered (Notifier.swift:57-59, 65-67, 86, 102). Tapping or swiping the push removes it from that list, and didReceive records nothing (Notifier.swift:151-164). willPresent suppresses only the open SMS thread (143-147) and never missed calls. Opening the app from a missed-call push therefore triggers a sync (GatewayConnection.swift:1446 -> 629) and a second banner with sound. Severity: medium.

Better fix: 

API notes: Set the 'pushed' mark only after APNs accepts the request. sendAlert currently passes no done callback (ApnsSender.kt:98-104), so add one to post() for alerts. It has to be persisted: the journal DB is at version 1 with a no-op onUpgrade (SmsJournal.kt:88), so use a separate table or prefs keyed by journal id rather than an unmigrated column. APNs acceptance does not prove delivery, especially with apns-expiration 0 (see CONS-13). On the iPhone, always post a pushed entry with interruptionLevel .passive and no sound rather than dropping it, so the alert is never lost entirely. Suppressing missed-call banners on Recents needs a recentsVisible flag next to openThreadKey, which is checked in willPresent for threadIdentifier "missed".

## CONS-5 [Consistency between the two phones as the owner experiences it on the iPhone (SMS/call history sync, read/seen state, notifications, contact names, timestamps, link gaps)] medium both real=True fix_ok=True
**Several SMS imported together are published newest-first, so the oldest message ends up on top of the iPhone's lock screen and notification stack**

Impact: Two or more SMS can arrive together: after a Redmi reboot, while the gateway service was down, or two texts within the 400 ms debounce, such as a bank OTP followed by a second OTP. The notification the owner sees on top is then the older one, for example an expired code.

Evidence: SmsJournal.importNow queries the provider ORDER BY _ID DESC (SmsJournal.kt:194-201) and upserts rows in that order (SmsJournal.kt:203-221). Each upsert takes the next AUTOINCREMENT seq (SmsJournal.kt:236-244), so the newest message gets the lowest seq. changed.sortBy{seq} then publishes newest-first (SmsJournal.kt:232-233). Sync replays in the same seq order (SmsJournal.kt:256-271, GatewayWebSocketServer.kt:609-614). HistoryStore.applySmsSync and applySmsPush return news in that order (HistoryStore.swift:172-185), and Notifier.add posts them one by one (Notifier.swift:76-89). The latest-posted notification, the oldest SMS, sits on top. The APNs fallback fires in the same order (PushNotifier.kt:97-102). By contrast, CallJournal imports _ID ASC (CallJournal.kt:177), so calls are not affected. (RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/journal/SmsJournal.kt:200)

Native: In Apple Messages the newest message is always the top notification and the last bubble.

Fix: In importNow, collect the qualifying cursor rows first, then upsert them in ascending _ID order, so seq follows arrival order for sync, live push and APNs alike. Keep the DESC scan only to find the early break. On iOS, sort records by date ascending in Notifier.add(sms:), and give sound only to the newest one per batch.

Verifier: Confirmed. importNow scans ORDER BY _ID DESC (SmsJournal.kt:194-200) and upserts in that order (203-221). Each upsert takes the next AUTOINCREMENT seq (236-243), so within one batch the newest SMS gets the lowest seq, and changed.sortBy{seq} publishes newest-first (232-233). readAfter replays in seq order (260-261). The iPhone returns news in item order (HistoryStore.swift:172-185), and Notifier.add posts in that order (Notifier.swift:76-88), so the oldest message is posted last and sits on top. PushNotifier schedules its APNs fallbacks in the same order (PushNotifier.kt:93-102). CallJournal uses ASC order (CallJournal.kt:177) and is not affected. Severity: low to medium. It needs two or more new rows in one import: after a reboot or service gap, or two SMS within the 400/800 ms debounce (SmsJournal.kt:78, 130).

Better fix: 

API notes: Collect the qualifying rows during the DESC scan, keeping the early-break logic at SmsJournal.kt:211, then upsert them in ascending _ID order. Status changes to older rows can stay in the same ascending pass. On iOS, sorting a batch by date and giving sound only to the newest notification is a harmless extra.

## CONS-6 [Consistency between the two phones as the owner experiences it on the iPhone (SMS/call history sync, read/seen state, notifications, contact names, timestamps, link gaps)] medium ios real=True fix_ok=True
**Viewing missed calls on the iPhone does not clear the iPhone's own missed-call notifications or the app-icon badge**

Impact: After opening Recents, the 'Cevapsız arama' banners stay in Notification Center. The home-screen badge keeps counting the missed calls until some later sync or SMS action happens to refresh it.

Evidence: RecentsView.onAppear calls history.markMissedSeen() (ContentView.swift:413-416), which only moves missedSeenAt (HistoryStore.swift:162-166). The only code that removes delivered notifications is clearConversation for SMS threads (Notifier.swift:114-123). updateBadge is called only from clearConversation (Notifier.swift:120), ConversationView.onDisappear (MessagesView.swift:213) and finishCatchUp (GatewayConnection.swift:1538), never after markMissedSeen and not on entering the background. The badge value includes unseenMissedCount (Notifier.swift:132-134). (RedmiGateway/ios/RedmiGateway/HistoryStore.swift:162)

Native: Opening Recents in Apple's Phone app clears the missed-call notifications and the badge at once.

Fix: In markMissedSeen, or right after it in RecentsView.onAppear, remove the delivered notifications whose threadIdentifier is "missed" (ids "call-<logId>") and call Notifier.shared.updateBadge(). Also call updateBadge() on didEnterBackground so the icon always matches what the app shows. Pair this with CONS-1's missed_seen so the Redmi clears too.

Verifier: Confirmed. RecentsView.onAppear calls only history.markMissedSeen() (ContentView.swift:413-416). That function only moves missedSeenAt, and it returns early when unseenMissedCount==0 (HistoryStore.swift:162-166). The only code that removes delivered notifications is clearConversation, which handles SMS threads only (Notifier.swift:114-123). updateBadge is called from Notifier.swift:120, MessagesView.swift:213 and GatewayConnection.swift:1538 only. The badge includes unseenMissedCount (Notifier.swift:132-134). 'Cevapsız arama' notifications therefore stay in Notification Center after Recents is shown. The badge stays stale unless a sync happens to finish after onAppear. Severity: medium.

Better fix: 

API notes: Do the clearing outside the unseenMissedCount>0 guard at HistoryStore.swift:163. Pushed missed-call notifications for calls not yet synced also use thread "missed" (PushNotifier.kt:111) and must be cleared too. Use getDeliveredNotifications, filter on threadIdentifier == "missed", call removeDeliveredNotifications(withIdentifiers:), then updateBadge(). setBadgeCount needs iOS 16, as at Notifier.swift:127.

## CONS-7 [Consistency between the two phones as the owner experiences it on the iPhone (SMS/call history sync, read/seen state, notifications, contact names, timestamps, link gaps)] low ios real=True fix_ok=False
**Seen markers are compared with the Redmi's message and call timestamps, so anything synced after a link gap can arrive already 'read'**

Impact: Say the owner opened a conversation, or Recents, while the link was down, and an SMS or missed call that happened on the Redmi before that moment syncs afterwards. It gets a banner but no unread dot and no badge. It looks read although it was never shown. The same happens for a delayed SMS that took the gateway's fallback path, which carries the network's (old) send time.

Evidence: isUnread is message.date > max(threadSeen[key], readBaseline) (HistoryStore.swift:290-294), and unseenMissedCount is call.date > missedSeenAt (HistoryStore.swift:158-160). Both markers are set to the iPhone's 'now' (HistoryStore.swift:164, 299). message.date is the Redmi provider DATE, or for a fallback row the PDU time (SmsReceiver.kt:25 -> SmsJournal.kt:129-133, 163-171). Meanwhile upsertSms and upsertCall still return such entries as news (HistoryStore.swift:147-149, 211-213), so the banner and the unread state disagree. (RedmiGateway/ios/RedmiGateway/HistoryStore.swift:293)

Native: Apple marks a message read only once it has been displayed; a message delivered late is still unread.

Fix: Store a local arrivedAt on SmsRecord and CallRecord, set at the first upsert, and compare the seen markers with arrivedAt instead of the Redmi date. Alternatively keep a per-message readLocally flag that is set only when the bubble is shown, which is what Apple does.

Verifier: The code matches the description. isUnread compares the Redmi's message date with max(threadSeen, readBaseline) (HistoryStore.swift:290-294). unseenMissedCount compares call.date with missedSeenAt (158-160). Both markers are set to the iPhone's current time (164, 299), while upsert still reports the entry as news (147-149, 211-213). An entry that happened on the Redmi before the iPhone viewed that thread or Recents, but synced afterwards, gets a banner but no unread dot or badge. Severity: low. It needs a view during a link gap; for missed calls also an existing unseen call, because of the guard at 163. A fallback row carries the PDU time (SmsJournal.kt:129-133, 163-171).

Better fix: Add `var arrivedAt: Date?` (Optional, so the synthesized Codable uses decodeIfPresent) to SmsRecord and CallRecord. In upsertSms and upsertCall, keep the existing record's arrivedAt when the id is already known, and set it to Date() otherwise. Compare the seen markers with (arrivedAt ?? date) in isUnread and unseenMissedCount. Fix CONS-3 first so that a decode failure can never wipe the file.

API notes: Adding a non-Optional stored property to SmsRecord or CallRecord makes the existing history.json fail to decode. Through the CONS-3 path, that wipes the whole history. Also, upsertSms and upsertCall rebuild the record on every update (HistoryStore.swift:139-146, 197, 206-210), so a first-seen timestamp is lost unless it is copied over from the previous record.

## CONS-8 [Consistency between the two phones as the owner experiences it on the iPhone (SMS/call history sync, read/seen state, notifications, contact names, timestamps, link gaps)] low ios real=True fix_ok=True
**A conversation left open when the app goes to the background keeps marking new messages seen and keeps their notifications on return**

Impact: If the owner leaves the app inside a conversation, messages fetched by a Bluetooth catch-up in the background can be marked read without being seen, so the badge drops. When the owner returns to that open conversation, its notifications stay in Notification Center.

Evidence: ConversationView marks the thread seen on every messages.count change (MessagesView.swift:180-183). openThreadKey is set in onAppear and cleared only in onDisappear (MessagesView.swift:206-214), and neither runs on a background or foreground switch. There is no scenePhase handling anywhere (grep finds no scenePhase in ios/RedmiGateway). clearConversation runs only in onAppear (MessagesView.swift:209). Speculative: whether SwiftUI evaluates onChange while the scene is in the background. The missing clear on returning to the foreground is certain. (RedmiGateway/ios/RedmiGateway/MessagesView.swift:182)

Native: Messages marks a thread read only when it is actually on screen in the foreground, and clears its notifications when you come back to it.

Fix: Read @Environment(\.scenePhase). Call markThreadSeen only while scenePhase == .active. When the phase becomes .active and the conversation is on screen, call markThreadSeen(key) and Notifier.shared.clearConversation(address). Set openThreadKey to nil on .background.

Verifier: The foreground half is confirmed. openThreadKey is set in onAppear and cleared only in onDisappear (MessagesView.swift:206-214). clearConversation and markThreadSeen run only on appear or on a count change (180-183, 207-209). There is no scenePhase handling in ios/RedmiGateway (grep finds none), and the willEnterForeground handler (GatewayConnection.swift:1437-1447) does not clear the open thread. When the owner returns to an open conversation, its notifications stay in Notification Center and the badge is not updated. The background half is speculative: it depends on whether SwiftUI runs onChange while the scene is in the background, as the reporter also says. Severity: low to medium.

Better fix: 

API notes: @Environment(\.scenePhase) inside ConversationView, with .onChange(of: scenePhase), is valid from iOS 14. Call markThreadSeen only while the phase is .active. On a change to .active, call markThreadSeen(key) and Notifier.shared.clearConversation(address). Clearing openThreadKey on .background is harmless: willPresent runs only in the foreground anyway.

## CONS-9 [Consistency between the two phones as the owner experiences it on the iPhone (SMS/call history sync, read/seen state, notifications, contact names, timestamps, link gaps)] low ios real=True fix_ok=True
**History is saved on a 0.5 s debounce after the background catch-up task has already ended; if iOS then kills the app, the next sync alerts about the same entries again**

Impact: After a Bluetooth-woken fetch in the background, the new sequence position, messages and seen markers may never reach disk. The next launch fetches the same SMS and missed calls again and, if their banners were dismissed, plays the alerts again.

Evidence: scheduleSave delays the write by 0.5 s on the main queue, then writes on a global queue (HistoryStore.swift:305-325). finishCatchUp ends the UIApplication background task straight after applySmsSync returns (GatewayConnection.swift:632-641, 1537-1552), before the delayed save has run, so the app can be suspended and later jetsammed with the save still pending. On relaunch the old snapshot loads (HistoryStore.swift:81-92), the sync refetches from the old smsSeq, and Notifier re-posts with sound unless the old banner is still delivered (Notifier.swift:76-89). (RedmiGateway/ios/RedmiGateway/HistoryStore.swift:311)

Native: Messages never re-announces a message after the app or phone was suspended.

Fix: Add HistoryStore.flush(), a synchronous encode and atomic write, and call it in finishCatchUp before endCatchUpTask, on didEnterBackground, and before endIdleLink ends its task. Alternatively, have saveNow take its own beginBackgroundTask around the write.

Verifier: Confirmed. scheduleSave defers the write by 0.5 s on main and then writes on a global queue (HistoryStore.swift:305-324). In the sync_sms handler, applySmsSync runs and finishCatchUp then ends the catch-up background task immediately (GatewayConnection.swift:634-639, 1537-1552), before the delayed save fires. A suspended app that is jetsammed loses the new smsSeq, the messages and the markers. On relaunch the old snapshot loads (HistoryStore.swift:81-91), and re-fetched entries are news again (147-149, 211-213). They are re-alerted with sound unless the old banner is still delivered (Notifier.swift:86, 102). Severity: low to medium. It needs a kill in the small window after the background task ends.

Better fix: 

API notes: A synchronous flush(): encode the snapshot and write it with Data.write(options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) on the calling thread before endCatchUpTask, on didEnterBackground and before the idle-link task ends. Cancel the pending saveWorkItem first. The flush must respect the CONS-3 'loaded' guard so that it never writes an unloaded store.

## CONS-10 [Consistency between the two phones as the owner experiences it on the iPhone (SMS/call history sync, read/seen state, notifications, contact names, timestamps, link gaps)] low ios real=True fix_ok=True
**A journal entry removes every pending copy with the same text to the same person within 10 minutes, so a second identical send that never left can vanish without a failure**

Impact: If the owner sends the same short text twice ('Tamam', 'Geliyorum'), the first message's journal entry deletes both bubbles. If the second never reached the Redmi, for example because the socket died while still looking authenticated, no 'Gönderilemedi' is ever shown, and the owner believes it was sent.

Evidence: upsertSms removes all pending copies with gatewayId==nil, the same body and the same PhoneIdentity.key whose dates are within 600 s (HistoryStore.swift:198-205, removeAll). Afterwards completePending and the 90 s timeout look up the requestId and find nothing (HistoryStore.swift:234-239, GatewayConnection.swift:1323-1332), so no failed state can surface. (RedmiGateway/ios/RedmiGateway/HistoryStore.swift:201)

Native: Each sent bubble in Messages has its own delivery state; two identical texts are two independent messages.

Fix: Remove only one pending copy per journal entry: the oldest match (firstIndex instead of removeAll). Better still, carry the requestId into the journal. SmsSender knows it when the radio result arrives, so the Redmi can tag the provider row id with the requestId and add "requestId" to SmsJournal.Entry.toJson, and the iPhone then matches exactly.

Verifier: Confirmed. For any non-received journal entry, upsertSms calls removeAll on every pending copy with gatewayId==nil, the same body and the same PhoneIdentity.key within 600 s (HistoryStore.swift:198-205). A second identical send that never reached the Redmi is removed together with the first. completePending and the 90 s timeout then find nothing (234-235; GatewayConnection.swift:1326-1331), and resendRetriedSms skips it (1376-1377). No 'Gönderilemedi' is ever shown. Severity: medium, because the owner believes a message was sent when it was not.

Better fix: 

API notes: Short-term: remove only the single oldest matching pending copy (firstIndex by date). Journal entries arrive in seq order, so the matching is one-to-one. For exact matching: when the platform persists a message for a non-default SMS app, it puts the provider row URI into the sentIntent fill-in as the extra "uri" (SMSDispatcher.SmsTracker). SmsSender's receiver (SmsSender.kt:40-57) can read intent.getStringExtra("uri") to map the provider _ID to the iPhone requestId, and SmsJournal can then add "requestId" to Entry.toJson. Verify on the device that /e/OS fills "uri".

## CONS-11 [Consistency between the two phones as the owner experiences it on the iPhone (SMS/call history sync, read/seen state, notifications, contact names, timestamps, link gaps)] low android real=True fix_ok=True
**An SMS can appear twice on the iPhone, with two Apple pushes, if the ROM's SMS app stores it more than 5 s after the broadcast**

Impact: A duplicate bubble and a duplicate notification for one SMS, most likely right after a Redmi reboot when the messaging app starts slowly. Speculative timing, not observed.

Evidence: noteReceived inserts a synthetic negative-id fallback if no provider row matches after 5 s (SmsJournal.kt:129-134, 146-160, 162-172). When the provider row appears later, importNow publishes it under its real _ID (SmsJournal.kt:203-221) without checking for the fallback, and the iPhone dedupes only by gatewayId (HistoryStore.swift:188, 197). PushNotifier pushes both, with different collapse ids sms-<negative> and sms-<real> (PushNotifier.kt:101). (RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/journal/SmsJournal.kt:132)

Native: One message, one bubble, one alert.

Fix: In importNow, when a new inbox provider row matches a fallback row (same body, date within the existsInProvider window), delete the fallback and publish the provider entry with a "replaces":<syntheticId> field that the iPhone uses to drop the old record. Or recheck the provider just before publishing the fallback and once more after about 30 s.

Verifier: The code path exists. noteReceived inserts a negative-id fallback when existsInProvider fails after 5 s (SmsJournal.kt:129-133, 146-171). importNow later publishes the provider row under its real _ID without checking for that fallback (203-221). The iPhone dedupes by gatewayId only (HistoryStore.swift:188, 197), and PushNotifier pushes both under different collapse ids (PushNotifier.kt:101). The trigger is speculative and probably rare. SMS_RECEIVED is normally sent only after the default app's SMS_DELIVER receiver finishes, by which point the row usually exists, and existsInProvider already allows ±120 s around DATE_SENT/DATE. The trigger needs an SMS app that stores the row asynchronously after its receiver returns, or a stored body that differs from the PDU text. Severity: low.

Better fix: 

API notes: On a new inbox provider row, look in the journal for rows with sms_id<0, the same body and a date within ±120 s. Delete them and publish the provider entry with "replaces": <syntheticId>. The iPhone drops the gatewayId==replaces record and reuses its notification identifier, or posts quietly. PushNotifier should not push an entry that replaces one it already pushed. Older iPhone builds ignore the field and only keep today's duplicate.

## CONS-12 [Consistency between the two phones as the owner experiences it on the iPhone (SMS/call history sync, read/seen state, notifications, contact names, timestamps, link gaps)] low android real=True fix_ok=True
**MMS never reach the iPhone**

Impact: A picture message, a group message, or a long text that the sender's network turned into MMS is stored only by the Redmi's messaging app. The iPhone shows nothing and gives no hint that something arrived.

Evidence: SmsJournal mirrors only Telephony.Sms.CONTENT_URI (SmsJournal.kt:194-200), and SmsReceiver listens only for SMS_RECEIVED (AndroidManifest.xml:145-152, SmsReceiver.kt:20). Nothing reads Telephony.Mms or handles WAP_PUSH. (RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/journal/SmsJournal.kt:195)

Native: Apple Messages shows MMS and group texts inline.

Fix: Mirror Telephony.Mms inbox rows into the journal: the text parts from content://mms/part, plus a placeholder ('Resimli mesaj — Redmi'de') for media. Use a distinct id space, such as an mms flag or a high bit, so they cannot collide with SMS _IDs. If the gateway becomes the SMS role holder (CONS-2) it must handle WAP_PUSH_DELIVER anyway.

Verifier: Confirmed. SmsJournal queries and observes only Telephony.Sms.CONTENT_URI (SmsJournal.kt:113, 194-200). SmsReceiver handles only SMS_RECEIVED (AndroidManifest.xml:145-152, SmsReceiver.kt:20). Nothing reads Telephony.Mms or handles WAP_PUSH. MMS, including group texts and texts that carriers convert to MMS, never reach the iPhone, and the iPhone gives no hint that one arrived. Severity: medium.

Better fix: 

API notes: READ_SMS (already declared) is enough to read content://mms. Telephony.Mms.DATE is in seconds, not milliseconds. The sender comes from content://mms/<id>/addr with type=137 (PduHeaders.FROM, which is hidden, so use the literal). Text parts come from content://mms/part with mid=<id> and ct='text/plain', column 'text'. m_type 132 is retrieve-conf (downloaded); 130 is notification-ind (not downloaded yet), which should show as a placeholder. Register a second ContentObserver on Telephony.Mms.CONTENT_URI or content://mms-sms/, because the current one only watches the SMS URI. Use a separate id range, such as a large positive offset, because negative ids are already used by fallback rows (SmsJournal.kt:165). The MMS download stays with foundation.e.message, so do not take ROLE_SMS (see CONS-2).

## CONS-13 [Consistency between the two phones as the owner experiences it on the iPhone (SMS/call history sync, read/seen state, notifications, contact names, timestamps, link gaps)] low android real=True fix_ok=True
**SMS and missed-call Apple pushes expire immediately, so an iPhone that is briefly offline never gets them**

Impact: The iPhone has no cellular service. If it is between Wi-Fi networks, or briefly off the hotspot, when an SMS or missed call arrives, Apple drops the alert, and nothing resends it when the iPhone is back online out of Bluetooth range. The owner finds out only by opening the app.

Evidence: Every APNs request, alerts included, sets apns-expiration: 0 (ApnsSender.kt:189-190; alerts go through post() from sendAlert at ApnsSender.kt:98-104). The alert is sent once, after 8 s (PushNotifier.kt:97-102, 107-112). Late recovery depends on the Bluetooth beacon seq (GatewayConnection.swift:1018-1020), which works only near the Redmi. (RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/push/ApnsSender.kt:190)

Native: iMessage and SMS notifications arrive when the iPhone comes back online, even if it was offline when they were sent.

Fix: Pass an expiration per type: keep 0 for VoIP, and for alert pushes use now+24 h (epoch seconds). APNs stores the latest alert per app for an offline device and delivers it on reconnect. The collapse id keeps it from duplicating the local copy.

Verifier: Confirmed. post() sets apns-expiration: 0 for every push type (ApnsSender.kt:189-190), and alerts go through it (98-104). The comment shows this was deliberate ('Never deliver late'). With 0, APNs tries once and does not store the push. An iPhone with no cellular that is between networks when the 8 s fallback fires (PushNotifier.kt:97-112) loses the alert. Recovery needs the Bluetooth beacon seq (GatewayConnection.swift:1018-1020) or opening the app. Severity: medium.

Better fix: 

API notes: apns-expiration takes a UNIX epoch in seconds. Keep 0 for voip and background pushes. For alerts, a few hours fits OTP lifetimes better than 24 h; missed calls can use 24 h. APNs keeps only the most recent stored notification per app for an offline device, so earlier ones are still lost. A stored alert delivered after the app has already posted its local copy under the same identifier (collapse id) replaces it and may sound again. Combine this change with CONS-4 so that the iPhone's later copy is passive.

## CONS-14 [Consistency between the two phones as the owner experiences it on the iPhone (SMS/call history sync, read/seen state, notifications, contact names, timestamps, link gaps)] low both real=True fix_ok=True
**Pushed banners use the Redmi's contacts and a different thread key than the app, so names and grouping change between the push and the app**

Impact: Away from home a banner shows a raw '+90532…' or the Redmi's seed-contact name, while the app and its own notifications show the iPhone contact name. Pushed SMS from alphanumeric senders (banks, OTP, operators) all collapse into one notification group, and opening the conversation does not clear them until the app has replaced them.

Evidence: PushNotifier.contactName queries only the Redmi's ContactsContract (PushNotifier.kt:99, 110, 115-124). The Redmi's contacts are seeded from a bundled VCF (ContactResolver.kt:148-233), and names the iPhone sends back (GatewayWebSocketServer.kt:490-496, ContactResolver.kt:235-247) are never consulted. The iPhone uses its own contacts (Notifier.swift:80, 97; PhoneContactPicker.swift:97-102). The thread id is 'sms-'+last 10 digits on Android (PushNotifier.kt:156-157), which is 'sms-' for every alphanumeric sender, but 'sms-'+PhoneIdentity.key on iOS (Notifier.swift:83, 115; PhoneContactPicker.swift:14-20 keeps letters for alphanumeric senders). (RedmiGateway/android/app/src/main/java/com/ilkay/redmigateway/push/PushNotifier.kt:115)

Native: Every Messages and Phone notification uses the iPhone's own contact name and groups by conversation.

Fix: Short term: in contactName, try ContactResolver.getCached(number) first. Make smsPushThreadId use the same rule as PhoneIdentity.key (keep the uppercased text for senders without digits). Proper fix: send number-only alerts with mutable-content:1 and add an iOS Notification Service Extension that formats the title from a contact map the main app writes to an App Group. The same extension can decrypt a sealed body, as the VoIP push already does.

Verifier: Confirmed. PushNotifier.contactName reads only the Redmi's ContactsContract (PushNotifier.kt:115-124). ContactResolver.put keeps iPhone names in an in-memory cache only (ContactResolver.kt:235-242), and only for numbers asked about through resolve_contact (GatewayWebSocketServer.kt:809-812, GatewayConnection.swift:668-678). The iPhone titles its own notifications from its own contacts (Notifier.swift:80, 97). The thread id is 'sms-'+last 10 digits on Android (PushNotifier.kt:156-157), so every alphanumeric sender gets 'sms-'. iOS uses PhoneIdentity.key, which keeps uppercased text (PhoneContactPicker.swift:14-20), and clearConversation matches on that key (Notifier.swift:115-117). International numbers longer than 12 digits also differ between the two. Severity: low.

Better fix: 

API notes: Make smsPushThreadId mirror PhoneIdentity.key exactly. No digits: trimmed uppercased text. 12 digits starting with 90, or 11 starting with 0: last 10 digits. Otherwise: all digits. Do not use ContactResolver.normalize, whose rules differ. getCached helps only for numbers resolved during the current Redmi process lifetime. The proper Notification Service Extension fix needs mutable-content:1 in aps, a new extension target with its own bundle id and provisioning, and an App Group entitlement on both targets for the contact map. The CI/TestFlight setup has to be updated for all of these.

## CONS-15 [Consistency between the two phones as the owner experiences it on the iPhone (SMS/call history sync, read/seen state, notifications, contact names, timestamps, link gaps)] low both real=True fix_ok=True
**The iPhone's 'N SMS' counter can disagree with the number of parts the Redmi actually sends**

Impact: The composer can say '1 SMS' or '2 SMS' while the Redmi sends and charges for a different number of parts. Sending is a paid action on the SIM.

Evidence: MessagesView.swift:248-253 treats only ASCII as GSM-7 (160/153 characters) and everything else as UCS-2 (70/67). The Redmi splits with SmsManager.divideMessage (SmsSender.kt:73), which counts GSM-7 non-ASCII characters (é, £, €) as single, extension characters ([ ] { } ~ ^ | \) as double, and uses the Turkish national shift table where the carrier config enables it. The real parts.size is never reported back (GatewayWebSocketServer.kt:554-568). (RedmiGateway/ios/RedmiGateway/MessagesView.swift:250)

Native: Apple's SMS segment count matches what the carrier bills.

Fix: Return "parts": parts.size in sms_send_result and show it on the sent bubble. For the live counter, add a cheap {type:'sms_parts', text} query answered by divideMessage, or mirror the GSM-7 basic and extension tables on iOS.

Verifier: Confirmed, low severity. The composer counts parts as ASCII → 160/153, anything else → 70/67, measured in grapheme clusters (draft.count) (MessagesView.swift:237-253). The Redmi splits with SmsManager.divideMessage (SmsSender.kt:73). divideMessage counts GSM-7 non-ASCII characters as one septet and extension characters as two, can use national shift tables if the carrier or resource config enables them, and counts UCS-2 in UTF-16 units, so an emoji takes 2. The sms_send_result reply carries only success and error, never parts.size (GatewayWebSocketServer.kt:557-564). The counter is shown only above 140 characters (MessagesView.swift:237). Whether Turkish shift tables are on for this SIM is unverified.

Better fix: 

API notes: For a live count, SmsMessage.calculateLength(text, false) returns int[4] {msgCount, codeUnitCount, codeUnitsRemaining, codeUnitSize}. It uses the same encoder rules as divideMessage and is cheaper. Debounce the query from the composer. Add parts.size to sms_send_result; parts is already computed at SmsSender.kt:73 but is not passed to the callback.

## CONS-16 [Consistency between the two phones as the owner experiences it on the iPhone (SMS/call history sync, read/seen state, notifications, contact names, timestamps, link gaps)] low ios real=False fix_ok=False
**Two call histories on the iPhone disagree: Apple's Phone Recents (CallKit) and the app's Recents (Redmi journal)**

Impact: Apple's Phone app Recents lists only calls that were shown through CallKit on the iPhone. Calls missed while the iPhone was not reached, and calls answered on the Redmi, appear only in the app's own Recents. An answer that the Redmi did not confirm adds an extra 'missed' row in Apple's Recents for a call the owner then answers. The extra row is speculative: it depends on how iOS renders .unanswered.

Evidence: config.includesCallsInRecents = true (CallKitManager.swift:94) records CallKit calls in Apple's Recents. The app's Recents comes from the journal (HistoryStore.swift:123-150). When an answer is not confirmed, the call is ended as .unanswered and reported again as a new incoming call (CallKitManager.swift:153-161). (RedmiGateway/ios/RedmiGateway/CallKitManager.swift:94)

Native: On a normal iPhone there is exactly one Recents list, and it contains every call the line received.

Fix: Pick one source of truth. Either set includesCallsInRecents=false, so only the journal-backed Recents exists, or keep it and end the withdrawn-answer call with .failed or .remoteEnded so the re-presented call is the only Recents row. Document that missed calls the iPhone never saw live only in the app.

Verifier: Apple Recents integration is a deliberate feature. includesCallsInRecents=true (CallKitManager.swift:94) is paired with handling of call-backs from Apple's Recents: onContinueUserActivity for INStartCallIntent and INStartAudioCallIntent (ContentView.swift:47-55) and the NSUserActivityTypes declaration (Info.plist:44-47). Apple's Recents showing only CallKit-presented calls is inherent, not a code defect. The extra 'missed' row from ending an unconfirmed answer with .unanswered (CallKitManager.swift:154-161) depends on how iOS renders that end reason, which is speculative and not observed. Setting includesCallsInRecents=false would remove a working feature.

Better fix: Keep includesCallsInRecents=true. If the owner actually sees a missed row followed by an answered row after an unconfirmed answer, test the alternative end reasons on the device and keep the one that leaves a single row.

API notes: No reliable documentation says whether .remoteEnded, .failed or .unanswered for a never-connected incoming call shows as missed in Apple's Recents. Any change would have to be checked on the device.
