import Combine
import SwiftUI

private struct MessageThread: Identifiable {
    let id: String
    let address: String
    let last: SmsRecord
    let unread: Int
}

/// SMS conversations on the Redmi's line, grouped by person.
struct MessagesView: View {
    @ObservedObject private var history = HistoryStore.shared
    @ObservedObject private var directory = PhoneContactDirectory.shared
    @Binding var target: String?
    let openSettings: () -> Void
    @State private var query = ""
    @State private var showingCompose = false
    /// The conversation open from its row, by PhoneIdentity.key.
    ///
    /// One person's number arrives in several formats: as typed here ("0532 123 45 67"), as the
    /// Redmi's journal stores a send ("05321234567") and as the network delivers a reply
    /// ("+90532..."). The row used to be tagged with the newest message's address, so a reply or
    /// the journal copy of a send swapped the tag, popped the open conversation and lost its draft.
    @State private var openKey: String?
    /// A conversation opened before it has a row (a new number from "Yeni mesaj", a link or a
    /// contact). Its own link stays active when the first message creates the row, so the screen
    /// does not pop and push again.
    @State private var routedAddress: String?

    /// Built once per update: it groups the whole history, up to 3000 messages.
    private func makeThreads() -> [MessageThread] {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return Dictionary(grouping: history.messages) { PhoneIdentity.key($0.address) }
            .compactMap { key, items -> MessageThread? in
                guard let last = items.max(by: { $0.date < $1.date }) else { return nil }
                if !text.isEmpty {
                    // Searches this thread's own messages, not the whole history once per thread.
                    let matches = directory.displayName(for: last.address).localizedCaseInsensitiveContains(text)
                        || last.address.contains(text)
                        || items.contains { $0.body.localizedCaseInsensitiveContains(text) }
                    guard matches else { return nil }
                }
                return MessageThread(id: key, address: last.address, last: last,
                                     unread: items.filter { history.isUnread($0) }.count)
            }
            .sorted { $0.last.date > $1.last.date }
    }

    var body: some View {
        let threads = makeThreads()
        Group {
            if history.messages.isEmpty {
                EmptyStateView(
                    symbol: "message",
                    title: "Mesaj yok",
                    message: "Redmi hattına gelen ve giden SMS'ler burada kişi bazında görünür.",
                    actionTitle: "Yeni mesaj",
                    action: { showingCompose = true }
                )
            } else {
                List(threads) { thread in
                    NavigationLink(tag: thread.id, selection: $openKey) {
                        ConversationView(address: thread.address)
                    } label: {
                        ThreadRow(thread: thread)
                    }
                }
                .listStyle(.plain)
                .searchable(text: $query, prompt: "Ara")
                .refreshable { GatewayConnection.shared.requestSync() }
            }
            if let address = routedAddress {
                NavigationLink(isActive: Binding(get: { routedAddress != nil }, set: { if !$0 { routedAddress = nil } })) {
                    // A different conversation in the same link must not inherit this one's draft.
                    ConversationView(address: address)
                        .id(PhoneIdentity.key(address))
                } label: { EmptyView() }
                .hidden()
            }
        }
        .navigationTitle("Mesajlar")
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { showingCompose = true } label: { Image(systemName: "square.and.pencil") }
                    .accessibilityLabel("Yeni mesaj")
            }
            SettingsToolbarButton(action: openSettings)
        }
        .sheet(isPresented: $showingCompose) {
            NewMessageSheet { address in
                showingCompose = false
                openThread(address)
            }
        }
        .onAppear {
            directory.ensureLoaded()
            consumeTarget()
        }
        .onChange(of: target) { _ in consumeTarget() }
    }

    private func consumeTarget() {
        guard let address = target else { return }
        target = nil
        openThread(address)
    }

    private func openThread(_ address: String) {
        let key = PhoneIdentity.key(address)
        // Already on screen: opening it again would rebuild it and drop the draft.
        if openKey == key { return }
        if let routed = routedAddress, PhoneIdentity.key(routed) == key { return }
        if makeThreads().contains(where: { $0.id == key }) {
            routedAddress = nil
            openKey = key
        } else {
            openKey = nil
            routedAddress = address
        }
    }
}

