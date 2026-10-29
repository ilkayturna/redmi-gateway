import AudioToolbox
import Intents
import SwiftUI

enum AppTab: Int {
    case favorites, recents, contacts, keypad, messages
}

struct ContentView: View {
    @ObservedObject private var connection = GatewayConnection.shared
    @ObservedObject private var history = HistoryStore.shared
    @ObservedObject private var router = AppRouter.shared
    @AppStorage("selected_tab") private var selectedTabRaw = AppTab.keypad.rawValue
    @State private var showingSettings = false
    @State private var callMinimized = false
    @State private var messageTarget: String?
    @AppStorage("call_wake_prompted") private var callWakePrompted = false
    @State private var showingWakePrompt = false

    private var selectedTab: Binding<Int> {
        Binding(get: { selectedTabRaw }, set: { selectedTabRaw = $0 })
    }

    var body: some View {
        Group {
            if connection.isPaired {
                mainTabs
            } else {
                PairingView()
            }
        }
        .onAppear {
            _ = CallKitManager.shared
            AudioEngine.shared.requestMicrophonePermission()
            connection.start()
            offerCallWake()
            // A notification tapped while the app was not running sets its route before this view
            // exists, and onChange does not fire for a value that was already there.
            if let route = router.pending { follow(route) }
        }
        .onChange(of: connection.pairing != nil) { _ in offerCallWake() }
        .onChange(of: router.pending) { route in
            if let route { follow(route) }
        }
        // A call chosen in iOS Recents or Contacts for this app. It waits for the link the way a
        // tap inside the app does, with the same "Arama bekliyor" bar and the same failure note.
        .onContinueUserActivity("INStartCallIntent") { activity in
            if let number = (activity.interaction?.intent as? INStartCallIntent)?.contacts?.first?.personHandle?.value {
                InAppDial.start(number)
            }
        }
        .onContinueUserActivity("INStartAudioCallIntent") { activity in
            if let number = (activity.interaction?.intent as? INStartAudioCallIntent)?.contacts?.first?.personHandle?.value {
                InAppDial.start(number)
            }
        }
        .alert("Uygulama kapalıyken de aramalar", isPresented: $showingWakePrompt) {
            Button("Şimdi değil", role: .cancel) {}
            Button("İzin ver") { CallWakeMonitor.shared.requestPermission() }
        } message: {
            Text("Uygulamayı kaydırıp kapatsan bile gelen aramaların iPhone'da çalması için konum iznini \"Her Zaman\" olarak ver. Yalnızca Redmi'nin yakınlığı algılanır; konumun okunmaz ve kaydedilmez.")
        }
        .onChange(of: connection.currentCall?.rawCallId) { callId in
            callMinimized = false
            // The call screen cannot cover the app while a sheet is open.
            if callId != nil { showingSettings = false }
        }
    }

    private var mainTabs: some View {
        TabView(selection: selectedTab) {
            NavigationView { FavoritesView(openSettings: openSettings, openMessages: openMessages) }
                .navigationViewStyle(.stack)
                .tabItem { Label("Favoriler", systemImage: "star.fill") }
                .tag(AppTab.favorites.rawValue)
            NavigationView { RecentsView(openSettings: openSettings, openMessages: openMessages) }
                .navigationViewStyle(.stack)
                .tabItem { Label("Son Aramalar", systemImage: "clock.fill") }
                .badge(history.unseenMissedCount)
                .tag(AppTab.recents.rawValue)
            NavigationView { ContactsView(openSettings: openSettings, openMessages: openMessages) }
                .navigationViewStyle(.stack)
                .tabItem { Label("Kişiler", systemImage: "person.crop.circle.fill") }
                .tag(AppTab.contacts.rawValue)
            NavigationView { KeypadView(openSettings: openSettings) }
                .navigationViewStyle(.stack)
                .tabItem { Label("Tuş Takımı", systemImage: "circle.grid.3x3.fill") }
                .tag(AppTab.keypad.rawValue)
            NavigationView { MessagesView(target: $messageTarget, openSettings: openSettings) }
                .navigationViewStyle(.stack)
                .tabItem { Label("Mesajlar", systemImage: "message.fill") }
                .badge(history.unreadCount)
                .tag(AppTab.messages.rawValue)
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            VStack(spacing: 0) {
                PendingDialBar()
                if connection.currentCall != nil && callMinimized {
                    ReturnToCallBar { callMinimized = false }
                }
                ConnectionBanner(openSettings: openSettings)
                RedmiHealthBanner(openSettings: openSettings)
            }
        }
        .fullScreenCover(isPresented: Binding(
            get: { connection.currentCall != nil && !callMinimized },
            set: { presented in if !presented { callMinimized = true } }
        )) {
            CallScreen(minimize: { callMinimized = true })
        }
        .sheet(isPresented: $showingSettings) {
            SettingsView()
        }
    }

