import SwiftUI

enum AppTab: Int {
    case favorites, recents, contacts, keypad, messages
}

struct ContentView: View {
    @ObservedObject private var connection = GatewayConnection.shared
    @ObservedObject private var history = HistoryStore.shared
    @AppStorage("selected_tab") private var selectedTabRaw = AppTab.keypad.rawValue
    @State private var showingSettings = false
    @State private var callMinimized = false
    @State private var messageTarget: String?

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
                if connection.currentCall != nil && callMinimized {
                    ReturnToCallBar { callMinimized = false }
                }
                ConnectionBanner(openSettings: openSettings)
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

    private func openMessages(_ number: String) {
        messageTarget = number
        selectedTabRaw = AppTab.messages.rawValue
    }
}

/// Only visible when the link is not ready; everyday use never sees technical detail.
struct ConnectionBanner: View {
    @ObservedObject private var connection = GatewayConnection.shared
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
        case .searching: return "Redmi aranıyor…"
        case .connecting: return "Redmi'ye bağlanıyor…"
        case .offline(let reason): return reason
        case .incompatible(let reason): return reason
        }
    }

    private var isWorking: Bool {
        switch connection.link {
        case .pairing, .searching, .connecting: return true
        default: return false
        }
    }

    private var icon: String {
        if case .incompatible = connection.link { return "exclamationmark.triangle.fill" }
        return "wifi.exclamationmark"
    }

    private var tint: Color {
        if case .incompatible = connection.link { return .red }
        return isWorking ? .secondary : .orange
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
                            CallKitManager.shared.startOutgoingCall(phoneNumber: favorite.number)
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
            if !directory.favorites.isEmpty {
                ToolbarItem(placement: .navigationBarLeading) { EditButton() }
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
    let openSettings: () -> Void
    let openMessages: (String) -> Void
    @State private var missedOnly = false

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
                    RecentRow(call: call)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            guard !call.number.isEmpty else { return }
                            CallKitManager.shared.startOutgoingCall(phoneNumber: call.number)
                        }
                        .swipeActions(edge: .leading) {
                            if !call.number.isEmpty {
                                Button { openMessages(call.number) } label: { Label("Mesaj", systemImage: "message.fill") }
                                    .tint(.blue)
                            }
                        }
                }
                .listStyle(.plain)
                .refreshable { GatewayConnection.shared.requestSync() }
            }
        }
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
        .onAppear {
            directory.ensureLoaded()
            history.markMissedSeen()
        }
    }
}

struct RecentRow: View {
    @ObservedObject private var directory = PhoneContactDirectory.shared
    let call: CallRecord

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
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
                    Text(kindText)
                    if let carrier = carrierText {
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
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }

    private var symbol: String {
        switch call.type {
        case "outgoing": return "phone.arrow.up.right"
        case "missed", "declined", "blocked": return "phone.arrow.down.left"
        default: return "phone.arrow.down.left"
        }
    }

    private var kindText: String {
        switch call.type {
        case "outgoing": return "Giden"
        case "incoming": return call.duration > 0 ? "Gelen · \(CallTimerText.format(TimeInterval(call.duration)))" : "Gelen"
        case "missed": return "Cevapsız"
        case "declined": return "Reddedildi"
        case "blocked": return "Engellendi"
        case "voicemail": return "Sesli mesaj"
        default: return "Arama"
        }
    }

    private var carrierText: String? {
        switch call.answeredOn {
        case "iphone": return "iPhone'da"
        case "redmi": return "Redmi'de"
        default: return nil
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

    private var filtered: [PhoneContact] {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return directory.contacts }
        let digits = text.filter { $0.isNumber }
        return directory.contacts.filter { contact in
            contact.name.localizedCaseInsensitiveContains(text)
                || (!digits.isEmpty && contact.numbers.contains { $0.filter { $0.isNumber }.contains(digits) })
        }
    }

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
                            Text("Telefon").font(.footnote).foregroundColor(.secondary)
                            Text(PhoneIdentity.formatted(number)).font(.body.monospacedDigit())
                        }
                        Spacer()
                        Button { openMessages(number) } label: {
                            Image(systemName: "message.fill").frame(width: 36, height: 36)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Mesaj gönder")
                        Button { CallKitManager.shared.startOutgoingCall(phoneNumber: number) } label: {
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
    let openSettings: () -> Void
    @State private var number = ""

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
                Text(number.isEmpty ? " " : number)
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
                Text(matchName ?? " ")
                    .font(.subheadline)
                    .foregroundColor(.accentColor)
                    .lineLimit(1)
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
                        number.append(key.digit)
                    } onLongPress: {
                        if key.digit == "0" { number.append("+") }
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

    private var canCall: Bool {
        connection.isAuthenticated && number.filter { $0.isNumber }.count >= 2
    }

    private func call() {
        CallKitManager.shared.startOutgoingCall(phoneNumber: number)
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
