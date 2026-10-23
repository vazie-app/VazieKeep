import Foundation
import KeepApp
import KeepCore
import Testing

@MainActor
struct HistoryModelTests {
    let documents = FakeDocuments()
    let opener = FakeOpener()
    let store: MemoryReferenceStore
    let clipboard = FakeClipboard()
    let plus = KeepAccess(state: .plusActive, plusFeatures: true, needsOnlineVerification: false, leaseValidUntil: nil, observedDeviceTime: 0)

    init() {
        documents.put("vault.kdbx", kdbxHeader)
        store = MemoryReferenceStore(VaultReference(vault: reference("vault.kdbx")))
    }

    func unlocked() async -> (VaultSessionController, FakeSession) {
        let controller = VaultSessionController(opener: opener, documents: documents, references: store, settings: { SecuritySettings() })
        _ = await controller.unlock(password: Data("pw".utf8))
        let session = opener.sessions.withLock { $0.last! }
        session.versions.withLock { $0[FakeSession.mail] = [0: "first", 1: "second"] }
        return (controller, session)
    }

    func version(_ index: Int, _ controller: VaultSessionController, allowed: Bool = true) -> HistoryVersionModel {
        HistoryVersionModel(
            entryID: FakeSession.mail, index: index, controller: controller, clipboard: clipboard, clipboardTimeout: { .seconds(30) },
            sleep: { _ in try? await Task.sleep(for: .seconds(3600)) }, access: { allowed ? plus : .free }
        )
    }

    @Test func withoutKeepPlusTheHistoryIsNotRead() async {
        let (controller, _) = await unlocked()
        let list = HistoryModel(entryID: FakeSession.mail, controller: controller, access: { .free })
        list.load()
        #expect(list.phase == .needsPlus)
        #expect(list.versions.isEmpty)

        let model = version(0, controller, allowed: false)
        model.load()
        #expect(model.phase == .needsPlus)
        model.reveal(.password)
        #expect(model.revealed == nil)
        #expect(model.copy(.password) == .failed)
        #expect(await model.restore() == .failed)
    }

    @Test func versionsAreListedNewestFirst() async {
        let (controller, _) = await unlocked()
        let list = HistoryModel(entryID: FakeSession.mail, controller: controller, access: { plus })
        list.load()
        #expect(list.phase == .loaded)
        #expect(list.versions.map(\.index) == [1, 0])
    }

    @Test func aVersionShowsAndCopiesItsOwnPassword() async {
        let (controller, _) = await unlocked()
        let model = version(0, controller)
        model.load()
        #expect(model.phase == .loaded)
        #expect(model.version?.index == 0)

        model.toggle(.password)
        #expect(model.revealed?.text == "first")
        model.toggle(.password)
        #expect(model.revealed == nil)

        #expect(model.copy(.password) == .copied(clearsAfter: .seconds(30)))
        #expect(clipboard.items.map(\.0) == ["first"])
    }

    @Test func aRevealedValueHidesItselfAfterAWhile() async {
        let (controller, _) = await unlocked()
        let gate = AsyncStream<Void>.makeStream()
        let model = HistoryVersionModel(
            entryID: FakeSession.mail, index: 1, controller: controller, clipboard: clipboard, clipboardTimeout: { nil },
            sleep: { _ in for await _ in gate.stream { break } }, access: { plus }
        )
        model.load()
        model.reveal(.password)
        #expect(model.revealed?.text == "second")
        gate.continuation.yield()
        for _ in 0..<50 where model.revealed != nil { await Task.yield() }
        #expect(model.revealed == nil)
    }

    @Test func restoringSavesTheVault() async {
        let (controller, session) = await unlocked()
        let model = version(0, controller)
        model.load()
        model.reveal(.password)

        #expect(await model.restore() == .restored)
        #expect(session.restored.withLock { $0 } == [0])
        #expect(model.revealed == nil)
        #expect(!controller.hasUnsavedChanges)
    }

    @Test func aVersionThatIsGoneIsUnavailable() async {
        let (controller, _) = await unlocked()
        let model = version(7, controller)
        model.load()
        #expect(model.phase == .unavailable)
        #expect(await model.restore() == .failed)
    }
}
