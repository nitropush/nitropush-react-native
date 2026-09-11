import Foundation
import CryptoKit

/// App-private, content-addressed files are only reusable after verification.
/// Never treats a filename, a previous release receipt, or a partial copy as proof.
enum NPContentHashCache {
    enum Failure: Error { case invalidPath, invalidBounds, cannotCreateTemporary }

    static func file(directory: URL, sha256: String) throws -> URL {
        guard sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else {
            throw Failure.invalidPath
        }
        // Resolve OS aliases in the parent, but never follow a substituted cache directory.
        let directory = directory.deletingLastPathComponent().resolvingSymlinksInPath()
            .appendingPathComponent(directory.lastPathComponent, isDirectory: true).standardizedFileURL
        guard directory.resolvingSymlinksInPath().path == directory.path else { throw Failure.invalidPath }
        return directory.appendingPathComponent(sha256)
    }

    /// Returns the exact installed size, or nil for a missing/corrupt cache entry.
    /// Copies in bounded chunks and hashes the copy before exposing the destination.
    static func copyVerified(directory: URL, sha256: String, destination: URL, announcedSize: Int, maximumBytes: Int) throws -> Int? {
        guard maximumBytes > 0, announcedSize == -1 || announcedSize > 0 else { throw Failure.invalidBounds }
        let cached = try file(directory: directory, sha256: sha256)
        let manager = FileManager.default
        let attributes: [FileAttributeKey: Any]
        do { attributes = try manager.attributesOfItem(atPath: cached.path) }
        catch let error as NSError where error.domain == NSCocoaErrorDomain && error.code == NSFileReadNoSuchFileError { return nil }
        let type = attributes[.type] as? FileAttributeType
        func discardEntry() {
            // Remove only this leaf. Never recursively delete an unexpected cache directory.
            if type == .typeRegular || type == .typeSymbolicLink { try? manager.removeItem(at: cached) }
        }
        guard type == .typeRegular,
              cached.resolvingSymlinksInPath().path == cached.path,
              let size = (attributes[.size] as? NSNumber)?.intValue,
              size > 0, size <= maximumBytes,
              announcedSize <= 0 || size == announcedSize else {
            discardEntry()
            return nil
        }
        // The application root itself may use an OS alias (/var -> /private/var).
        // Resolve that trusted root, then reject symlinks within the release path.
        let logicalRoot = directory.deletingLastPathComponent().standardizedFileURL
        let physicalRoot = logicalRoot.resolvingSymlinksInPath()
        let requested = destination.standardizedFileURL.path
        let prefix = requested.hasPrefix(logicalRoot.path + "/") ? logicalRoot.path : physicalRoot.path
        guard requested.hasPrefix(prefix + "/") else { throw Failure.invalidPath }
        let relative = String(requested.dropFirst(prefix.count + 1))
        let destination = physicalRoot.appendingPathComponent(relative).standardizedFileURL
        guard destination.resolvingSymlinksInPath().path == destination.path,
              destination.path != cached.path else { throw Failure.invalidPath }
        let temporary = destination.deletingLastPathComponent()
            .appendingPathComponent(".np-cache-\(UUID().uuidString)")
        guard manager.createFile(atPath: temporary.path, contents: nil) else { throw Failure.cannotCreateTemporary }
        defer { try? manager.removeItem(at: temporary) }
        let input = try FileHandle(forReadingFrom: cached)
        defer { input.closeFile() }
        let output = try FileHandle(forWritingTo: temporary)
        defer { output.closeFile() }
        var hash = SHA256()
        var copied = 0
        while true {
            let chunk = input.readData(ofLength: 64 * 1024)
            if chunk.isEmpty { break }
            guard chunk.count <= size - copied else {
                discardEntry()
                return nil
            }
            copied += chunk.count
            hash.update(data: chunk)
            output.write(chunk)
        }
        let copiedHash = hash.finalize().map { String(format: "%02x", $0) }.joined()
        guard copied == size, copiedHash == sha256 else {
            discardEntry()
            return nil
        }
        // The staging path must not have been replaced while the copy was verified.
        guard destination.resolvingSymlinksInPath().path == destination.path else { throw Failure.invalidPath }
        if manager.fileExists(atPath: destination.path) {
            let destinationType = try manager.attributesOfItem(atPath: destination.path)[.type] as? FileAttributeType
            guard destinationType == .typeRegular else { throw Failure.invalidPath }
            try manager.removeItem(at: destination)
        }
        try manager.moveItem(at: temporary, to: destination)
        try? manager.setAttributes([.modificationDate: Date()], ofItemAtPath: cached.path)
        return copied
    }
}
