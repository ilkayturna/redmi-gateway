import SwiftUI
import UIKit

@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // First, before anything below logs: this run's own lines go to the same backlog file.
        let unsentTelemetry = TelemetryBacklog.takePrevious()
        // iOS may relaunch the app in the background for a Bluetooth event (state restoration).
        // The Bluetooth manager, the Redmi link and CallKit must exist before that event is
        // delivered, whether or not a window is ever shown.
        _ = BLECentralManager.shared
        _ = CallKitManager.shared
        _ = Notifier.shared
        // A crash on the seventeenth left nothing behind: the log is a memory ring and the phone
        // this app talks to cannot read iOS logs. Capture the next one into a file that the
        // existing "copy report" path shows, so a crash at hang-up is diagnosable next time.
        CrashTrace.install()
        // Swift traps, watchdog and memory kills never reach the exception handler; iOS reports
        // them through MetricKit, on this launch for a crash of the last one.
        MetricKitReporter.shared.start()
        let crashed = CrashTrace.reportPreviousCrash()
        // A run that ended while in use without an exception (a kill during a call, for example)
        // is reported from the marker it left behind; then this run's marker replaces it.
        ExitMarker.reportPreviousRun(crashRecorded: crashed,
                                     launchAppState: application.applicationState == .background ? "background" : "foreground")
        GatewayConnection.shared.recordRunMarker()
        // Lines the last run wrote but never handed to the socket go to the Redmi first.
        GatewayConnection.shared.restoreUnsentTelemetry(unsentTelemetry)
        // The memory ring starts empty on every launch, and the call worth explaining usually ran in
        // the previous one: iOS kills a backgrounded app, the person opens it to copy the report, and
        // the failure is already out of memory. The file keeps it, so its tail is put back on the
        // screen the copied report comes from.
        LogFile.replayTail()
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

/// Keeps the ending of the previous run on disk.
///
/// The app's log lives in memory only, so a crash at hang-up on 17.10.2026 left no trace at all,
/// and the phone this app talks to cannot read iOS logs either. An uncaught exception writes its
/// name, reason and symbols to a file, and the next launch sends that text to the Redmi as an
/// `nsexception` report (DiagnosticsOutbox) and logs its first lines.
///
/// Only the exception path is covered here. A watchdog kill, a memory kill or a Swift trap leaves
/// no exception; MetricKitReporter and ExitMarker cover those, and the Redmi keeps its own
/// diagnostics of the same call: together they say whether this app died or merely stopped
/// answering.
enum CrashTrace {
    private static var fileURL: URL {
        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return directory.appendingPathComponent("last-crash.txt")
    }

    static func install() {
        NSSetUncaughtExceptionHandler { exception in
            let text = """
            \(Date()) uncaught \(exception.name.rawValue)
            reason: \(exception.reason ?? "none")
            \(exception.callStackSymbols.joined(separator: "\n"))
            """
            try? text.write(to: CrashTrace.fileURL, atomically: true, encoding: .utf8)
            // The lines written just before the exception may still be on their way to the backlog.
            TelemetryBacklog.drain()
        }
    }

    /// Sends the previous run's exception text to the Redmi and logs its first two lines; returns
    /// whether there was one.
    ///
    /// The whole text used to go into the log as one line with embedded newlines: the Redmi cut it
    /// at 4,000 chars, and the batch around it could pass the Redmi's frame gate and vanish. The
    /// outbox sends it in parts that are confirmed one by one.
    @MainActor
    @discardableResult
    static func reportPreviousCrash() -> Bool {
        let url = fileURL
        // No file: the previous run raised no exception.
        guard let text = try? String(contentsOf: url, encoding: .utf8), !text.isEmpty else { return false }
        let head = text.split(separator: "\n", maxSplits: 2).prefix(2).joined(separator: " | ")
        if DiagnosticsOutbox.shared.enqueue(kind: "nsexception", data: Data(text.utf8)) {
            GatewayConnection.shared.log("Previous run crashed: \(head) (\(text.utf8.count) bytes queued for the Redmi)")
        } else {
            // The outbox could not keep it; the log still carries it, split into pieces.
            GatewayConnection.shared.log("Previous run crashed: " + text)
        }
        try? FileManager.default.removeItem(at: url)
        return true
    }
}
