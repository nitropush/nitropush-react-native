import Foundation
@main struct AssetDeliveryURLDriver {
    static func main() throws {
        func expect(_ value: Bool, line: UInt = #line) { assert(value, line: line) }
        let api = URL(string: "https://api.example.test/base")!
        let hash = String(repeating: "a", count: 64)
        let relative = "/api/sdk/asset?ticket=proof&hash=\(hash)"
        let resolved = try NPAssetDeliveryURL.resolve(relative, api: api, expectedHash: hash)
        expect(resolved.0.absoluteString == "https://api.example.test\(relative)" && resolved.1)
        expect(try NPAssetDeliveryURL.resolve("https://api.example.test:443\(relative)", api: api, expectedHash: hash).1)
        for candidate in ["https://cdn.example.test\(relative)", "https://api.example.test:444\(relative)", "https://api.example.test/assets/file.png", "http://api.example.test\(relative)"] {
            expect(try !NPAssetDeliveryURL.resolve(candidate, api: api, expectedHash: hash).1)
        }
        for candidate in [relative + "&hash=\(hash)", relative + "&extra=1", "/api/sdk/asset?ticket=&hash=\(hash)",
            "/api/sdk/asset?ticket=proof&hash=wrong", relative + "#fragment", "//api.example.test\(relative)",
            "https://user:pass@api.example.test\(relative)", "/different/path"] {
            do { _ = try NPAssetDeliveryURL.resolve(candidate, api: api, expectedHash: hash); fatalError("accepted invalid asset proxy URL") } catch {}
        }
        do { _ = try NPAssetDeliveryURL.resolve(relative, api: URL(string: "http://api.example.test"), expectedHash: hash); fatalError("accepted HTTP proxy") } catch {}
        do { let result = try NPAssetDeliveryURL.resolve("https://api.example.test/api/sdk/%61sset?ticket=proof&hash=\(hash)", api: api, expectedHash: hash); expect(!result.1) } catch {}
        print("asset proxy URL origin, exact hash, query cardinality and credential isolation passed")
    }
}
