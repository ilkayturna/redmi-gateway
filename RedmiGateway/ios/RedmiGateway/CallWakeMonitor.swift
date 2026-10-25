import CoreLocation
import Foundation
import UIKit

private let callWakeRegionIdentifier = "com.ilkay.redmigateway.call-wake"
/// The Redmi cycles its beacon through these majors. iOS reports an entry only after it saw a
/// region empty, so one region per major means a new wake never waits for the last one to end.
private let callWakeMajors: [CLBeaconMajorValue] = [1, 2, 3, 4]

private func isCallWakeRegion(_ region: CLRegion) -> Bool {
    region.identifier == callWakeRegionIdentifier || region.identifier.hasPrefix(callWakeRegionIdentifier + ".")
}

/// Wakes the app for an incoming call, and brings back its Bluetooth link, even after the
/// person swiped it away.
///
/// iOS does not relaunch a swiped-away app for Bluetooth, but it does for iBeacon region
/// monitoring. The Redmi advertises an iBeacon whose UUID is derived from the pairing key while
/// a call rings that this iPhone has not yet shown, when an SMS or missed call arrives while this
/// iPhone has no link, and for a few seconds now and then until the link is back. Entering the
/// region launches the app in the background; it reconnects over Bluetooth, fetches new messages
/// and calls, and a ringing call arrives as usual. Only proximity to the paired Redmi is
/// observed; no location is read, stored or sent.
@MainActor
final class CallWakeMonitor: NSObject, ObservableObject {
    static let shared = CallWakeMonitor()

    @Published private(set) var authorization: CLAuthorizationStatus = .notDetermined
    @Published private(set) var accuracy: CLAccuracyAuthorization = .fullAccuracy

    private let manager = CLLocationManager()
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    /// iOS asks about "Always" only once after "While Using"; remembered so the button then goes
    /// straight to Settings.
    private let kAlwaysAsked = "call_wake_always_asked"
    /// When the app last lost the screen, for example to a permission question.
    private var resignedActiveAt: Date?

    override private init() {
        super.init()
        manager.delegate = self
        authorization = manager.authorizationStatus
        accuracy = manager.accuracyAuthorization
        NotificationCenter.default.addObserver(
            forName: UIApplication.willResignActiveNotification, object: nil, queue: .main
        ) { _ in
            Task { @MainActor in CallWakeMonitor.shared.resignedActiveAt = Date() }
        }
    }

    var isEnabled: Bool { authorization == .authorizedAlways }

    /// "Always" is granted but "Precise Location" is off. iOS does not monitor or range beacons
    /// for an app with approximate location, so the call wake is silently off although the
    /// permission reads as granted.
    var needsPreciseLocation: Bool { isEnabled && accuracy == .reducedAccuracy }

    /// Monitors the beacons of the current pairing, or nothing when unpaired or not allowed.
    func refresh() {
        // Before the first unlock after a restart the pairing cannot be read and looks absent.
        // Stopping the regions then would leave the app with nothing to relaunch it once the
        // iPhone is unlocked, so they stay as they are until the pairing is readable again.
        if GatewayConnection.shared.pairingLocked {
            GatewayConnection.shared.log("Call wake regions kept: pairing locked until the first unlock")
            return
        }
        let wanted: UUID? = {
            guard let pairing = GatewayConnection.shared.pairing, authorization == .authorizedAlways,
                  CLLocationManager.isMonitoringAvailable(for: CLBeaconRegion.self) else { return nil }
            return Self.beaconUUID(for: pairing)
        }()
        var present = Set<CLBeaconMajorValue>()
        for region in manager.monitoredRegions where isCallWakeRegion(region) {
            // Leave correct regions alone: a relaunch for their event must not lose that event.
            if let beacon = region as? CLBeaconRegion, let wanted, beacon.uuid == wanted,
               let major = beacon.major?.uint16Value, callWakeMajors.contains(major),
               beacon.identifier == "\(callWakeRegionIdentifier).\(major)" {
                present.insert(major)
            } else {
                manager.stopMonitoring(for: region)
            }
        }
        guard let wanted else { return }
        let missing = callWakeMajors.filter { !present.contains($0) }
        for major in missing {
            let region = CLBeaconRegion(uuid: wanted, major: major, identifier: "\(callWakeRegionIdentifier).\(major)")
            region.notifyOnEntry = true
            region.notifyOnExit = true
            region.notifyEntryStateOnDisplay = true
            manager.startMonitoring(for: region)
        }
        if !missing.isEmpty { GatewayConnection.shared.log("Call wake monitoring on (\(callWakeMajors.count) regions)") }
    }

