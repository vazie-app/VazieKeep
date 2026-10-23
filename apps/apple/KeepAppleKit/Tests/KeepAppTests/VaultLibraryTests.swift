import Foundation
import KeepApp
import KeepCore
import Synchronization
import Testing

final class MemoryVaultListStore: VaultListStore {
    private let state = Mutex<VaultListSnapshot?>(nil)

    init(_ snapshot: VaultListSnapshot? = nil) { state.withLock { $0 = snapshot } }

    var current: VaultListSnapshot? { state.withLock { $0 } }
    func load() -> VaultListSnapshot? { current }
    func save(_ snapshot: VaultListSnapshot) { state.withLock { $0 = snapshot } }
}

@MainActor
final class PlanBox {
    var plan: VaultPlan = .free
}

@MainActor
struct VaultLibraryTests {
    let documents = FakeDocuments()
    let opener = FakeOpener()
    let references = MemoryReferenceStore()
    let list = MemoryVaultListStore()
    let box = PlanBox()

    init() {
        for name in ["a.kdbx", "b.kdbx", "c.kdbx"] { documents.put(name, kdbxHeader) }
    }

    func make() -> (VaultSessionController, VaultLibrary) {
        let controller = VaultSessionController(opener: opener, documents: documents, references: references, settings: { SecuritySettings() })
        let box = box
        return (controller, VaultLibrary(controller: controller, store: list, plan: { box.plan }))
    }

    func url(_ name: String) -> URL { URL(fileURLWithPath: "/vaults/\(name)") }

    // MARK: Rules

    @Test func keepPlusLeavesEveryVaultEditableAndLetsMoreBeAdded() {
        let rules = VaultRules.of(plan: .plus, vaultIDs: ["a", "b", "c"], storedMain: "a")
        #expect(rules.canEdit("a") && rules.canEdit("c"))
        #expect(rules.canAdd)
        #expect(!rules.mustChooseMain)
    }

    @Test func aFreeAccountKeepsItsOneVaultAndCannotAddASecond() {
        #expect(VaultRules.of(plan: .free, vaultIDs: [], storedMain: nil).canAdd)
        let one = VaultRules.of(plan: .free, vaultIDs: ["a"], storedMain: nil)
        #expect(one.canEdit("a"))
        #expect(!one.canAdd)
        #expect(!one.mustChooseMain)
    }

    @Test func aFreeAccountWithSeveralVaultsEditsOnlyTheMainOne() {
        let asking = VaultRules.of(plan: .free, vaultIDs: ["a", "b"], storedMain: nil)
        #expect(asking.mustChooseMain)
        #expect(!asking.canEdit("a") && !asking.canEdit("b"))
        let chosen = VaultRules.of(plan: .free, vaultIDs: ["a", "b"], storedMain: "b")
        #expect(!chosen.mustChooseMain)
        #expect(chosen.canEdit("b") && !chosen.canEdit("a"))
        #expect(VaultRules.of(plan: .free, vaultIDs: ["a", "b"], storedMain: "gone").mustChooseMain)
    }

    @Test func anUncheckedLeaseAsksNothingAndKeepsOneVaultEditable() {
        let never = VaultRules.of(plan: .unverified, vaultIDs: ["a", "b"], storedMain: nil)
        #expect(!never.mustChooseMain)
        #expect(never.canEdit("a") && !never.canEdit("b"))
        #expect(!never.canAdd)
    }

    @Test func thePlanFollowsTheAccess() {
        #expect(KeepAccess.free.vaultPlan == .free)
        #expect(KeepAccess(state: .plusActive, plusFeatures: true, needsOnlineVerification: false, leaseValidUntil: nil, observedDeviceTime: 0).vaultPlan == .plus)
        #expect(KeepAccess(state: .plusExpired, plusFeatures: false, needsOnlineVerification: true, leaseValidUntil: 1, observedDeviceTime: 0).vaultPlan == .unverified)
    }

    // MARK: Library

    @Test func theVaultTheAppAlreadyKnewBecomesTheFirstEntry() {
        references.save(VaultReference(vault: reference("a.kdbx")))
        let (_, library) = make()
        #expect(library.vaults.map(\.name) == ["a"])
        #expect(library.activeID == library.vaults[0].id)
        #expect(list.current?.vaults.count == 1)
    }