private struct ThreadRow: View {
    @ObservedObject private var directory = PhoneContactDirectory.shared
    let thread: MessageThread

    var body: some View {
        let contact = directory.contact(for: thread.address)
        HStack(alignment: .top, spacing: 12) {
            ContactAvatar(name: contact?.name ?? "", thumbnail: contact?.thumbnail, size: 44)
            VStack(alignment: .leading, spacing: 3) {
                HStack(alignment: .firstTextBaseline) {
                    Text(directory.displayName(for: thread.address))
                        .font(.body.weight(thread.unread > 0 ? .semibold : .regular))
                        .lineLimit(1)
                    Spacer()
                    Text(RelativeDay.text(for: thread.last.date))
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
                HStack(alignment: .top) {
                    Text(preview)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .lineLimit(2)
                    Spacer(minLength: 0)
                    if thread.unread > 0 {
                        Circle().fill(Color.accentColor).frame(width: 10, height: 10)
                            .accessibilityLabel("\(thread.unread) okunmamış")
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }

    private var preview: String {
        switch thread.last.status {
        case .failed: return "Gönderilemedi: \(thread.last.body)"
        case .sending: return "Gönderiliyor: \(thread.last.body)"
        default: return thread.last.body
        }
    }
}

struct ConversationView: View {
    @ObservedObject private var history = HistoryStore.shared
    @ObservedObject private var directory = PhoneContactDirectory.shared
    @Environment(\.scenePhase) private var scenePhase
    /// The address it was opened with; the newest message's address takes over once there is one.
    let address: String
    /// Between onAppear and onDisappear: in front in the Messages tab, not under another tab.
    @State private var isOnScreen = false

    private var key: String { PhoneIdentity.key(address) }

    var body: some View {
        // Evaluated once per update; the filter runs over the whole history. The connection is not
        // observed here: its log publishes many times a minute and would redraw every bubble.
        let items = history.messages.filter { PhoneIdentity.key($0.address) == key }
        let entries = ConversationEntry.build(items)
        let replyAddress = items.last?.address ?? address
        let canReply = PhoneLink.isDialable(replyAddress)
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(entries) { entry in
                            if let header = entry.header {
                                Text(header)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                    .padding(.top, 10)
                            }
                            MessageBubble(message: entry.message, showsTime: entry.showsTime) {
                                GatewayConnection.shared.retrySms(entry.message)
                            }
                            .id(entry.message.id)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                }
                .modifier(ConversationScrolling())
                .onAppear { scrollToEnd(proxy, id: items.last?.id, animated: false) }
                .onChange(of: "\(items.count)|\(items.last?.id ?? "")") { _ in
                    scrollToEnd(proxy, id: items.last?.id, animated: true)
                    markSeenIfInFront()
                }
                // The keyboard shrinks the scroll view; keep the newest bubble above it.
                .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidShowNotification)) { _ in
                    scrollToEnd(proxy, id: items.last?.id, animated: true)
                }
            }
            Divider()
            if canReply {
                MessageComposer(key: key, address: replyAddress)
            } else {
                Text("Bu gönderene yanıt verilemez.")
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(12)
            }
        }
        .navigationTitle(directory.displayName(for: replyAddress))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                // Operator and service senders ("TURKCELL") cannot be called; the call would be
                // refused with an error only the keypad shows.
                if canReply {
                    Button { InAppDial.start(replyAddress) } label: {
                        Image(systemName: "phone")
                    }
                    .accessibilityLabel("Ara")
                }
            }
        }
        .onAppear {
            isOnScreen = true
            history.markThreadSeen(key)
            history.openThreadKey = key
            Notifier.shared.clearConversation(replyAddress)
        }
        .onDisappear {
            isOnScreen = false
            if history.openThreadKey == key { history.openThreadKey = nil }
            Notifier.shared.updateBadge()
        }
        .onChange(of: scenePhase) { phase in
            guard isOnScreen else { return }
            switch phase {
            case .active:
                // Back in front of the person: what arrived meanwhile is read now, and its
                // notifications go, as in Messages.
                history.openThreadKey = key
                history.markThreadSeen(key)
                Notifier.shared.clearConversation(address)
            case .background:
                if history.openThreadKey == key { history.openThreadKey = nil }
            default:
                break
            }
        }
    }

    /// A message counts as read only while the conversation is really in front of the person: not
    /// under another tab, and not after a Bluetooth catch-up while the app is in the background.
    private func markSeenIfInFront() {
        guard isOnScreen, scenePhase == .active else { return }
        history.markThreadSeen(key)
    }

    /// Deferred one turn: on appear and right after an insert the lazy rows have no size yet, and a
    /// synchronous scrollTo stops short of the newest bubble.
    private func scrollToEnd(_ proxy: ScrollViewProxy, id: String?, animated: Bool) {
        guard let id else { return }
        Task { @MainActor in
            if animated {
                withAnimation { proxy.scrollTo(id, anchor: .bottom) }
            } else {
                proxy.scrollTo(id, anchor: .bottom)
            }
        }
    }
}