    private func openSettings() {
        showingSettings = true
    }

    /// Asked once after pairing; Settings shows the state afterwards.
    private func offerCallWake() {
        // The pairing itself, not isPaired: a pairing still locked behind the first unlock is not
        // the moment to ask for permissions.
        guard connection.pairing != nil else { return }
        Notifier.shared.requestPermission()
        guard !callWakePrompted, !CallWakeMonitor.shared.isEnabled else { return }
        callWakePrompted = true
        showingWakePrompt = true
    }

    private func openMessages(_ number: String) {
        messageTarget = number
        selectedTabRaw = AppTab.messages.rawValue
    }

    /// Where a tapped notification leads.
    private func follow(_ route: AppRoute) {
        router.pending = nil
        switch route {
        case .messages(let address): openMessages(address)
        case .conversations: selectedTabRaw = AppTab.messages.rawValue
        case .recents: selectedTabRaw = AppTab.recents.rawValue
        case .settings: openSettings()
        }
    }
}

/// Only visible when the link is not ready; everyday use never sees technical detail.
///
/// The network link is closed on purpose while the app is off screen, so a closed socket is not a
/// problem in itself: with the Redmi still connected over Bluetooth the banner says so calmly, and
/// "aranıyor" is left for the case where neither link is up.
struct ConnectionBanner: View {
    @ObservedObject private var connection = GatewayConnection.shared
    @ObservedObject private var ble = BLECentralManager.shared
    let openSettings: () -> Void

    var body: some View {
        if let text {
            Button(action: openSettings) {
                HStack(spacing: 8) {
                    if isWorking {
                        ProgressView().scaleEffect(0.8)
                    } else {
                        Image(systemName: icon)
                    }
                    Text(text).font(.footnote.weight(.medium)).lineLimit(2)
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right").font(.caption2.weight(.semibold))
                }
                .foregroundColor(tint)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity)
                .background(tint.opacity(0.12))
            }
            .buttonStyle(.plain)
            .accessibilityHint("Bağlantı ayrıntılarını açar")
        }
    }

    private var text: String? {
        switch connection.link {
        case .ready, .unpaired: return nil
        case .pairing: return "Eşleştiriliyor…"
        case .searching: return restsOnBluetooth ? "Redmi bağlı · Bluetooth" : "Redmi aranıyor…"
        case .connecting: return "Redmi'ye bağlanıyor…"
        case .offline(let reason): return reason
        case .incompatible(let reason): return reason
        }
    }

    /// Read through `ble` so the banner redraws when the Bluetooth link comes or goes.
    private var restsOnBluetooth: Bool {
        ble.isConnected && connection.restsOnBluetooth
    }

    private var isWorking: Bool {
        switch connection.link {
        case .searching: return !restsOnBluetooth
        case .pairing, .connecting: return true
        default: return false
        }
    }

    private var icon: String {
        if case .incompatible = connection.link { return "exclamationmark.triangle.fill" }
        if restsOnBluetooth { return "antenna.radiowaves.left.and.right" }
        return "wifi.exclamationmark"
    }

    private var tint: Color {
        if case .incompatible = connection.link { return .red }
        return (isWorking || restsOnBluetooth) ? .secondary : .orange
    }
}

struct ReturnToCallBar: View {
    @ObservedObject private var connection = GatewayConnection.shared
    @ObservedObject private var directory = PhoneContactDirectory.shared
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack(spacing: 8) {
                Image(systemName: "phone.fill")
                Text(title).font(.footnote.weight(.semibold)).lineLimit(1)
                Spacer(minLength: 0)
                if let start = connection.currentCall?.connectedAt {
                    CallTimerText(start: start).font(.footnote.monospacedDigit())
                }
            }
            .foregroundColor(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity)
            .background(Color.green)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Görüşmeye dön")
    }

    private var title: String {
        let name = directory.displayName(for: connection.currentCall?.number ?? "")
        return "Görüşmeye dön · \(name)"
    }
}

struct CallTimerText: View {
    let start: Date

    var body: some View {
        TimelineView(.periodic(from: start, by: 1)) { context in
            Text(Self.format(context.date.timeIntervalSince(start)))
        }
    }

    static func format(_ interval: TimeInterval) -> String {
        let total = max(0, Int(interval))
        let hours = total / 3600, minutes = (total % 3600) / 60, seconds = total % 60
        return hours > 0 ? String(format: "%d:%02d:%02d", hours, minutes, seconds) : String(format: "%02d:%02d", minutes, seconds)
    }
}

struct SettingsToolbarButton: ToolbarContent {
    let action: () -> Void

