import Foundation
@main struct SequenceDriver {
    static func main() throws {
        var disk: [String: NPSequenceStamp] = [:]
        func ledger() -> NPSequenceLedger { NPSequenceLedger(load: { disk[$0] }, save: { disk[$0] = $1 }) }
        let first = ledger()
        try first.seedAccepted("key/ios/1.0", 10, "new")
        try first.seedAccepted("key/ios/1.0", 8, "previous")
        try first.check("key/ios/*", 1, "wildcard", remember: true)
        let rebooted = ledger()
        func rejects(_ version: Double, _ id: String = "old") {
            do { try rebooted.check("key/ios/1.0", version, id); fatalError("accepted stale or invalid sequence") } catch {}
        }
        rejects(9); rejects(10); rejects(0); rejects(-1); rejects(10.5)
        rejects(.nan); rejects(.infinity); rejects(9_007_199_254_740_992); rejects(Double(Int64.max))
        try rebooted.check("key/ios/1.0", 10, "new")
        try rebooted.check("key/ios/1.0", 11, "later", remember: true)
        try rebooted.check("key/ios/2.0", 1, "new-binary", remember: true)
        try rebooted.check("other-key/ios/1.0", 1, "other-deployment", remember: true)
        try rebooted.check("key/ios/1.0", 9_007_199_254_740_991, "maximum")
        assert(disk["key/ios/1.0"]?.version == 11)
        print("sequence replay, numeric bounds, independent runtimes and persistence passed")
    }
}
