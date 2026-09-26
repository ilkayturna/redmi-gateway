import SwiftUI
import Combine

struct ContentView: View {
    @StateObject private var connection = GatewayConnection.shared
    @AppStorage("gateway_ip") private var gatewayIp = ""
    @AppStorage("gateway_port") private var gatewayPort = "8787"
    @AppStorage("pairing_token") private var pairingToken = ""

    @State private var selectedTab = 3 // Default to Klavye (Keypad)
    @State private var showingSettings = false
    @State private var showingContactPicker = false
    @State private var contactPickerAddsFavorite = false
    @State private var outgoingNumber = ""
    @State private var isMuted = false
    @State private var isSpeaker = false
    @State private var showingDtmfSheet = false

    // Recents and Favorites simple persistence
    @AppStorage("favorite_numbers") private var favoritesData: String = "[]"
    @AppStorage("recents_history") private var recentsData: String = "[]"

    var body: some View {
        ZStack(alignment: .bottom) {
            TabView(selection: $selectedTab) {
                // Tab 0: Favoriler
                NavigationView {
                    favoritesView
                        .navigationTitle("Favoriler")
                        .toolbar { settingsToolbarItem }
                }
                .tabItem {
                    Label("Favoriler", systemImage: "star.fill")
                }
                .tag(0)

                // Tab 1: Son Aramalar
                NavigationView {
                    recentsView
                        .navigationTitle("Son Aramalar")
                        .toolbar { settingsToolbarItem }
                }
                .tabItem {
                    Label("Son Aramalar", systemImage: "clock.fill")
                }
                .tag(1)

                // Tab 2: Kişiler
                NavigationView {
                    contactsView
                        .navigationTitle("Kişiler")
                        .toolbar { settingsToolbarItem }
                }
                .tabItem {
                    Label("Kişiler", systemImage: "person.crop.circle.fill")
                }
                .tag(2)

                // Tab 3: Klavye (Keypad)
                NavigationView {
                    keypadView
                        .navigationTitle("Telefon")
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar { settingsToolbarItem }
                }
                .tabItem {
                    Label("Klavye", systemImage: "circle.grid.3x3.fill")
                }
                .tag(3)

                // Tab 4: Mesajlar
                NavigationView {
                    MessagesView()
                        .toolbar { settingsToolbarItem }
                }
                .tabItem {
                    Label("Mesajlar", systemImage: "message.fill")
                }
                .tag(4)
            }

            // In-Call HUD Overlay if a call is present
            if let call = connection.currentCall {
                activeCallOverlay(call: call)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .zIndex(10)
            }
        }
        .sheet(isPresented: $showingSettings) {
            settingsSheet
        }
        .sheet(isPresented: $showingContactPicker) {
            PhoneContactPicker { number in
                if contactPickerAddsFavorite {
                    addFavorite(number: number)
                } else {
                    outgoingNumber = number
                    selectedTab = 3
                }
                showingContactPicker = false
            }
        }
        .onAppear {
            _ = CallKitManager.shared
            AudioEngine.shared.requestMicrophonePermission()
            connection.connectSavedConfiguration()
            if pairingToken.isEmpty { showingSettings = true }
        }
        .onChange(of: connection.currentCall?.state) { newState in
            updateProximitySensor()
            if let call = connection.currentCall, newState == .active || newState == .dialing {
                addRecent(number: call.number.isEmpty ? "Gizli Numara" : call.number)
            }
            if newState == nil || newState == .idle || newState == .disconnected {
                isMuted = false
                isSpeaker = false
                showingDtmfSheet = false
                UIDevice.current.isProximityMonitoringEnabled = false
            }
        }
        .onChange(of: isSpeaker) { _ in
            updateProximitySensor()
        }
    }

    private var settingsToolbarItem: some ToolbarContent {
        ToolbarItem(placement: .navigationBarTrailing) {
            Button {
                showingSettings = true
            } label: {
                HStack(spacing: 4) {
                    Circle()
                        .fill(connection.state == .authenticated ? Color.green : Color.orange)
                        .frame(width: 8, height: 8)
                    Image(systemName: "gearshape")
                        .font(.body)
                }
            }
            .accessibilityLabel("Bağlantı ayarları")
        }
    }

    private func updateProximitySensor() {
        let isCallActive = connection.currentCall?.state == .active
        UIDevice.current.isProximityMonitoringEnabled = (isCallActive && !isSpeaker)
    }

