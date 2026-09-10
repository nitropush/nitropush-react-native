import Foundation

struct NPSequenceStamp {
    let version: Double
    let releaseId: String
}

/// Caller supplies app-private persistent storage. Separate runtime counters
/// must never be compared globally (the wildcard is its own runtime).
final class NPSequenceLedger {
    private let load: (String) throws -> NPSequenceStamp?
    private let save: (String, NPSequenceStamp) throws -> Void
    private let lock = NSRecursiveLock()
    init(load: @escaping (String) throws -> NPSequenceStamp?, save: @escaping (String, NPSequenceStamp) throws -> Void) {
        self.load = load; self.save = save
    }
    /// Only for records already accepted into app-private installed state.
    /// Older active/previous records must never lower a newer durable floor.
    func seedAccepted(_ scope: String, _ version: Double, _ releaseId: String) throws {
        lock.lock(); defer { lock.unlock() }
        if let current = try load(scope), current.version >= version { return }
        try check(scope, version, releaseId, remember: true)
    }
    func check(_ scope: String, _ version: Double, _ releaseId: String, remember: Bool = false) throws {
        lock.lock(); defer { lock.unlock() }
        guard version.isFinite, version >= 1, version <= 9_007_199_254_740_991,
              Int64(exactly: version) != nil else { throw Failure.invalid }
        if let current = try load(scope) {
            guard version > current.version || (version == current.version && releaseId == current.releaseId) else { throw Failure.replay }
        }
        if remember { try save(scope, NPSequenceStamp(version: version, releaseId: releaseId)) }
    }
    enum Failure: Error { case invalid, replay, storage }
}
