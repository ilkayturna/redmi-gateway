import AVFoundation
import SwiftUI

/// First run (or after the pairing was removed): pair once with the code shown on the Redmi.
struct PairingView: View {
    @ObservedObject private var connection = GatewayConnection.shared
    @State private var code = ""
    @State private var showingAdvanced = false
    @FocusState private var codeFocused: Bool

    private var isWorking: Bool { connection.link == .pairing }

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 24) {
                    Image(systemName: "iphone.and.arrow.forward")
                        .font(.system(size: 56, weight: .light))
                        .foregroundColor(.accentColor)
                        .padding(.top, 32)
                        .accessibilityHidden(true)
                    VStack(spacing: 8) {
                        Text("Redmi ile eşleştir")
                            .font(.title.weight(.semibold))
                        Text("Redmi'de Telefon Geçidi › Ayarlar › Gelişmiş ve tanılama ekranındaki 6 karakterlik kodu gir. Bu yalnızca bir kez gerekir.")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    TextField("Kod", text: $code)
                        .font(.system(size: 32, weight: .semibold, design: .monospaced))
                        .multilineTextAlignment(.center)
                        .textInputAutocapitalization(.characters)
                        .disableAutocorrection(true)
                        .keyboardType(.asciiCapable)
                        .focused($codeFocused)
                        .padding(.vertical, 12)
                        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color(UIColor.secondarySystemFill)))
                        .onChange(of: code) { value in
                            let clean = String(value.uppercased().filter { $0.isLetter || $0.isNumber }.prefix(6))
                            if clean != value { code = clean }
                        }
                        .onSubmit(pair)
                        .accessibilityLabel("Eşleştirme kodu")
                    if let error = connection.pairingError {
                        Text(error)
                            .font(.footnote)
                            .foregroundColor(.red)
                            .multilineTextAlignment(.center)
                    }
                    Button(action: pair) {
                        HStack(spacing: 8) {
                            if isWorking { ProgressView() }
                            Text(isWorking ? statusText : "Eşleştir")
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(code.count != 6 || isWorking)
                    Text("iPhone ve Redmi aynı Wi-Fi ağında olmalı; Bluetooth açık olursa Redmi daha hızlı bulunur.")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(24)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Gelişmiş") { showingAdvanced = true }
                }
            }
            .sheet(isPresented: $showingAdvanced) {
                NavigationView { DiagnosticsView() }
            }
        }
        .navigationViewStyle(.stack)
        .onAppear { connection.start() }
    }

    private var statusText: String {
        connection.state == .connected ? "Eşleştiriliyor…" : "Redmi aranıyor…"
    }

    private func pair() {
        guard code.count == 6 else { return }
        codeFocused = false
        connection.pair(code: code)
    }
}

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var connection = GatewayConnection.shared
    @ObservedObject private var directory = PhoneContactDirectory.shared
    @State private var confirmingUnpair = false

    var body: some View {
        NavigationView {
            List {
                Section(header: Text("Redmi")) {
                    LabeledRow(title: "Durum", value: linkText, valueColor: connection.link == .ready ? .green : .secondary)
                    if let device = connection.gatewayDevice { LabeledRow(title: "Cihaz", value: device) }
                    if let version = connection.gatewayAppVersion { LabeledRow(title: "Redmi uygulaması", value: version) }
                    if connection.compatibility == .degraded {
                        Text("Redmi'deki uygulama daha yeni. iPhone uygulamasını güncellemen önerilir.")
                            .font(.footnote)
                            .foregroundColor(.orange)
                    }
                    if let pairing = connection.pairing {
                        LabeledRow(title: "Eşleşme", value: pairing.pairedAt.formatted(date: .abbreviated, time: .omitted))
                    }
                }
                Section(header: Text("İzinler"), footer: Text("Mikrofon, görüşme iPhone'dayken konuşmanı Redmi'ye iletmek için; Kişiler, numaraları adlarla göstermek için kullanılır. Kişilerin iPhone'dan çıkmaz.")) {
                    LabeledRow(title: "Mikrofon", value: microphoneText,
                               valueColor: AudioEngine.shared.microphoneAllowed ? .secondary : .red)
                    LabeledRow(title: "Kişiler", value: directory.canRead ? "İzin var" : "İzin yok",
                               valueColor: directory.canRead ? .secondary : .orange)
                    if !AudioEngine.shared.microphoneAllowed || !directory.canRead {
                        Button("iPhone Ayarları'nı aç") {
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                        }
                    }
                }
                Section {
                    NavigationLink { DiagnosticsView() } label: {
                        Label("Gelişmiş ve tanılama", systemImage: "wrench.and.screwdriver")
                    }
                }
                Section(footer: Text("Eşleşme kaldırılınca bu iPhone Redmi'ye bağlanmaz ve Redmi'den gelen geçmiş iPhone'dan silinir. Yeniden eşleştirmek için Redmi'deki yeni kod gerekir.")) {
                    Button("Eşleşmeyi kaldır", role: .destructive) { confirmingUnpair = true }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Ayarlar")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) { Button("Bitti") { dismiss() } }
            }
            .confirmationDialog("Eşleşme kaldırılsın mı?", isPresented: $confirmingUnpair, titleVisibility: .visible) {
                Button("Eşleşmeyi kaldır", role: .destructive) {
                    connection.unpair()
                    dismiss()
                }
                Button("Vazgeç", role: .cancel) {}
            }
        }
        .navigationViewStyle(.stack)
    }

    private var linkText: String {
        switch connection.link {
        case .ready: return "Bağlı"
        case .pairing: return "Eşleştiriliyor"
        case .searching: return "Aranıyor"
        case .connecting: return "Bağlanıyor"
        case .unpaired: return "Eşleşmedi"
        case .offline(let reason): return reason
        case .incompatible: return "Uyumsuz sürüm"
        }
    }

    private var microphoneText: String {
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted: return "İzin var"
        case .denied: return "İzin yok"
        default: return "Sorulmadı"
        }
    }
}

