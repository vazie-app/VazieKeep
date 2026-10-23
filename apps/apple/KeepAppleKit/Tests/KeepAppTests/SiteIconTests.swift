import Foundation
import KeepApp
import KeepCore
import Synchronization
import Testing

final class FakeSiteHTTP: SiteHTTP {
    let pages = Mutex<[String: Data]>([:])
    let asked = Mutex<[String]>([])

    func get(_ url: String, limit: Int, cut: Bool) async -> Data? {
        asked.withLock { $0.append(url) }
        return pages.withLock { $0[url] }
    }
}

struct FakeSiteIcons: SiteIconSource {
    let answering: Set<String>
    func fetch(host: String) async -> Data? { answering.contains(host) ? Data([1, 2, 3]) : nil }
}

@MainActor
struct SiteIconTests {
    let http = FakeSiteHTTP()

    func icons() -> HTTPSiteIcons {
        HTTPSiteIcons(
            http: http,
            links: { host, html in (html.contains("touch") ? ["https://\(host)/touch.png"] : []) + ["https://\(host)/favicon.ico"] },
            toPNG: { $0.first == 7 ? $0 : nil }
        )
    }

    @Test func theIconThePageNamesComesFirst() async {
        http.pages.withLock {
            $0["https://example.com/"] = Data("touch".utf8)
            $0["https://example.com/touch.png"] = Data([7, 1])
            $0["https://example.com/favicon.ico"] = Data([7, 2])
        }
        #expect(await icons().fetch(host: "example.com") == Data([7, 1]))
        #expect(http.asked.withLock { $0 } == ["https://example.com/", "https://example.com/touch.png"])
    }

    @Test func whatIsNotAnImageIsPassedOverAndASubdomainTakesTheIconOfItsSite() async {
        http.pages.withLock {
            $0["https://accounts.example.com/"] = Data("touch".utf8)
            $0["https://accounts.example.com/touch.png"] = Data("<html>".utf8)
            $0["https://example.com/favicon.ico"] = Data([7, 3])
        }
        #expect(await icons().fetch(host: "accounts.example.com") == Data([7, 3]))
        #expect(await icons().fetch(host: "nobody.example.org") == nil)
        #expect(siteCandidates("a.b.example.com") == ["a.b.example.com", "b.example.com"])
        #expect(siteCandidates("example.com") == ["example.com", "www.example.com"])
    }

    @Test func aPictureBecomesASmallPNGAndAPageDoesNot() throws {
        let pixel = try #require(Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="))
        let png = try #require(iconPNG(pixel))
        #expect(png.starts(with: [0x89, 0x50, 0x4E, 0x47]))
        #expect(iconPNG(Data("<html></html>".utf8)) == nil)
    }

    func unlocked(_ opener: FakeOpener = FakeOpener()) async -> (VaultSessionController, FakeSession) {
        let documents = FakeDocuments()
        documents.put("a.kdbx", kdbxHeader)
        let controller = VaultSessionController(opener: opener, documents: documents, references: MemoryReferenceStore(), settings: { SecuritySettings() })
        _ = await controller.chooseVault(URL(fileURLWithPath: "/vaults/a.kdbx"))
        _ = await controller.unlock(password: Data("pw".utf8))
        return (controller, opener.sessions.withLock { $0.last! })
    }

    @Test func thePassKeepsTheIconsOfTheSitesThatAnswered() async {
        let (controller, session) = await unlocked()
        session.iconHosts.withLock { $0 = ["a.example", "b.example"] }
        let pass = SiteIconPass(controller: controller, source: FakeSiteIcons(answering: ["a.example"]))
        #expect(await pass.run() == .assigned(1))
        #expect(pass.progress == nil)
        #expect(session.images.withLock { $0.keys.sorted() } == ["a.example"])
    }

    @Test func whenNoSiteAnswersThePersonIsTold() async {
        let (controller, session) = await unlocked()
        session.iconHosts.withLock { $0 = ["a.example"] }
        #expect(await SiteIconPass(controller: controller, source: FakeSiteIcons(answering: [])).run() == .unavailable)
        session.iconHosts.withLock { $0 = [] }
        #expect(await SiteIconPass(controller: controller, source: FakeSiteIcons(answering: [])).run() == .nothing)
    }

    @Test func aSavedEntryGetsItsSiteIconOnlyWhenThatIsSwitchedOn() async {
        let (controller, session) = await unlocked()
        session.entryIconHost.withLock { $0 = "a.example" }
        session.iconHosts.withLock { $0 = ["a.example"] }
        let source = FakeSiteIcons(answering: ["a.example"])
        #expect(SiteIconUpdater(controller: controller, source: source, enabled: { false }).entrySaved(EntryID("e")) == nil)
        #expect(session.images.withLock { $0.isEmpty })
        await SiteIconUpdater(controller: controller, source: source, enabled: { true }).entrySaved(EntryID("e"))?.value
        #expect(session.images.withLock { $0.keys.sorted() } == ["a.example"])
        session.entryIconHost.withLock { $0 = nil }
        #expect(SiteIconUpdater(controller: controller, source: source, enabled: { true }).entrySaved(EntryID("e")) == nil, "no website, nothing asked")
    }
}