/// Opens at the newest message and lets a drag put the keyboard away, as in Messages.
private struct ConversationScrolling: ViewModifier {
    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 17.0, *) {
            content
                .defaultScrollAnchor(.bottom)
                .scrollDismissesKeyboard(.interactively)
        } else if #available(iOS 16.0, *) {
            content.scrollDismissesKeyboard(.interactively)
        } else {
            content
        }
    }
}

/// One bubble with what goes above and below it, worked out once per update.
private struct ConversationEntry: Identifiable {
    let message: SmsRecord
    /// "Bugün 14:32" above the first message of a day or after a quiet hour, as in Messages.
    let header: String?
    /// The time under the last bubble of a run; a burst of messages shows it once.
    let showsTime: Bool

    var id: String { message.id }

    static func build(_ items: [SmsRecord]) -> [ConversationEntry] {
        var entries: [ConversationEntry] = []
        entries.reserveCapacity(items.count)
        for index in items.indices {
            let message = items[index]
            let previous: SmsRecord? = index > 0 ? items[index - 1] : nil
            let next: SmsRecord? = index + 1 < items.count ? items[index + 1] : nil
            let header: String? = startsSection(after: previous, message) ? TimeHeader.text(for: message.date) : nil
            let showsTime: Bool
            if let next {
                showsTime = next.isIncoming != message.isIncoming
                    || next.date.timeIntervalSince(message.date) > 600
                    || startsSection(after: message, next)
            } else {
                showsTime = true
            }
            entries.append(ConversationEntry(message: message, header: header, showsTime: showsTime))
        }
        return entries
    }

    private static func startsSection(after previous: SmsRecord?, _ message: SmsRecord) -> Bool {
        guard let previous else { return true }
        return !Calendar.current.isDate(previous.date, inSameDayAs: message.date)
            || message.date.timeIntervalSince(previous.date) > 3600
    }
}

/// Time headers as in Messages: "Bugün 14:32", "Dün 09:10", "Salı 14:32", "12 Eki 14:32".
private enum TimeHeader {
    static func text(for date: Date) -> String {
        let calendar = Calendar.current
        let now = Date()
        let time = date.formatted(date: .omitted, time: .shortened)
        if calendar.isDateInToday(date) { return "Bugün \(time)" }
        if calendar.isDateInYesterday(date) { return "Dün \(time)" }
        if let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: date),
                                              to: calendar.startOfDay(for: now)).day,
           days >= 0, days < 7 {
            return "\(date.formatted(.dateTime.weekday(.wide))) \(time)"
        }
        if calendar.isDate(date, equalTo: now, toGranularity: .year) {
            return "\(date.formatted(.dateTime.day().month(.abbreviated))) \(time)"
        }
        return "\(date.formatted(.dateTime.day().month(.abbreviated).year())) \(time)"
    }
}

