import SwiftUI
import UIKit

@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // iOS may relaunch the app in the background for a Bluetooth event (state restoration).
        // The Bluetooth manager, the Redmi link and CallKit must exist before that event is
        // delivered, whether or not a window is ever shown.
        _ = BLECentralManager.shared
        _ = CallKitManager.shared
        _ = Notifier.shared
        GatewayConnection.shared.start()
        VoipPush.shared.start()
        // SMS and missed-call notifications through Apple use the ordinary notification token
        // (calls use the PushKit token). Registering needs no permission; showing needs it.
        application.registerForRemoteNotifications()
        // Also relaunched by iOS for the call wake beacon, even after the app was swiped away.
        CallWakeMonitor.shared.refresh()
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        GatewayConnection.shared.setAlertPushToken(deviceToken.map { String(format: "%02x", $0) }.joined())
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        GatewayConnection.shared.log("Notification registration failed: \((error as NSError).code)")
    }
}

@main
struct RedmiGatewayApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
