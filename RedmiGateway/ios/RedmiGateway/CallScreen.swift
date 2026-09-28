import SwiftUI

/// The in-app call screen. It can be minimized; a bar at the top leads back while a call lasts.
struct CallScreen: View {
    @ObservedObject private var connection = GatewayConnection.shared
    @ObservedObject private var directory = PhoneContactDirectory.shared
    let minimize: () -> Void
    @State private var muted = false
    @State private var speaker = false
    @State private var showingKeypad = false

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
        let contact = directory.contact(for: call.number)
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
                ContactAvatar(name: contact?.name ?? "", thumbnail: contact?.thumbnail, size: 104)
                    .padding(.bottom, 6)
                Text(directory.displayName(for: call.number))
                    .font(.largeTitle.weight(.regular))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.6)
                statusLine(call)
                    .font(.title3)
                    .foregroundColor(.secondary)
                AudioLocationChip(location: connection.audioLocation, call: call)
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

    private func activeControls(_ call: GSMCall) -> some View {
        let onIPhone = connection.audioLocation == .iphone
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
            }
            RoundCallButton(symbol: "phone.down.fill", title: "Bitir", fill: .red) {
                connection.sendHangup(callId: call.rawCallId)
            }
        }
        .padding(.horizontal, 24)
    }

    private func updateProximity(call: GSMCall?) {
        UIDevice.current.isProximityMonitoringEnabled =
            call?.state == .active && connection.audioLocation == .iphone && !speaker
    }
}

/// A second call ringing during this one: decline it, or end this call and answer it.
private struct WaitingCallBanner: View {
    let name: String

    var body: some View {
        VStack(spacing: 10) {
            Text("Bekleyen arama: \(name)")
                .font(.subheadline.weight(.semibold))
                .multilineTextAlignment(.center)
            HStack(spacing: 12) {
                Button("Reddet") { CallKitManager.shared.declineWaiting() }
                    .buttonStyle(.bordered)
                Button("Kapat ve yanıtla") { CallKitManager.shared.answerWaitingEndingCurrent() }
                    .buttonStyle(.borderedProminent)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color(UIColor.secondarySystemFill)))
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
                ToolbarItem(placement: .navigationBarTrailing) { Button("Bitti") { dismiss() } }
            }
        }
    }
}
