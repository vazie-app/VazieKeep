import Foundation
import ImageIO
import KeepCore
import Observation
import UniformTypeIdentifiers

/// The icon of a website, as a small PNG. The request goes straight to the site: no service in
/// between learns which sites a vault holds.
public protocol SiteIconSource: Sendable {
    func fetch(host: String) async -> Data?
}

public protocol SiteHTTP: Sendable {
    /// The body of an https page, or nil. A longer body is cut at `limit` when `cut`, refused otherwise.
    func get(_ url: String, limit: Int, cut: Bool) async -> Data?
}

public struct HTTPSiteIcons: SiteIconSource {
    private let http: any SiteHTTP
    private let links: @Sendable (_ host: String, _ html: String) -> [String]
    private let toPNG: @Sendable (Data) -> Data?

    public init(
        http: any SiteHTTP = URLSessionSiteHTTP(),
        links: @escaping @Sendable (_ host: String, _ html: String) -> [String] = { siteIconLinks(host: $0, html: $1) },
        toPNG: @escaping @Sendable (Data) -> Data? = iconPNG
    ) {
        self.http = http
        self.links = links
        self.toPNG = toPNG
    }

    public func fetch(host: String) async -> Data? {
        for site in siteCandidates(host) {
            let page = await http.get("https://\(site)/", limit: 512 * 1024, cut: true).map { String(decoding: $0, as: UTF8.self) } ?? ""
            for link in links(site, page) {
                if let image = await http.get(link, limit: 1024 * 1024, cut: false), let png = toPNG(image) { return png }
            }
        }
        return nil
    }
}

/// Some sites answer only as "www."; and "accounts.example.com" has no icon of its own more often
/// than "example.com" does.
public func siteCandidates(_ host: String) -> [String] {
    let labels = host.split(separator: ".")
    return labels.count > 2 ? [host, labels.dropFirst().joined(separator: ".")] : [host, "www.\(host)"]
}

public struct URLSessionSiteHTTP: SiteHTTP {
    private let session: URLSession

    public init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCache = nil
        configuration.urlCredentialStorage = nil
        configuration.timeoutIntervalForRequest = 8
        configuration.timeoutIntervalForResource = 20
        // What any phone browser sends: the request says nothing about this app.
        configuration.httpAdditionalHeaders = [
            "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
            "Accept": "text/html,image/*;q=0.9,*/*;q=0.5",
        ]
        session = URLSession(configuration: configuration)
    }

    public func get(_ url: String, limit: Int, cut: Bool) async -> Data? {
        guard url.hasPrefix("https://"), let address = URL(string: url) else { return nil }
        guard let (data, response) = try? await session.data(from: address),
              let http = response as? HTTPURLResponse, http.statusCode == 200, http.url?.scheme == "https"
        else { return nil }
        if data.count <= limit { return data }
        return cut ? data.prefix(limit) : nil
    }
}

/// Whatever image the site serves, kept as a PNG no larger than a list row needs.
public func iconPNG(_ data: Data) -> Data? {
    guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
    let count = CGImageSourceGetCount(source)
    guard count > 0 else { return nil }
    // An .ico holds several sizes: the largest one scales down best.
    var best = 0
    var widest = 0
    for index in 0..<min(count, 16) {
        let properties = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any]
        let width = properties?[kCGImagePropertyPixelWidth] as? Int ?? 0
        if width > widest {
            widest = width
            best = index
        }
    }
    guard widest > 0, widest <= 4096 else { return nil }
    let options: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceThumbnailMaxPixelSize: 96,
    ]
    guard let image = CGImageSourceCreateThumbnailAtIndex(source, best, options as CFDictionary) else { return nil }
    let out = NSMutableData()
    guard let destination = CGImageDestinationCreateWithData(out, UTType.png.identifier as CFString, 1, nil) else { return nil }
    CGImageDestinationAddImage(destination, image, nil)
    return CGImageDestinationFinalize(destination) ? out as Data : nil
}

