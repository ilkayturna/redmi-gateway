import SwiftUI

struct MessagesView: View {
    @ObservedObject var connection = GatewayConnection.shared
    @State private var recipient: String = ""
    @State private var messageText: String = ""
    
    var body: some View {
        VStack(spacing: 0) {
            // Header / Recipient Bar
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("To:")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(.secondary)
                    TextField("+90 5xx xxx xx xx", text: $recipient)
                        .keyboardType(.phonePad)
                        .textFieldStyle(.roundedBorder)
                }
            }
            .padding()
            .background(Color(UIColor.secondarySystemBackground))
            
            // Messages List
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 12) {
                        if connection.smsMessages.isEmpty {
                            VStack(spacing: 8) {
                                Image(systemName: "message.badge")
                                    .font(.system(size: 44))
                                    .foregroundColor(.secondary)
                                Text("No SMS Messages")
                                    .font(.system(size: 16, weight: .medium))
                                    .foregroundColor(.secondary)
                                Text("Incoming and outgoing SMS via Turkcell cellular SIM will appear here.")
                                    .font(.system(size: 13))
                                    .foregroundColor(.secondary)
                                    .multilineTextAlignment(.center)
                                    .padding(.horizontal, 32)
                            }
                            .padding(.top, 60)
                        } else {
                            ForEach(connection.smsMessages) { msg in
                                HStack {
                                    if !msg.isIncoming { Spacer() }
                                    
                                    VStack(alignment: msg.isIncoming ? .leading : .trailing, spacing: 4) {
                                        Text(msg.senderOrRecipient)
                                            .font(.system(size: 11, weight: .bold))
                                            .foregroundColor(msg.isIncoming ? .blue : .secondary)
                                        
                                        Text(msg.body)
                                            .font(.system(size: 15))
                                            .foregroundColor(msg.isIncoming ? .primary : .white)
                                            .padding(.horizontal, 14)
                                            .padding(.vertical, 10)
                                            .background(msg.isIncoming ? Color(UIColor.systemGray5) : Color.blue)
                                            .cornerRadius(18)
                                        
                                        Text(formatDate(msg.timestamp))
                                            .font(.system(size: 10))
                                            .foregroundColor(.secondary)
                                    }
                                    .id(msg.id)
                                    
                                    if msg.isIncoming { Spacer() }
                                }
                                .padding(.horizontal)
                            }
                        }
                    }
                    .padding(.vertical)
                }
                .onChange(of: connection.smsMessages.count) { _ in
                    if let last = connection.smsMessages.last {
                        withAnimation {
                            proxy.scrollTo(last.id, anchor: .bottom)
                        }
                    }
                }
            }
            
            Divider()
            
            // Compose Bar
            HStack(spacing: 10) {
                TextField("iMessage / SMS...", text: $messageText)
                    .textFieldStyle(.roundedBorder)
                
                Button(action: sendCurrentMessage) {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 32))
                        .foregroundColor(canSend ? .blue : .secondary)
                }
                .disabled(!canSend)
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
            .background(Color(UIColor.systemBackground))
        }
        .navigationTitle("Cellular SMS")
        .navigationBarTitleDisplayMode(.inline)
    }
    
    private var canSend: Bool {
        !recipient.trimmingCharacters(in: .whitespaces).isEmpty &&
        !messageText.trimmingCharacters(in: .whitespaces).isEmpty &&
        connection.state == .authenticated
    }
    
    private func sendCurrentMessage() {
        let cleanRecipient = recipient.trimmingCharacters(in: .whitespaces)
        let cleanText = messageText.trimmingCharacters(in: .whitespaces)
        guard !cleanRecipient.isEmpty, !cleanText.isEmpty else { return }
        
        connection.sendSms(recipient: cleanRecipient, message: cleanText)
        messageText = ""
    }
    
    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }
}