/// Advanced and Diagnostics: technical detail and the manual address. Never needed day to day.
struct DiagnosticsView: View {
    @ObservedObject private var connection = GatewayConnection.shared
    @ObservedObject private var ble = BLECentralManager.shared
    @State private var manualHost = ""
    @State private var manualPort = "8787"
    @State private var copied = false

    var body: some View {
        List {
            Section(header: Text("Bağlantı")) {
                LabeledRow(title: "Durum", value: connection.state.rawValue)
                LabeledRow(title: "Adres", value: connection.connectedHost.isEmpty ? "—" : connection.connectedHost)
                LabeledRow(title: "Protokol", value: protocolText)
                LabeledRow(title: "Wi-Fi", value: connection.isWiFiConnected ? "Açık" : "Kapalı")
                LabeledRow(title: "Bluetooth", value: ble.isBluetoothPoweredOn ? (ble.isConnected ? "Redmi'ye bağlı" : "Açık") : "Kapalı")
                if let seen = ble.lastSeenDate {
                    LabeledRow(title: "Son Bluetooth sinyali", value: seen.formatted(date: .omitted, time: .standard))
                }
                LabeledRow(title: "iPhone uygulaması", value: appVersion)
            }
            Section(header: Text("Elle adres"),
                    footer: Text("Yalnızca Redmi ağda otomatik bulunamıyorsa kullan. Boş bırakınca otomatik bulma kullanılır.")) {
                TextField("Redmi IP adresi", text: $manualHost)
                    .keyboardType(.numbersAndPunctuation)
                    .textInputAutocapitalization(.never)
                    .disableAutocorrection(true)
                TextField("Port", text: $manualPort).keyboardType(.numberPad)
                Button("Kaydet ve bağlan") {
                    connection.setManualAddress(host: manualHost, port: Int(manualPort) ?? 8787)
                }
                if connection.manualAddress != nil {
                    Button("Otomatik bulmaya dön", role: .destructive) {
                        manualHost = ""
                        connection.setManualAddress(host: "", port: 8787)
                    }
                }
            }
            Section(header: Text("Redmi raporu")) {
                Button("Redmi'den rapor al") { connection.requestDiagnostics() }
                    .disabled(!connection.isAuthenticated)
                if let report = connection.remoteDiagnostics {
                    Text(report)
                        .font(.system(size: 11, design: .monospaced))
                        .textSelection(.enabled)
                }
            }
            Section(header: Text("Uygulama günlüğü"), footer: Text("Günlük; numara, mesaj içeriği veya anahtar içermez.")) {
                Button(copied ? "Kopyalandı" : "Raporu kopyala") {
                    UIPasteboard.general.string = fullReport
                    copied = true
                }
                ForEach(Array(connection.logs.suffix(60).reversed().enumerated()), id: \.offset) { _, line in
                    Text(line).font(.system(size: 11, design: .monospaced)).foregroundColor(.secondary)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Gelişmiş ve tanılama")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if let manual = connection.manualAddress {
                manualHost = manual.host
                manualPort = String(manual.port)
            }
        }
    }

    private var protocolText: String {
        switch connection.compatibility {
        case .supported: return "2 · uyumlu"
        case .degraded: return "2 · Redmi daha yeni"
        case .incompatible: return "uyumsuz"
        }
    }

    private var appVersion: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(version) (\(build))"
    }

    private var fullReport: String {
        var lines = ["Telefon Geçidi iPhone \(appVersion)", "state=\(connection.state.rawValue) protocol=\(protocolText)",
                     "wifi=\(connection.isWiFiConnected) ble=\(ble.isConnected)"]
        if let report = connection.remoteDiagnostics { lines.append(report) }
        lines.append(contentsOf: connection.logs)
        return lines.joined(separator: "\n")
    }
}

struct LabeledRow: View {
    let title: String
    let value: String
    var valueColor: Color = .secondary

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
            Spacer(minLength: 12)
            Text(value).foregroundColor(valueColor).multilineTextAlignment(.trailing)
        }
        .accessibilityElement(children: .combine)
    }
}
