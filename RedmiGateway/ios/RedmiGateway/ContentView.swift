import SwiftUI

struct ContentView: View {
    @StateObject private var connection = GatewayConnection.shared
    @AppStorage("gateway_ip") private var gatewayIp = ""
    @AppStorage("gateway_port") private var gatewayPort = "8787"
    @AppStorage("pairing_token") private var pairingToken = ""
    @State private var showingSettings = false
    @State private var showingContactPicker = false
    @State private var outgoingNumber = ""

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    connectionCard
                    callCard
                    NavigationLink(destination: MessagesView()) {
                        HStack(spacing: 12) {
                            Image(systemName: "message")
                                .font(.title3)
                            VStack(alignment: .leading, spacing: 4) {
                                Text("SMS")
                                    .font(.headline)
                                Text(connection.smsMessages.isEmpty
                                     ? "Mesajlar burada görünecek"
                                     : "\(connection.smsMessages.count) mesaj")
                                    .font(.subheadline)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.footnote.weight(.semibold))
                                .foregroundColor(.secondary)
                        }
                        .foregroundColor(.primary)
                        .padding(16)
                        .background(Color(UIColor.secondarySystemGroupedBackground))
                        .cornerRadius(16)
                    }
                    Text("Arama sesi şu anda Redmi'den gelir. iPhone bildirimleri uygulama açıkken çalışır.")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                        .padding(.horizontal, 4)
                }
                .padding(16)
            }
            .background(Color(UIColor.systemGroupedBackground))
            .navigationTitle("Telefon Geçidi")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showingSettings = true
                    } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel("Bağlantı ayarları")
                }
            }
            .sheet(isPresented: $showingSettings) {
                settingsSheet
            }
            .sheet(isPresented: $showingContactPicker) {
                PhoneContactPicker { number in
                    outgoingNumber = number
                    showingContactPicker = false
                }
            }
            .onAppear {
                // Register the CallKit observer before connecting and receiving call events.
                _ = CallKitManager.shared
                connection.connectSavedConfiguration()
                if pairingToken.isEmpty { showingSettings = true }
            }
        }
    }

    private var connectionCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Bağlantı")
                .font(.headline)
            HStack(spacing: 8) {
                Image(systemName: connection.state == .authenticated ? "checkmark.circle.fill" : "circle.dotted")
                    .foregroundColor(connection.state == .authenticated ? .green : .secondary)
                Text(connectionStatus)
                    .font(.body.weight(.medium))
                Spacer()
            }
            Text(gatewayIp.isEmpty ? "Redmi otomatik aranıyor" : "Redmi Note 8 · \(gatewayIp)")
                .font(.subheadline)
                .foregroundColor(.secondary)
            if connection.state == .failed || connection.state == .disconnected {
                Button("Yeniden bağlan") { connectGateway() }
                    .disabled(pairingToken.isEmpty)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .cornerRadius(16)
    }

    private var callCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Arama")
                .font(.headline)
            if let call = connection.currentCall {
                Text(call.number.isEmpty ? "Gizli numara" : call.number)
                    .font(.title3.weight(.semibold))
                    .textSelection(.enabled)
                Text(callDescription(call.state))
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                if call.state == .ringing {
                    HStack(spacing: 12) {
                        Button("Yanıtla") { connection.sendAnswer(callId: call.rawCallId) }
                            .buttonStyle(.borderedProminent)
                        Button("Reddet") { connection.sendReject(callId: call.rawCallId) }
                            .buttonStyle(.bordered)
                    }
                } else {
                    Button("Sonlandır") { connection.sendHangup(callId: call.rawCallId) }
                        .buttonStyle(.bordered)
                }
            } else {
                Text("Arama yok")
                    .font(.body)
                    .foregroundColor(.secondary)
            }
            HStack(spacing: 8) {
                TextField("Telefon numarası", text: $outgoingNumber)
                    .keyboardType(.phonePad)
                    .textFieldStyle(.roundedBorder)
                Button {
                    showingContactPicker = true
                } label: {
                    Image(systemName: "person.crop.circle")
                }
                .accessibilityLabel("Rehberden numara seç")
                Button("Ara") {
                    let number = outgoingNumber.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !number.isEmpty else { return }
                    CallKitManager.shared.startOutgoingCall(phoneNumber: number)
                    outgoingNumber = ""
                }
                .buttonStyle(.borderedProminent)
                .disabled(connection.state != .authenticated || outgoingNumber.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .cornerRadius(16)
    }

    private var settingsSheet: some View {
        NavigationView {
            Form {
                Section(header: Text("Redmi bağlantısı"),
                        footer: Text("Redmi adresi otomatik bulunur. Eşleştirme kodunu yalnızca ilk kurulumda girin.")) {
                    TextField("Redmi adresi (isteğe bağlı)", text: $gatewayIp)
                        .keyboardType(.numbersAndPunctuation)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                    TextField("Eşleştirme kodu", text: $pairingToken)
                        .autocapitalization(.allCharacters)
                        .disableAutocorrection(true)
                }
                Section {
                    Button("Kaydet ve bağlan") {
                        connectGateway()
                        showingSettings = false
                    }
                    .disabled(pairingToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .navigationTitle("Bağlantı ayarları")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Bitti") { showingSettings = false }
                }
            }
        }
    }

    private var connectionStatus: String {
        switch connection.state {
        case .authenticated: return "Redmi'ye bağlı"
        case .connecting, .connected: return "Bağlanıyor"
        case .disconnected: return "Bağlı değil"
        case .failed: return "Bağlantı kurulamadı"
        }
    }

    private func callDescription(_ state: GSMCallState) -> String {
        switch state {
        case .ringing: return "Gelen arama"
        case .active: return "Görüşme sürüyor"
        case .dialing, .connecting: return "Bağlanıyor"
        case .holding: return "Beklemede"
        case .idle, .disconnected: return "Arama yok"
        }
    }

    private func connectGateway() {
        connection.connect(
            host: gatewayIp.trimmingCharacters(in: .whitespacesAndNewlines),
            port: Int(gatewayPort) ?? 8787,
            token: pairingToken.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        )
    }
}
