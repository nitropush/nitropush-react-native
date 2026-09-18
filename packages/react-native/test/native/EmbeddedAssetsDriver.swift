import Foundation
import CryptoKit

@main struct EmbeddedAssetsDriver {
    static func main() throws {
        func expect(_ value: Bool, line: UInt = #line) { assert(value, line: line) }
        let root = URL(fileURLWithPath: CommandLine.arguments[1]).resolvingSymlinksInPath()
        let hash = CommandLine.arguments[2]
        let size = Int(CommandLine.arguments[3])!
        let updateRoot = root.deletingLastPathComponent().appendingPathComponent("updates")
        try FileManager.default.createDirectory(at: updateRoot, withIntermediateDirectories: true)
        let cache = updateRoot.appendingPathComponent("cache")
        let dest = updateRoot.appendingPathComponent("renamed-in-new-release.png")
        let assets = NPEmbeddedAssets(root: root)
        expect(assets.populate(directory: cache, sha256: hash, announcedSize: size, maximumBytes: 1024))
        expect(try NPContentHashCache.copyVerified(directory: cache, sha256: hash, destination: dest, announcedSize: size, maximumBytes: 1024) == size)
        let cached = try NPContentHashCache.file(directory: cache, sha256: hash)
        try FileManager.default.removeItem(at: cached)
        expect(!assets.populate(directory: cache, sha256: hash, announcedSize: size + 1, maximumBytes: 1024))
        expect(!assets.populate(directory: cache, sha256: hash, announcedSize: size, maximumBytes: size - 1))
        expect(!assets.populate(directory: cache, sha256: String(repeating: "b", count: 64), announcedSize: size, maximumBytes: 1024))
        // A source changed after indexing is not reused, even if the size is unchanged.
        let source = root.appendingPathComponent("assets/images/logo.png")
        try Data(repeating: 42, count: size).write(to: source)
        expect(!assets.populate(directory: cache, sha256: hash, announcedSize: size, maximumBytes: 1024))
        expect(!FileManager.default.fileExists(atPath: cached.path))
        // A stream that is longer than advertised must never populate the cache.
        expect(try !NPEmbeddedAssets.storeVerified(input: InputStream(data: Data(repeating: 42, count: 1025)), directory: cache, sha256: hash, size: size))
        let invalidRoot = root.deletingLastPathComponent().appendingPathComponent("invalid-index")
        try FileManager.default.createDirectory(at: invalidRoot, withIntermediateDirectories: true)
        expect(!NPEmbeddedAssets(root: invalidRoot).populate(directory: cache, sha256: hash, announcedSize: size, maximumBytes: 1024))
        try Data("{\"schemaVersion\":999,\"platform\":\"ios\",\"entries\":[]}".utf8).write(to: invalidRoot.appendingPathComponent("nitropush-embedded-assets.json"))
        expect(!NPEmbeddedAssets(root: invalidRoot).populate(directory: cache, sha256: hash, announcedSize: size, maximumBytes: 1024))
        let malformed = "{\"schemaVersion\":1,\"platform\":\"ios\",\"entries\":[{\"sha256\":\"\(hash)\",\"size\":\(size),\"kind\":\"file\",\"path\":\"../bundle/assets/images/logo.png\"}]}"
        try Data(malformed.utf8).write(to: invalidRoot.appendingPathComponent("nitropush-embedded-assets.json"))
        expect(!NPEmbeddedAssets(root: invalidRoot).populate(directory: cache, sha256: hash, announcedSize: size, maximumBytes: 1024))
        print("embedded inventory lookup, exact reuse, corruption, bounds and path rejection passed")
    }
}