    var body: some ToolbarContent {
        ToolbarItem(placement: .navigationBarTrailing) {
            Button(action: action) { Image(systemName: "gearshape") }
                .accessibilityLabel("Ayarlar")
        }
    }
}

// MARK: - Calls started inside the app

/// A call tapped in Favorites, Recents, Contacts, the keypad, a conversation, a missed-call
/// notification or iOS Recents.
///
/// The network link is closed on purpose 25 s after the app leaves the screen, so almost every
/// time the app is opened a tap lands before the link is back. The call waits for the link, with
/// "Arama bekliyor: <ad> · Vazgeç" at the top of the screen. It used to wait invisibly and was
/// dropped without a word after 20 s; when the link does not come up in time the bar now says
/// the call was not made. Emergency numbers never wait: they go to the iPhone's own phone service.
@MainActor
final class InAppDial: ObservableObject {
    static let shared = InAppDial()

    /// The number waiting for the link.
    @Published private(set) var waitingNumber: String?
    /// Why the last tapped call did not start; shown for a few seconds.
    @Published private(set) var failure: String?

    /// Must stay in step with CallKitManager.emergencyNumbers: these go to the iPhone's own phone
    /// service at once and must never wait for the Redmi.
    private static let emergencyNumbers: Set<String> = ["112", "911", "999", "110", "155", "156", "158", "177"]

    private var waitTask: Task<Void, Never>?
    private var failureTask: Task<Void, Never>?

    /// `showsErrorItself`: the caller (the keypad) shows lastCallError under the number already.
    static func start(_ number: String, showsErrorItself: Bool = false) {
        shared.dial(number, showsErrorItself: showsErrorItself)
    }

    private func dial(_ number: String, showsErrorItself: Bool) {
        let connection = GatewayConnection.shared
        let clean = number.replacingOccurrences(of: "[\\s().-]", with: "", options: .regularExpression)
        dismissFailure()
        if connection.isAuthenticated || Self.emergencyNumbers.contains(clean) {
            if waitingNumber != nil { cancel(reason: "another call was tapped") }
            startCall(number, showsErrorItself: showsErrorItself)
            return
        }
        waitTask?.cancel()
        waitingNumber = number
        // Away from home the link runs through the VPN and takes longer to come up.
        let window: TimeInterval = connection.isRemoteLink ? 45 : 25
        connection.log("Call tapped before the link was up; dialling once it is (up to \(Int(window)) s)")
        connection.reloadPairingIfLocked(reason: "call tapped")
        connection.reconnectNow(reason: "call tapped")
        waitTask = Task { @MainActor [weak self] in
            let deadline = Date().addingTimeInterval(window)
            while !Task.isCancelled, !GatewayConnection.shared.isAuthenticated, Date() < deadline {
                try? await Task.sleep(nanoseconds: 250_000_000)
            }
            guard !Task.isCancelled else { return }
            self?.waitEnded(number, window: window, showsErrorItself: showsErrorItself)
        }
    }

    private func waitEnded(_ number: String, window: TimeInterval, showsErrorItself: Bool) {
        let connection = GatewayConnection.shared
        waitTask = nil
        waitingNumber = nil
        guard connection.isAuthenticated else {
            connection.log("Waiting call dropped: no link to the Redmi within \(Int(window)) s")
            let message = "Redmi'ye ulaşılamadı; arama yapılmadı."
            connection.lastCallError = message
            UINotificationFeedbackGenerator().notificationOccurred(.error)
            if !showsErrorItself { showFailure(message) }
            return
        }
        connection.log("Link up; dialling the call that waited for it")
        startCall(number, showsErrorItself: showsErrorItself)
    }

    private func startCall(_ number: String, showsErrorItself: Bool) {
        let connection = GatewayConnection.shared
        CallKitManager.shared.startOutgoingCall(phoneNumber: number)
        // A refusal (another call being set up, an invalid number) is otherwise shown only on the
        // keypad and the call screen, and a tap elsewhere looked dead.
        if !showsErrorItself, let error = connection.lastCallError { showFailure(error) }
    }

    /// "Vazgeç" on the waiting bar.
    func cancel() {
        cancel(reason: "cancelled by the person")
    }

    private func cancel(reason: String) {
        guard waitingNumber != nil else { return }
        waitTask?.cancel()
        waitTask = nil
        waitingNumber = nil
        GatewayConnection.shared.log("Waiting call cancelled: \(reason)")
    }

    private func showFailure(_ message: String) {
        failure = message
        failureTask?.cancel()
        failureTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 8_000_000_000)
            guard !Task.isCancelled else { return }
            self?.failure = nil
        }
    }

    func dismissFailure() {
        failureTask?.cancel()
        failureTask = nil
        failure = nil
    }
}

