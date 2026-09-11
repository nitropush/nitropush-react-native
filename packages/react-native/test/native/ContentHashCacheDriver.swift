import Foundation
import CryptoKit

@main struct ContentHashCacheDriver {
    static func main() throws {
        func assert(_ value: Bool, line: UInt = #line) { Swift.assert(value, line: line) }
        let manager = FileManager.default
        let root = manager.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("np-cache-test-\(UUID().uuidString)")
        try manager.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? manager.removeItem(at: root) }
        let cache = root.appendingPathComponent("cache")
        try manager.createDirectory(at: cache, withIntermediateDirectories: true)
        let payload = Data("reusable asset bytes".utf8)
        let hash = SHA256.hash(data: payload).map { String(format: "%02x", $0) }.joined()
        let cached = cache.appendingPathComponent(hash)
        let dest = root.appendingPathComponent("asset.png")
        func copy(_ announced: Int = payload.count, _ maximum: Int = 1024) throws -> Int? {
            try NPContentHashCache.copyVerified(directory: cache, sha256: hash, destination: dest, announcedSize: announced, maximumBytes: maximum)
        }
        assert(try copy() == nil)
        try payload.write(to: cached)
        assert(try copy() == payload.count)
        assert(try Data(contentsOf: dest) == payload)
        // Reusing the same content in another release/path performs no transfer work.
        let secondDest = root.appendingPathComponent("another-release.png")
        var deltaAttempts = 0, fullDownloads = 0
        if try NPContentHashCache.copyVerified(directory: cache, sha256: hash, destination: secondDest, announcedSize: -1, maximumBytes: 1024) == nil {
            deltaAttempts += 1; fullDownloads += 1
        }
        assert(deltaAttempts == 0 && fullDownloads == 0)
        assert(try Data(contentsOf: secondDest) == payload)
        let aliasRoot = root.deletingLastPathComponent().appendingPathComponent("np-cache-alias-\(UUID().uuidString)")
        try manager.createSymbolicLink(at: aliasRoot, withDestinationURL: root)
        defer { try? manager.removeItem(at: aliasRoot) }
        let aliasSize = try NPContentHashCache.copyVerified(directory: aliasRoot.appendingPathComponent("cache"), sha256: hash,
            destination: aliasRoot.appendingPathComponent("alias-asset.png"), announcedSize: payload.count, maximumBytes: 1024)
        assert(aliasSize == payload.count)
        let outsideDestination = root.deletingLastPathComponent().appendingPathComponent("np-outside-\(UUID().uuidString)")
        do { _ = try NPContentHashCache.copyVerified(directory: cache, sha256: hash, destination: outsideDestination, announcedSize: payload.count, maximumBytes: 1024); fatalError("accepted outside destination") } catch {}
        try Data(repeating: 42, count: payload.count).write(to: cached)
        assert(try copy() == nil)
        assert(!manager.fileExists(atPath: cached.path))
        assert(try Data(contentsOf: dest) == payload)
        try payload.write(to: cached)
        assert(try copy(payload.count + 1) == nil)
        try payload.write(to: cached)
        assert(try copy(payload.count, payload.count - 1) == nil)
        try Data().write(to: cached)
        assert(try copy() == nil)
        let outside = root.appendingPathComponent("outside")
        try payload.write(to: outside)
        try manager.createSymbolicLink(at: cached, withDestinationURL: outside)
        assert(try copy() == nil)
        assert(try Data(contentsOf: outside) == payload)
        try manager.createDirectory(at: cached, withIntermediateDirectories: true)
        let child = cached.appendingPathComponent("preserve-me")
        try payload.write(to: child)
        assert(try copy() == nil)
        assert(manager.fileExists(atPath: child.path))
        try manager.removeItem(at: cached)
        try payload.write(to: cached)
        try manager.removeItem(at: dest)
        try manager.createSymbolicLink(at: dest, withDestinationURL: outside)
        do { _ = try copy(); fatalError("accepted symlink destination") } catch {}
        assert(try Data(contentsOf: outside) == payload)
        let redirectedCache = root.appendingPathComponent("redirected-cache")
        try manager.createSymbolicLink(at: redirectedCache, withDestinationURL: cache)
        do { _ = try NPContentHashCache.file(directory: redirectedCache, sha256: hash); fatalError("accepted symlink cache directory") } catch {}
        do { _ = try NPContentHashCache.file(directory: cache, sha256: "../outside"); fatalError("accepted invalid hash") } catch {}
        assert(!(try manager.contentsOfDirectory(atPath: root.path)).contains(where: { $0.hasPrefix(".np-cache-") }))
        print("verified cache reuse, corruption, byte bounds and path safety passed")
    }
}
