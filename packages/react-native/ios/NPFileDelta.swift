import Foundation

/// Bounded NPDIFF01 decoder. File hashes and signatures are verified by the caller.
enum NPFileDelta {
    static func apply(base: Data, patch: Data, maximumBytes: Int) throws -> Data {
        func invalid() -> NSError { NSError(domain: "NitroPushFileDelta", code: 1) }
        guard !base.isEmpty, base.count <= maximumBytes, patch.count >= 12,
              patch.count <= maximumBytes, patch.prefix(8) == Data("NPDIFF01".utf8) else { throw invalid() }
        var cursor = 8
        func u32() throws -> Int {
            guard cursor + 4 <= patch.count else { throw invalid() }
            var result = 0
            for _ in 0..<4 { result = (result << 8) | Int(patch[cursor]); cursor += 1 }
            return result
        }
        let size = try u32()
        guard size > 0, size <= maximumBytes else { throw invalid() }
        var output = Data(); output.reserveCapacity(size)
        while cursor < patch.count {
            let op = patch[cursor]; cursor += 1
            guard op == 0 || op == 1 else { throw invalid() }
            let offset = op == 0 ? try u32() : 0
            let length = try u32()
            guard length > 0, length <= size - output.count else { throw invalid() }
            if op == 0 {
                guard offset <= base.count, length <= base.count - offset else { throw invalid() }
                output.append(base.subdata(in: offset..<(offset + length)))
            } else {
                guard length <= patch.count - cursor else { throw invalid() }
                output.append(patch.subdata(in: cursor..<(cursor + length))); cursor += length
            }
        }
        guard output.count == size else { throw invalid() }
        return output
    }
}