    // MARK: - Tab 0: Favoriler

    private var favoritesView: some View {
        let list = decodeFavorites()
        return Group {
            if list.isEmpty {
                VStack(spacing: 16) {
                    Image(systemName: "star")
                        .font(.system(size: 48))
                        .foregroundColor(.secondary)
                    Text("Henüz favori eklenmedi")
                        .font(.headline)
                        .foregroundColor(.secondary)
                    Text("Hızlı aramak istediğiniz kişileri rehberden favorilere ekleyin.")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                    Button {
                        contactPickerAddsFavorite = true
                        showingContactPicker = true
                    } label: {
                        Label("Kişi Seç ve Ekle", systemImage: "plus.circle.fill")
                    }
                    .buttonStyle(.borderedProminent)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(list, id: \.self) { num in
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(num)
                                    .font(.body.weight(.medium))
                                Text("Hızlı Arama")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            Button {
                                CallKitManager.shared.startOutgoingCall(phoneNumber: num)
                            } label: {
                                Image(systemName: "phone.fill")
                                    .foregroundColor(.green)
                                    .padding(8)
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                    .onDelete(perform: deleteFavorite)
                }
                .listStyle(.insetGrouped)
                .toolbar {
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button {
                            contactPickerAddsFavorite = true
                            showingContactPicker = true
                        } label: {
                            Image(systemName: "plus")
                        }
                    }
                }
            }
        }
    }

    // MARK: - Tab 1: Son Aramalar

    private var recentsView: some View {
        let list = decodeRecents()
        return Group {
            if list.isEmpty {
                VStack(spacing: 16) {
                    Image(systemName: "clock")
                        .font(.system(size: 48))
                        .foregroundColor(.secondary)
                    Text("Son arama kaydı yok")
                        .font(.headline)
                        .foregroundColor(.secondary)
                    Text("iPhone veya Redmi üzerinden yapılan aramalar burada listelenir.")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(list.indices, id: \.self) { idx in
                        let item = list[idx]
                        HStack {
                            Image(systemName: "phone.arrow.up.right.fill")
                                .font(.caption)
                                .foregroundColor(.secondary)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item)
                                    .font(.body.weight(.medium))
                                Text("GSM Geçit Araması")
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            Button {
                                CallKitManager.shared.startOutgoingCall(phoneNumber: item)
                            } label: {
                                Image(systemName: "phone.fill")
                                    .foregroundColor(.green)
                                    .padding(8)
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                    .onDelete(perform: deleteRecent)
                }
                .listStyle(.insetGrouped)
            }
        }
    }

    // MARK: - Tab 2: Kişiler

    private var contactsView: some View {
        VStack(spacing: 24) {
            Spacer()
            Image(systemName: "person.crop.circle.badge.plus")
                .font(.system(size: 64))
                .foregroundColor(.blue)
            Text("Rehberden Ara")
                .font(.title2.weight(.bold))
            Text("iPhone rehberinizdeki kişileri seçerek Redmi GSM hattı üzerinden arama başlatabilirsiniz.")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 36)
            Button {
                contactPickerAddsFavorite = false
                showingContactPicker = true
            } label: {
                Label("Kişileri Aç", systemImage: "person.2.fill")
                    .font(.headline)
                    .frame(maxWidth: 240, minHeight: 48)
            }
            .buttonStyle(.borderedProminent)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(UIColor.systemGroupedBackground))
    }

    // MARK: - Tab 3: Klavye (Keypad)

    private var keypadView: some View {
        VStack(spacing: 12) {
            Spacer(minLength: 10)

            // Display Area
            HStack {
                Spacer()
                Text(outgoingNumber.isEmpty ? " " : outgoingNumber)
                    .font(.system(size: 36, weight: .semibold, design: .rounded))
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .foregroundColor(.primary)
                Spacer()
                if !outgoingNumber.isEmpty {
                    Button {
                        outgoingNumber.removeLast()
                    } label: {
                        Image(systemName: "delete.left.fill")
                            .font(.title2)
                            .foregroundColor(.secondary)
                    }
                    .padding(.trailing, 16)
                }
            }
            .frame(height: 50)
            .padding(.horizontal)

            if let error = connection.lastCallError {
                Text(error)
                    .font(.caption)
                    .foregroundColor(.red)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
            }

            // Keypad Grid
            VStack(spacing: 14) {
                HStack(spacing: 24) {
                    keypadButton(digit: "1", letters: "")
                    keypadButton(digit: "2", letters: "A B C")
                    keypadButton(digit: "3", letters: "D E F")
                }
                HStack(spacing: 24) {
                    keypadButton(digit: "4", letters: "G H I")
                    keypadButton(digit: "5", letters: "J K L")
                    keypadButton(digit: "6", letters: "M N O")
                }
                HStack(spacing: 24) {
                    keypadButton(digit: "7", letters: "P Q R S")
                    keypadButton(digit: "8", letters: "T U V")
                    keypadButton(digit: "9", letters: "W X Y Z")
                }
                HStack(spacing: 24) {
                    keypadButton(digit: "*", letters: "")
                    keypadButton(digit: "0", letters: "+")
                    keypadButton(digit: "#", letters: "")
                }
                // Call Action Button
                HStack(spacing: 24) {
                    Spacer()
                    Button {
                        let clean = outgoingNumber.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !clean.isEmpty else { return }
                        CallKitManager.shared.startOutgoingCall(phoneNumber: clean)
                    } label: {
                        Image(systemName: "phone.fill")
                            .font(.system(size: 30))
                            .foregroundColor(.white)
                            .frame(width: 72, height: 72)
                            .background(connection.state == .authenticated && !outgoingNumber.isEmpty ? Color.green : Color.gray.opacity(0.5))
                            .clipShape(Circle())
                    }
                    .disabled(connection.state != .authenticated || outgoingNumber.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    Spacer()
                }
                .padding(.top, 4)
            }

            Spacer(minLength: 16)
        }
        .background(Color(UIColor.systemBackground))
    }

    private func keypadButton(digit: String, letters: String) -> some View {
        Button {
            outgoingNumber.append(digit)
        } label: {
            VStack(spacing: 1) {
                Text(digit)
                    .font(.system(size: 32, weight: .regular, design: .rounded))
                    .foregroundColor(.primary)
                if !letters.isEmpty {
                    Text(letters)
                        .font(.system(size: 9, weight: .bold))
                        .foregroundColor(.secondary)
                } else {
                    Spacer().frame(height: 10)
                }
            }
            .frame(width: 76, height: 76)
            .background(Color(UIColor.secondarySystemBackground))
            .clipShape(Circle())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Active Call HUD Overlay

    private func activeCallOverlay(call: GSMCall) -> some View {
        VStack(spacing: 0) {
            VStack(spacing: 16) {
                // Header status
                VStack(spacing: 4) {
                    Text(call.number.isEmpty ? "Gizli Numara" : call.number)
                        .font(.title2.weight(.bold))
                        .foregroundColor(.primary)
                    Text(callStateDescription(call.state))
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                    HStack(spacing: 6) {
                        Image(systemName: connection.isIPhoneAudioConfirmed
                              ? (isSpeaker ? "speaker.wave.3.fill" : "ear.fill")
                              : "iphone")
                            .font(.caption)
                        Text(connection.isIPhoneAudioConfirmed
                             ? (isSpeaker ? "Ses iPhone hoparlöründe" : "Ses iPhone ahizesinde")
                             : connection.audioStatus)
                            .font(.caption.weight(.medium))
                    }
                    .foregroundColor(.blue)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 3)
                    .background(Color.blue.opacity(0.12))
                    .clipShape(Capsule())
                }
                .padding(.top, 8)

                if call.state == .ringing {
                    // Incoming Ringing Controls
                    HStack(spacing: 48) {
                        Button {
                            connection.sendReject(callId: call.rawCallId)
                        } label: {
                            VStack(spacing: 6) {
                                Image(systemName: "phone.down.fill")
                                    .font(.title)
                                    .foregroundColor(.white)
                                    .frame(width: 64, height: 64)
                                    .background(Color.red)
                                    .clipShape(Circle())
                                Text("Reddet")
                                    .font(.caption.weight(.medium))
                                    .foregroundColor(.primary)
                            }
                        }
                        Button {
                            CallKitManager.shared.answerIncomingFromApp(callId: call.rawCallId)
                        } label: {
                            VStack(spacing: 6) {
                                Image(systemName: "phone.fill")
                                    .font(.title)
                                    .foregroundColor(.white)
                                    .frame(width: 64, height: 64)
                                    .background(Color.green)
                                    .clipShape(Circle())
                                Text("Yanıtla")
                                    .font(.caption.weight(.medium))
                                    .foregroundColor(.primary)
                            }
                        }
                    }
                    .padding(.vertical, 8)
                } else {
                    // Active / Dialing 2x2 Control Grid
                    VStack(spacing: 16) {
                        HStack(spacing: 36) {
                            // Mute button
                            Button {
                                isMuted.toggle()
                                AudioEngine.shared.isMuted = isMuted
                            } label: {
                                VStack(spacing: 6) {
                                    Image(systemName: isMuted ? "mic.slash.fill" : "mic.fill")
                                        .font(.title2)
                                        .foregroundColor(isMuted ? .white : .primary)
                                        .frame(width: 58, height: 58)
                                        .background(isMuted ? Color.orange : Color(UIColor.secondarySystemBackground))
                                        .clipShape(Circle())
                                    Text(isMuted ? "Sessizde" : "Sessiz")
                                        .font(.caption2.weight(.medium))
                                        .foregroundColor(.primary)
                                }
                            }

                            // Speaker toggle button
                            Button {
                                let nextValue = !isSpeaker
                                if AudioEngine.shared.setSpeakerEnabled(nextValue) {
                                    isSpeaker = nextValue
                                }
                            } label: {
                                VStack(spacing: 6) {
                                    Image(systemName: isSpeaker ? "speaker.wave.3.fill" : "speaker.fill")
                                        .font(.title2)
                                        .foregroundColor(isSpeaker ? .white : .primary)
                                        .frame(width: 58, height: 58)
                                        .background(isSpeaker ? Color.blue : Color(UIColor.secondarySystemBackground))
                                        .clipShape(Circle())
                                    Text("Hoparlör")
                                        .font(.caption2.weight(.medium))
                                        .foregroundColor(.primary)
                                }
                            }
                        }

                        HStack(spacing: 36) {
                            // DTMF Keypad sheet toggle
                            Button {
                                showingDtmfSheet.toggle()
                            } label: {
                                VStack(spacing: 6) {
                                    Image(systemName: "circle.grid.3x3.fill")
                                        .font(.title2)
                                        .foregroundColor(.primary)
                                        .frame(width: 58, height: 58)
                                        .background(Color(UIColor.secondarySystemBackground))
                                        .clipShape(Circle())
                                    Text("Tuşlar")
                                        .font(.caption2.weight(.medium))
                                        .foregroundColor(.primary)
                                }
                            }

                            // End Call button
                            Button {
                                connection.sendHangup(callId: call.rawCallId)
                            } label: {
                                VStack(spacing: 6) {
                                    Image(systemName: "phone.down.fill")
                                        .font(.title2)
                                        .foregroundColor(.white)
                                        .frame(width: 58, height: 58)
                                        .background(Color.red)
                                        .clipShape(Circle())
                                    Text("Bitir")
                                        .font(.caption2.weight(.medium))
                                        .foregroundColor(.primary)
                                }
                            }
                        }
                    }
                    .padding(.top, 4)
                }
            }
            .padding(20)
            .background(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(Color(UIColor.systemBackground))
                    .shadow(color: Color.black.opacity(0.2), radius: 16, x: 0, y: 4)
            )
            .padding(.horizontal, 16)
            .padding(.bottom, 64)
        }
        .sheet(isPresented: $showingDtmfSheet) {
            dtmfKeypadSheet(callId: call.rawCallId)
        }
    }

    private func dtmfKeypadSheet(callId: String) -> some View {
        NavigationView {
            VStack(spacing: 20) {
                Text("Görüşme Tuşları (DTMF)")
                    .font(.headline)
                    .foregroundColor(.secondary)
                    .padding(.top)

                let rows = [
                    ["1", "2", "3"],
                    ["4", "5", "6"],
                    ["7", "8", "9"],
                    ["*", "0", "#"]
                ]
                ForEach(rows, id: \.self) { row in
                    HStack(spacing: 28) {
                        ForEach(row, id: \.self) { digit in
                            Button {
                                connection.sendDtmf(digit: digit, callId: callId)
                            } label: {
                                Text(digit)
                                    .font(.system(size: 28, weight: .medium, design: .rounded))
                                    .frame(width: 68, height: 68)
                                    .background(Color(UIColor.secondarySystemBackground))
                                    .clipShape(Circle())
                            }
                        }
                    }
                }
                Spacer()
            }
            .navigationTitle("Tuş Takımı")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Kapat") { showingDtmfSheet = false }
                }
            }
        }
    }

    // MARK: - Settings Sheet

    private var settingsSheet: some View {
        NavigationView {
            Form {
                Section(header: Text("Redmi Bağlantı Bilgisi")) {
                    HStack {
                        Text("Durum")
                        Spacer()
                        Text(connectionStatus)
                            .foregroundColor(connection.state == .authenticated ? .green : .secondary)
                            .fontWeight(.medium)
                    }
                    HStack {
                        Text("Ses Yönü")
                        Spacer()
                        Text(connection.audioStatus)
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    }
                }

                Section(header: Text("Ağ ve Eşleştirme"),
                        footer: Text("Redmi yerel ağda otomatik bulunur. Gerekirse Redmi'nin IP adresini manuel girebilirsiniz.")) {
                    TextField("Redmi IP Adresi (isteğe bağlı)", text: $gatewayIp)
                        .keyboardType(.numbersAndPunctuation)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                    TextField("Port", text: $gatewayPort)
                        .keyboardType(.numberPad)
                    TextField("Eşleştirme Kodu", text: $pairingToken)
                        .autocapitalization(.allCharacters)
                        .disableAutocorrection(true)
                }

                Section {
                    Button("Kaydet ve Yeniden Bağlan") {
                        connectGateway()
                        showingSettings = false
                    }
                    .disabled(pairingToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                    if connection.state == .authenticated || connection.state == .connecting {
                        Button("Bağlantıyı Kes", role: .destructive) {
                            connection.disconnect()
                        }
                    }
                }

                if !connection.logs.isEmpty {
                    Section {
                        DisclosureGroup("Tanılama") {
                            ForEach(connection.logs.suffix(8), id: \.self) { log in
                                Text(log)
                                    .font(.system(size: 11, design: .monospaced))
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Bağlantı Ayarları")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Bitti") { showingSettings = false }
                }
            }
        }
    }

    // MARK: - Helpers

    private var connectionStatus: String {
        switch connection.state {
        case .authenticated: return "Bağlandı (Hazır)"
        case .connecting, .connected: return "Bağlanıyor..."
        case .disconnected: return "Bağlı Değil"
        case .failed: return "Bağlantı Hatası"
        }
    }

    private func callStateDescription(_ state: GSMCallState) -> String {
        switch state {
        case .ringing: return "Gelen Arama..."
        case .active: return "Görüşme Sürüyor"
        case .dialing, .connecting: return "Bağlanıyor..."
        case .holding: return "Beklemede"
        case .idle, .disconnected: return "Sonlandı"
        }
    }

    private func connectGateway() {
        connection.connect(
            host: gatewayIp.trimmingCharacters(in: .whitespacesAndNewlines),
            port: Int(gatewayPort) ?? 8787,
            token: pairingToken.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        )
    }

    private func decodeFavorites() -> [String] {
        guard let data = favoritesData.data(using: .utf8),
              let list = try? JSONDecoder().decode([String].self, from: data) else { return [] }
        return list
    }

    private func addFavorite(number: String) {
        let cleaned = number.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else { return }
        var list = decodeFavorites()
        if !list.contains(cleaned) { list.append(cleaned) }
        if let encoded = try? JSONEncoder().encode(list),
           let value = String(data: encoded, encoding: .utf8) {
            favoritesData = value
        }
    }

    private func deleteFavorite(at offsets: IndexSet) {
        var list = decodeFavorites()
        list.remove(atOffsets: offsets)
        if let encoded = try? JSONEncoder().encode(list), let str = String(data: encoded, encoding: .utf8) {
            favoritesData = str
        }
    }

    private func decodeRecents() -> [String] {
        guard let data = recentsData.data(using: .utf8),
              let list = try? JSONDecoder().decode([String].self, from: data) else { return [] }
        return list
    }

    private func addRecent(number: String) {
        var list = decodeRecents()
        list.removeAll { $0 == number }
        list.insert(number, at: 0)
        if list.count > 30 { list.removeLast() }
        if let encoded = try? JSONEncoder().encode(list), let str = String(data: encoded, encoding: .utf8) {
            recentsData = str
        }
    }

    private func deleteRecent(at offsets: IndexSet) {
        var list = decodeRecents()
        list.remove(atOffsets: offsets)
        if let encoded = try? JSONEncoder().encode(list), let str = String(data: encoded, encoding: .utf8) {
            recentsData = str
        }
    }
}
