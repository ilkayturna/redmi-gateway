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

    /// A silent push from the Redmi: a call started or was answered while this iPhone had no
    /// socket. Region monitoring can take a minute to notice a beacon, so this is the wake that has
    /// to be quick. The app connects the link and nothing else: an outgoing call the person dialled
    /// on the Redmi must not put a call screen on this iPhone, and it does not.
    ///
    /// iOS delivers this to a suspended app; an app the person swiped away from the app switcher
    /// gets nothing until it is opened again, which is an iOS rule and not something this code can
    /// change.
    func application(_ application: UIApplication,
                     didReceiveRemoteNotification userInfo: [AnyHashable: Any],
                     fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        let connection = GatewayConnection.shared
        let completion = SilentPushCompletion(completionHandler)
        connection.noteWake(reason: "silent push")
        connection.log("Silent push received; waking the link")
        // The shared wake path holds background time, reconnects and keeps the link while a call is
        // live, exactly as a beacon wake does.
        connection.wakeFromBeacon()
        Task { @MainActor in
            // Answer as soon as the socket is authenticated, or after a bounded wait.
            for _ in 0..<24 {
                if connection.isAuthenticated { break }
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
            connection.log("Silent push handled: authenticated=\(connection.isAuthenticated)")
            completion.finish(connection.isAuthenticated ? .newData : .failed)
        }
    }
}

/// Calls the fetch completion handler exactly once, whichever hop finishes first.
@MainActor
private final class SilentPushCompletion {
    private var done = false
    private let handler: (UIBackgroundFetchResult) -> Void

    init(_ handler: @escaping (UIBackgroundFetchResult) -> Void) {
        self.handler = handler
    }

    func finish(_ result: UIBackgroundFetchResult) {
        guard !done else { return }
        done = true
        handler(result)
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