/// One pass over the vault: fetches the icon of every site the entries name and keeps the images in
/// the vault file. An entry whose site gave nothing gets one of the built-in icons by its address.
@MainActor
@Observable
public final class SiteIconPass {
    public enum Outcome: Equatable, Sendable {
        case assigned(Int)
        case nothing
        /// There were sites to ask and none of them answered.
        case unavailable
        case failed
    }

    public struct Progress: Equatable, Sendable {
        public let done: Int
        public let total: Int
    }

    public private(set) var progress: Progress?
    public var saveFailure: SaveFailure?

    @ObservationIgnored private let controller: VaultSessionController
    @ObservationIgnored private let source: any SiteIconSource
    @ObservationIgnored private var running: Task<Outcome?, Never>?

    public init(controller: VaultSessionController, source: any SiteIconSource) {
        self.controller = controller
        self.source = source
    }

    /// Nil when the pass was cancelled or one is already running.
    public func run() async -> Outcome? {
        guard running == nil else { return nil }
        let task = Task { await pass() }
        running = task
        let outcome = await task.value
        running = nil
        progress = nil
        return outcome
    }

    public func cancel() {
        running?.cancel()
    }

    private func pass() async -> Outcome? {
        let hosts = controller.siteIconHosts()
        let fetched = await fetch(hosts)
        if Task.isCancelled { return nil }
        let result = await controller.edit { session throws(VaultEditError) in
            let images = fetched.isEmpty ? 0 : try session.setSiteIcons(fetched)
            return Counts(images: images, plain: try session.assignIconsByURL())
        }
        let counts: Counts
        switch result {
        case let .saved(value): counts = value
        case let .notSaved(value, failure):
            saveFailure = failure
            counts = value
        case .failed: return .failed
        }
        let changed = counts.images > 0 ? counts.images : counts.plain
        if changed > 0 { return .assigned(changed) }
        return hosts.isEmpty ? .nothing : .unavailable
    }

    private struct Counts: Sendable {
        let images: Int
        let plain: Int
    }

    private func fetch(_ hosts: [String]) async -> [SiteIcon] {
        guard !hosts.isEmpty else { return [] }
        progress = Progress(done: 0, total: hosts.count)
        let source = source
        var icons: [SiteIcon] = []
        var done = 0
        await withTaskGroup(of: SiteIcon?.self) { group in
            var waiting = hosts.makeIterator()
            let next = { (group: inout TaskGroup<SiteIcon?>) in
                guard let host = waiting.next() else { return }
                group.addTask { await source.fetch(host: host).map { SiteIcon(host: host, png: $0) } }
            }
            for _ in 0..<min(6, hosts.count) { next(&group) }
            for await icon in group {
                if let icon { icons.append(icon) }
                done += 1
                progress = Progress(done: done, total: hosts.count)
                if Task.isCancelled {
                    group.cancelAll()
                } else {
                    next(&group)
                }
            }
        }
        return icons.sorted { $0.host < $1.host }
    }
}

/// Fetches the icon of one entry's site after the entry is saved, when the person turned that on in
/// Settings. Off by default: without it nothing in a vault leads to a network request by itself.
@MainActor
@Observable
public final class SiteIconUpdater {
    @ObservationIgnored private let controller: VaultSessionController
    @ObservationIgnored private let source: any SiteIconSource
    @ObservationIgnored private let enabled: () -> Bool

    public init(controller: VaultSessionController, source: any SiteIconSource, enabled: @escaping () -> Bool) {
        self.controller = controller
        self.source = source
        self.enabled = enabled
    }

    @discardableResult
    public func entrySaved(_ id: EntryID) -> Task<Void, Never>? {
        guard enabled(), let host = controller.siteIconHost(for: id) else { return nil }
        let source = source
        return Task {
            guard let png = await source.fetch(host: host) else { return }
            _ = await controller.edit { session throws(VaultEditError) in try session.setSiteIcons([SiteIcon(host: host, png: png)]) }
        }
    }
}
