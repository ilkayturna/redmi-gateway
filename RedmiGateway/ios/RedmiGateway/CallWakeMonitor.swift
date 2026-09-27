import CoreLocation
import Foundation
import UIKit

private let callWakeRegionIdentifier = "com.ilkay.redmigateway.call-wake"

/// Wakes the app for an incoming call even after the person swiped it away.
///
/// iOS does not relaunch a swiped-away app for Bluetooth, but it does for iBeacon region
/// monitoring. While a call rings and this iPhone has not yet shown it, the Redmi advertises
/// an iBeacon whose UUID is derived from the pairing key. Entering that region launches the
/// app in the background; it then connects to the Redmi, and the call arrives through
/// Bluetooth or the network as usual. Only proximity to the paired Redmi is observed; no
/// location is read, stored or sent.
@MainActor
final class CallWakeMonitor: NSObject, ObservableObject {
    static let shared = CallWakeMonitor()

    @Published private(set) var authorization: CLAuthorizationStatus = .notDetermined

    private let manager = CLLocationManager()
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    override private init() {
        super.init()
        manager.delegate = self
        authorization = manager.authorizationStatus
    }

    var isEnabled: Bool { authorization == .authorizedAlways }

    /// Monitors the beacon of the current pairing, or nothing when unpaired or not allowed.
    func refresh() {
        let wanted: UUID? = {
            guard let pairing = GatewayConnection.shared.pairing, authorization == .authorizedAlways,
                  CLLocationManager.isMonitoringAvailable(for: CLBeaconRegion.self) else { return nil }
            return Self.beaconUUID(for: pairing)
        }()
        for region in manager.monitoredRegions where region.identifier == callWakeRegionIdentifier {
            // Leave a correct region alone: a relaunch for its event must not lose that event.
            if let beacon = region as? CLBeaconRegion, beacon.uuid == wanted { return }
            manager.stopMonitoring(for: region)
        }
        guard let wanted else { return }
        let region = CLBeaconRegion(uuid: wanted, identifier: callWakeRegionIdentifier)
        region.notifyOnEntry = true
        region.notifyOnExit = true
        region.notifyEntryStateOnDisplay = true
        manager.startMonitoring(for: region)
        GatewayConnection.shared.log("Call wake monitoring on")
    }

    /// Asks for "Always" location access, or opens Settings when iOS no longer asks.
    func requestPermission() {
        switch authorization {
        case .notDetermined, .authorizedWhenInUse:
            manager.requestAlwaysAuthorization()
        default:
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
        }
    }

    static func beaconUUID(for pairing: GatewayPairing) -> UUID {
        let bytes = [UInt8](BridgeCrypto.derive(pairing.key, "beacon").prefix(16))
        return NSUUID(uuidBytes: bytes) as UUID
    }

    private func wake(_ reason: String) {
        GatewayConnection.shared.log("Call wake beacon: \(reason)")
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
        GatewayConnection.shared.reconnectNow(reason: "call wake")
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }
}

extension CallWakeMonitor: CLLocationManagerDelegate {
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            let monitor = CallWakeMonitor.shared
            monitor.authorization = status
            monitor.refresh()
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        guard region.identifier == callWakeRegionIdentifier else { return }
        Task { @MainActor in CallWakeMonitor.shared.wake("entered") }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didDetermineState state: CLRegionState, for region: CLRegion) {
        guard region.identifier == callWakeRegionIdentifier, state == .inside else { return }
        Task { @MainActor in CallWakeMonitor.shared.wake("inside") }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: Error) {
        let code = (error as NSError).code
        Task { @MainActor in GatewayConnection.shared.log("Call wake monitoring failed: \(code)") }
    }
}
