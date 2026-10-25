import SwiftUI

/// The in-app call screen. It can be minimized; a bar at the top leads back while a call lasts.
struct CallScreen: View {
    @ObservedObject private var connection = GatewayConnection.shared
    @ObservedObject private var directory = PhoneContactDirectory.shared
    let minimize: () -> Void
    @State private var muted = false
    @State private var speaker = false
    @State private var showingKeypad = false
    @State private var showingAddCall = false

    var body: some View {
        if let call = connection.currentCall {
            content(call)
                .onChange(of: connection.audioLocation) { location in
                    if location != .iphone { speaker = false }
                    updateProximity(call: connection.currentCall)
                }
                .onChange(of: call.state) { _ in updateProximity(call: connection.currentCall) }
                .onAppear {
                    muted = AudioEngine.shared.isMuted
                    speaker = AudioEngine.shared.isSpeakerEnabled
                    updateProximity(call: call)
                }
                .onDisappear { UIDevice.current.isProximityMonitoringEnabled = false }
        } else {
            Color(UIColor.systemBackground).ignoresSafeArea()
        }
    }

    private func content(_ call: GSMCall) -> some View {
        let conference = isConference(call)
        let contact: PhoneContact? = conference ? nil : directory.contact(for: call.number)
        let other = otherCall(for: call)
        return VStack(spacing: 0) {
            HStack {
                Button(action: minimize) {
                    Image(systemName: "chevron.down")
                        .font(.title3.weight(.semibold))
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("Küçült")
                Spacer()
            }
            .padding(.horizontal, 8)

            VStack(spacing: 10) {
                // Smaller with a second call, so the card for it still fits a 4.7-inch screen.
                ContactAvatar(name: contact?.name ?? "", thumbnail: contact?.thumbnail, size: other == nil ? 104 : 72)
                    .padding(.bottom, 6)
                Text(conference ? "Konferans" : directory.displayName(for: call.number))
                    .font(.largeTitle.weight(.regular))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.6)
                statusLine(call)
                    .font(.title3)
                    .foregroundColor(.secondary)
                AudioLocationChip(location: connection.audioLocation, call: call)
                if let other = other {
                    OtherCallCard(name: other.isConference ? "Konferans" : directory.displayName(for: other.number ?? ""),
                                  state: other.state, merging: connection.callsMerging) {
                        connection.sendHangup(callId: other.callId)
                    }
                }
                if let waiting = connection.waitingCall {
                    WaitingCallBanner(name: directory.displayName(for: waiting.number))
                }
                if let notice = connection.audioNotice {
                    Text(notice)
                        .font(.footnote)
                        .foregroundColor(.orange)
                        .multilineTextAlignment(.center)
                }
                if let error = connection.lastCallError {
                    Text(error)
                        .font(.footnote)
                        .foregroundColor(.red)
                        .multilineTextAlignment(.center)
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 12)

            Spacer(minLength: 24)

            if call.state == .ringing && call.isIncoming {
                ringingControls(call)
            } else {
                activeControls(call)
            }
        }
        .padding(.bottom, 32)
        .background(Color(UIColor.systemBackground).ignoresSafeArea())
        .sheet(isPresented: $showingKeypad) {
            DtmfKeypad(callId: call.rawCallId)
        }
    }

    @ViewBuilder
    private func statusLine(_ call: GSMCall) -> some View {
        switch call.state {
        case .ringing: Text(call.isIncoming ? "Gelen arama" : "Çalıyor…")
        case .dialing, .connecting: Text("Aranıyor…")
        case .holding: Text("Beklemede")
        case .active:
            if let start = call.connectedAt { CallTimerText(start: start).monospacedDigit() } else { Text("Görüşmede") }
        default: Text("Sonlanıyor…")
        }
    }

    private func ringingControls(_ call: GSMCall) -> some View {
        HStack {
            RoundCallButton(symbol: "phone.down.fill", title: "Reddet", fill: .red) {
                connection.sendReject(callId: call.rawCallId)
            }
            Spacer()
            RoundCallButton(symbol: "phone.fill", title: "Yanıtla", fill: .green) {
                CallKitManager.shared.answerFromApp(callId: call.rawCallId)
            }
        }
        .padding(.horizontal, 56)
    }

    /// The Redmi lists the current call as a conference, or names it as one.
    private func isConference(_ call: GSMCall) -> Bool {
        if let item = connection.calls.first(where: { $0.callId == call.rawCallId }) { return item.isConference }
        return call.number.hasPrefix("Konferans")
    }

    /// The second call of a two-call session: on hold, or still being dialled. Conference
    /// participants and a waiting call are not one.
    private func otherCall(for call: GSMCall) -> GatewayCallInfo? {
        connection.calls.first { item in
            item.callId != call.rawCallId && !item.isWaiting && !item.isConferenceChild && item.state != "disconnecting"
        }
    }

    /// "Çağrı ekle" as in the iPhone's Phone app: one call in progress and nothing else going on.
    /// An older Redmi sends no calls list and refuses a second call, so the button stays off there.
    private func canAddCall(_ call: GSMCall) -> Bool {
        call.state == .active && otherCall(for: call) == nil && connection.waitingCall == nil
            && !connection.callsMerging && connection.isAuthenticated && !connection.calls.isEmpty
    }

    private func activeControls(_ call: GSMCall) -> some View {
        let onIPhone = connection.audioLocation == .iphone
        let other = otherCall(for: call)
        let pairStates = [call.state.rawValue, other?.state ?? ""]
        // Değiştir and Birleştir need one active and one held call, and wait out a merge (~20 s).
        let canSwapOrMerge = other != nil && pairStates.contains("active") && pairStates.contains("holding")
            && !connection.callsMerging && connection.isAuthenticated
        return VStack(spacing: 28) {
            HStack(spacing: 28) {
                ToggleCallButton(symbol: muted ? "mic.slash.fill" : "mic.fill", title: "Sessiz",
                                 isOn: muted, enabled: onIPhone) {
                    muted.toggle()
                    CallKitManager.shared.setMuted(muted)
                }
                ToggleCallButton(symbol: "circle.grid.3x3.fill", title: "Tuşlar",
                                 isOn: false, enabled: call.state == .active) {
                    showingKeypad = true
                }
                ToggleCallButton(symbol: speaker ? "speaker.wave.3.fill" : "speaker.fill", title: "Hoparlör",
                                 isOn: speaker, enabled: onIPhone) {
                    if AudioEngine.shared.setSpeakerEnabled(!speaker) { speaker.toggle() }
                    updateProximity(call: call)
                }
            }
            HStack(spacing: 28) {
                if connection.audioLocation == .redmi {
                    ToggleCallButton(symbol: "iphone", title: "iPhone'a al", isOn: false,
                                     enabled: call.state == .active && connection.isAuthenticated) {
                        CallKitManager.shared.takeCallOnIPhone()
                    }
                } else {
                    ToggleCallButton(symbol: "arrow.left.arrow.right", title: "Redmi'ye aktar", isOn: false,
                                     enabled: connection.isAuthenticated) {
                        CallKitManager.shared.handOffToRedmi()
                    }
                }
                if other != nil {
                    ToggleCallButton(symbol: "arrow.triangle.swap", title: "Değiştir", isOn: false,
                                     enabled: canSwapOrMerge) {
                        CallKitManager.shared.swapCalls()
                    }
                    ToggleCallButton(symbol: "arrow.triangle.merge", title: "Birleştir", isOn: false,
                                     enabled: canSwapOrMerge) {
                        CallKitManager.shared.mergeCalls()
                    }
                } else {
                    ToggleCallButton(symbol: "plus", title: "Çağrı ekle", isOn: false,
                                     enabled: canAddCall(call)) {
                        connection.lastCallError = nil
                        showingAddCall = true
                    }
                }
            }
            RoundCallButton(symbol: "phone.down.fill", title: "Bitir", fill: .red) {
                connection.sendHangup(callId: call.rawCallId)
            }
        }
        .padding(.horizontal, 24)
        .sheet(isPresented: $showingAddCall) {
            AddCallSheet()
        }
    }

    private func updateProximity(call: GSMCall?) {
        UIDevice.current.isProximityMonitoringEnabled =
            call?.state == .active && connection.audioLocation == .iphone && !speaker
    }
}

/// A second call ringing during this one, with a normal phone's three choices: decline it, put this
/// call on hold and answer it, or end this call and answer it.
private struct WaitingCallBanner: View {
    let name: String

