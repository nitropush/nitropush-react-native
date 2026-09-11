import Foundation
import CryptoKit

/// Immutable binary inventory, loaded once. Missing/invalid indexes are cache misses.
/// Native builds generate the inventory; device requests never scan the app bundle.
final class NPEmbeddedAssets {
    struct Entry: Decodable {
        let sha256: String
        let size: Int
        let kind: String
        let path: String?
    }
    private struct Inventory: Decodable { let schemaVersion: Int; let platform: String; let entries: [Entry] }
    private let root: URL
    private let lock = NSLock()
    private var index: [String: [Entry]]?
    init(root: URL = Bundle.main.resourceURL ?? Bundle.main.bundleURL) { self.root = root.resolvingSymlinksInPath() }

    private static func safePath(_ value: String) -> Bool {
        let parts = value.split(separator: "/", omittingEmptySubsequences: false)
        return !value.isEmpty && value.utf8.count <= 512 && !value.contains("\\") &&
            !value.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value == 0x7f }) &&
            !parts.contains(where: { $0.isEmpty || $0 == "." || $0 == ".." })
    }
    private func source(_ path: String, maximum: Int) throws -> URL? {
        guard Self.safePath(path) else { return nil }
        let url = root.appendingPathComponent(path).standardizedFileURL
        guard url.path.hasPrefix(root.path + "/"), url.resolvingSymlinksInPath().path == url.path else { return nil }
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        guard attributes[.type] as? FileAttributeType == .typeRegular,
              let size = (attributes[.size] as? NSNumber)?.intValue, size > 0, size <= maximum else { return nil }
        return url
    }
    private func entries(_ hash: String) -> [Entry] {
        lock.lock(); defer { lock.unlock() }
        if index == nil {
            index = [:]
            for path in ["nitropush-embedded-assets.json", "app/nitropush-embedded-assets.json"] {
                guard let url = try? source(path, maximum: 2 * 1024 * 1024),
                      let data = try? Data(contentsOf: url), data.count <= 2 * 1024 * 1024,
                      let inventory = try? JSONDecoder().decode(Inventory.self, from: data),
                      inventory.schemaVersion == 1, inventory.platform == "ios", inventory.entries.count <= 10000 else { continue }
                for entry in inventory.entries {
                    guard entry.sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
                          entry.size > 0, entry.size <= 64 * 1024 * 1024, entry.kind == "file",
                          let path = entry.path, Self.safePath(path), (index?[entry.sha256]?.count ?? 0) < 8 else { continue }
                    index?[entry.sha256, default: []].append(entry)
                }
                break
            }
        }
        return index?[hash] ?? []
    }

    /// A local candidate is not proof: copy+hash it before adding it to the cache.
    func populate(directory: URL, sha256: String, announcedSize: Int, maximumBytes: Int) -> Bool {
        for entry in entries(sha256) where entry.size <= maximumBytes && (announcedSize <= 0 || entry.size == announcedSize) {
            do {
                guard let path = entry.path, let source = try source(path, maximum: entry.size),
                      let input = InputStream(url: source) else { continue }
                if try Self.storeVerified(input: input, directory: directory, sha256: sha256, size: entry.size) { return true }
            } catch { continue }
        }
        return false
    }

    static func storeVerified(input: InputStream, directory: URL, sha256: String, size: Int) throws -> Bool {
        guard size > 0, size <= 64 * 1024 * 1024 else { return false }
        let manager = FileManager.default
        let cached = try NPContentHashCache.file(directory: directory, sha256: sha256)
        try manager.createDirectory(at: cached.deletingLastPathComponent(), withIntermediateDirectories: true)
        let temporary = cached.deletingLastPathComponent().appendingPathComponent(".np-embedded-\(UUID().uuidString)")
        guard manager.createFile(atPath: temporary.path, contents: nil) else { return false }
        defer { try? manager.removeItem(at: temporary) }
        input.open(); defer { input.close() }
        let output = try FileHandle(forWritingTo: temporary)
        defer { output.closeFile() }
        var digest = SHA256(), written = 0
        var buffer = [UInt8](repeating: 0, count: 64 * 1024)
        while true {
            let count = input.read(&buffer, maxLength: buffer.count)
            if count < 0 { return false }
            if count == 0 { break }
            guard count <= size - written else { return false }
            written += count
            let data = Data(buffer.prefix(count))
            digest.update(data: data); output.write(data)
        }
        guard written == size, digest.finalize().map({ String(format: "%02x", $0) }).joined() == sha256 else { return false }
        if manager.fileExists(atPath: cached.path) {
            guard cached.resolvingSymlinksInPath().path == cached.path,
                  try manager.attributesOfItem(atPath: cached.path)[.type] as? FileAttributeType == .typeRegular else { return false }
            try manager.removeItem(at: cached)
        }
        try manager.moveItem(at: temporary, to: cached)
        return true
    }
}
