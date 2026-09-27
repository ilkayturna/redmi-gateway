import SwiftUI
import Combine

struct ContentView: View {
    @StateObject private var connection = GatewayConnection.shared
    @StateObject private var contactDirectory = PhoneContactDirectory.shared
    @AppStorage("gateway_ip") private var gatewayIp = ""
    @AppStorage("gateway_port") private var gatewayPort = "8787"
    @AppStorage("pairing_token") private var pairingToken = ""

    @State private var selectedTab = 3 // Default to Klavye (Keypad)
    @State private var showingSettings = false
    @State private var showingContactPicker = false
    @State private var contactPickerAddsFavorite = false
    @State private var outgoingNumber = ""
    @State private var contactSearch = ""
    @State private var messageRecipient = ""
    @State private var isMuted = false
    @State private var isSpeaker = false
    @State private var showingDtmfSheet = false
    @StateObject private var bleManager = BLECentralManager.shared
    @State private var showingDiagnostics = false

    // Recents and Favorites simple persistence
    @AppStorage("favorite_numbers") private var favoritesData: String = "[]"
    @AppStorage("recents_history") private var recentsData: String = "[]"

    var body: some View {
        ZStack(alignment: .bottom) {
            VStack(spacing: 0) {
                gatewayStatusBar

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
                        MessagesView(prefilledRecipient: $messageRecipient)
                            .toolbar { settingsToolbarItem }
                    }
                    .tabItem {
                        Label("Mesajlar", systemImage: "message.fill")
                    }
                    .tag(4)
                }
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
                                Text(contactDirectory.name(for: num) ?? "Hızlı Arama")
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
        Group {
            if contactDirectory.contacts.isEmpty {
                VStack(spacing: 16) {
                    Image(systemName: "person.crop.circle")
                        .font(.system(size: 48))
                        .foregroundColor(.secondary)
                    Text("Kişiler Görünmüyor")
                        .font(.headline)
                    Text("iPhone rehberini burada aramak için Kişiler iznini aç.")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                    Button("Yeniden Dene") { contactDirectory.reload() }
                        .buttonStyle(.borderedProminent)
                }
                .padding(24)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(filteredContacts) { contact in
                        NavigationLink {
                            contactDetail(contact)
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "person.crop.circle.fill")
                                    .font(.title2)
                                    .foregroundColor(.blue)
                                    .frame(width: 36)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(contact.name).font(.body.weight(.medium))
                                    if let first = contact.numbers.first {
                                        Text(first).font(.footnote).foregroundColor(.secondary)
                                    }
                                }
                            }
                            .padding(.vertical, 4)
                        }
                    }
                }
                .listStyle(.plain)
            }
        }
        .searchable(text: $contactSearch, prompt: "Ad veya numara ara")
        .onAppear { contactDirectory.ensureLoaded() }
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button("Yenile") { contactDirectory.reload() }
            }
        }
    }

    private var filteredContacts: [PhoneContact] {
        let query = contactSearch.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return contactDirectory.contacts }
        return contactDirectory.contacts.filter { contact in
            contact.name.localizedCaseInsensitiveContains(query)
                || contact.numbers.contains { $0.localizedCaseInsensitiveContains(query) }
        }
    }

    private func contactDetail(_ contact: PhoneContact) -> some View {
        List {
            Section {
                ForEach(contact.numbers, id: \.self) { number in
                    VStack(alignment: .leading, spacing: 12) {
                        Text(number).font(.body.monospacedDigit())
                        HStack(spacing: 12) {
                            Button {
                                CallKitManager.shared.startOutgoingCall(phoneNumber: number)
                            } label: {
                                Label("Ara", systemImage: "phone.fill")
                            }
                            .buttonStyle(.borderedProminent)

                            Button {
                                messageRecipient = number
                                selectedTab = 4
                            } label: {
                                Label("Mesaj", systemImage: "message.fill")
                            }
                            .buttonStyle(.bordered)

                            Button {
                                addFavorite(number: number)
                            } label: {
                                Image(systemName: "star")
                            }
                            .buttonStyle(.bordered)
                            .accessibilityLabel("Favorilere ekle")
                        }
                    }
                    .padding(.vertical, 8)
                }
            }
        }
        .navigationTitle(contact.name)
        .navigationBarTitleDisplayMode(.large)
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
            Spacer(minLength: 48)
            VStack(spacing: 16) {
                // Header status
                VStack(spacing: 8) {
                    Text("Telefon Geçidi")
                        .font(.footnote.weight(.semibold))
                        .foregroundColor(.secondary)
                    Text(call.number.isEmpty ? "Gizli Numara" : contactDirectory.displayName(for: call.number))
                        .font(.largeTitle.weight(.semibold))
                        .foregroundColor(.primary)
                        .minimumScaleFactor(0.7)
                        .lineLimit(2)
                    if let name = contactDirectory.name(for: call.number), name != call.number {
                        Text(call.number)
                            .font(.body.monospacedDigit())
                            .foregroundColor(.secondary)
                    }
                    Text(callStateDescription(call.state))
                        .font(.headline)
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
                .multilineTextAlignment(.center)

                Spacer(minLength: 36)

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
                    VStack(spacing: 28) {
                        HStack(spacing: 48) {
                            // Mute button
                            Button {
                                isMuted.toggle()
                                AudioEngine.shared.isMuted = isMuted
                            } label: {
                                VStack(spacing: 6) {
                                    Image(systemName: isMuted ? "mic.slash.fill" : "mic.fill")
                                        .font(.title2)
                                        .foregroundColor(isMuted ? .white : .primary)
                                        .frame(width: 72, height: 72)
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
                                        .frame(width: 72, height: 72)
                                        .background(isSpeaker ? Color.blue : Color(UIColor.secondarySystemBackground))
                                        .clipShape(Circle())
                                    Text("Hoparlör")
                                        .font(.caption2.weight(.medium))
                                        .foregroundColor(.primary)
                                }
                            }
                        }

                        HStack(spacing: 48) {
                            // DTMF Keypad sheet toggle
                            Button {
                                showingDtmfSheet.toggle()
                            } label: {
                                VStack(spacing: 6) {
                                    Image(systemName: "circle.grid.3x3.fill")
                                        .font(.title2)
                                        .foregroundColor(.primary)
                                        .frame(width: 72, height: 72)
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
                                        .frame(width: 72, height: 72)
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
            .padding(.horizontal, 24)
            Spacer(minLength: 48)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(UIColor.systemBackground).ignoresSafeArea())
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
                Section(header: Text("Eşleştirme"),
                        footer: Text("Redmi ekranındaki eşleştirme kodunu girin. IP adresi girmeye gerek yoktur; Redmi yerel ağda (mDNS) ve Bluetooth ile otomatik olarak bulunur.")) {
                    TextField("Eşleştirme Kodu", text: $pairingToken)
                        .autocapitalization(.allCharacters)
                        .disableAutocorrection(true)
                }

                Section(header: Text("Bağlantı Durumu")) {
                    HStack {
                        Text("Durum")
                        Spacer()
                        Text(connectionStatus)
                            .foregroundColor(connection.state == .authenticated ? .green : .secondary)
                            .fontWeight(.medium)
                    }
                    if !connection.connectedHost.isEmpty {
                        HStack {
                            Text("Redmi Adresi")
                            Spacer()
                            Text(connection.connectedHost)
                                .font(.footnote.monospacedDigit())
                                .foregroundColor(.secondary)
                        }
                    }
                    HStack {
                        Text("Ses Yönü")
                        Spacer()
                        Text(connection.audioStatus)
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    }
                    HStack {
                        Text("Bluetooth Eşlikçi")
                        Spacer()
                        Text(bleStatusText)
                            .font(.footnote)
                            .foregroundColor(bleManager.isConnected ? .blue : .secondary)
                    }
                }

                Section {
                    Button(connection.state == .authenticated ? "Yeniden Bağlan" : "Bağlan / Otomatik Bul") {
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

                Section(header: Text("Gelişmiş")) {
                    Button {
                        showingDiagnostics = true
                    } label: {
                        HStack {
                            Label("Tanılama ve Manuel Ayarlar", systemImage: "wrench.and.screwdriver")
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                    .foregroundColor(.primary)
                }
            }
            .navigationTitle("Bağlantı Ayarları")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Bitti") { showingSettings = false }
                }
            }
            .sheet(isPresented: $showingDiagnostics) {
                DiagnosticsView(
                    gatewayIp: $gatewayIp,
                    gatewayPort: $gatewayPort,
                    pairingToken: $pairingToken
                )
            }
        }
    }

    private var gatewayStatusBar: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(statusBarColor)
                .frame(width: 8, height: 8)

            Text(statusBarText)
                .font(.caption.weight(.medium))
                .foregroundColor(.primary)
                .lineLimit(1)

            Spacer()

            if bleManager.isConnected {
                HStack(spacing: 3) {
                    Image(systemName: "bolt.horizontal.fill")
                        .font(.system(size: 8))
                    Text("BLE")
                        .font(.system(size: 9, weight: .bold))
                }
                .foregroundColor(.blue)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(Color.blue.opacity(0.12))
                .clipShape(Capsule())
            }

            Button {
                showingSettings = true
            } label: {
                Image(systemName: "slider.horizontal.3")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
        .background(Color(UIColor.secondarySystemBackground))
    }

    private var statusBarColor: Color {
        switch connection.uiStatus {
        case .connected:
            return .green
        case .connecting, .discovered:
            return .orange
        case .searching:
            return .blue
        case .disconnected:
            return .gray
        case .error:
            return .red
        }
    }

    private var statusBarText: String {
        if connection.state == .authenticated {
            if let call = connection.currentCall, call.state == .active {
                return connection.audioStatus
            }
            return "Redmi Bağlı"
        }
        return connection.uiStatus.displayText
    }

    private var bleStatusText: String {
        if bleManager.isConnected {
            if let devId = bleManager.discoveredDeviceId {
                return "Bağlı (\(devId))"
            }
            return "Bağlı"
        } else if bleManager.isBluetoothPoweredOn {
            return "Taranıyor..."
        } else {
            return "Kapalı"
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

struct DiagnosticsView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var connection = GatewayConnection.shared
    @ObservedObject private var bleManager = BLECentralManager.shared

    @Binding var gatewayIp: String
    @Binding var gatewayPort: String
    @Binding var pairingToken: String

    @State private var showingCopiedAlert = false

    var body: some View {
        NavigationView {
            List {
                // 1. Diagnostics Failures & Warnings
                if let failure = connection.lastDiagnosticFailure {
                    Section(header: Label("Aktif Uyarı", systemImage: "exclamationmark.triangle.fill").foregroundColor(.red)) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(failure.title)
                                .font(.headline)
                                .foregroundColor(.red)
                            Text(failure.suggestion)
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                        .padding(.vertical, 4)

                        Button("Uyarıyı Temizle") {
                            connection.clearDiagnosticFailure()
                        }
                        .font(.footnote)
                    }
                }

                // 2. Network & Discovery Health Checks
                Section(header: Text("Sistem Sağlık Kontrolleri")) {
                    HStack {
                        Label("Wi-Fi Ağı", systemImage: "wifi")
                        Spacer()
                        Text(connection.isWiFiConnected ? "Bağlı" : "Bağlı Değil")
                            .foregroundColor(connection.isWiFiConnected ? .green : .red)
                            .font(.footnote.weight(.medium))
                    }

                    HStack {
                        Label("mDNS Keşfi", systemImage: "antenna.radiowaves.left.and.right")
                        Spacer()
                        Text(connection.discovery.isSearching ? "Aktif Taranıyor" : "Beklemede")
                            .foregroundColor(connection.discovery.isSearching ? .blue : .secondary)
                            .font(.footnote)
                    }

                    HStack {
                        Label("Bluetooth Donanımı", systemImage: "bolt.horizontal.fill")
                        Spacer()
                        Text(bleManager.isBluetoothPoweredOn ? "Açık" : "Kapalı")
                            .foregroundColor(bleManager.isBluetoothPoweredOn ? .green : .orange)
                            .font(.footnote)
                    }

                    HStack {
                        Label("BLE Eşlikçi Bağlantısı", systemImage: "point.3.filled.connected.trianglepath.dotted")
                        Spacer()
                        Text(bleManager.isConnected ? "Bağlı" : "Bağlı Değil")
                            .foregroundColor(bleManager.isConnected ? .green : .secondary)
                            .font(.footnote)
                    }

                    if let devId = bleManager.discoveredDeviceId {
                        HStack {
                            Text("Cihaz ID (BLE)")
                            Spacer()
                            Text(devId)
                                .font(.footnote.monospaced())
                                .foregroundColor(.secondary)
                        }
                    }

                    if let rssi = bleManager.lastRssi {
                        HStack {
                            Text("BLE Sinyal Gücü (RSSI)")
                            Spacer()
                            Text("\(rssi) dBm")
                                .font(.footnote.monospacedDigit())
                                .foregroundColor(.secondary)
                        }
                    }

                    if let date = bleManager.lastSeenDate {
                        HStack {
                            Text("Son BLE Görülme")
                            Spacer()
                            Text(date, style: .time)
                                .font(.footnote)
                                .foregroundColor(.secondary)
                        }
                    }

                    Button("BLE Eşleştirmesini Sıfırla") {
                        BLECentralManager.shared.clearPairing()
                        connection.log("BLE eşleştirmesi sıfırlandı.")
                    }
                    .foregroundColor(.red)
                    .font(.footnote)
                }

                // 3. Manual IP Override
                Section(header: Text("Manuel IP Geçersiz Kılma"),
                        footer: Text("Sıfır yapılandırmalı keşif çalışmıyorsa veya statik IP kullanmak istiyorsanız manuel IP ve port belirtebilirsiniz.")) {
                    TextField("Redmi IP Adresi", text: $gatewayIp)
                        .keyboardType(.numbersAndPunctuation)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                    TextField("Port", text: $gatewayPort)
                        .keyboardType(.numberPad)

                    Button("Manuel Adresle Bağlan") {
                        connection.connect(
                            host: gatewayIp.trimmingCharacters(in: .whitespacesAndNewlines),
                            port: Int(gatewayPort) ?? 8787,
                            token: pairingToken.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
                        )
                        dismiss()
                    }
                    .disabled(gatewayIp.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || pairingToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }

                // 4. Live Diagnostic Logs
                Section(header: HStack {
                    Text("Canlı Günlükler (\(connection.logs.count))")
                    Spacer()
                    Button("Kopyala") {
                        UIPasteboard.general.string = connection.logs.joined(separator: "\n")
                        showingCopiedAlert = true
                    }
                    .font(.caption)
                }) {
                    if connection.logs.isEmpty {
                        Text("Henüz günlük kaydı yok.")
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    } else {
                        ForEach(connection.logs.suffix(25).reversed(), id: \.self) { log in
                            Text(log)
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundColor(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("Tanılama & Manuel Ayarlar")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Kapat") { dismiss() }
                }
            }
            .alert("Kopyalandı", isPresented: $showingCopiedAlert) {
                Button("Tamam", role: .cancel) {}
            } message: {
                Text("Günlük kayıtları panoya kopyalandı.")
            }
        }
    }
}

