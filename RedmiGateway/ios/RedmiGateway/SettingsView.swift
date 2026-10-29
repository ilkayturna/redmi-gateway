import AVFoundation
import SwiftUI
import UserNotifications

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
                        Text("Redmi'de Telefon Geçidi › Ayarlar (sağ üstteki dişli) ekranındaki 6 karakterlik kodu gir. Bu yalnızca bir kez gerekir.")
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
    @ObservedObject private var wake = CallWakeMonitor.shared
    @ObservedObject private var ble = BLECentralManager.shared
    @ObservedObject private var notifier = Notifier.shared
    @State private var confirmingUnpair = false

    var body: some View {
        NavigationView {
            List {
                Section(header: Text("Redmi")) {
                    LabeledRow(title: "Durum", value: linkText,
                               valueColor: (connection.link == .ready || restsOnBluetooth) ? .green : .secondary)
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
                RedmiStatusSection()
                Section(header: Text("Aramalar"),
                        footer: Text("Konum iznini \"Her Zaman\" yaparsan, uygulamayı kaydırıp kapatsan bile gelen aramalar iPhone'da çalar. Yalnızca Redmi'nin yakınlığı algılanır; konumun okunmaz ve kaydedilmez.")) {
                    LabeledRow(title: "Uygulama kapalıyken aramalar", value: wakeText,
                               valueColor: (wake.isEnabled && !wake.needsPreciseLocation) ? .green : .orange)
                    if !wake.isEnabled {
                        Button("Konum iznini \"Her Zaman\" yap") { wake.requestPermission() }
                    } else if wake.needsPreciseLocation {
                        Text("\"Kesin Konum\" kapalı. iPhone, Redmi'nin yakınlığını yalnızca Kesin Konum açıkken algılar; uygulama kapalıyken aramalar çalmayabilir.")
                            .font(.footnote)
                            .foregroundColor(.orange)
                        Button("Kesin Konum'u aç") { wake.openSettings() }
                    }
                }
                Section(header: Text("İzinler"), footer: Text("Mikrofon, görüşme iPhone'dayken konuşmanı Redmi'ye iletmek için; Kişiler, numaraları adlarla göstermek için; Bildirimler, yeni SMS'leri ve cevapsız aramaları haber vermek için kullanılır. Kişilerin iPhone'dan çıkmaz.")) {
                    LabeledRow(title: "Mikrofon", value: microphoneText,
                               valueColor: AudioEngine.shared.microphoneAllowed ? .secondary : .red)
                    LabeledRow(title: "Kişiler", value: directory.canRead ? "İzin var" : "İzin yok",
                               valueColor: directory.canRead ? .secondary : .orange)
                    LabeledRow(title: "Bildirimler", value: notificationText,
                               valueColor: notifier.authorization == .denied ? .red : .secondary)
                    if notifier.authorization == .denied {
                        Text("Bildirimler kapalı. Yeni SMS'ler ve cevapsız aramalar iPhone'da haber verilmez.")
                            .font(.footnote)
                            .foregroundColor(.red)
                    }
                    if !AudioEngine.shared.microphoneAllowed || !directory.canRead || notifier.authorization == .denied {
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

    /// Read through `ble` so the row follows the Bluetooth link; see GatewayConnection.restsOnBluetooth.
    private var restsOnBluetooth: Bool {
        ble.isConnected && connection.restsOnBluetooth
    }

    private var wakeText: String {
        if !wake.isEnabled { return "Kapalı" }
        return wake.needsPreciseLocation ? "Kesin Konum kapalı" : "Açık"
    }

    private var notificationText: String {
        switch notifier.authorization {
        case .denied: return "İzin yok"
        case .notDetermined: return "Sorulmadı"
        default: return "İzin var"
        }
    }

    private var linkText: String {
        switch connection.link {
        case .ready: return "Bağlı"
        case .pairing: return "Eşleştiriliyor"
        case .searching: return restsOnBluetooth ? "Bağlı · Bluetooth" : "Aranıyor"
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

/// "Redmi durumu": what the Redmi last reported about itself, and how long this TestFlight build
/// still opens. The Redmi stays in a pocket and nobody reads its screen, so its problems, battery,
/// ROM and updater are read here; RedmiHealthBanner points here when something needs action.
struct RedmiStatusSection: View {
    @ObservedObject private var connection = GatewayConnection.shared

    var body: some View {
        Section(header: Text("Redmi durumu"),
                footer: Text("TestFlight sürümü yüklendikten 90 gün sonra açılmaz; süre bitmeden yeni sürüm yüklenmeli.")) {
            if let health = connection.redmiHealth {
                rows(for: health)
            } else {
                Text("Redmi henüz durum bildirmedi. Redmi uygulaması güncellenince sorunlar, pil ve ROM burada görünür.")
                    .font(.footnote)
                    .foregroundColor(.secondary)
            }
            LabeledRow(title: "iPhone uygulamasının süresi", value: testFlightText, valueColor: testFlightColor)
        }
    }

    @ViewBuilder
    private func rows(for health: RedmiHealth) -> some View {
        if health.problems.isEmpty {
            LabeledRow(title: "Sorun", value: "Yok", valueColor: .green)
        } else {
            ForEach(Array(health.problems.enumerated()), id: \.offset) { _, problem in
                RedmiProblemRow(problem: problem)
            }
        }
        LabeledRow(title: "Pil", value: batteryText(health),
                   valueColor: health.batteryNeedsCharging ? .orange : .secondary)
        LabeledRow(title: "ROM", value: health.romDisplay ?? "Bilinmiyor")
        if let changed = health.romChangedAt {
            LabeledRow(title: "ROM değişimi", value: changed.formatted(date: .abbreviated, time: .shortened))
        }
        LabeledRow(title: "Sistem güncelleyici", value: updaterText(health),
                   valueColor: health.updater?.enabled == true ? .orange : .secondary)
        if health.updater?.enabled == true {
            Text("Otomatik ROM güncellemesi Redmi'deki ses düzeltmesini siler. Güncelleyiciyi kapalı tut.")
                .font(.footnote)
                .foregroundColor(.orange)
        }
        if let adbWifi = health.adbWifi {
            LabeledRow(title: "Kablosuz hata ayıklama", value: adbWifi ? "Açık" : "Kapalı")
        }
        if let evaluated = health.evaluatedAt {
            LabeledRow(title: "Son bilgi", value: evaluated.formatted(date: .omitted, time: .shortened))
        }
    }

    private func batteryText(_ health: RedmiHealth) -> String {
        guard let level = health.battery?.level else { return "Bilinmiyor" }
        return health.battery?.charging == true ? "%\(level) · şarj oluyor" : "%\(level)"
    }

    private func updaterText(_ health: RedmiHealth) -> String {
        guard let updater = health.updater else { return "Bilinmiyor" }
        if updater.present == false { return "Yok" }
        switch updater.enabled {
        case .some(true): return "Açık"
        case .some(false): return "Kapalı"
        case .none: return "Bilinmiyor"
        }
    }

    private var testFlightText: String {
        guard let days = GatewayConnection.testFlightDaysLeft else { return "Bilinmiyor" }
        return days > 0 ? "\(days) gün" : "Süresi doldu"
    }

    private var testFlightColor: Color {
        guard let days = GatewayConnection.testFlightDaysLeft else { return .secondary }
        if days <= 7 { return .red }
        if days <= 21 { return .orange }
        return .secondary
    }
}

/// One problem the Redmi reported: what it is, what it breaks and how to fix it.
private struct RedmiProblemRow: View {
    let problem: RedmiHealth.Problem

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(problem.title ?? problem.code ?? "Sorun",
                  systemImage: problem.audioCritical ? "exclamationmark.triangle.fill" : "exclamationmark.circle")
                .foregroundColor(problem.audioCritical ? .red : .orange)
            if let detail = problem.detail, !detail.isEmpty {
                Text(detail).font(.footnote)
            }
            if let fix = problem.fix, !fix.isEmpty {
                Text(fix).font(.footnote).foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
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
        if let health = connection.redmiHealth { lines.append("redmiHealth " + health.summary) }
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
