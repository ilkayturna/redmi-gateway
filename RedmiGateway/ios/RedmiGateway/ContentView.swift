import SwiftUI

struct ContentView: View {
    @StateObject private var connection = GatewayConnection.shared
    
    @AppStorage("gateway_ip") private var gatewayIp: String = ""
    @AppStorage("gateway_port") private var gatewayPort: String = "8787"
    @AppStorage("pairing_token") private var pairingToken: String = ""
    
    @State private var outgoingNumber: String = ""
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 20) {
                    
                    // Connection Configuration Card
                    VStack(alignment: .leading, spacing: 14) {
                        Text("GATEWAY CONNECTION")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(.secondary)
                        
                        HStack {
                            Text("Gateway IP:")
                                .font(.system(size: 14, weight: .medium))
                                .frame(width: 100, alignment: .leading)
                            TextField("Redmi hotspot IP adresi", text: $gatewayIp)
                                .textFieldStyle(.roundedBorder)
                                .keyboardType(.numbersAndPunctuation)
                                .autocapitalization(.none)
                        }
                        
                        HStack {
                            Text("Port:")
                                .font(.system(size: 14, weight: .medium))
                                .frame(width: 100, alignment: .leading)
                            TextField("8787", text: $gatewayPort)
                                .textFieldStyle(.roundedBorder)
                                .keyboardType(.numberPad)
                        }
                        
                        HStack {
                            Text("Pairing Token:")
                                .font(.system(size: 14, weight: .medium))
                                .frame(width: 100, alignment: .leading)
                            TextField("6-char code", text: $pairingToken)
                                .textFieldStyle(.roundedBorder)
                                .autocapitalization(.allCharacters)
                                .font(.system(.body, design: .monospaced))
                        }
                        
                        HStack {
                            Text("Status:")
                                .font(.system(size: 14, weight: .medium))
                            Spacer()
                            Text(connection.state.rawValue)
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(statusColor)
                        }
                        .padding(.top, 4)
                        
                        if connection.state == .disconnected || connection.state == .failed {
                            Button(action: connectGateway) {
                                Text("CONNECT")
                                    .font(.system(size: 15, weight: .bold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 10)
                                    .background(Color.blue)
                                    .foregroundColor(.white)
                                    .cornerRadius(8)
                            }
                        } else {
                            HStack(spacing: 12) {
                                Button(action: syncState) {
                                    Text("SYNC STATE")
                                        .font(.system(size: 14, weight: .semibold))
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 10)
                                        .background(Color.orange)
                                        .foregroundColor(.white)
                                        .cornerRadius(8)
                                }
                                
                                Button(action: disconnectGateway) {
                                    Text("DISCONNECT")
                                        .font(.system(size: 14, weight: .bold))
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 10)
                                        .background(Color.red)
                                        .foregroundColor(.white)
                                        .cornerRadius(8)
                                }
                            }
                        }
                    }
                    .padding()
                    .background(Color(UIColor.secondarySystemBackground))
                    .cornerRadius(12)
                    
                    // Current GSM Call Card
                    VStack(alignment: .leading, spacing: 12) {
                        Text("CURRENT GSM CALL")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(.secondary)
                        
                        HStack {
                            Text("State:")
                                .font(.system(size: 14, weight: .medium))
                            Spacer()
                            Text(connection.currentCall?.state.rawValue.uppercased() ?? "IDLE")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(callStateColor)
                        }
                        
                        HStack {
                            Text("Number:")
                                .font(.system(size: 14, weight: .medium))
                            Spacer()
                            Text(connection.currentCall?.number.isEmpty == false ? connection.currentCall!.number : "None")
                                .font(.system(size: 14, design: .monospaced))
                        }
                    }
                    .padding()
                    .background(Color(UIColor.secondarySystemBackground))
                    .cornerRadius(12)
                    
                    // Outgoing Call Section
                    VStack(alignment: .leading, spacing: 12) {
                        Text("OUTGOING CELLULAR CALL")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(.secondary)
                        
                        HStack {
                            TextField("Phone number (e.g. +905...)", text: $outgoingNumber)
                                .textFieldStyle(.roundedBorder)
                                .keyboardType(.phonePad)
                            
                            Button(action: makeCall) {
                                Text("CALL")
                                    .font(.system(size: 14, weight: .bold))
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 8)
                                    .background(Color.green)
                                    .foregroundColor(.white)
                                    .cornerRadius(8)
                            }
                        }
                    }
                    .padding()
                    .background(Color(UIColor.secondarySystemBackground))
                    .cornerRadius(12)
                    
                    // Cellular SMS Navigation Card
                    NavigationLink(destination: MessagesView()) {
                        HStack {
                            Image(systemName: "message.fill")
                                .foregroundColor(.white)
                            Text("Cellular SMS Messages")
                                .font(.system(size: 15, weight: .bold))
                                .foregroundColor(.white)
                            Spacer()
                            if !connection.smsMessages.isEmpty {
                                Text("\(connection.smsMessages.count)")
                                    .font(.system(size: 12, weight: .bold))
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 2)
                                    .background(Color.white.opacity(0.3))
                                    .cornerRadius(10)
                                    .foregroundColor(.white)
                            }
                            Image(systemName: "chevron.right")
                                .foregroundColor(.white.opacity(0.7))
                        }
                        .padding()
                        .background(Color.blue)
                        .cornerRadius(12)
                    }
                    
                    // Event Logs Card
                    VStack(alignment: .leading, spacing: 8) {
                        Text("EVENT LOG")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(.secondary)
                        
                        ScrollView {
                            VStack(alignment: .leading, spacing: 4) {
                                ForEach(Array(connection.logs.enumerated()), id: \.offset) { _, log in
                                    Text(log)
                                        .font(.system(size: 11, design: .monospaced))
                                        .foregroundColor(Color.cyan)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                }
                            }
                        }
                        .frame(height: 120)
                        .padding(8)
                        .background(Color.black.opacity(0.85))
                        .cornerRadius(8)
                    }
                    .padding()
                    .background(Color(UIColor.secondarySystemBackground))
                    .cornerRadius(12)
                    
                }
                .padding()
            }
            .navigationTitle("Telefon Geçidi")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
    
    private var statusColor: Color {
        switch connection.state {
        case .authenticated: return .green
        case .connected: return .blue
        case .connecting: return .orange
        case .disconnected: return .gray
        case .failed: return .red
        }
    }
    
    private var callStateColor: Color {
        guard let call = connection.currentCall else { return .gray }
        switch call.state {
        case .ringing: return .orange
        case .active: return .green
        case .dialing, .connecting: return .blue
        default: return .gray
        }
    }
    
    private func connectGateway() {
        let portInt = Int(gatewayPort) ?? 8787
        connection.connect(
            host: gatewayIp.trimmingCharacters(in: .whitespaces),
            port: portInt,
            token: pairingToken.trimmingCharacters(in: .whitespaces)
        )
    }
    
    private func disconnectGateway() {
        connection.disconnect()
    }
    
    private func syncState() {
        connection.requestState()
    }
    
    private func makeCall() {
        let clean = outgoingNumber.trimmingCharacters(in: .whitespaces)
        guard !clean.isEmpty else { return }
        CallKitManager.shared.startOutgoingCall(phoneNumber: clean)
        outgoingNumber = ""
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
