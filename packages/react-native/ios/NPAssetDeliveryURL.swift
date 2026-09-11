import Foundation

enum NPAssetDeliveryURL {
    enum Failure: Error { case invalid }
    static func resolve(_ raw: String, api: URL?, expectedHash: String) throws -> (URL, Bool) {
        let url: URL?
        if raw.hasPrefix("/api/sdk/asset?"), let api { url = URL(string: raw, relativeTo: api)?.absoluteURL }
        else { url = URL(string: raw) }
        guard let url, let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme),
              url.host != nil, url.user == nil, url.password == nil, url.fragment == nil else { throw Failure.invalid }
        func port(_ value: URL) -> Int { value.port ?? (value.scheme?.lowercased() == "https" ? 443 : 80) }
        guard let api, scheme == api.scheme?.lowercased(), url.host?.lowercased() == api.host?.lowercased(),
              port(url) == port(api), url.path == "/api/sdk/asset" else { return (url, false) }
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let items = components?.queryItems ?? []
        guard scheme == "https", components?.percentEncodedPath == "/api/sdk/asset", items.count == 2,
              items.filter({ $0.name == "hash" && $0.value == expectedHash }).count == 1,
              items.filter({ $0.name == "ticket" && !($0.value ?? "").isEmpty && ($0.value?.utf8.count ?? 0) <= 8192 }).count == 1 else { throw Failure.invalid }
        return (url, true)
    }
}
