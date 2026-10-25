package com.ilkay.redmigateway.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object CallManager {
    private const val TAG = "CallManager"

    /**
     * How long an answer may be announced to the other phone before the cellular radio has to
     * confirm it (Telecom STATE_ACTIVE). A real answer is confirmed well inside a second; only a
     * failed one stays ringing, and then the announcement is taken back so both phones ring again
     * instead of the call going silent on both.
     *
     * 3 s, not 2: the 15.10.2026 log puts the radio's confirmation as late as ~2.2 s after the
     * answer, and taking the announcement back on a confirmation that is merely slow rings the
     * Redmi for a moment inside a live call before the real state arrives.
     */
    private const val ANSWER_CONFIRM_MS = 3_000L

    interface CallListener {
        fun onCallStateChanged(callId: String, state: String, number: String?)
        fun onLogMessage(message: String)
    }

    private val listeners = CopyOnWriteArrayList<CallListener>()

    private val calls = CopyOnWriteArrayList<Call>()
    private val callIds = ConcurrentHashMap<Call, String>()
    /** Whether each call last reported Wi-Fi calling; only changes are logged. */
    private val callOnWifi = ConcurrentHashMap<Call, Boolean>()
    private val callCallbacks = ConcurrentHashMap<Call, Call.Callback>()

    @Volatile var activeCall: Call? = null
        private set
    @Volatile var currentCallId: String? = null
        private set
    @Volatile var currentCallState: String = "idle"
        private set
    @Volatile var currentPhoneNumber: String? = null
        private set
    /** "incoming" or "outgoing" for the current call, fixed when Telecom adds it. */
    @Volatile var currentDirection: String = "outgoing"
        private set
    /** Wall-clock time the current call became active, or 0. */
    @Volatile var activeSinceMillis: Long = 0L
        private set
    /** Wall-clock creation time of the current call; the CallLog entry carries the same time. */
    @Volatile var addedAtMillis: Long = 0L
        private set

    /**
     * The call whose answer was announced to the other phone before Telecom confirmed it.
     *
     * Telecom reports STATE_ACTIVE only once the cellular network has set the call up, which the
     * 15.10.2026 log showed taking seconds (the iPhone kept ringing, and it kept its audio engine
     * idle, the whole time). Announcing the answer the moment it is executed silences the other
     * phone in one network round trip; [ANSWER_CONFIRM_MS] later the real state takes over again.
     *
     * Taking the announcement back is a full recovery, not just a state change: the ringing call
     * starts ringing here again ([RedmiRinger.resume]) and the audio session is released, because
     * nobody has answered yet and the iPhone does not ring again after its own answer tap.
     */
    @Volatile private var announcedAnswerCallId: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val answerConfirmTimeout = Runnable {
        val answeredId = announcedAnswerCallId ?: return@Runnable
        log("The radio did not confirm the answer within ${ANSWER_CONFIRM_MS} ms; showing the real state")
        announcedAnswerCallId = null
        IncomingCallPresenter.onAnswerNotConfirmed(answeredId)
        updatePrimaryCallAndNotify("answer_not_confirmed")
        if (currentCallState == "ringing" && currentCallId == answeredId) {
            // The call is still coming in and nobody has answered it. Without this the Redmi stays
            // silent: stop() also dropped its call reference and Telecom rings nothing itself.
            val call = getCall(answeredId)
            val silent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                call?.details?.extras?.getBoolean(Call.EXTRA_SILENT_RINGING_REQUESTED, false) == true
            val relay = com.ilkay.redmigateway.audio.CallAudioRelay
            if (relay.owner != com.ilkay.redmigateway.audio.CallAudioRelay.Owner.REDMI) {
                // Nobody owns the audio, so the iPhone must not keep its engine (and microphone)
                // open and the armed session must not sit there until its 90 s timeout.
                log("Releasing the call audio session: the answer was not confirmed")
                relay.disarm("answer_not_confirmed")
            }
            RedmiRinger.resume(answeredId, silent)
        }
    }

    /**
     * How long "no call is live" must hold before the call session is declared over.
     *
     * Measured on 25.10.2026 15:20:45 (IVR 532 twice, merged): the two merged calls left Telecom and
     * the conference call arrived 11 ms later. In that gap this object announced the end, the relay
     * gave the audio back to the Redmi, and the iPhone ended its call while the conference went on
     * on the Redmi - the "takıldı" the owner saw when calls were merged. A short wait costs nothing
     * when a call really ends; while this side has asked for a merge the network may take seconds.
     */
    private const val END_GRACE_MS = 700L
    private const val MERGE_GRACE_MS = 3_000L
    /** The merge measured that day took 21 s from the request to the conference. */
    private const val MERGE_WINDOW_MS = 45_000L
    /** When this side last asked for a merge; 0 once the conference exists. */
    @Volatile private var mergeRequestedAt = 0L
    private var endPending = false
    private val confirmEnd = Runnable { confirmEndNow() }
    private val resumeHeld = Runnable { resumeHeldNow() }

    private fun mergeInProgress() = mergeRequestedAt != 0L &&
        android.os.SystemClock.elapsedRealtime() - mergeRequestedAt < MERGE_WINDOW_MS

    /**
     * How long a call that Telecom adds already connected may wait for the call it replaces to end.
     *
     * Such a call was neither dialled nor answered here: it is how a network that moves our call
     * elsewhere shows up. When a friend merges this phone into their conference, the conference
     * server may send a new session that replaces ours; Telecom adds it as a new, already active
     * call and the old one ends. The iPhone knows only the old id: seen as a new call, it ended its
     * CallKit call and stopped the audio, and the conversation was left on the Redmi in the pocket.
     * So the new call stays out of the list for this long; if the other call ends meanwhile it goes
     * on under that call's id, otherwise it shows up as a call of its own.
     */
    private const val REPLACE_WINDOW_MS = 5_000L
    @Volatile private var replacing: Call? = null
    @Volatile private var replacedCall: Call? = null
    private var replacedId: String? = null
    private var replacedNumber: String? = null
    /** The number the iPhone already shows, kept for a call that took over another call's id. */
    private val keptNumbers = ConcurrentHashMap<Call, String>()
    /**
     * Calls that took over another call's id. Shown as active while Telecom still says dialling: the
     * iPhone had that id as a connected call, and "dialing" would restart its outgoing-call setup.
     */
    private val continuations: MutableSet<Call> = ConcurrentHashMap.newKeySet()
    /** Replacements of a call the person hung up, being ended; never shown. */
    private val discarded: MutableSet<Call> = ConcurrentHashMap.newKeySet()
    private val releaseReplacing = Runnable { releaseReplacingNow() }

    /**
     * The end grace for a call that ended "locally" although nothing here asked for it: when another
     * phone merges this one into its conference, our modem itself ends the old session (RFC 3891) and
     * the replacing call can arrive up to about a second later, past [END_GRACE_MS].
     */
    private const val REPLACE_GRACE_MS = 2_500L
    /** When this side last asked Telecom to end a call (hang up, or end and answer). */
    @Volatile private var hangupAskedAt = 0L
    /** The grace the pending end waits for. */
    private var endGraceMs = 0L

    /** The state the iPhone sees: a continuation is never shown as dialling again. */
    private fun visibleState(call: Call): String {
        val state = mapCallState(call.state)
        return if (call in continuations && (state == "dialing" || state == "connecting")) "active" else state
    }

    /** The call that just ended was ended by our own modem without a hangup asked here. */
    private fun endedWithoutHangupHere(call: Call?): Boolean {
        val cause = runCatching { call?.details?.disconnectCause }.getOrNull() ?: return false
        val asked = android.os.SystemClock.elapsedRealtime() - hangupAskedAt < 5_000L
        return !asked && (cause.code == android.telecom.DisconnectCause.LOCAL ||
            cause.reason?.contains("IMS_MERGED_SUCCESSFULLY") == true)
    }

    val isConference: Boolean
        get() = isConference(activeCall) || calls.any { isConference(it) && isCallLive(it) }

    fun isCallLive(): Boolean =
        currentCallState !in listOf("idle", "disconnected") || calls.any { isCallLive(it) }

    fun isConference(call: Call?): Boolean {
        if (call == null) return false
        val details = runCatching { call.details }.getOrNull()
        val hasConferenceProp = details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true
        val hasChildren = runCatching { call.children.isNotEmpty() }.getOrDefault(false)
        return hasConferenceProp || hasChildren
    }

    fun getConferenceParticipantSummary(call: Call? = activeCall): String? {
        val c = call ?: return null
        val children = runCatching { c.children }.getOrNull().orEmpty()
        if (children.isEmpty()) return null
        val numbers = children.mapNotNull { extractPhoneNumber(it) }.filter { it.isNotBlank() }
        return if (numbers.isNotEmpty()) numbers.joinToString(", ") else null
    }

    private fun getConferenceTitle(call: Call): String {
        val children = runCatching { call.children }.getOrNull().orEmpty()
        return if (children.isNotEmpty()) {
            "Konferans Görüşme (${children.size} kişi)"
        } else {
            "Konferans Görüşme"
        }
    }

    private fun isLiveState(state: Int): Boolean {
        return state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING
    }

    fun isCallLive(call: Call?): Boolean {
        return call != null && isLiveState(call.state)
    }

    /**
     * Call waiting: a second incoming call while one is in progress. It is tracked apart from the
     * current call, so every existing listener (audio relay, Bluetooth, notifications) keeps
     * seeing only the current call. It can be declined, or taken by ending the current call; it
     * then becomes the current call under the same id, so the iPhone's CallKit call continues.
     */
    interface WaitingListener {
        fun onWaitingCallChanged(callId: String?, number: String?)
    }

    @Volatile var waitingCallId: String? = null
        private set
    @Volatile var waitingNumber: String? = null
        private set
    private var waitingCall: Call? = null
    private var waitingCallback: Call.Callback? = null
    private var answerWaitingWhenFree = false
    private val waitingListeners = CopyOnWriteArrayList<WaitingListener>()

    fun addWaitingListener(listener: WaitingListener) {
        waitingListeners.addIfAbsent(listener)
        listener.onWaitingCallChanged(waitingCallId, waitingNumber)
    }

    fun removeWaitingListener(listener: WaitingListener) {
        waitingListeners.remove(listener)
    }

    private fun notifyWaitingChanged() {
        val id = waitingCallId
        val number = waitingNumber
        for (l in waitingListeners) runCatching { l.onWaitingCallChanged(id, number) }
    }

    private fun setWaiting(call: Call) {
        waitingCall = call
        val id = UUID.randomUUID().toString()
        waitingCallId = id
        callIds[call] = id
        waitingNumber = extractPhoneNumber(call)
        answerWaitingWhenFree = false
        val callback = object : Call.Callback() {
            override fun onDetailsChanged(c: Call, details: Call.Details) {
                if (waitingCall !== c) return
                val num = extractPhoneNumber(c)
                if (!num.isNullOrBlank() && num != waitingNumber) {
                    waitingNumber = num
                    notifyWaitingChanged()
                }
            }

            override fun onStateChanged(c: Call, state: Int) {
                if (waitingCall !== c) return
                if (state == Call.STATE_DISCONNECTED) {
                    log("Waiting call disconnected in onStateChanged")
                    clearWaiting()
                }
            }
        }
        waitingCallback = callback
        call.registerCallback(callback)
        log("Call waiting: ID=$waitingCallId")
        notifyWaitingChanged()
    }

    /** Clears the waiting call; returns it and its id when it should become the current call. */
    private fun clearWaiting(): Pair<Call, String>? {
        val call = waitingCall ?: return null
        val id = waitingCallId ?: return null
        waitingCallback?.let { runCatching { call.unregisterCallback(it) } }
        waitingCallback = null
        waitingCall = null
        waitingCallId = null
        waitingNumber = null
        RedmiRinger.onWaiting(false)
        notifyWaitingChanged()
        return call to id
    }

    fun addListener(listener: CallListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        // Immediately notify current state
        listener.onCallStateChanged(currentCallId ?: "none", currentCallState, currentPhoneNumber)
    }

    fun removeListener(listener: CallListener) {
        listeners.remove(listener)
    }

    fun log(msg: String) {
        Log.d(TAG, msg)
        com.ilkay.redmigateway.journal.DiagnosticsLog.record("Call", msg)
        for (l in listeners) {
            try {
                l.onLogMessage(msg)
            } catch (e: Exception) {
                Log.e(TAG, "Error in log listener", e)
            }
        }
    }

    fun onCallAdded(call: Call) {
        onCallAdded(call, null)
    }

    @Synchronized
    private fun onCallAdded(call: Call, reuseId: String?) {
        if (calls.contains(call)) {
            log("Call already tracked: ${callIds[call]}")
            return
        }

        // Added already connected, or as a Telecom "unknown" call (no direction; AOSP attaches it as
        // dialling first), not as a conference or a part of one, and not during a merge asked for
        // here: possibly a call replacing another one, see REPLACE_WINDOW_MS. Calls dialled here are
        // outgoing and calls answered here ring first, so neither is taken for one.
        val unknownDirection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            call.details?.callDirection == Call.Details.DIRECTION_UNKNOWN
        val arrivedConnected = call.state == Call.STATE_ACTIVE || call.state == Call.STATE_HOLDING ||
            (unknownDirection && (call.state == Call.STATE_DIALING || call.state == Call.STATE_CONNECTING))
        val unannounced = reuseId == null && arrivedConnected &&
            call.parent == null && !isConference(call) && !mergeInProgress()
        // The call it replaces has already gone and its end is still inside the grace: the same id,
        // so the iPhone never hears that it ended.
        val hungUpHere = android.os.SystemClock.elapsedRealtime() - hangupAskedAt < 5_000L
        val continuesId = if (unannounced && endPending && !hungUpHere) currentCallId else null
        val callId = reuseId ?: continuesId ?: UUID.randomUUID().toString()
        if (continuesId != null) {
            for (other in calls) if (callIds[other] == continuesId) callIds.remove(other)
            // A conference title would stick to a plain call.
            if (!isConference(activeCall)) currentPhoneNumber?.let { keptNumbers[call] = it }
            continuations.add(call)
        }
        callIds[call] = callId
        calls.add(call)

        val mappedState = mapCallState(call.state)
        val isConf = isConference(call)
        log("Call Added: ID=$callId, State=$mappedState, IsConference=$isConf, TotalCalls=${calls.size}" +
            (if (continuesId != null) " (added already connected; continues the call that just ended)" else ""))
        // The conference exists: the merge is done, and normal call ends need no long grace again.
        if (isConf) mergeRequestedAt = 0L

        val callback = object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) {
                val mapped = mapCallState(state)
                log("Call State Changed: ID=${callIds[c]}, State=$mapped")
                if (state == Call.STATE_DISCONNECTED) {
                    callOnWifi.remove(c)
                    // Why the call ended, from the radio's side. On 23.10.2026 a call dropped 35 s
                    // after the Redmi joined a Wi-Fi network mid-call, and only `dumpsys telecom`
                    // still held the reason (ImsReasonInfo 1513 CODE_NETWORK_DETACH); it is gone
                    // from there after a few dozen calls, so it is kept in the file as well.
                    runCatching { c.details.disconnectCause }.getOrNull()?.let { cause ->
                        log("Call ended: ID=${callIds[c]}, ${cause.toString().take(300)}")
                    }
                }
                if (c === waitingCall) {
                    if (state == Call.STATE_DISCONNECTED) {
                        clearWaiting()
                    }
                    return
                }
                if (mapped != "ringing") {
                    RedmiRinger.stop("call_state_$mapped")
                }
                updatePrimaryCallAndNotify("state_changed")
            }

            override fun onDetailsChanged(c: Call, details: Call.Details) {
                if (c === waitingCall) {
                    val num = extractPhoneNumber(c)
                    if (!num.isNullOrBlank() && num != waitingNumber) {
                        waitingNumber = num
                        notifyWaitingChanged()
                    }
                    return
                }
                // Which network carries the call, and when that changes: a move from the mobile
                // network to Wi-Fi calling mid-call is the moment the 23.10.2026 drop followed.
                // Only once the radio carries the call: while connecting the property is not set yet,
                // and after the end a last details update would log the call again.
                val carried = c.state in setOf(Call.STATE_DIALING, Call.STATE_RINGING, Call.STATE_ACTIVE, Call.STATE_HOLDING)
                val onWifi = details.hasProperty(Call.Details.PROPERTY_WIFI)
                val before = if (carried) callOnWifi.put(c, onWifi) else onWifi
                if (carried && before != onWifi) {
                    log("Call ID=${callIds[c]} " + when {
                        before == null -> if (onWifi) "runs over Wi-Fi calling" else "runs over the mobile network"
                        onWifi -> "moved to Wi-Fi calling"
                        else -> "moved to the mobile network"
                    })
                }
                updatePrimaryCallAndNotify("details_changed")
            }

            override fun onChildrenChanged(c: Call, children: MutableList<Call>) {
                log("onChildrenChanged on ID=${callIds[c]}, children count=${children.size}")
                for (child in children) {
                    if (!calls.contains(child)) {
                        onCallAdded(child)
                    }
                }
                updatePrimaryCallAndNotify("children_changed")
            }

            override fun onParentChanged(c: Call, parent: Call?) {
                log("onParentChanged on ID=${callIds[c]}, hasParent=${parent != null}")
                if (parent != null && !calls.contains(parent)) {
                    onCallAdded(parent)
                }
                updatePrimaryCallAndNotify("parent_changed")
            }

            override fun onConferenceableCallsChanged(c: Call, conferenceableCalls: MutableList<Call>) {
                log("onConferenceableCallsChanged on ID=${callIds[c]}, count=${conferenceableCalls.size}")
                updatePrimaryCallAndNotify("conferenceable_changed")
            }

            // Merges, remote holds and the like arrive as connection events. No merge with real
            // people has been seen yet, so each one is kept in the file (the name only, no extras).
            override fun onConnectionEvent(c: Call, event: String, extras: Bundle?) {
                log("Connection event on ID=${callIds[c]}: $event")
                if (event == android.telecom.Connection.EVENT_CALL_MERGE_FAILED && mergeInProgress()) {
                    // Otherwise the iPhone showed "Birleştiriliyor" and refused Değiştir for 45 s.
                    log("The network refused the merge; the calls stay as they were")
                    mergeRequestedAt = 0L
                    notifyStateChanged()
                }
            }
        }
        callCallbacks[call] = callback
        call.registerCallback(callback)

        if (unannounced && endPending && hungUpHere) {
            // The person just hung up, and the network's replacement for that call arrived inside
            // the end grace: it is the conversation the person left.
            log("Call $callId arrived connected right after a hangup here; ending it too")
            discarded.add(call)
            runCatching { call.disconnect() }
            return
        }
        if (unannounced && continuesId == null) {
            val others = calls.filter { it !== call && it !== waitingCall && isCallLive(it) }
            if (others.size == 1 && waitingCall == null && replacing == null) {
                val old = others[0]
                replacing = call
                replacedCall = old
                replacedId = callIds[old]
                replacedNumber = keptNumbers[old] ?: extractPhoneNumber(old)
                mainHandler.removeCallbacks(releaseReplacing)
                mainHandler.postDelayed(releaseReplacing, REPLACE_WINDOW_MS)
                log("Call $callId was added already connected while call $replacedId is live; " +
                    "kept out of the list for up to $REPLACE_WINDOW_MS ms in case it replaces that call")
                return
            }
        }

        // Check if there is already an active/connected call and this is a second incoming ringing call
        val hasActiveLiveCall = calls.any { it !== call && isCallLive(it) && it.state != Call.STATE_RINGING }
        if (call.state == Call.STATE_RINGING && hasActiveLiveCall && waitingCall == null) {
            setWaiting(call)
            RedmiRinger.onWaiting(true)
            return
        }

        updatePrimaryCallAndNotify("call_added")

        if (activeCall === call && call.state == Call.STATE_RINGING && currentDirection == "incoming") {
            val silent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                call.details?.extras?.getBoolean(Call.EXTRA_SILENT_RINGING_REQUESTED, false) == true
            RedmiRinger.onIncoming(callId, silent)
        }
    }

    @Synchronized
    fun onCallRemoved(call: Call) {
        val id = callIds[call]
        log("Call Removed from Telecom: ID=$id, State=${mapCallState(call.state)}, Total before=${calls.size}")

        if (waitingCall === call) {
            log("Waiting call ended")
            clearWaiting()
            answerWaitingWhenFree = false
            calls.remove(call)
            callIds.remove(call)
            callCallbacks.remove(call)?.let { cb -> runCatching { call.unregisterCallback(cb) } }
            return
        }

        callCallbacks.remove(call)?.let { cb ->
            runCatching { call.unregisterCallback(cb) }
        }
        calls.remove(call)
        callIds.remove(call)
        keptNumbers.remove(call)
        continuations.remove(call)
        discarded.remove(call)
        if (call === replacing) clearReplacing()

        updatePrimaryCallAndNotify("call_removed")
        // Telecom settles the remaining call first; a merge in flight is left alone.
        mainHandler.removeCallbacks(resumeHeld)
        mainHandler.postDelayed(resumeHeld, 800L)
    }

    /** One call as the iPhone sees it in a `calls` message (docs/PROTOCOL.md). */
    data class CallInfo(
        val callId: String,
        val state: String,
        val number: String?,
        val direction: String,
        val conference: Boolean,
        val parentId: String?,
        val waiting: Boolean,
    )

    /**
     * Every call Telecom holds right now, not only the primary one. The single primary call was all
     * the iPhone ever heard about, so a call on hold, the calls inside a conference and the moment a
     * merge replaced two calls with one were invisible to it.
     */
    @Synchronized
    fun snapshot(): List<CallInfo> = calls.mapNotNull { call ->
        if (call === replacing || call in discarded) return@mapNotNull null
        val id = callIds[call] ?: return@mapNotNull null
        val state = visibleState(call)
        if (state == "disconnected" || state == "idle") return@mapNotNull null
        val conf = isConference(call)
        val direction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            call.details?.callDirection == Call.Details.DIRECTION_INCOMING) "incoming" else "outgoing"
        CallInfo(
            callId = id,
            state = state,
            number = if (conf) getConferenceTitle(call) else keptNumbers[call] ?: extractPhoneNumber(call),
            direction = direction,
            conference = conf,
            parentId = call.parent?.let { callIds[it] },
            waiting = call === waitingCall,
        )
    }

    val isMerging: Boolean get() = mergeInProgress()

    private fun selectPrimaryCall(): Call? {
        val liveCalls = calls.filter { isCallLive(it) && it !== waitingCall && it !== replacing && it !in discarded }
        if (liveCalls.isEmpty()) return null

        // 1. Live conference call (parent)
        val conf = liveCalls.firstOrNull { isConference(it) }
        if (conf != null) return conf

        // 2. Active call
        val active = liveCalls.firstOrNull { it.state == Call.STATE_ACTIVE }
        if (active != null) return active

        // 3. Dialing or connecting call
        val dialing = liveCalls.firstOrNull { it.state == Call.STATE_DIALING || it.state == Call.STATE_CONNECTING }
        if (dialing != null) return dialing

        // 4. Ringing call (if not handled as waiting)
        val ringing = liveCalls.firstOrNull { it.state == Call.STATE_RINGING }
        if (ringing != null) return ringing

        // 5. Holding call
        val holding = liveCalls.firstOrNull { it.state == Call.STATE_HOLDING }
        if (holding != null) return holding

        // 6. First live call
        return liveCalls.firstOrNull()
    }

    /**
     * The call a possible replacement waited on has ended: the replacement goes on under that call's
     * id and with the number the iPhone already shows. Runs before every primary-call evaluation.
     */
    private fun adoptReplacementIfDue() {
        val candidate = replacing ?: return
        if (!isCallLive(candidate)) {
            clearReplacing()
            return
        }
        val old = replacedCall
        if (old != null && calls.contains(old) && isCallLive(old)) return
        if (android.os.SystemClock.elapsedRealtime() - hangupAskedAt < 5_000L) {
            // The person hung up the old call (hangup() ends the replacement with it); a replacement
            // that is somehow still there shows up as a call of its own instead of reviving the id.
            log("Call $replacedId was hung up here; call ${callIds[candidate]} does not take over its id")
            clearReplacing()
            return
        }
        val oldId = replacedId
        val number = replacedNumber
        clearReplacing()
        if (oldId == null) return
        val newId = callIds[candidate]
        // Only the replacement may answer to the old id now; a hangup must not find the ended call.
        if (old != null && callIds[old] == oldId) callIds.remove(old)
        callIds[candidate] = oldId
        number?.let { keptNumbers[candidate] = it }
        continuations.add(candidate)
        log("Call $oldId ended and call $newId, added already connected, replaces it; it goes on as $oldId")
    }

    private fun clearReplacing() {
        replacing = null
        replacedCall = null
        replacedId = null
        replacedNumber = null
        mainHandler.removeCallbacks(releaseReplacing)
    }

    @Synchronized
    private fun releaseReplacingNow() {
        val candidate = replacing ?: return
        log("Call ${callIds[candidate]} replaced no call within $REPLACE_WINDOW_MS ms; it is a call of its own")
        clearReplacing()
        updatePrimaryCallAndNotify("replacement_window_over")
    }

    @Synchronized
    private fun updatePrimaryCallAndNotify(reason: String) {
        adoptReplacementIfDue()
        val primary = selectPrimaryCall()
        if (primary != null && endPending) {
            mainHandler.removeCallbacks(confirmEnd)
            endPending = false
            log("A call replaced the ended one within the grace ($reason); the call goes on")
        }
        if (primary != null) {
            activeCall = primary
            currentCallId = callIds[primary] ?: UUID.randomUUID().toString().also { callIds[primary] = it }
            val realState = visibleState(primary)
            // An answer already announced to the other phone stands until Telecom confirms it. A
            // "ringing" event arriving in that window is stale: publishing it would restart the
            // ringer on the phone that just answered.
            val holdAnswerAnnouncement = realState == "ringing" && announcedAnswerCallId == currentCallId
            if (!holdAnswerAnnouncement) clearAnswerAnnouncement()
            currentCallState = if (holdAnswerAnnouncement) "active" else realState

            val isConf = isConference(primary)
            val number = if (isConf) {
                getConferenceTitle(primary)
            } else {
                keptNumbers[primary] ?: extractPhoneNumber(primary) ?: currentPhoneNumber
            }
            currentPhoneNumber = number

            if (primary.state == Call.STATE_RINGING ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    primary.details?.callDirection == Call.Details.DIRECTION_INCOMING)) {
                currentDirection = "incoming"
            } else if (!isConf && (currentDirection.isBlank() || primary.state == Call.STATE_DIALING || primary.state == Call.STATE_CONNECTING)) {
                currentDirection = "outgoing"
            }

            // activeSinceMillis only follows what the radio really confirmed: a merely announced
            // answer must not make a failed call look answered in the call journal.
            if (realState == "active" && activeSinceMillis == 0L) {
                activeSinceMillis = System.currentTimeMillis()
            }
            if (addedAtMillis == 0L) {
                addedAtMillis = primary.details?.creationTimeMillis?.takeIf { it > 0 } ?: System.currentTimeMillis()
            }

            log("Primary call evaluated ($reason): ID=$currentCallId, State=$currentCallState, Number=$currentPhoneNumber, Conf=$isConf")
            notifyStateChanged()
        } else {
            // No live calls remain - possibly only for the moment a merge swaps calls; see END_GRACE_MS.
            // A waiting call keeps the old immediate path: "end and answer" promotes it from here.
            if ((activeCall != null || currentCallState != "idle") && waitingCall == null) {
                if (!endPending) {
                    val grace = when {
                        mergeInProgress() -> MERGE_GRACE_MS
                        endedWithoutHangupHere(activeCall) -> REPLACE_GRACE_MS
                        else -> END_GRACE_MS
                    }
                    endPending = true
                    endGraceMs = grace
                    log("No live call ($reason); waiting $grace ms for a merged or replacing call before ending")
                    mainHandler.postDelayed(confirmEnd, grace)
                } else if (endGraceMs < REPLACE_GRACE_MS && endedWithoutHangupHere(activeCall)) {
                    // The disconnect cause arrives with DISCONNECTED, after the grace was chosen.
                    endGraceMs = REPLACE_GRACE_MS
                    mainHandler.removeCallbacks(confirmEnd)
                    mainHandler.postDelayed(confirmEnd, REPLACE_GRACE_MS)
                    log("The call ended without a hangup asked here; waiting $REPLACE_GRACE_MS ms for a replacing call")
                }
                return
            }
            finishCallSession(reason)
        }
    }

    @Synchronized
    private fun confirmEndNow() {
        if (!endPending) return
        endPending = false
        if (selectPrimaryCall() != null) {
            updatePrimaryCallAndNotify("end_cancelled")
            return
        }
        finishCallSession("end_confirmed")
    }

    /**
     * The other call ended and the one left is on hold: resume it. Holding was this phone's doing
     * (a second call was added), so the person expects to be back with the first caller; left on
     * hold, that caller heard hold music and the iPhone showed a call nobody could talk on.
     */
    @Synchronized
    private fun resumeHeldNow() {
        if (mergeInProgress()) return
        val held = calls.filter { isCallLive(it) && it !== waitingCall }.singleOrNull() ?: return
        if (held.state != Call.STATE_HOLDING) return
        log("The other call ended; resuming the call on hold: ID=${callIds[held]}")
        runCatching { held.unhold() }.onFailure { log("Could not resume the call on hold: ${it.message}") }
    }

    @Synchronized
    private fun finishCallSession(reason: String) {
        run {
            if (activeCall != null || currentCallState != "idle") {
                log("No live calls remain ($reason). Transitioning to disconnected then idle.")
                val finalId = currentCallId
                currentCallState = "disconnected"
                notifyStateChanged()

                if (finalId != null) {
                    IncomingCallPresenter.onCallEnded(finalId)
                    if (activeSinceMillis != 0L) {
                        val device = if (com.ilkay.redmigateway.audio.CallAudioRelay.carriedCallId == finalId) "iphone" else "redmi"
                        com.ilkay.redmigateway.journal.CallJournal.get(com.ilkay.redmigateway.RedmiGatewayApp.instance)
                            .noteAnsweredOn(addedAtMillis, device)
                    }
                }

                activeCall = null
                currentCallState = "idle"
                currentCallId = null
                currentPhoneNumber = null
                activeSinceMillis = 0L
                addedAtMillis = 0L
                clearAnswerAnnouncement()
                RedmiRinger.stop("call_ended")
                notifyStateChanged()
                promoteWaiting()
            }
        }
    }

    /**
     * "Beklet ve yanıtla": the waiting call is answered and the current one goes on hold, as on any
     * phone. Until 2.0.13 every answer of a waiting call ended the current call, so the iPhone's own
     * "Hold & Accept" button hung up on the first caller.
     *
     * Telecom holds the active call itself when another call is answered, but only a call that can be
     * held; one that cannot would be disconnected instead, so that case is refused here.
     */
    @Synchronized
    private fun answerWaitingHoldingCurrent(): Boolean {
        val current = activeCall
        if (current != null && isCallLive(current) &&
            (current.details?.callCapabilities?.and(Call.Details.CAPABILITY_HOLD) ?: 0) == 0) {
            log("Hold & accept refused: the current call cannot be held")
            return false
        }
        val (call, id) = clearWaiting() ?: return false
        log("Hold & accept: answering the waiting call $id; the current call goes on hold")
        return try {
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
            updatePrimaryCallAndNotify("hold_and_accept")
            true
        } catch (e: Exception) {
            log("Hold & accept failed: ${e.message}")
            false
        }
    }

    /** The current call ended while another was waiting: the waiting call becomes current. */
    private fun promoteWaiting() {
        val (call, id) = clearWaiting() ?: return
        val answerNow = answerWaitingWhenFree
        answerWaitingWhenFree = false
        if (call.state == Call.STATE_DISCONNECTED) {
            calls.remove(call)
            callIds.remove(call)
            callCallbacks.remove(call)?.let { cb -> runCatching { call.unregisterCallback(cb) } }
            return
        }
        log("Waiting call becomes the current call: ID=$id")
        updatePrimaryCallAndNotify("promote_waiting")
        if (answerNow && call.state == Call.STATE_RINGING) {
            answer(id)
        }
    }

    private fun notifyStateChanged() {
        val id = currentCallId ?: "none"
        val state = currentCallState
        val num = currentPhoneNumber
        for (l in listeners) {
            try {
                l.onCallStateChanged(id, state, num)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener", e)
            }
        }
    }

    fun answer(expectedCallId: String? = null, holdCurrent: Boolean = false): Boolean {
        if (expectedCallId != null && expectedCallId == waitingCallId && waitingCall != null && holdCurrent) {
            return answerWaitingHoldingCurrent()
        }
        if (expectedCallId != null && expectedCallId == waitingCallId && waitingCall != null) {
            // "End & Accept": end the current call; the waiting one is answered once it is current.
            log("Answering the waiting call after ending the current one")
            answerWaitingWhenFree = true
            val current = activeCall
            if (current == null) {
                promoteWaiting()
            } else if (current.state != Call.STATE_DISCONNECTED && current.state != Call.STATE_DISCONNECTING) {
                hangupAskedAt = android.os.SystemClock.elapsedRealtime()
                runCatching { current.disconnect() }
            }
            return true
        }
        var staleCallId = false
        var call = if (expectedCallId != null) {
            calls.firstOrNull { callIds[it] == expectedCallId } ?: activeCall
        } else {
            activeCall
        }
        if (call != null && expectedCallId != null &&
            expectedCallId != callIds[call] && expectedCallId != currentCallId) {
            // The iPhone can answer with an id this Redmi no longer uses: it keeps its own id for a
            // call the Redmi re-created, or falls back to the id-less "gateway_call" placeholder.
            // With exactly one ringing call there is nothing else the answer can mean, and refusing
            // it is what left the Redmi ringing after an answer on the iPhone.
            val ringing = calls.filter { it !== waitingCall && it.state == Call.STATE_RINGING }
            if (ringing.size == 1) {
                log("Answer carries the unknown call id $expectedCallId; taking the ringing call ${callIds[ringing[0]]}")
                staleCallId = true
                call = ringing[0]
            }
        }
        // The same answer can arrive twice: the command repeated, or the iPhone's Bluetooth retry
        // after a missing ack. Telecom keeps the call RINGING until the radio confirms it, so
        // without this the radio would be asked to answer twice and the confirmation window would
        // start over, delaying the recovery of a genuinely failed answer.
        if (call != null && announcedAnswerCallId != null && announcedAnswerCallId == callIds[call]) {
            log("Answer already sent for call ${callIds[call]}; ignoring the repeat")
            return true
        }
        // Whoever answered - this phone or the iPhone - the Redmi stops ringing now. Waiting for
        // the Telecom callback is what kept it ringing after an answer on the iPhone.
        if (calls.any { it !== waitingCall && it.state == Call.STATE_RINGING }) {
            RedmiRinger.stop(if (staleCallId) "peer_answered_stale_id" else "peer_answered")
        }
        if (call != null &&
            (staleCallId || expectedCallId == null || expectedCallId == callIds[call] || expectedCallId == currentCallId) &&
            call.state == Call.STATE_RINGING) {
            log("Executing ANSWER on call: ${callIds[call]}")
            return try {
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
                announceAnswer(callIds[call])
                true
            } catch (e: Exception) {
                log("Error answering call: ${e.message}")
                false
            }
        } else {
            log("Cannot answer: call ID or state does not match a ringing call")
            return false
        }
    }

    /**
     * Tells every listener - the iPhone over WebSocket or Bluetooth, the call screen, the
     * notification - that the call is active, without waiting for Telecom to confirm the cellular
     * setup. [ANSWER_CONFIRM_MS] later [updatePrimaryCallAndNotify] publishes what Telecom says.
     */
    @Synchronized
    private fun announceAnswer(callId: String?) {
        val id = callId ?: return
        if (id != currentCallId) return
        announcedAnswerCallId = id
        mainHandler.removeCallbacks(answerConfirmTimeout)
        mainHandler.postDelayed(answerConfirmTimeout, ANSWER_CONFIRM_MS)
        if (currentCallState == "active") return
        currentCallState = "active"
        log("Announced the answer to the other phone without waiting for Telecom")
        notifyStateChanged()
    }

    /** Drops a pending answer announcement; the state listeners see is Telecom's own again. */
    private fun clearAnswerAnnouncement() {
        if (announcedAnswerCallId == null) return
        announcedAnswerCallId = null
        mainHandler.removeCallbacks(answerConfirmTimeout)
    }

    fun reject(expectedCallId: String? = null): Boolean {
        if (expectedCallId != null && expectedCallId == waitingCallId) return rejectWaiting()
        val call = if (expectedCallId != null) {
            calls.firstOrNull { callIds[it] == expectedCallId } ?: activeCall
        } else {
            activeCall
        }
        if (call != null &&
            (expectedCallId == null || expectedCallId == callIds[call] || expectedCallId == currentCallId) &&
            call.state == Call.STATE_RINGING) {
            log("Executing REJECT on call: ${callIds[call]}")
            // A decline on the iPhone is as final as an answer: stop the Redmi ringer now instead
            // of waiting for Telecom to report the disconnected call.
            RedmiRinger.stop("peer_rejected")
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    call.reject(Call.REJECT_REASON_DECLINED)
                } else {
                    @Suppress("DEPRECATION")
                    call.reject(false, null)
                }
                true
            } catch (e: Exception) {
                log("Error rejecting call: ${e.message}")
                false
            }
        } else {
            log("Cannot reject: call ID or state does not match a ringing call")
            return false
        }
    }

    fun rejectWaiting(): Boolean {
        val call = waitingCall ?: return false
        log("Rejecting the waiting call")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                call.reject(Call.REJECT_REASON_DECLINED)
            } else {
                @Suppress("DEPRECATION")
                call.reject(false, null)
            }
            true
        } catch (e: Exception) {
            log("Error rejecting the waiting call: ${e.message}")
            false
        }
    }

    fun hangup(expectedCallId: String? = null): Boolean {
        if (expectedCallId != null && expectedCallId == waitingCallId) return rejectWaiting()
        val call = if (expectedCallId != null && expectedCallId != currentCallId && !expectedCallId.startsWith("outgoing_")) {
            calls.firstOrNull { callIds[it] == expectedCallId } ?: activeCall
        } else {
            activeCall
        }

        if (call != null && isCallLive(call)) {
            log("Executing HANGUP / DISCONNECT on call: ${callIds[call]} (conference=${isConference(call)})")
            hangupAskedAt = android.os.SystemClock.elapsedRealtime()
            // A call that may be replacing this one is still kept out of the list; the person hung up
            // the conversation, so it goes too.
            val replacement = replacing?.takeIf { call === replacedCall && isCallLive(it) }
            return try {
                call.disconnect()
                replacement?.let {
                    log("Also ending call ${callIds[it]}, which may have been replacing it")
                    runCatching { it.disconnect() }
                }
                if (isConference(call)) {
                    for (child in call.children.orEmpty()) {
                        if (isCallLive(child)) {
                            runCatching { child.disconnect() }
                        }
                    }
                }
                true
            } catch (e: Exception) {
                log("Error hanging up call: ${e.message}")
                false
            }
        } else if (calls.any { isCallLive(it) }) {
            log("Executing HANGUP on all remaining live calls (${calls.size})")
            hangupAskedAt = android.os.SystemClock.elapsedRealtime()
            var anyDisconnected = false
            for (c in calls) {
                if (isCallLive(c)) {
                    runCatching {
                        c.disconnect()
                        anyDisconnected = true
                    }
                }
            }
            return anyDisconnected
        } else {
            log("Cannot hangup: call ID or state does not match a current call")
            return false
        }
    }

    fun canMerge(): Boolean {
        val current = activeCall ?: return false
        val details = current.details
        val hasMergeCap = details != null && (details.callCapabilities and Call.Details.CAPABILITY_MERGE_CONFERENCE) != 0
        if (hasMergeCap) return true
        if (runCatching { current.conferenceableCalls.isNotEmpty() }.getOrDefault(false)) return true
        val otherLiveCalls = calls.filter { it !== current && isCallLive(it) }
        return otherLiveCalls.isNotEmpty()
    }

    fun merge(): Boolean {
        val current = activeCall ?: return false
        mergeRequestedAt = android.os.SystemClock.elapsedRealtime()
        notifyStateChanged()
        val details = current.details
        val hasMergeCap = details != null && (details.callCapabilities and Call.Details.CAPABILITY_MERGE_CONFERENCE) != 0
        if (hasMergeCap) {
            log("Merging conference via mergeConference()")
            runCatching { current.mergeConference() }
            return true
        }
        val confCall = runCatching { current.conferenceableCalls.firstOrNull() }.getOrNull()
        if (confCall != null) {
            log("Merging call with conferenceable call via conference()")
            runCatching { current.conference(confCall) }
            return true
        }
        val other = calls.firstOrNull { it !== current && isCallLive(it) }
        if (other != null) {
            log("Merging call with other live call via conference()")
            runCatching { current.conference(other) }
            return true
        }
        log("Cannot merge: no mergeable calls found")
        return false
    }

    fun getCall(callId: String): Call? {
        return callIds.entries.firstOrNull { it.value == callId }?.key
            ?: calls.firstOrNull { callIds[it] == callId }
            ?: if (callId == currentCallId) activeCall else if (callId == waitingCallId) waitingCall else null
    }

    fun swap(): Boolean {
        val active = calls.firstOrNull { it.state == Call.STATE_ACTIVE } ?: activeCall?.takeIf { it.state == Call.STATE_ACTIVE }
        val holding = calls.firstOrNull { it.state == Call.STATE_HOLDING }
        if (active != null && holding != null) {
            log("Swapping calls: holding active=${callIds[active]}, unholding holding=${callIds[holding]}")
            runCatching { active.hold() }
            runCatching { holding.unhold() }
            return true
        }
        log("Cannot swap calls: active=$active, holding=$holding")
        return false
    }

    fun playDtmf(digit: Char): Boolean {
        val call = activeCall
        if (call != null && (currentCallState == "active" || currentCallState == "dialing" || currentCallState == "connecting")) {
            return try {
                call.playDtmfTone(digit)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runCatching { call.stopDtmfTone() }
                }, 200L)
                log("Played DTMF tone: $digit")
                true
            } catch (e: Exception) {
                log("Error playing DTMF tone: ${e.message}")
                false
            }
        } else {
            log("Cannot play DTMF: no active call")
            return false
        }
    }

    fun maskPhoneNumber(number: String?): String {
        if (number.isNullOrBlank()) return "Unknown"
        val clean = number.trim()
        if (clean.length <= 4) return "***"
        val prefixLen = (clean.length / 3).coerceIn(1, 3)
        val suffixLen = (clean.length / 3).coerceIn(1, 2)
        val prefix = clean.substring(0, prefixLen)
        val suffix = clean.substring(clean.length - suffixLen)
        return "$prefix***$suffix"
    }

    fun dial(context: Context, number: String): Boolean {
        // Contacts commonly format numbers with spaces, parentheses and hyphens.
        val cleanNumber = number.trim().replace(Regex("[\\s().-]"), "")
        if (!cleanNumber.matches(Regex("^[0-9*#+]{3,20}$"))) {
            log("Cannot dial: invalid number format")
            return false
        }
        // One call in progress may get a second one ("Çağrı ekle"): Telecom puts the current call on
        // hold by itself (measured 25.10.2026 15:20:06). More than that, or a call still being set
        // up or ringing, is refused.
        val live = calls.filter { isCallLive(it) && it !== waitingCall }
        val busy = waitingCall != null || live.size > 1 || live.any {
            it.state == Call.STATE_RINGING || it.state == Call.STATE_DIALING || it.state == Call.STATE_CONNECTING
        }
        if (busy) {
            log("Cannot dial: ${live.size} call(s) already in progress")
            return false
        }
        if (live.size == 1) log("Adding a second call; the current one goes on hold")
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (telecomManager == null) {
            log("Cannot dial: TelecomManager unavailable")
            return false
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            log("Cannot dial: CALL_PHONE permission not granted")
            return false
        }

        log("Initiating outgoing GSM call for: ${maskPhoneNumber(cleanNumber)}")
        return try {
            val uri = Uri.fromParts("tel", cleanNumber, null)
            val extras = Bundle()
            telecomManager.placeCall(uri, extras)
            true
        } catch (e: Exception) {
            log("Exception in placeCall: ${e.message}")
            false
        }
    }

    private val INVALID_NUMBERS = setOf(
        "unknown", "private", "restricted", "unavailable", "anonymous", "null", "gizli", "bilinmeyen"
    )

    private fun cleanCandidateNumber(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        if (s.startsWith("sip:", ignoreCase = true)) s = s.substring(4)
        if (s.startsWith("tel:", ignoreCase = true)) s = s.substring(4)
        if (s.contains("@")) s = s.substringBefore("@")
        if (s.contains(";")) s = s.substringBefore(";")
        if (s.contains("?")) s = s.substringBefore("?")
        s = s.trim()
        if (s.isBlank() || s.lowercase() in INVALID_NUMBERS) return null
        return s
    }

    private fun extractFromBundle(bundle: Bundle?): String? {
        if (bundle == null) return null
        val keys = arrayOf(
            "android.telecom.extra.INCOMING_CALL_ADDRESS",
            "android.telecom.extra.INCOMING_NUMBER",
            "android.telephony.extra.INCOMING_NUMBER",
            "incoming_number",
            "cce_caller_id",
            "com.android.phone.extra.INCOMING_NUMBER"
        )
        for (key in keys) {
            val value = runCatching { bundle.get(key) }.getOrNull() ?: continue
            val raw = when (value) {
                is Uri -> value.schemeSpecificPart ?: value.toString()
                is String -> value
                else -> value.toString()
            }
            cleanCandidateNumber(raw)?.let { return it }
        }
        return null
    }

    private fun extractPhoneNumber(call: Call): String? {
        val details = call.details

        // 1. Check handle (and clean SIP URI if present)
        val handle = details?.handle
        if (handle != null) {
            val s = handle.schemeSpecificPart ?: handle.toString()
            cleanCandidateNumber(s)?.let { return it }
        }

        // 2. Check gatewayInfo originalAddress
        val gatewayAddress = details?.gatewayInfo?.originalAddress
        if (gatewayAddress != null) {
            val s = gatewayAddress.schemeSpecificPart ?: gatewayAddress.toString()
            cleanCandidateNumber(s)?.let { return it }
        }

        // 3. Check vendor extras from extras and intentExtras
        extractFromBundle(details?.extras)?.let { return it }
        extractFromBundle(details?.intentExtras)?.let { return it }

        // 4. Check callerDisplayName
        val callerName = cleanCandidateNumber(details?.callerDisplayName)
        if (callerName != null) {
            return callerName
        }

        return null
    }

    private fun mapCallState(state: Int): String {
        return when (state) {
            Call.STATE_RINGING -> "ringing"
            Call.STATE_DIALING -> "dialing"
            Call.STATE_CONNECTING -> "connecting"
            Call.STATE_ACTIVE -> "active"
            Call.STATE_HOLDING -> "holding"
            Call.STATE_DISCONNECTED -> "disconnected"
            Call.STATE_DISCONNECTING -> "disconnecting"
            Call.STATE_SELECT_PHONE_ACCOUNT -> "selecting_account"
            else -> "idle"
        }
    }
}