    var body: some View {
        VStack(spacing: 10) {
            Text("Bekleyen arama: \(name)")
                .font(.subheadline.weight(.semibold))
                .multilineTextAlignment(.center)
            // Stacked, not in one row: three labels do not fit side by side at 375 pt or with a
            // large text size.
            Button { CallKitManager.shared.answerWaitingHoldingCurrent() } label: {
                Text("Beklet ve yanıtla").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            HStack(spacing: 10) {
                Button { CallKitManager.shared.declineWaiting() } label: {
                    Text("Reddet").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .tint(.red)
                Button { CallKitManager.shared.answerWaitingEndingCurrent() } label: {
                    Text("Kapat ve yanıtla").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color(UIColor.secondarySystemFill)))
    }
}

/// The other call of a two-call session (on hold, or still being dialled), with its own end button.
private struct OtherCallCard: View {
    let name: String
    let state: String
    let merging: Bool
    let onEnd: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text("\(statusText): \(name)")
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(.primary)
                    .fixedSize(horizontal: false, vertical: true)
                if merging {
                    HStack(spacing: 6) {
                        ProgressView()
                        Text("Birleştiriliyor… Bu 20 saniye kadar sürebilir.")
                            .font(.footnote)
                            .foregroundColor(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: onEnd) {
                Image(systemName: "phone.down.fill")
                    .font(.body.weight(.semibold))
                    .foregroundColor(.white)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(Color.red))
            }
            .buttonStyle(KeypadPressStyle())
            .accessibilityLabel("\(name) çağrısını bitir")
        }
        .padding(12)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color(UIColor.secondarySystemFill)))
    }

