import Foundation
import Observation

public enum LinkSection: String, Sendable, CaseIterable {
    case author = "AUTHOR"
    case project = "PROJECT"
    case support = "SUPPORT"
    case feedback = "FEEDBACK"
}

public struct LocalizedText: Equatable, Sendable {
    public var ru: String
    public var en: String

    public init(_ ru: String, _ en: String) {
        self.ru = ru
        self.en = en
    }

    public func pick(russian: Bool) -> String { russian ? ru : en }
}

public struct AppLink: Equatable, Sendable, Identifiable {
    public var key: String
    public var section: LinkSection
    public var url: URL
    public var title: LocalizedText
    public var subtitle: LocalizedText?
    public var action: LocalizedText?

    public var id: String { key }

    public init(key: String, section: LinkSection, url: URL, title: LocalizedText, subtitle: LocalizedText? = nil, action: LocalizedText? = nil) {
        self.key = key
        self.section = section
        self.url = url
        self.title = title
        self.subtitle = subtitle
        self.action = action
    }
}

public extension Array where Element == AppLink {
    func inSection(_ section: LinkSection) -> [AppLink] { filter { $0.section == section } }
}

public enum AppLinks {
    /// The answer of `GET /v1/apps/keep/links`. A link the app cannot show is dropped on its own; `nil` when none is left.
    public static func parse(_ data: Data) -> [AppLink]? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let raw = root["links"] as? [[String: Any]]
        else { return nil }
        let links = raw.compactMap(link)
        return links.isEmpty ? nil : links
    }

    public static func encode(_ links: [AppLink]) -> Data {
        func text(_ value: LocalizedText) -> [String: String] { ["ru": value.ru, "en": value.en] }
        let raw: [[String: Any]] = links.map { link in
            var object: [String: Any] = ["key": link.key, "section": link.section.rawValue, "url": link.url.absoluteString, "title": text(link.title)]
            if let subtitle = link.subtitle { object["subtitle"] = text(subtitle) }
            if let action = link.action { object["action"] = text(action) }
            return object
        }
        return (try? JSONSerialization.data(withJSONObject: ["links": raw])) ?? Data()
    }

    /// Only a web page over `https` with a host, or a `mailto:` address: nothing else is ever opened.
    public static func openable(_ string: String) -> URL? {
        guard string.count <= 512, !string.contains(where: { $0.isWhitespace || $0.isNewline }),
              !string.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
              let url = URL(string: string), let scheme = url.scheme?.lowercased()
        else { return nil }
        switch scheme {
        case "https":
            return (url.host ?? "").isEmpty ? nil : url
        case "mailto":
            let address = String(string.dropFirst("mailto:".count))
            return address.contains("@") && !address.hasPrefix("@") ? url : nil
        default:
            return nil
        }
    }

    private static func link(_ raw: [String: Any]) -> AppLink? {
        guard let key = raw["key"] as? String, !key.trimmingCharacters(in: .whitespaces).isEmpty,
              let section = (raw["section"] as? String).flatMap(LinkSection.init(rawValue:)),
              let url = (raw["url"] as? String).flatMap(openable),
              let title = text(raw["title"])
        else { return nil }
        return AppLink(key: key, section: section, url: url, title: title, subtitle: text(raw["subtitle"]), action: text(raw["action"]))
    }

    private static func text(_ raw: Any?) -> LocalizedText? {
        guard let object = raw as? [String: Any], let ru = object["ru"] as? String, let en = object["en"] as? String,
              isText(ru), isText(en)
        else { return nil }
        return LocalizedText(ru, en)
    }

    private static func isText(_ text: String) -> Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && text.count <= 400
            && !text.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
    }

    public static let shipped: [AppLink] = [
        AppLink(key: "developer", section: .author, url: URL(string: "https://t.me/vazovsky17")!,
                title: LocalizedText("Vazovsky в Telegram", "Vazovsky on Telegram"),
                subtitle: LocalizedText("@vazovsky17 — лично автору", "@vazovsky17 — to the author personally")),
        AppLink(key: "folio", section: .author, url: URL(string: "https://folio.link/vazovsky")!,
                title: LocalizedText("Folio", "Folio"),
                subtitle: LocalizedText("folio.link/vazovsky — другие проекты", "folio.link/vazovsky — other projects")),
        AppLink(key: "channel", section: .project, url: URL(string: "https://t.me/vazieapp")!,
                title: LocalizedText("Vazie в Telegram", "Vazie on Telegram"),
                subtitle: LocalizedText("@vazieapp — новости, релизы и вопросы о приложении", "@vazieapp — news, releases and questions about the app")),
        AppLink(key: "support_email", section: .project, url: URL(string: "mailto:support@vazie.app")!,
                title: LocalizedText("Почта поддержки", "Support email"),
                subtitle: LocalizedText("support@vazie.app — Keep+, оплата, возвраты", "support@vazie.app — Keep+, payments, refunds")),
        AppLink(key: "source", section: .project, url: URL(string: "https://github.com/vazovsky17/VazieKeep")!,
                title: LocalizedText("Исходный код", "Source code"),
                subtitle: LocalizedText("github.com/vazovsky17/VazieKeep", "github.com/vazovsky17/VazieKeep")),
        AppLink(key: "behance", section: .project, url: URL(string: "https://www.behance.net/gallery/256724355/Vazie-Keep-Local-First-Password-Manager")!,
                title: LocalizedText("Поддержать проект на Behance", "Support the project on Behance"),
                subtitle: LocalizedText("behance.net — кейс Vazie Keep", "behance.net — the Vazie Keep case")),
        AppLink(key: "bug_report", section: .feedback, url: URL(string: "https://forms.gle/ugJpPq7F1HyQkrbr8")!,
                title: LocalizedText("Сообщить об ошибке или оставить отзыв", "Report a bug or leave feedback"),
                subtitle: LocalizedText("Короткая форма для ошибок, предложений и отзывов.", "A short form for bug reports, suggestions and feedback.")),
    ]
}

