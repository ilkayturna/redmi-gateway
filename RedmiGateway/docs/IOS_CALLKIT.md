# iOS CallKit Client Architecture & Integration Specification

**Target Platform:** iOS 14.0+ (SwiftUI, CallKit, AVFoundation)  
**Companion Host:** Xiaomi Redmi Note 8 (`ginkgo` / Android 11 / MIUI 12.5.2)  
**Protocol:** WebSocket (`ws://<gateway_ip>:8787`) with JSON RPC  

---

## 1. Overview & Architecture

The iOS Redmi Gateway client bridges incoming GSM/VoLTE cellular calls arriving at the Redmi Note 8 onto the iPhone as native system-level calls using Apple's **CallKit** framework (`CXProvider` and `CXCallController`).

```
Turkcell GSM Network
         ↓
Xiaomi Redmi Note 8 (Default Telecom Dialer)
         ↓
GatewayWebSocketServer (Port 8787 / Local SoftAP 192.168.207.0/24)
         ↓
iPhone GatewayConnection (WebSocket Client)
         ↓
CallKitManager (CXProvider / CXCallController)
         ↓
iOS Native In-Call UI (Full-screen lock screen / System banner)
```

---

## 2. CallKit Mapping: Android `callId` <-> iOS `UUID`

CallKit strictly requires a 128-bit `UUID` for every call transaction (`CXAction`, `reportNewIncomingCall`, `reportCall`). Android Telecom generates arbitrary string call identifiers or `UUID.randomUUID().toString()`.

### Mapping Strategy (`CallKitManager.swift`):
1. **Bidirectional Cache:**
   - `callIdToUuidMap: [String: UUID]`
   - `uuidToCallIdMap: [UUID: String]`
   - `callStates: [UUID: String]`
2. **Deterministic Lookup:**
   - When an event arrives with Android `callId`, `getOrCreateUuid(for:)` checks the cache. If `callId` is a valid RFC 4122 UUID string, it is directly converted to `UUID(uuidString:)`. Otherwise, a new `UUID` is allocated and bidirectionally linked.
3. **Deduplication:**
   - `reportedIncomingUuids: Set<UUID>` tracks reported calls. Repeated `ringing` events (e.g. from network retries or `state` queries) are discarded so CallKit is never prompted multiple times for the same incoming call.
4. **Lifecycle Cleanup:**
   - When a call enters `disconnected`, all mappings are evicted via `cleanupCall(uuid:rawId:)` and CallKit is notified via `reportCall(with:endedAt:reason: .remoteEnded)`.

---

## 3. Call Signaling Flows

### A. Incoming Call (Turkcell -> Redmi -> iPhone)
1. Redmi receives incoming call, Telecom fires `onCallAdded(call)` with state `RINGING`.
2. Redmi broadcasts:
   ```json
   {
     "type": "call",
     "state": "ringing",
     "number": "+905xxxxxxxxx",
     "callId": "c47b561e-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
   }
   ```
3. iPhone `GatewayConnection` decodes payload and invokes `CallKitManager.handleIncomingGatewayCallEvent`.
4. CallKit presents native iOS incoming call screen with caller number.
5. If user taps **Answer**:
   - `provider(_:perform: CXAnswerCallAction)` fires.
   - iPhone sends `{"type": "answer", "callId": "..."}`.
   - Redmi answers cellular call (`call.answer()`).
   - Redmi broadcasts `{"type": "call", "state": "active", ...}`.
   - CallKit marks call connected via `reportOutgoingCall(with:connectedAt:)`.
6. If user taps **Decline / End**:
   - `provider(_:perform: CXEndCallAction)` fires.
   - Because state is `ringing`, iPhone sends `{"type": "reject", "callId": "..."}`.
   - Redmi rejects cellular call (`call.reject()`).

### B. Outgoing Call (iPhone -> Redmi -> Turkcell)
1. User enters number in iPhone app and taps **Call**.
2. `CallKitManager.startOutgoingCall(phoneNumber:)` creates `CXStartCallAction`.
3. Action fulfills -> sends `{"type": "dial", "number": "+905..."}` to Redmi.
4. Redmi Telecom initiates call via `TelecomManager.placeCall()`.
5. State transitions (`connecting` -> `dialing` -> `active` -> `disconnected`) stream to iPhone and update CallKit.

### C. State Resynchronization (`get_state`)
If the iPhone disconnects and reconnects (e.g., Wi-Fi roam or app switch), it immediately sends:
```json
{"type": "get_state"}
```
Redmi responds with:
```json
{
  "type": "state",
  "call": {
    "callId": "...",
    "number": "+905...",
    "state": "active"
  }
}
```
Or if no call is active:
```json
{
  "type": "state",
  "call": null
}
```
The iPhone resyncs its UI and CallKit provider state without ghost calls.

---

## 4. Current Foreground Limitation & No Fake Background Sockets

In iOS, a standard `URLSessionWebSocketTask` suspended in the background will be paused by the OS within 30 seconds unless a background entitlement is active.

- **Current Prototype State:** The WebSocket signaling client is designed for active foreground operation or while the device is kept awake over the local hotspot.
- **Auto-Reconnect:** If the connection drops while the app is active, `GatewayConnection` automatically reconnects using exponential backoff (2s, 4s, 8s, 16s, max 30s) without aggressive CPU spinning.
- **Why no fake permanent background WebSocket:** Apple strictly terminates apps that keep sockets open using audio/location hacks. Fake backgrounding drains battery and violates App Store policies.

---

## 5. Production Background Architecture: PushKit + APNs + CallKit

To wake up the iPhone from deep sleep (screen off, pocket) when an incoming cellular call arrives at the Redmi:

```
[ Redmi Note 8: Incoming GSM Call ]
                ↓
[ Lightweight Cloud Relay / Gateway Server ]
                ↓ (Apple Push Notification service - APNs)
[ VoIP Push Notification: PushKit payload ]
                ↓ (Zero latency, wakes iOS kernel)
[ PKPushRegistry in iPhone Client ]
                ↓
[ reportNewIncomingCall to CallKit within 5s ]
                ↓
[ Native Ring Screen appears instantly on iPhone ]
```

### Key Requirements for PushKit Implementation:
1. **Apple Developer Account & VoIP Push Certificate:** APNs requires a dedicated VoIP Services Certificate.
2. **Mandatory CallKit Presentation:** iOS requires that every received VoIP push notification MUST immediately report a call to CallKit; otherwise, the OS terminates the app and revokes VoIP push permissions.
3. **Redmi Upstream Trigger:** The Redmi Gateway pushes a lightweight HTTP POST to the APNs relay when `Call.STATE_RINGING` occurs.
