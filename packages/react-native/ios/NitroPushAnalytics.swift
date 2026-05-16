import Foundation
import UIKit

/// One JS-shaped analytics event. Wire matches the existing `/api/sdk/events`
/// contract — moving from JS to native must not change the server schema.
struct NlAnalyticsEvent: Codable {
    let eventType: String
    let clientUniqueId: String
    let appVersion: String
    let otaVersion: Double?
    let releaseId: String?
    let platform: String
    let osVersion: String?
    let deviceModel: String?
    let occurredAt: String
}

/// Native equivalent of the deleted JS `createAnalyticsEmitter`. Owns the
/// queue, flush timer, retry/backoff, and `URLSession` call. Lives entirely
/// in Swift so events fire even when the JS thread is asleep, mid-restart,
/// or hasn't loaded yet.
///
/// **Threading.** All queue mutation goes through `serial` so callers (the
/// Nitro bridge, lifecycle observers, the rollback sweep) can hammer
/// `enqueue` from any thread without locking.
final class NlAnalytics {
    private let serverUrl: String
    private let deploymentKey: String
    private let capacity: Int
    private let flushAt: Int
    private let flushIntervalSeconds: TimeInterval

    private let serial = DispatchQueue(label: "com.nitropush.analytics", qos: .utility)
    private let session: URLSession

    private var queue: [NlAnalyticsEvent] = []
    private var flushing = false
    private var backoffMs: Int = 0
    private var flushTimer: DispatchSourceTimer?
    private var stopped = false

    init(
        serverUrl: String,
        deploymentKey: String,
        capacity: Int = 200,
        flushAt: Int = 10,
        flushIntervalSeconds: TimeInterval = 30
    ) {
        // Trim trailing slash so we can append `/api/sdk/events` without a
        // double-slash (some reverse proxies treat them as different paths).
        self.serverUrl = serverUrl.hasSuffix("/") ? String(serverUrl.dropLast()) : serverUrl
        self.deploymentKey = deploymentKey
        self.capacity = capacity
        self.flushAt = flushAt
        self.flushIntervalSeconds = flushIntervalSeconds

        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 15
        cfg.timeoutIntervalForResource = 60
        // Telemetry is best-effort and small; we don't want it riding on the
        // device's metered foreground budget.
        cfg.allowsExpensiveNetworkAccess = true
        cfg.allowsConstrainedNetworkAccess = true
        self.session = URLSession(configuration: cfg)
    }

    func enqueue(_ event: NlAnalyticsEvent) {
        serial.async { [weak self] in
            guard let self = self, !self.stopped else { return }
            self.queue.append(event)
            // Drop oldest at capacity — we'd rather lose old events than
            // grow unbounded waiting on a dead network.
            while self.queue.count > self.capacity {
                self.queue.removeFirst()
            }
            if self.queue.count >= self.flushAt {
                self.flushLocked()
            } else {
                self.scheduleTimerLocked()
            }
        }
    }

    func flush() {
        serial.async { [weak self] in self?.flushLocked() }
    }

    func stop() {
        serial.async { [weak self] in
            self?.stopped = true
            self?.flushTimer?.cancel()
            self?.flushTimer = nil
        }
    }

    // MARK: - Private (must be called on `serial`)

    private func scheduleTimerLocked() {
        if flushTimer != nil { return }
        let timer = DispatchSource.makeTimerSource(queue: serial)
        timer.schedule(deadline: .now() + flushIntervalSeconds)
        timer.setEventHandler { [weak self] in
            self?.flushTimer = nil
            self?.flushLocked()
        }
        timer.resume()
        flushTimer = timer
    }

    private func flushLocked() {
        if flushing || stopped || queue.isEmpty { return }
        let batch = queue
        queue.removeAll()
        flushing = true

        guard let url = URL(string: "\(serverUrl)/api/sdk/events") else {
            // Bad URL — drop the batch rather than retrying forever.
            flushing = false
            return
        }

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.addValue("application/json", forHTTPHeaderField: "Content-Type")

        let body = Body(deploymentKey: deploymentKey, events: batch)
        do {
            req.httpBody = try JSONEncoder().encode(body)
        } catch {
            flushing = false
            return
        }

        session.dataTask(with: req) { [weak self] _, response, error in
            guard let self = self else { return }
            self.serial.async {
                self.flushing = false
                let ok = error == nil
                    && (response as? HTTPURLResponse).map { (200..<300).contains($0.statusCode) } == true
                if ok {
                    self.backoffMs = 0
                } else {
                    // Re-queue the failed batch at the head, exponential
                    // backoff up to 60s between retries.
                    self.queue.insert(contentsOf: batch, at: 0)
                    while self.queue.count > self.capacity {
                        self.queue.removeFirst()
                    }
                    self.backoffMs = min(max(self.backoffMs * 2, 1_000), 60_000)
                    self.scheduleRetryLocked()
                }
            }
        }.resume()
    }

    private func scheduleRetryLocked() {
        flushTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: serial)
        timer.schedule(deadline: .now() + .milliseconds(backoffMs))
        timer.setEventHandler { [weak self] in
            self?.flushTimer = nil
            self?.flushLocked()
        }
        timer.resume()
        flushTimer = timer
    }

    private struct Body: Encodable {
        let deploymentKey: String
        let events: [NlAnalyticsEvent]
    }
}

/// Helpers shared between `NitroPushSdk` (calls these from configure /
/// download / install) and the rollback sweep so events tag with the
/// same device fields regardless of who fires them.
enum NlAnalyticsContext {
    static func now() -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.string(from: Date())
    }

    static func osVersion() -> String {
        UIDevice.current.systemVersion
    }

    static func deviceModel() -> String {
        var sys = utsname()
        uname(&sys)
        let mirror = Mirror(reflecting: sys.machine)
        let id = mirror.children.compactMap { c -> String? in
            guard let v = c.value as? Int8, v != 0 else { return nil }
            return String(UnicodeScalar(UInt8(v)))
        }.joined()
        return id.isEmpty ? UIDevice.current.model : id
    }
}