/// The call waiting for the link, or why a tapped call did not start, above every tab.
struct PendingDialBar: View {
    @ObservedObject private var dial = InAppDial.shared
    @ObservedObject private var directory = PhoneContactDirectory.shared

    var body: some View {
        if let number = dial.waitingNumber {
            HStack(spacing: 8) {
                ProgressView().scaleEffect(0.8)
                Text("Arama bekliyor: \(directory.displayName(for: number))")
                    .font(.footnote.weight(.medium))
                    .lineLimit(1)
                Spacer(minLength: 0)
                Button("Vazgeç") { InAppDial.shared.cancel() }
                    .font(.footnote.weight(.semibold))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity)
            .background(Color.green.opacity(0.15))
            .accessibilityElement(children: .contain)
        } else if let failure = dial.failure {
            HStack(spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill")
                Text(failure).font(.footnote.weight(.medium)).lineLimit(2)
                Spacer(minLength: 0)
                Button { InAppDial.shared.dismissFailure() } label: {
                    Image(systemName: "xmark").font(.caption.weight(.semibold))
                }
                .accessibilityLabel("Kapat")
            }
            .foregroundColor(.red)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity)
            .background(Color.red.opacity(0.12))
        }
    }
}

// MARK: - Favorites

struct FavoritesView: View {
    @ObservedObject private var directory = PhoneContactDirectory.shared
    let openSettings: () -> Void
    let openMessages: (String) -> Void
    @State private var showingPicker = false

    var body: some View {
        Group {
            if directory.favorites.isEmpty {
                EmptyStateView(
                    symbol: "star",
                    title: "Favori yok",
                    message: "Sık aradığın kişileri buraya ekle.",
                    actionTitle: "Favori ekle",
                    action: { showingPicker = true }
                )
            } else {
                List {
                    ForEach(directory.favorites) { favorite in
                        let contact = directory.contact(for: favorite.number)
                        Button {
                            InAppDial.start(favorite.number)
                        } label: {
                            HStack(spacing: 12) {
                                ContactAvatar(name: contact?.name ?? favorite.name ?? "", thumbnail: contact?.thumbnail)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(contact?.name ?? favorite.name ?? PhoneIdentity.formatted(favorite.number))
                                        .font(.body).foregroundColor(.primary)
                                    Text(PhoneIdentity.formatted(favorite.number))
                                        .font(.footnote).foregroundColor(.secondary)
                                }
                                Spacer()
                                Image(systemName: "phone.fill").foregroundColor(.accentColor)
                            }
                        }
                        .swipeActions(edge: .leading) {
                            Button { openMessages(favorite.number) } label: { Label("Mesaj", systemImage: "message.fill") }
                                .tint(.blue)
                        }
                    }
                    .onDelete { directory.removeFavorites(at: $0) }
                    .onMove { directory.moveFavorites(from: $0, to: $1) }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle("Favoriler")
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { showingPicker = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Favori ekle")
            }
            ToolbarItem(placement: .navigationBarLeading) {
                if !directory.favorites.isEmpty { EditButton() }
            }
            SettingsToolbarButton(action: openSettings)
        }
        .sheet(isPresented: $showingPicker) {
            PhoneContactPicker { number in
                if !directory.isFavorite(number) {
                    directory.toggleFavorite(number: number, name: directory.name(for: number))
                }
                showingPicker = false
            }
        }
        .onAppear { directory.ensureLoaded() }
    }
}

// MARK: - Recents

struct RecentsView: View {
    @ObservedObject private var history = HistoryStore.shared
    @ObservedObject private var directory = PhoneContactDirectory.shared
    @Environment(\.scenePhase) private var scenePhase
    let openSettings: () -> Void
    let openMessages: (String) -> Void
    @State private var missedOnly = false
    @State private var detailNumber = ""
    @State private var showingDetail = false
    @State private var newContactNumber = ""
    @State private var showingNewContact = false

    private var items: [CallRecord] {
        missedOnly ? history.calls.filter { $0.isMissed } : history.calls
    }