    private var statusText: String {
        switch state {
        case "holding": return "Beklemede"
        case "dialing", "connecting": return "Aranıyor"
        case "active": return "Görüşmede"
        case "ringing": return "Çalıyor"
        default: return "Sonlanıyor"
        }
    }
}

/// "Çağrı ekle": dials a second call while this one goes on hold, as in the iPhone's Phone app.
private struct AddCallSheet: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var connection = GatewayConnection.shared
    @ObservedObject private var directory = PhoneContactDirectory.shared
    @State private var number = ""
    @State private var showingPicker = false

    private let rows = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["*", "0", "#"]]
    private let letters: [String: String] = [
        "2": "ABC", "3": "DEF", "4": "GHI", "5": "JKL", "6": "MNO",
        "7": "PQRS", "8": "TUV", "9": "WXYZ", "0": "+"
    ]

    var body: some View {
        NavigationView {
            // Scrolls only when a large text size makes the pad taller than the sheet.
            ScrollView {
                VStack(spacing: 16) {
                    VStack(spacing: 4) {
                        Text(number.isEmpty ? " " : number)
                            .font(.largeTitle.monospacedDigit())
                            .lineLimit(1)
                            .minimumScaleFactor(0.5)
                            .truncationMode(.head)
                        Text(matchName ?? " ")
                            .font(.subheadline)
                            .foregroundColor(.accentColor)
                            .multilineTextAlignment(.center)
                        if let error = connection.lastCallError {
                            Text(error)
                                .font(.footnote)
                                .foregroundColor(.red)
                                .multilineTextAlignment(.center)
                        }
                    }
                    .padding(.horizontal)
                    ForEach(rows, id: \.self) { row in
                        HStack(spacing: 24) {
                            ForEach(row, id: \.self) { digit in
                                KeypadButton(digit: digit, letters: letters[digit] ?? "") {
                                    number.append(digit)
                                } onLongPress: {
                                    if digit == "0" { number.append("+") }
                                }
                            }
                        }
                    }
                    HStack(spacing: 24) {
                        Button { showingPicker = true } label: {
                            Image(systemName: "person.crop.circle")
                                .font(.title2)
                                .foregroundColor(.accentColor)
                                .frame(width: 80, height: 80)
                        }
                        .accessibilityLabel("Kişilerden seç")
                        Button(action: call) {
                            Image(systemName: "phone.fill")
                                .font(.system(size: 30))
                                .foregroundColor(.white)
                                .frame(width: 76, height: 76)
                                .background(Circle().fill(canCall ? Color.green : Color.green.opacity(0.4)))
                        }
                        .frame(width: 80, height: 80)
                        .disabled(!canCall)
                        .accessibilityLabel("Ara")
                        Button {
                            if !number.isEmpty { number.removeLast() }
                        } label: {
                            Image(systemName: "delete.left")
                                .font(.title2)
                                .foregroundColor(.secondary)
                                .frame(width: 80, height: 80)
                        }
                        .opacity(number.isEmpty ? 0 : 1)
                        .disabled(number.isEmpty)
                        .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in number = "" })
                        .accessibilityLabel("Sil")
                        .accessibilityHint("Basılı tutunca numaranın tamamını siler")
                    }
                }
                .padding(.vertical, 24)
                .frame(maxWidth: .infinity)
            }
            .navigationTitle("Çağrı ekle")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { Button("Vazgeç") { dismiss() } }
            }
            // Not a nested .sheet: the picker's own dismissal closed this sheet with it.
            .background(
                ContactPickerPresenter(isPresented: $showingPicker) { picked in
                    number = picked.filter { "0123456789*#+".contains($0) }
                }
            )
        }
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
        // A dial that cannot go out now is refused at once with lastCallError; the sheet then stays
        // open, so the reason shows next to the number.
        if connection.lastCallError == nil { dismiss() }
    }
}