    /// Asks for "Always" location access, or opens Settings when iOS no longer asks.
    ///
    /// After "While Using" iOS shows the "Always" question once; every later request does nothing
    /// at all, and the button looked broken. It now asks only when iOS can still show the question,
    /// and opens the app's Settings page when no question took the screen within a second (an
    /// earlier build may already have used the one chance).
    func requestPermission() {
        switch authorization {
        case .notDetermined:
            manager.requestAlwaysAuthorization()
        case .authorizedWhenInUse:
            let defaults = UserDefaults.standard
            guard !defaults.bool(forKey: kAlwaysAsked) else {
                openSettings()
                return
            }
            defaults.set(true, forKey: kAlwaysAsked)
            let askedAt = Date()
            manager.requestAlwaysAuthorization()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                Task { @MainActor in
                    let monitor = CallWakeMonitor.shared
                    if let resigned = monitor.resignedActiveAt, resigned >= askedAt { return }
                    guard monitor.authorization == .authorizedWhenInUse else { return }
                    monitor.openSettings()
                }
            }
        default:
            openSettings()
        }
    }

    /// This app's page in iOS Settings (location, precise location, notifications).
    func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }

    static func beaconUUID(for pairing: GatewayPairing) -> UUID {
        let bytes = [UInt8](BridgeCrypto.derive(pairing.key, "beacon").prefix(16))
        return NSUUID(uuidBytes: bytes) as UUID
    }

    private func wake(_ reason: String) {
        GatewayConnection.shared.noteWake(reason: "beacon \(reason)")
        GatewayConnection.shared.log("Call wake beacon: \(reason)")
        // While the app is alive, ranging notices the beacon in about a second where region
        // monitoring can take a minute; it is dropped as soon as the link is up, so the radio is
        // busy only during the wake itself.
        startRanging()
        if backgroundTask == .invalid {
            // Time to connect and show the call; CallKit keeps the app running after that.
            backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "incoming-call") {
                Task { @MainActor in CallWakeMonitor.shared.endBackgroundTask() }
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 25) {
                Task { @MainActor in CallWakeMonitor.shared.endBackgroundTask() }
            }
        }
        BLECentralManager.shared.start()
        GatewayConnection.shared.wakeFromBeacon()
    }

    /// Ranging is the fast path to a beacon entry; region monitoring is the one iOS still delivers
    /// while the app is suspended. Both run, and ranging is dropped once the socket is up.
    private func startRanging() {
        let wanted = GatewayConnection.shared.pairing.map(Self.beaconUUID(for:))
        for region in manager.monitoredRegions where isCallWakeRegion(region) {
            guard let beacon = region as? CLBeaconRegion else { continue }
            if let wanted, beacon.uuid == wanted,
               !manager.rangedRegions.contains(where: { $0.identifier == region.identifier }) {
                manager.startRangingBeacons(in: beacon)
            }
        }
    }

    /// Called when the link comes up: the wake is over, so the radio goes quiet again.
    func stopRanging() {
        for case let region as CLBeaconRegion in manager.rangedRegions where isCallWakeRegion(region) {
            manager.stopRangingBeacons(in: region)
        }
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }
}

extension CallWakeMonitor: CLLocationManagerDelegate {
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        // Called for a change of either the permission or its precision.
        let status = manager.authorizationStatus
        let accuracy = manager.accuracyAuthorization
        Task { @MainActor in
            let monitor = CallWakeMonitor.shared
            monitor.authorization = status
            monitor.accuracy = accuracy
            monitor.refresh()
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        guard isCallWakeRegion(region) else { return }
        Task { @MainActor in CallWakeMonitor.shared.wake("entered") }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didDetermineState state: CLRegionState, for region: CLRegion) {
        guard isCallWakeRegion(region), state == .inside else { return }
        Task { @MainActor in CallWakeMonitor.shared.wake("inside") }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: Error) {
        let code = (error as NSError).code
        Task { @MainActor in GatewayConnection.shared.log("Call wake monitoring failed: \(code)") }
    }
}