    var body: some View {
        Group {
            if items.isEmpty {
                EmptyStateView(
                    symbol: missedOnly ? "phone.arrow.down.left" : "clock",
                    title: missedOnly ? "Cevapsız arama yok" : "Son arama yok",
                    message: "Redmi hattındaki aramalar, iPhone kapalıyken olanlar da dahil, burada görünür."
                )
            } else {
                List(items) { call in
                    RecentRow(call: call, showDetails: { showDetails(call.number) })
                        .contentShape(Rectangle())
                        .onTapGesture {
                            guard !call.number.isEmpty else { return }
                            InAppDial.start(call.number)
                        }
                        .swipeActions(edge: .leading) {
                            if !call.number.isEmpty {
                                Button { openMessages(call.number) } label: { Label("Mesaj", systemImage: "message.fill") }
                                    .tint(.blue)
                            }
                        }
                        .contextMenu { actions(for: call) }
                }
                .listStyle(.plain)
                .refreshable { GatewayConnection.shared.requestSync() }
            }
        }
        .background(
            NavigationLink(destination: CallDetailView(number: detailNumber, openMessages: openMessages),
                           isActive: $showingDetail) { EmptyView() }
                .hidden()
        )
        .navigationTitle("Son Aramalar")
        .toolbar {
            ToolbarItem(placement: .principal) {
                Picker("Görünüm", selection: $missedOnly) {
                    Text("Tümü").tag(false)
                    Text("Cevapsız").tag(true)
                }
                .pickerStyle(.segmented)
                .frame(width: 200)
            }
            SettingsToolbarButton(action: openSettings)
        }
        .sheet(isPresented: $showingNewContact) {
            NewContactView(number: newContactNumber) { showingNewContact = false }
        }
        .onAppear {
            directory.ensureLoaded()
            history.recentsVisible = true
            history.markMissedSeen()
        }
        .onDisappear { history.recentsVisible = false }
        // onAppear does not run again for a tab that is already on screen: coming back to the app
        // on Recents, or a missed call arriving while it is shown, counts as seen as well.
        .onChange(of: scenePhase) { phase in
            if phase == .active, history.recentsVisible { history.markMissedSeen() }
        }
        .onChange(of: history.unseenMissedCount) { count in
            if count > 0, history.recentsVisible, scenePhase == .active { history.markMissedSeen() }
        }
    }

    /// Long press on a row, as in Apple's Recents.
    @ViewBuilder
    private func actions(for call: CallRecord) -> some View {
        if !call.number.isEmpty {
            Button { InAppDial.start(call.number) } label: { Label("Ara", systemImage: "phone") }
            Button { openMessages(call.number) } label: { Label("Mesaj gönder", systemImage: "message") }
            Button { UIPasteboard.general.string = call.number } label: {
                Label("Numarayı kopyala", systemImage: "doc.on.doc")
            }
            if directory.contact(for: call.number) == nil {
                Button {
                    newContactNumber = call.number
                    showingNewContact = true
                } label: {
                    Label("Kişilere ekle", systemImage: "person.badge.plus")
                }
            }
        }
        Button { showDetails(call.number) } label: { Label("Ayrıntılar", systemImage: "info.circle") }
    }

    private func showDetails(_ number: String) {
        detailNumber = number
        showingDetail = true
    }
}

extension CallRecord {
    /// "Giden", "Gelen · 02:14", "Cevapsız"…
    var kindText: String {
        switch type {
        case "outgoing": return duration > 0 ? "Giden · \(CallTimerText.format(TimeInterval(duration)))" : "Giden"
        case "incoming": return duration > 0 ? "Gelen · \(CallTimerText.format(TimeInterval(duration)))" : "Gelen"
        case "missed": return "Cevapsız"
        case "declined": return "Reddedildi"
        case "blocked": return "Engellendi"
        case "voicemail": return "Sesli mesaj"
        default: return "Arama"
        }
    }

    /// Which phone carried the conversation.
    var carrierText: String? {
        switch answeredOn {
        case "iphone": return "iPhone'da"
        case "redmi": return "Redmi'de"
        default: return nil
        }
    }

    var symbol: String {
        type == "outgoing" ? "phone.arrow.up.right" : "phone.arrow.down.left"
    }
}

struct RecentRow: View {
    @ObservedObject private var directory = PhoneContactDirectory.shared
    let call: CallRecord
    /// The (i) button; Recents shows it, a contact's own call list does not.
    var showDetails: (() -> Void)?

    var body: some View {
        HStack(spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: call.symbol)
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .frame(width: 18)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(directory.displayName(for: call.number))
                        .font(.body)
                        .foregroundColor(call.isMissed ? .red : .primary)
                        .lineLimit(1)
                    HStack(spacing: 4) {
                        Text(call.kindText)
                        if let carrier = call.carrierText {
                            Text("·")
                            Text(carrier)
                        }
                    }
                    .font(.footnote)
                    .foregroundColor(.secondary)
                }
                Spacer()
                Text(RelativeDay.text(for: call.date))
                    .font(.footnote)
                    .foregroundColor(.secondary)
            }
            .accessibilityElement(children: .combine)
            if let showDetails {
                Button(action: showDetails) {
                    Image(systemName: "info.circle").font(.title3)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Ayrıntılar")
            }
        }
        .padding(.vertical, 2)
    }
}

