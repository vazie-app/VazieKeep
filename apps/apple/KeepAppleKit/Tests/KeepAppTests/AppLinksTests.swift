import Foundation
import Testing
@testable import KeepApp

@Suite("About-screen links")
struct AppLinksTests {
    let body = Data("""
    {"links":[
      {"key":"developer","section":"AUTHOR","url":"https://t.me/vazovsky17","title":{"ru":"Автор","en":"Author"}},
      {"key":"mail","section":"PROJECT","url":"mailto:support@vazie.app","title":{"ru":"Почта","en":"Email"},"subtitle":{"ru":"Пишите","en":"Write"}},
      {"key":"script","section":"PROJECT","url":"javascript:alert(1)","title":{"ru":"X","en":"X"}},
      {"key":"plain","section":"PROJECT","url":"http://vazie.app","title":{"ru":"X","en":"X"}},
      {"key":"half","section":"PROJECT","url":"https://vazie.app","title":{"ru":"Только","en":""}},
      {"key":"future","section":"BANNER","url":"https://vazie.app","title":{"ru":"X","en":"X"}},
      {"key":"form","section":"FEEDBACK","url":"https://forms.gle/x","title":{"ru":"Отзыв","en":"Feedback"}}
    ]}
    """.utf8)

    @Test func onlyShowableLinksAreKeptInOrder() throws {
        let links = try #require(AppLinks.parse(body))
        #expect(links.map(\.key) == ["developer", "mail", "form"])
        #expect(links[1].subtitle?.pick(russian: false) == "Write")
        #expect(links[1].title.pick(russian: true) == "Почта")
    }

    @Test func nothingShowableIsNoAnswer() {
        #expect(AppLinks.parse(Data(#"{"links":[]}"#.utf8)) == nil)
        #expect(AppLinks.parse(Data("<html>".utf8)) == nil)
    }

    @Test func onlyHTTPSAndMailtoOpen() {
        #expect(AppLinks.openable("https://github.com/vazovsky17/VazieKeep") != nil)
        #expect(AppLinks.openable("mailto:support@vazie.app") != nil)
        #expect(AppLinks.openable("https:///nohost") == nil)
        #expect(AppLinks.openable("intent://x#Intent;end") == nil)
        #expect(AppLinks.openable("mailto:@x") == nil)
        #expect(AppLinks.openable("https://vazie.app/a b") == nil)
    }

    @Test func shippedLinksSurviveTheirOwnRoundTrip() {
        #expect(AppLinks.parse(AppLinks.encode(AppLinks.shipped)) == AppLinks.shipped)
        #expect(AppLinks.shipped.inSection(.feedback).map(\.url.absoluteString) == ["https://forms.gle/ugJpPq7F1HyQkrbr8"])
    }

    @MainActor
    @Test func theBackendsListReplacesTheShippedOneIsKeptAndSurvivesAFailure() async throws {
        let file = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString).appending(path: "app_links.json")
        let fetched = try #require(AppLinks.parse(body))
        let source = ScriptedLinks(fetched)
        var clock = Date(timeIntervalSince1970: 0)
        let store = AppLinksStore(source: source, file: file, now: { clock })

        #expect(store.links == AppLinks.shipped)
        await store.refresh()
        #expect(store.links == fetched)
        #expect(FileManager.default.fileExists(atPath: file.path))

        await store.refresh()
        #expect(await source.asked == 1)

        clock = clock.addingTimeInterval(700)
        await source.answer(nil)
        await store.refresh()
        #expect(await source.asked == 2)
        #expect(store.links == fetched)

        let offline = AppLinksStore(source: ScriptedLinks(nil), file: file)
        await offline.refresh()
        #expect(offline.links == fetched)
    }
}

private actor ScriptedLinks: AppLinksSource {
    private var next: [AppLink]?
    private(set) var asked = 0

    init(_ next: [AppLink]?) { self.next = next }

    func answer(_ value: [AppLink]?) { next = value }

    func fetch() async -> [AppLink]? {
        asked += 1
        return next
    }
}
