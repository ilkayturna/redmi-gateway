import Foundation
import MetricKit

/// Hands Apple's own crash, hang and exit reports to the diagnostics outbox, and so to the Redmi.
///
/// CrashTrace sees only NSException. A Swift trap, a watchdog kill or a memory kill leaves nothing
/// in this process; iOS reports those through MetricKit instead, on a later launch (diagnostics
/// right away since iOS 15, the daily metrics with their exit counts about once a day). Each
/// payload's raw JSON goes to the outbox as it came, and one summary line goes to the log, so the
/// Redmi's iPhone log says at a glance what the report is about.
///
/// Not tied to the main actor: MetricKit calls in on a queue of its own, and a report can be tens
/// of KB, which is better written off the main thread during a call.
final class MetricKitReporter: NSObject, MXMetricManagerSubscriber {
    static let shared = MetricKitReporter()

    private var started = false

    /// Registered at every launch, before anything else can fail, so a report waiting from the last
    /// run is delivered to this one.
    @MainActor
    func start() {
        guard !started else { return }
        started = true
        MXMetricManager.shared.add(self)
    }

    func didReceive(_ payloads: [MXMetricPayload]) {
        for payload in payloads {
            let json = payload.jsonRepresentation()
            store(kind: "metrickit-metric", json: json, summary: DiagnosticsFormat.metricKitMetricSummary(json))
        }
    }

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        for payload in payloads {
            let json = payload.jsonRepresentation()
            store(kind: DiagnosticsFormat.metricKitKind(diagnosticJSON: json), json: json,
                  summary: DiagnosticsFormat.metricKitDiagnosticSummary(json))
        }
    }

    private func store(kind: String, json: Data, summary: String) {
        let result = DiagnosticsOutboxStore.write(kind: kind, data: json)
        Task { @MainActor in
            let connection = GatewayConnection.shared
            switch result {
            case .success(let stored):
                connection.log("MetricKit \(kind) (\(json.count) bytes): \(summary)")
                DiagnosticsOutbox.shared.noteStored(stored, kind: kind)
            case .failure(let failure):
                connection.log("MetricKit \(kind) of \(json.count) bytes could not be stored: \(failure.reason); \(summary)")
            }
        }
    }
}