    @Test func withoutKeepPlusASecondVaultIsRefusedButTheKnownOneStillOpens() async {
        let (controller, library) = make()
        #expect(await controller.chooseVault(url("a.kdbx")) == .success(reference("a.kdbx")))
        #expect(library.vaults.count == 1)
        #expect(await controller.chooseVault(url("b.kdbx")) == .failure(.needsPlus))
        #expect(library.vaults.count == 1)
        #expect(controller.reference?.vault == reference("a.kdbx"))
        #expect(await controller.chooseVault(url("a.kdbx")) == .success(reference("a.kdbx")))
        #expect(await controller.createVault(name: "New", password: "pw", in: URL(fileURLWithPath: NSTemporaryDirectory())) == .failure(.needsPlus))
    }

    @Test func withKeepPlusVaultsAreAddedAndSwitched() async {
        box.plan = .plus
        let (controller, library) = make()
        _ = await controller.chooseVault(url("a.kdbx"))
        _ = await controller.chooseVault(url("b.kdbx"))
        #expect(library.vaults.map(\.name) == ["a", "b"])
        #expect(library.active?.name == "b")

        library.activate(library.vaults[0].id)
        #expect(controller.reference?.vault == reference("a.kdbx"))
        #expect(library.active?.name == "a")
        #expect(references.current?.vault == reference("a.kdbx"))

        library.rename(library.vaults[0].id, to: "  Family  ")
        #expect(library.vaults[0].name == "Family")
    }

    @Test func theMainVaultIsChosenOnceAndTheOthersRefuseEdits() async {
        box.plan = .plus
        let (controller, library) = make()
        _ = await controller.chooseVault(url("a.kdbx"))
        _ = await controller.chooseVault(url("b.kdbx"))
        let a = library.vaults[0].id
        let b = library.vaults[1].id
        #expect(!library.chooseMain(a), "nothing is asked while Keep+ is on")

        box.plan = .free
        #expect(library.rules.mustChooseMain)
        #expect(library.chooseMain(a))
        #expect(!library.chooseMain(b), "the choice is made once")
        #expect(library.rules.mainID == a)
        #expect(list.current?.mainID == a)

        #expect(library.activeIsReadOnly, "b is the chosen vault and it is not the main one")
        #expect(await controller.unlock(password: Data("pw".utf8)) == .success)
        let refused = await controller.edit { session throws(VaultEditError) in try session.setFavorite(FakeSession.mail, true) }
        guard case let .failed(error) = refused else {
            Issue.record("an edit went through in a read-only vault")
            return
        }
        #expect(error.reason == .readOnly)

        library.activate(a)
        #expect(!library.activeIsReadOnly)
        #expect(await controller.unlock(password: Data("pw".utf8)) == .success)
        guard case .saved = await controller.edit({ session throws(VaultEditError) in try session.setFavorite(FakeSession.mail, true) }) else {
            Issue.record("the main vault refused an edit")
            return
        }

        box.plan = .plus
        #expect(!library.activeIsReadOnly)
        library.activate(b)
        #expect(!library.activeIsReadOnly)
        box.plan = .free
        #expect(library.rules.mainID == a, "the main vault stays after Keep+ comes and goes")
        #expect(library.activeIsReadOnly)
    }

    @Test func forgettingTheChosenVaultLeavesTheOthers() async {
        box.plan = .plus
        let (controller, library) = make()
        _ = await controller.chooseVault(url("a.kdbx"))
        _ = await controller.chooseVault(url("b.kdbx"))
        library.forget(library.vaults[1].id)
        #expect(library.vaults.map(\.name) == ["a"])
        #expect(library.activeID == nil)
        #expect(controller.reference == nil)

        library.activate(library.vaults[0].id)
        #expect(controller.reference?.vault == reference("a.kdbx"))
        library.forgetAll()
        #expect(library.vaults.isEmpty)
        #expect(controller.reference == nil)
    }

    // MARK: One file, one vault

    /// A file reached in two ways: "old:a.kdbx" and "a.kdbx" are the same place.
    func place(_ reference: DocumentReference) -> String? {
        String(decoding: reference.bookmark, as: UTF8.self).replacingOccurrences(of: "old:", with: "")
    }