/// One number from Recents: every call with it, and what can be done with the number.
struct CallDetailView: View {
    @ObservedObject private var history = HistoryStore.shared
    @ObservedObject private var directory = PhoneContactDirectory.shared
    let number: String
    let openMessages: (String) -> Void
    @State private var showingNewContact = false

    private var contact: PhoneContact? { directory.contact(for: number) }

    private var calls: [CallRecord] {
        let key = PhoneIdentity.key(number)
        return history.calls.filter { PhoneIdentity.key($0.number) == key }
    }

    var body: some View {
        List {
            Section {
                VStack(spacing: 10) {
                    ContactAvatar(name: contact?.name ?? "", thumbnail: contact?.thumbnail, size: 96)
                    Text(directory.displayName(for: number))
                        .font(.title2.weight(.semibold))
                        .multilineTextAlignment(.center)
                    if contact != nil {
                        Text(PhoneIdentity.formatted(number))
                            .font(.subheadline.monospacedDigit())
                            .foregroundColor(.secondary)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .listRowBackground(Color.clear)
            }
            if !number.isEmpty {
                Section {
                    Button { InAppDial.start(number) } label: { Label("Ara", systemImage: "phone.fill") }
                    Button { openMessages(number) } label: { Label("Mesaj gönder", systemImage: "message.fill") }
                    Button { UIPasteboard.general.string = number } label: {
                        Label("Numarayı kopyala", systemImage: "doc.on.doc")
                    }
                    if contact == nil {
                        Button { showingNewContact = true } label: {
                            Label("Yeni kişi oluştur", systemImage: "person.badge.plus")
                        }
                    }
                }
            }
            if !calls.isEmpty {
                Section(header: Text("Aramalar")) {
                    ForEach(calls) { call in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(call.date.formatted(date: .abbreviated, time: .shortened))
                                    .font(.body.monospacedDigit())
                                HStack(spacing: 4) {
                                    Text(call.kindText)
                                    if let carrier = call.carrierText {
                                        Text("·")
                                        Text(carrier)
                                    }
                                }
                                .font(.footnote)
                                .foregroundColor(call.isMissed ? .red : .secondary)
                            }
                            Spacer()
                            Image(systemName: call.symbol)
                                .font(.footnote)
                                .foregroundColor(.secondary)
                                .accessibilityHidden(true)
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showingNewContact) {
            NewContactView(number: number) { showingNewContact = false }
        }
    }
}

enum RelativeDay {
    static func text(for date: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) {
            return date.formatted(date: .omitted, time: .shortened)
        }
        if calendar.isDateInYesterday(date) { return "Dün" }
        if let days = calendar.dateComponents([.day], from: date, to: Date()).day, days < 7 {
            return date.formatted(.dateTime.weekday(.wide))
        }
        return date.formatted(date: .numeric, time: .omitted)
    }
}

// MARK: - Contacts

struct ContactsView: View {
    @ObservedObject private var directory = PhoneContactDirectory.shared
    let openSettings: () -> Void
    let openMessages: (String) -> Void
    @State private var query = ""

    private var filtered: [PhoneContact] { directory.matching(query) }

    private struct ContactSection {
        let letter: String
        let contacts: [PhoneContact]
    }

    private var sections: [ContactSection] {
        let groups = Dictionary(grouping: filtered) { contact -> String in
            guard let first = contact.name.first, first.isLetter else { return "#" }
            return String(first).uppercased(with: Locale(identifier: "tr_TR"))
        }
        return groups.keys.sorted { a, b in
            if a == "#" { return false }
            if b == "#" { return true }
            return a.compare(b, locale: Locale(identifier: "tr_TR")) == .orderedAscending
        }.map { ContactSection(letter: $0, contacts: groups[$0] ?? []) }
    }

    var body: some View {
        Group {
            if !directory.canRead {
                EmptyStateView(
                    symbol: "person.crop.circle.badge.exclamationmark",
                    title: "Kişilere erişim yok",
                    message: "Rehberindeki kişileri burada görmek ve aramak için Kişiler iznini aç.",
                    actionTitle: directory.authorization == .notDetermined ? "İzin ver" : "Ayarları aç",
                    action: {
                        if directory.authorization == .notDetermined {
                            directory.reload()
                        } else if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                )
            } else {
                List {
                    ForEach(sections, id: \.letter) { section in
                        Section(header: Text(section.letter)) {
                            ForEach(section.contacts) { contact in
                                NavigationLink {
                                    ContactDetailView(contact: contact, openMessages: openMessages)
                                } label: {
                                    HStack(spacing: 12) {
                                        ContactAvatar(name: contact.name, thumbnail: contact.thumbnail, size: 36)
                                        Text(contact.name)
                                    }
                                }
                            }
                        }
                    }
                }
                .listStyle(.plain)
                .searchable(text: $query, prompt: "Ad veya numara")
            }
        }
        .navigationTitle("Kişiler")
        .toolbar { SettingsToolbarButton(action: openSettings) }
        .onAppear { directory.ensureLoaded() }
    }
}

struct ContactDetailView: View {
    @ObservedObject private var directory = PhoneContactDirectory.shared
    @ObservedObject private var history = HistoryStore.shared
    let contact: PhoneContact
    let openMessages: (String) -> Void

    private var recentCalls: [CallRecord] {
        let keys = Set(contact.numbers.map(PhoneIdentity.key))
        return Array(history.calls.filter { keys.contains(PhoneIdentity.key($0.number)) }.prefix(5))
    }

    var body: some View {
        List {
            Section {
                VStack(spacing: 10) {
                    ContactAvatar(name: contact.name, thumbnail: contact.thumbnail, size: 96)
                    Text(contact.name).font(.title2.weight(.semibold)).multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .listRowBackground(Color.clear)
            }
            Section {
                ForEach(contact.numbers, id: \.self) { number in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(contact.label(for: number)).font(.footnote).foregroundColor(.secondary)
                            Text(PhoneIdentity.formatted(number)).font(.body.monospacedDigit())
                        }
                        Spacer()
                        Button { openMessages(number) } label: {
                            Image(systemName: "message.fill").frame(width: 36, height: 36)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Mesaj gönder")
                        Button { InAppDial.start(number) } label: {
                            Image(systemName: "phone.fill").frame(width: 36, height: 36)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Ara")
                    }
                    .contextMenu {
                        Button {
                            directory.toggleFavorite(number: number, name: contact.name)
                        } label: {
                            Label(directory.isFavorite(number) ? "Favorilerden çıkar" : "Favorilere ekle",
                                  systemImage: directory.isFavorite(number) ? "star.slash" : "star")
                        }
                        Button { UIPasteboard.general.string = number } label: {
                            Label("Numarayı kopyala", systemImage: "doc.on.doc")
                        }
                    }
                }
            }
            if let first = contact.numbers.first {
                Section {
                    Button {
                        directory.toggleFavorite(number: first, name: contact.name)
                    } label: {
                        Label(directory.isFavorite(first) ? "Favorilerden çıkar" : "Favorilere ekle",
                              systemImage: directory.isFavorite(first) ? "star.fill" : "star")
                    }
                }
            }
            if !recentCalls.isEmpty {
                Section(header: Text("Son aramalar")) {
                    ForEach(recentCalls) { RecentRow(call: $0) }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Keypad

struct KeypadView: View {
    @ObservedObject private var connection = GatewayConnection.shared
    @ObservedObject private var directory = PhoneContactDirectory.shared
    @ObservedObject private var history = HistoryStore.shared
    let openSettings: () -> Void
    @State private var number = ""
    /// When holding 0 typed "+"; the release of that same touch must not add a 0 as well.
    @State private var plusTypedAt: Date?

    private struct Key: Hashable {
        let digit: String
        let letters: String
    }

    private let keys: [Key] = [
        Key(digit: "1", letters: ""), Key(digit: "2", letters: "ABC"), Key(digit: "3", letters: "DEF"),
        Key(digit: "4", letters: "GHI"), Key(digit: "5", letters: "JKL"), Key(digit: "6", letters: "MNO"),
        Key(digit: "7", letters: "PQRS"), Key(digit: "8", letters: "TUV"), Key(digit: "9", letters: "WXYZ"),
        Key(digit: "*", letters: ""), Key(digit: "0", letters: "+"), Key(digit: "#", letters: "")
    ]

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 8)
            VStack(spacing: 6) {
                Text(number.isEmpty ? " " : PhoneIdentity.typing(number))
                    .font(.system(size: 34, weight: .regular, design: .default).monospacedDigit())
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .padding(.horizontal, 24)
                    .contextMenu {
                        Button {
                            if let pasted = UIPasteboard.general.string {
                                number = pasted.filter { "0123456789*#+".contains($0) }
                            }
                        } label: { Label("Yapıştır", systemImage: "doc.on.clipboard") }
                        if !number.isEmpty {
                            Button { UIPasteboard.general.string = number } label: { Label("Kopyala", systemImage: "doc.on.doc") }
                        }
                    }
                if let name = matchName {
                    Text(name)
                        .font(.subheadline)
                        .foregroundColor(.accentColor)
                        .lineLimit(1)
                } else if let match = suggestion {
                    // A contact whose number starts with the digits so far; a tap fills it in.
                    Button {
                        number = match.number.filter { "0123456789*#+".contains($0) }
                    } label: {
                        Text("\(match.name) · \(PhoneIdentity.formatted(match.number))")
                            .font(.subheadline)
                            .lineLimit(1)
                    }
                    .padding(.horizontal, 24)
                    .accessibilityHint("Numarayı doldurur")
                } else {
                    Text(" ").font(.subheadline)
                }
                if let error = connection.lastCallError {
                    Text(error)
                        .font(.footnote)
                        .foregroundColor(.red)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                }
            }
            .frame(minHeight: 96)
            Spacer(minLength: 8)
            LazyVGrid(columns: Array(repeating: GridItem(.fixed(84), spacing: 24), count: 3), spacing: 16) {
                ForEach(keys, id: \.self) { key in
                    KeypadButton(digit: key.digit, letters: key.letters) {
                        typed(key.digit)
                    } onLongPress: {
                        if key.digit == "0" {
                            number.append("+")
                            plusTypedAt = Date()
                        }
                    }
                }
            }
            HStack {
                Color.clear.frame(width: 84, height: 84)
                Spacer()
                Button(action: call) {
                    Image(systemName: "phone.fill")
                        .font(.system(size: 30))
                        .foregroundColor(.white)
                        .frame(width: 76, height: 76)
                        .background(Circle().fill(canCall ? Color.green : Color.green.opacity(0.4)))
                }
                .disabled(!canCall)
                .accessibilityLabel("Ara")
                Spacer()
                Button {
                    if !number.isEmpty { number.removeLast() }
                } label: {
                    Image(systemName: "delete.left")
                        .font(.title2)
                        .foregroundColor(.secondary)
                        .frame(width: 84, height: 84)
                }
                .opacity(number.isEmpty ? 0 : 1)
                .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in number = "" })
                .accessibilityLabel("Sil")
            }
            .frame(width: 84 * 3 + 48)
            .padding(.top, 16)
            Spacer(minLength: 16)
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { SettingsToolbarButton(action: openSettings) }
        .onAppear { directory.ensureLoaded() }
    }

    private var matchName: String? {
        number.count >= 3 ? directory.name(for: number) : nil
    }

    private var suggestion: (name: String, number: String)? {
        directory.suggestion(forTyped: number)
    }

    /// Never tied to the link (CALL-04): a call typed while the link wakes up waits for it, and
    /// an emergency number needs no Redmi at all. An empty field offers the last dialled number.
    private var canCall: Bool {
        if number.isEmpty { return history.lastDialledNumber != nil }
        return number.filter { $0.isNumber }.count >= 2
    }

    private func call() {
        if number.isEmpty {
            // As on Apple's keypad: the first tap brings back the last number, the second calls it.
            if let last = history.lastDialledNumber {
                number = last.filter { "0123456789*#+".contains($0) }
            }
            return
        }
        InAppDial.start(number, showsErrorItself: true)
    }

    private func typed(_ digit: String) {
        if digit == "0", let at = plusTypedAt, Date().timeIntervalSince(at) < 1.5 {
            // The release after holding 0 for "+"; a 0 never follows a + in a phone number.
            plusTypedAt = nil
            return
        }
        plusTypedAt = nil
        number.append(digit)
        playKeyTone(digit)
    }

    /// Apple's keypad tones, which follow the ring/silent switch. Never during a call: the call's
    /// audio session owns the output then.
    private func playKeyTone(_ digit: String) {
        guard connection.currentCall == nil, !CallKitManager.shared.hasCalls else { return }
        let sound: SystemSoundID
        switch digit {
        case "*": sound = 1210
        case "#": sound = 1211
        default:
            guard let value = UInt32(digit) else { return }
            sound = 1200 + value
        }
        AudioServicesPlaySystemSound(sound)
    }
}

struct KeypadButton: View {
    let digit: String
    let letters: String
    let action: () -> Void
    var onLongPress: (() -> Void)?

    var body: some View {
        Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            action()
        } label: {
            VStack(spacing: 0) {
                Text(digit).font(.system(size: 34, weight: .regular))
                Text(letters.isEmpty ? " " : letters)
                    .font(.system(size: 10, weight: .semibold))
                    .kerning(1.5)
                    .foregroundColor(.secondary)
            }
            .foregroundColor(.primary)
            .frame(width: 80, height: 80)
            .background(Circle().fill(Color(UIColor.secondarySystemFill)))
        }
        .buttonStyle(KeypadPressStyle())
        .simultaneousGesture(LongPressGesture(minimumDuration: 0.6).onEnded { _ in onLongPress?() })
        .accessibilityLabel(digit)
    }
}

struct KeypadPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.opacity(configuration.isPressed ? 0.55 : 1)
    }
}

struct EmptyStateView: View {
    let symbol: String
    let title: String
    let message: String
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 44, weight: .light))
                .foregroundColor(.secondary)
            Text(title).font(.title3.weight(.semibold))
            Text(message)
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 4)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