public protocol AppLinksSource: Sendable {
    func fetch() async -> [AppLink]?
}

public struct HTTPAppLinksSource: AppLinksSource {
    private let origin: URL

    public init(sharedOrigin: URL) { origin = sharedOrigin }

    public func fetch() async -> [AppLink]? {
        var request = URLRequest(url: origin.appending(path: "v1/apps/keep/links"), timeoutInterval: 8)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        guard let (data, response) = try? await URLSession.shared.data(for: request), (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return AppLinks.parse(data)
    }
}

/// The About screen's links: what the app shipped with, then the last list the backend published, kept on disk so a
/// cold start offline shows it too. `refresh` asks at most once per `minInterval`; a failure keeps what is there.
@MainActor
@Observable
public final class AppLinksStore {
    public private(set) var links: [AppLink] = AppLinks.shipped

    @ObservationIgnored private let source: any AppLinksSource
    @ObservationIgnored private let file: URL
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let minInterval: TimeInterval
    @ObservationIgnored private var loaded = false
    @ObservationIgnored private var lastAsked: Date?
    @ObservationIgnored private var refreshing = false

    public init(source: any AppLinksSource, file: URL, now: @escaping () -> Date = Date.init, minInterval: TimeInterval = 600) {
        self.source = source
        self.file = file
        self.now = now
        self.minInterval = minInterval
    }

    public func refresh() async {
        guard !refreshing else { return }
        refreshing = true
        defer { refreshing = false }
        if !loaded {
            loaded = true
            if let data = try? Data(contentsOf: file), let stored = AppLinks.parse(data) { links = stored }
        }
        let current = now()
        if let lastAsked, current.timeIntervalSince(lastAsked) < minInterval { return }
        lastAsked = current
        guard let fetched = await source.fetch(), fetched != links else { return }
        links = fetched
        try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? AppLinks.encode(fetched).write(to: file, options: .atomic)
    }
}