private struct MessageBubble: View {
    let message: SmsRecord
    let showsTime: Bool
    let retry: () -> Void
    @ScaledMetric(relativeTo: .body) private var jumboSize: CGFloat = 48

    var body: some View {
        HStack {
            if !message.isIncoming { Spacer(minLength: 48) }
            VStack(alignment: message.isIncoming ? .leading : .trailing, spacing: 3) {
                content
                if message.isIncoming, let code = OneTimeCode.detect(in: message.body) {
                    OneTimeCodeButton(code: code)
                }
                footer
            }
            if message.isIncoming { Spacer(minLength: 48) }
        }
    }

    /// One to three emoji stand large without a bubble, as in Messages.
    @ViewBuilder
    private var content: some View {
        if EmojiText.jumboCount(message.body) != nil {
            Text(message.body.trimmingCharacters(in: .whitespacesAndNewlines))
                .font(.system(size: jumboSize))
                .textSelection(.enabled)
        } else {
            bubbleText
                .padding(.horizontal, 13)
                .padding(.vertical, 8)
                .background(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(bubbleColor)
                )
        }
    }

    @ViewBuilder
    private var bubbleText: some View {
        if #available(iOS 16.0, *) {
            MessageText(text: message.body, isIncoming: message.isIncoming)
                .fixedSize(horizontal: false, vertical: true)
        } else {
            // iOS 15 cannot size the text view (no UIViewRepresentable.sizeThatFits): plain text
            // with the whole-message copy.
            Text(message.body)
                .foregroundColor(message.isIncoming ? .primary : .white)
                .textSelection(.enabled)
        }
    }

    /// Messages paints SMS bubbles green; a failed send stays red.
    private var bubbleColor: Color {
        if message.isIncoming { return Color(UIColor.secondarySystemFill) }
        return message.status == .failed ? Color.red.opacity(0.75) : Color(UIColor.systemGreen)
    }

    @ViewBuilder
    private var footer: some View {
        switch message.status {
        case .sending:
            Text("Gönderiliyor…").font(.caption2).foregroundColor(.secondary)
        case .failed:
            Button(action: retry) {
                Label("Gönderilemedi · Tekrar dene", systemImage: "exclamationmark.circle")
                    .font(.caption2)
                    .foregroundColor(.red)
            }
            .buttonStyle(.plain)
        default:
            if showsTime {
                Text(message.date.formatted(date: .omitted, time: .shortened))
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
    }
}

/// "Kodu kopyala" under an incoming one-time code. iOS AutoFill only offers codes from Apple's
/// Messages, and every code now arrives here.
private struct OneTimeCodeButton: View {
    let code: String
    @State private var copied = false

    var body: some View {
        Button {
            UIPasteboard.general.string = code
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            copied = true
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                copied = false
            }
        } label: {
            Label(copied ? "Kopyalandı" : "Kodu kopyala · \(code)",
                  systemImage: copied ? "checkmark" : "doc.on.doc")
                .font(.footnote.weight(.semibold).monospacedDigit())
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Capsule().fill(Color(UIColor.tertiarySystemFill)))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(copied ? "Kod kopyalandı" : "Kodu kopyala")
        .accessibilityValue(code)
    }
}

/// Unsent text per conversation for as long as the app runs, so leaving a conversation does not
/// lose what was typed, as in Messages.
@MainActor
private enum ComposerDrafts {
    static var texts: [String: String] = [:]
}

/// The input row. It keeps its own state, so a keystroke redraws this row and not every bubble.
private struct MessageComposer: View {
    @ObservedObject private var connection = GatewayConnection.shared
    let key: String
    let address: String
    @State private var draft = ""

    /// The Redmi refuses longer text (SmsSender.MAX_LENGTH, Kotlin String.length = UTF-16 units).
    private static let maxLength = 1600

