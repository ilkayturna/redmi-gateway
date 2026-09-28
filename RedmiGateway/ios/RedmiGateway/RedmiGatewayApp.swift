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
        // Also relaunched by iOS for the call wake beacon, even after the app was swiped away.
        CallWakeMonitor.shared.refresh()
        return true
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
