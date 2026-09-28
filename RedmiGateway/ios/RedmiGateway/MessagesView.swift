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
    @State private var openAddress: String?

    private var threads: [MessageThread] {
        Dictionary(grouping: history.messages) { PhoneIdentity.key($0.address) }
            .compactMap { key, items -> MessageThread? in
                guard let last = items.max(by: { $0.date < $1.date }) else { return nil }
                return MessageThread(id: key, address: last.address, last: last,
                                     unread: items.filter { history.isUnread($0) }.count)
            }
            .filter { thread in
                let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else { return true }
                return directory.displayName(for: thread.address).localizedCaseInsensitiveContains(text)
                    || thread.address.contains(text)
                    || history.messages.contains { PhoneIdentity.key($0.address) == thread.id && $0.body.localizedCaseInsensitiveContains(text) }
            }
            .sorted { $0.last.date > $1.last.date }
    }

    var body: some View {
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
                    NavigationLink(tag: thread.address, selection: $openAddress) {
                        ConversationView(address: thread.address)
                    } label: {
                        ThreadRow(thread: thread)
                    }
                }
                .listStyle(.plain)
                .searchable(text: $query, prompt: "Ara")
                .refreshable { GatewayConnection.shared.requestSync() }
            }
            // Opens a conversation for which no thread row exists yet.
            if let address = openAddress, !threads.contains(where: { $0.address == address }) {
                NavigationLink(isActive: Binding(get: { openAddress != nil }, set: { if !$0 { openAddress = nil } })) {
                    ConversationView(address: address)
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
        openAddress = threads.first(where: { $0.id == key })?.address ?? address
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
    @ObservedObject private var connection = GatewayConnection.shared
    let address: String
    @State private var draft = ""

    private static let maxLength = 1600

    private var key: String { PhoneIdentity.key(address) }
    /// Operator and service senders ("TURKCELL") have no number to answer.
    private var canReply: Bool { address.filter { $0.isNumber }.count >= 3 }

    private var messages: [SmsRecord] {
        history.messages.filter { PhoneIdentity.key($0.address) == key }
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(Array(messages.enumerated()), id: \.element.id) { index, message in
                            if index == 0 || !Calendar.current.isDate(messages[index - 1].date, inSameDayAs: message.date) {
                                Text(message.date.formatted(date: .abbreviated, time: .omitted))
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                    .padding(.top, 10)
                            }
                            MessageBubble(message: message, retry: { connection.retrySms(message) })
                                .id(message.id)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                }
                .onAppear { scrollToEnd(proxy) }
                .onChange(of: messages.count) { _ in
                    withAnimation { scrollToEnd(proxy) }
                    history.markThreadSeen(key)
                }
            }
            Divider()
            if canReply {
                composer
            } else {
                Text("Bu gönderene yanıt verilemez.")
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(12)
            }
        }
        .navigationTitle(directory.displayName(for: address))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { CallKitManager.shared.startOutgoingCall(phoneNumber: address) } label: {
                    Image(systemName: "phone")
                }
                .accessibilityLabel("Ara")
            }
        }
        .onAppear {
            history.markThreadSeen(key)
            history.openThreadKey = key
            Notifier.shared.clearConversation(address)
        }
        .onDisappear {
            if history.openThreadKey == key { history.openThreadKey = nil }
            Notifier.shared.updateBadge()
        }
    }

    private var composer: some View {
        VStack(alignment: .trailing, spacing: 4) {
            if !connection.isAuthenticated {
                Text("Redmi'ye bağlanınca gönderilebilir")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(alignment: .bottom, spacing: 8) {
                TextField("Mesaj", text: $draft)
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
            if draft.count > 140 {
                Text("\(draft.count)/\(Self.maxLength) · \(segments) SMS")
                    .font(.caption2.monospacedDigit())
                    .foregroundColor(draft.count > Self.maxLength ? .red : .secondary)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color(UIColor.systemBackground))
    }

    /// GSM-7 fits 160 characters per part (153 when split); Turkish letters use UCS-2 (70/67).
    private var segments: Int {
        let gsm = draft.unicodeScalars.allSatisfy { $0.isASCII }
        let single = gsm ? 160 : 70, part = gsm ? 153 : 67
        return draft.count <= single ? 1 : Int(ceil(Double(draft.count) / Double(part)))
    }

    private var canSend: Bool {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        return connection.isAuthenticated && !text.isEmpty && text.count <= Self.maxLength
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard canSend else { return }
        connection.sendSms(recipient: address, message: text)
        draft = ""
    }

    private func scrollToEnd(_ proxy: ScrollViewProxy) {
        if let last = messages.last { proxy.scrollTo(last.id, anchor: .bottom) }
    }
}

private struct MessageBubble: View {
    let message: SmsRecord
    let retry: () -> Void

    var body: some View {
        HStack {
            if !message.isIncoming { Spacer(minLength: 48) }
            VStack(alignment: message.isIncoming ? .leading : .trailing, spacing: 3) {
                Text(message.body)
                    .foregroundColor(message.isIncoming ? .primary : .white)
                    .padding(.horizontal, 13)
                    .padding(.vertical, 8)
                    .background(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .fill(bubbleColor)
                    )
                    .textSelection(.enabled)
                footer
            }
            if message.isIncoming { Spacer(minLength: 48) }
        }
    }

    private var bubbleColor: Color {
        if message.isIncoming { return Color(UIColor.secondarySystemFill) }
        return message.status == .failed ? Color.red.opacity(0.75) : Color.accentColor
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
            Text(message.date.formatted(date: .omitted, time: .shortened))
                .font(.caption2)
                .foregroundColor(.secondary)
        }
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