    var body: some View {
        let length = SmsLength(draft)
        let units = draft.utf16.count
        VStack(alignment: .trailing, spacing: 4) {
            if !connection.isAuthenticated {
                Text("Redmi'ye bağlanınca gönderilebilir")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(alignment: .bottom, spacing: 8) {
                field
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .stroke(Color(UIColor.separator)))
                Button(action: send) {
                    Image(systemName: "arrow.up.circle.fill").font(.system(size: 32))
                }
                .disabled(!canSend)
                .accessibilityLabel("Gönder")
            }
            // Shown as soon as the text costs more than one SMS or uses Turkish letters, which cut
            // a part from 160 characters to 70.
            if units > Self.maxLength {
                Text("Çok uzun: \(units)/\(Self.maxLength)")
                    .font(.caption2.monospacedDigit())
                    .foregroundColor(.red)
            } else if length.parts > 1 || length.isUnicode {
                Text("\(length.parts) SMS · \(length.units)/\(length.capacity)")
                    .font(.caption2.monospacedDigit())
                    .foregroundColor(.secondary)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color(UIColor.systemBackground))
        .onAppear {
            if draft.isEmpty, let saved = ComposerDrafts.texts[key] { draft = saved }
        }
        .onDisappear {
            ComposerDrafts.texts[key] = draft.isEmpty ? nil : draft
        }
    }

    @ViewBuilder
    private var field: some View {
        if #available(iOS 16.0, *) {
            // Grows to six lines like Messages; Return adds a line break.
            TextField("Mesaj", text: $draft, axis: .vertical)
                .lineLimit(1...6)
        } else {
            TextField("Mesaj", text: $draft)
        }
    }

    private var canSend: Bool {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        return connection.isAuthenticated && !text.isEmpty && text.utf16.count <= Self.maxLength
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard canSend else {
            connection.log("SMS send refused here: link=\(connection.isAuthenticated) empty=\(text.isEmpty) tooLong=\(text.utf16.count > Self.maxLength)")
            return
        }
        connection.sendSms(recipient: address, message: text)
        draft = ""
    }
}

private struct NewMessageSheet: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var directory = PhoneContactDirectory.shared
    let onChoose: (String) -> Void
    @State private var recipient = ""
    @State private var showingPicker = false

    private var suggestions: [(name: String, number: String)] {
        let text = recipient.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.count >= 2 else { return [] }
        let digits = text.filter { $0.isNumber }
        var result: [(name: String, number: String)] = []
        for contact in directory.contacts {
            let nameMatch = contact.name.localizedCaseInsensitiveContains(text)
            for number in contact.numbers where nameMatch || (!digits.isEmpty && number.filter({ $0.isNumber }).contains(digits)) {
                result.append((name: contact.name, number: number))
            }
            if result.count >= 20 { break }
        }
        return result
    }

    var body: some View {
        NavigationView {
            List {
                Section {
                    HStack {
                        TextField("Ad veya numara", text: $recipient)
                            .keyboardType(.default)
                            .disableAutocorrection(true)
                        Button { showingPicker = true } label: { Image(systemName: "person.crop.circle.badge.plus") }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("Kişilerden seç")
                    }
                }
                if !suggestions.isEmpty {
                    Section {
                        ForEach(suggestions, id: \.number) { suggestion in
                            Button { onChoose(suggestion.number) } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(suggestion.name).foregroundColor(.primary)
                                    Text(PhoneIdentity.formatted(suggestion.number)).font(.footnote).foregroundColor(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Yeni mesaj")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { Button("Vazgeç") { dismiss() } }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("İleri") { onChoose(recipient.trimmingCharacters(in: .whitespacesAndNewlines)) }
                        .disabled(recipient.filter { $0.isNumber }.count < 3)
                }
            }
            .sheet(isPresented: $showingPicker) {
                PhoneContactPicker { number in
                    showingPicker = false
                    onChoose(number)
                }
            }
        }
        .onAppear { directory.ensureLoaded() }
    }
}
