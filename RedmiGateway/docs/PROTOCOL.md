# Redmi Gateway WebSocket Protocol (v1)

## Overview

The Redmi Gateway exposes a lightweight WebSocket server binding on `0.0.0.0:8787`.
Communication occurs over a local Wi-Fi or Redmi Hotspot connection. No external internet or cloud server is required.

All payloads are UTF-8 encoded JSON objects.

---

## Authentication & Handshake

Upon opening the WebSocket connection, the Android Gateway sends a greeting banner:

### Gateway Greeting (`hello`)
```json
{
  "type": "hello",
  "device": "Redmi Note 8",
  "protocol": 1
}
```

Clients must authenticate with the 6-character alphanumeric pairing token displayed on the Redmi Gateway screen before any call control commands will be processed.

### Client Authentication (`auth`)
```json
{
  "type": "auth",
  "token": "AB93KD"
}
```

### Gateway Auth Response
On success:
```json
{
  "type": "auth_result",
  "success": true,
  "message": "Authenticated successfully"
}
```
On failure:
```json
{
  "type": "auth_result",
  "success": false,
  "message": "Invalid pairing token"
}
```

---

## Call State Broadcasts (Android → Client)

Whenever Android Telecom reports a state change for a call, the Gateway broadcasts a `call` event to all authenticated clients.

### 1. Incoming Call (Ringing)
```json
{
  "type": "call",
  "state": "ringing",
  "number": "+905xxxxxxxxx",
  "callId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

### 2. Outgoing Call Initiated (Dialing / Connecting)
```json
{
  "type": "call",
  "state": "dialing",
  "number": "+905xxxxxxxxx",
  "callId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

### 3. Call Answered & Connected (Active)
```json
{
  "type": "call",
  "state": "active",
  "number": "+905xxxxxxxxx",
  "callId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

### 4. Call Put On Hold (Holding)
```json
{
  "type": "call",
  "state": "holding",
  "number": "+905xxxxxxxxx",
  "callId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

### 5. Call Terminated (Disconnected)
```json
{
  "type": "call",
  "state": "disconnected",
  "callId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

---

## Call Control Commands (Client → Android)

Only authenticated clients may issue call control commands. The Gateway validates input and fails gracefully if a call is not found.

### 1. Answer Incoming Call
```json
{
  "type": "answer",
  "callId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```
*Action:* Calls `activeCall.answer(VideoProfile.STATE_AUDIO_ONLY)`.

### 2. Reject Incoming Call
```json
{
  "type": "reject",
  "callId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```
*Action:* Calls `activeCall.reject(false, null)`.

### 3. Hang Up Active Call
```json
{
  "type": "hangup",
  "callId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```
*Action:* Calls `activeCall.disconnect()`.

### 4. Place Outgoing Call
```json
{
  "type": "dial",
  "number": "+905xxxxxxxxx"
}
```
*Action:* Calls `telecomManager.placeCall(Uri.parse("tel:" + number), Bundle())`.

---

## Error Handling
If an error occurs, the server responds with:
```json
{
  "type": "error",
  "message": "Description of error"
}
```
The server will never crash if a call has already ended or if the JSON payload is malformed.