private struct AudioLocationChip: View {
    let location: AudioLocation
    let call: GSMCall

    var body: some View {
        if call.state != .ringing {
            Label(text, systemImage: symbol)
                .font(.footnote.weight(.medium))
                .foregroundColor(location == .iphone ? .green : .secondary)
                .padding(.horizontal, 12)
                .padding(.vertical, 5)
                .background(Capsule().fill(Color(UIColor.secondarySystemFill)))
                .accessibilityLabel("Ses: \(text)")
        }
    }

    private var text: String {
        switch location {
        case .iphone: return "Ses bu iPhone'da"
        case .movingToIPhone: return "Ses iPhone'a geçiyor…"
        case .redmi: return "Ses Redmi'de"
        }
    }

    private var symbol: String {
        switch location {
        case .iphone: return "ear"
        case .movingToIPhone: return "arrow.right.circle"
        case .redmi: return "iphone.homebutton"
        }
    }
}

private struct RoundCallButton: View {
    let symbol: String
    let title: String
    let fill: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: symbol)
                    .font(.system(size: 30))
                    .foregroundColor(.white)
                    .frame(width: 76, height: 76)
                    .background(Circle().fill(fill))
                Text(title).font(.footnote).foregroundColor(.primary)
            }
        }
        .buttonStyle(KeypadPressStyle())
        .accessibilityLabel(title)
    }
}

private struct ToggleCallButton: View {
    let symbol: String
    let title: String
    let isOn: Bool
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: symbol)
                    .font(.system(size: 26))
                    .foregroundColor(isOn ? Color(UIColor.systemBackground) : .primary)
                    .frame(width: 72, height: 72)
                    .background(Circle().fill(isOn ? Color.primary : Color(UIColor.secondarySystemFill)))
                Text(title).font(.footnote).foregroundColor(.primary).lineLimit(1).minimumScaleFactor(0.8)
            }
            .frame(width: 84)
        }
        .buttonStyle(KeypadPressStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.35)
        .accessibilityLabel(title)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}

private struct DtmfKeypad: View {
    @Environment(\.dismiss) private var dismiss
    let callId: String
    @State private var typed = ""

    private let rows = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["*", "0", "#"]]

    var body: some View {
        NavigationView {
            VStack(spacing: 16) {
                Text(typed.isEmpty ? " " : typed)
                    .font(.system(size: 30).monospacedDigit())
                    .lineLimit(1)
                    .truncationMode(.head)
                    .padding(.horizontal)
                ForEach(rows, id: \.self) { row in
                    HStack(spacing: 24) {
                        ForEach(row, id: \.self) { digit in
                            KeypadButton(digit: digit, letters: "") {
                                typed.append(digit)
                                GatewayConnection.shared.sendDtmf(digit: digit, callId: callId)
                            }
                        }
                    }
                }
                Spacer()
            }
            .padding(.top, 24)
            .navigationTitle("Tuşlar")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // Clears only the digits shown here. Every tone already went to the network the
                // moment its key was pressed, and a DTMF tone cannot be taken back.
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Temizle") { typed = "" }
                        .disabled(typed.isEmpty)
                        .accessibilityHint("Yalnızca ekrandaki rakamları siler; gönderilen tuşlar geri alınamaz")
                }
                ToolbarItem(placement: .navigationBarTrailing) { Button("Bitti") { dismiss() } }
            }
        }
    }
}
