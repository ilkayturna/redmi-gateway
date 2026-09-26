import SwiftUI

private struct MessageThread: Identifiable {
    let id: String
    let number: String
    let latest: SmsMessageItem
    let count: Int
}

struct MessagesView: View {
    @ObservedObject private var connection = GatewayConnection.shared
    @ObservedObject private var directory = PhoneContactDirectory.shared
    @Binding var prefilledRecipient: String
    @State private var showingCompose = false
    @State private var showingContactPicker = false
    @State private var recipient = ""
    @State private var draft = ""

    private var threads: [MessageThread] {
        Dictionary(grouping: connection.smsMessages) {
            PhoneIdentity.key($0.senderOrRecipient)
        }
        .compactMap { key, items in
            guard let latest = items.max(by: { $0.timestamp < $1.timestamp }) else { return nil }
            return MessageThread(
                id: key,
                number: latest.senderOrRecipient,
                latest: latest,
                count: items.count
            )
        }
        .sorted { $0.latest.timestamp > $1.latest.timestamp }
    }

    var body: some View {
        Group {
            if threads.isEmpty {
                VStack(spacing: 16) {
                    Image(systemName: "message")
                        .font(.system(size: 48))
                        .foregroundColor(.secondary)
                    Text("Henüz konuşma yok")
                        .font(.headline)
                    Text("Redmi hattına gelen SMS’ler kişi bazında burada görünür.")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                    Button("Yeni Mesaj") { beginCompose() }
                        .buttonStyle(.borderedProminent)
                }
                .padding(24)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(threads) { thread in
                        NavigationLink {
                            ConversationView(number: thread.number, key: thread.id)
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "person.crop.circle.fill")
                                    .font(.title2)
                                    .foregroundColor(.blue)
                                    .frame(width: 40)
                                VStack(alignment: .leading, spacing: 5) {
                                    HStack {
                                        Text(directory.displayName(for: thread.number))
                                            .font(.body.weight(.semibold))
                                            .lineLimit(1)
                                        Spacer()
                                        Text(thread.latest.timestamp, style: .date)
                                            .font(.caption2)
                                            .foregroundColor(.secondary)
                                    }
                                    Text(thread.latest.body)
                                        .font(.subheadline)
                                        .foregroundColor(.secondary)
                                        .lineLimit(1)
                                }
                            }
                            .padding(.vertical, 5)
                        }
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle("Mesajlar")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: beginCompose) {
                    Image(systemName: "square.and.pencil")
                }
                .accessibilityLabel("Yeni mesaj")
            }
        }
        .sheet(isPresented: $showingCompose) {
            composeSheet
        }
        .onChange(of: prefilledRecipient) { value in
            guard !value.isEmpty else { return }
            recipient = value
            draft = ""
            showingCompose = true
            prefilledRecipient = ""
        }
        .onAppear {
            directory.ensureLoaded()
            if !prefilledRecipient.isEmpty {
                recipient = prefilledRecipient
                draft = ""
                showingCompose = true
                prefilledRecipient = ""
            }
        }
    }

    private func beginCompose() {
        recipient = ""
        draft = ""
        showingCompose = true
    }

    private var composeSheet: some View {
        NavigationView {
            Form {
                Section(header: Text("Alıcı")) {
                    HStack {
                        TextField("Ad veya telefon numarası", text: $recipient)
                            .keyboardType(.phonePad)
                        Button {
                            showingContactPicker = true
                        } label: {
                            Image(systemName: "person.crop.circle")
                        }
                        .accessibilityLabel("Kişi seç")
                    }
                }
                Section(header: Text("Mesaj")) {
                    TextEditor(text: $draft)
                        .frame(minHeight: 120)
                }
                if connection.state != .authenticated {
                    Section {
                        Label("SMS göndermek için Redmi’ye bağlan.", systemImage: "wifi.slash")
                            .foregroundColor(.secondary)
                    }
                }
            }
            .navigationTitle("Yeni Mesaj")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Vazgeç") { showingCompose = false }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Gönder") {
                        connection.sendSms(
                            recipient: recipient.trimmingCharacters(in: .whitespacesAndNewlines),
                            message: draft.trimmingCharacters(in: .whitespacesAndNewlines)
                        )
                        showingCompose = false
                    }
                    .disabled(!canSend)
                }
            }
            .sheet(isPresented: $showingContactPicker) {
                PhoneContactPicker { number in
                    recipient = number
                    showingContactPicker = false
                }
            }
        }
    }

    private var canSend: Bool {
        connection.state == .authenticated
            && !PhoneIdentity.key(recipient).isEmpty
            && !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

private struct ConversationView: View {
    @ObservedObject private var connection = GatewayConnection.shared
    @ObservedObject private var directory = PhoneContactDirectory.shared
    let number: String
    let key: String
    @State private var draft = ""

    private var messages: [SmsMessageItem] {
        connection.smsMessages
            .filter { PhoneIdentity.key($0.senderOrRecipient) == key }
            .sorted { $0.timestamp < $1.timestamp }
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(messages) { message in
                            HStack {
                                if !message.isIncoming { Spacer(minLength: 44) }
                                VStack(alignment: message.isIncoming ? .leading : .trailing, spacing: 4) {
                                    Text(message.body)
                                        .font(.body)
                                        .foregroundColor(message.isIncoming ? .primary : .white)
                                        .padding(.horizontal, 14)
                                        .padding(.vertical, 10)
                                        .background(message.isIncoming
                                            ? Color(UIColor.secondarySystemBackground) : Color.blue)
                                        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                                    Text(message.timestamp, style: .time)
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }
                                .id(message.id)
                                if message.isIncoming { Spacer(minLength: 44) }
                            }
                        }
                    }
                    .padding(16)
                }
                .onAppear {
                    if let last = messages.last { proxy.scrollTo(last.id, anchor: .bottom) }
                }
                .onChange(of: messages.count) { _ in
                    if let last = messages.last { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }

            Divider()
            HStack(spacing: 12) {
                TextField("SMS yaz", text: $draft)
                    .textFieldStyle(.roundedBorder)
                Button {
                    let body = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                    connection.sendSms(recipient: number, message: body)
                    draft = ""
                } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.title)
                }
                .disabled(connection.state != .authenticated
                    || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityLabel("SMS gönder")
            }
            .padding(12)
            .background(Color(UIColor.systemBackground))
        }
        .background(Color(UIColor.systemGroupedBackground))
        .navigationTitle(directory.displayName(for: number))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    CallKitManager.shared.startOutgoingCall(phoneNumber: number)
                } label: {
                    Image(systemName: "phone")
                }
                .accessibilityLabel("Bu kişiyi ara")
            }
        }
    }
}