    func makeLocated(_ snapshot: VaultListSnapshot) -> (VaultSessionController, VaultLibrary, MemoryVaultListStore) {
        let store = MemoryVaultListStore(snapshot)
        let controller = VaultSessionController(opener: opener, documents: documents, references: references, settings: { SecuritySettings() })
        let box = box
        return (controller, VaultLibrary(controller: controller, store: store, plan: { box.plan }, location: { [self] in place($0) }), store)
    }

    @Test func severalFilesOfOneDatabaseCountAsOneVault() {
        let same = VaultRules.of(plan: .free, vaultIDs: ["a", "b"], storedMain: nil, fingerprints: ["a": "f1", "b": "f1"])
        #expect(!same.mustChooseMain)
        #expect(same.canEdit("a") && same.canEdit("b"))
        #expect(!same.canAdd)
        let mixed = VaultRules.of(plan: .free, vaultIDs: ["a", "b", "c"], storedMain: "a", fingerprints: ["a": "f1", "b": "f1", "c": "f2"])
        #expect(mixed.canEdit("a") && mixed.canEdit("b") && !mixed.canEdit("c"))
        let unknown = VaultRules.of(plan: .free, vaultIDs: ["a", "b"], storedMain: nil, fingerprints: ["a": "f1"])
        #expect(unknown.mustChooseMain)
    }

    @Test func aRenewedWayToTheFileIsStillTheSameVault() {
        let known = KnownVault(id: "one", name: "a", reference: VaultReference(vault: reference("old:a.kdbx")))
        references.save(VaultReference(vault: reference("a.kdbx")))
        let (_, library, store) = makeLocated(VaultListSnapshot(vaults: [known], activeID: "one"))
        #expect(library.vaults.map(\.id) == ["one"])
        #expect(library.activeID == "one")
        #expect(store.current?.vaults.first?.reference.vault == reference("a.kdbx"))
    }

    @Test func theSameFilePickedAgainIsNotASecondVault() async {
        let known = KnownVault(id: "one", name: "Mine", reference: VaultReference(vault: reference("old:a.kdbx"), keyFile: reference("my.keyx")))
        let (controller, library, _) = makeLocated(VaultListSnapshot(vaults: [known]))
        #expect(await controller.chooseVault(url("a.kdbx")) == .success(reference("a.kdbx")))
        #expect(library.vaults.map(\.id) == ["one"])
        #expect(library.activeID == "one")
        #expect(controller.reference?.keyFile == reference("my.keyx"))
    }

    @Test func entriesOfOneFileAreMergedAndTheMainVaultStays() async {
        let first = KnownVault(id: "one", name: "a", reference: VaultReference(vault: reference("old:a.kdbx")))
        let second = KnownVault(id: "two", name: "a", reference: VaultReference(vault: reference("a.kdbx")))
        let other = KnownVault(id: "three", name: "b", reference: VaultReference(vault: reference("b.kdbx")))
        let (_, library, store) = makeLocated(VaultListSnapshot(vaults: [first, second, other], mainID: "two"))
        var forgotten: [String] = []
        library.onForgotten = { id, _ in forgotten.append(id) }
        await library.mergeDuplicates()
        #expect(library.vaults.map(\.id) == ["two", "three"])
        #expect(library.mainID == "two")
        #expect(forgotten == ["one"])
        #expect(store.current?.vaults.count == 2)
    }

    @Test func theDatabaseIsRememberedAfterUnlock() async {
        let (controller, library) = make()
        _ = await controller.chooseVault(url("a.kdbx"))
        library.vaultUnlocked(fingerprint: "f1")
        #expect(list.current?.vaults.first?.fingerprint == "f1")
    }

    @Test func aKeyFileChoiceIsKeptWithItsVault() async {
        box.plan = .plus
        documents.put("my.keyx", Data("key".utf8))
        let (controller, library) = make()
        _ = await controller.chooseVault(url("a.kdbx"))
        _ = await controller.chooseKeyFile(url("my.keyx"))
        _ = await controller.chooseVault(url("b.kdbx"))
        #expect(controller.reference?.keyFile == nil)
        library.activate(library.vaults[0].id)
        #expect(controller.reference?.keyFile == reference("my.keyx"))
    }
}
