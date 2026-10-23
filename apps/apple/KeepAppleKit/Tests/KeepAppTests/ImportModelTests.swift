import Foundation
import KeepApp
import KeepCore
import Testing

@MainActor
struct ImportModelTests {
    let documents = FakeDocuments()
    let opener = FakeOpener()
    let store: MemoryReferenceStore
    let summary = ImportSummary(source: .bitwardenJSON, entries: 3, folders: 1, withTotp: 1, skipped: 0)

    init() {
        documents.put("vault.kdbx", kdbxHeader)
        store = MemoryReferenceStore(VaultReference(vault: reference("vault.kdbx")))
    }

    func unlocked() async -> (VaultSessionController, FakeSession) {
        let controller = VaultSessionController(opener: opener, documents: documents, references: store, settings: { SecuritySettings() })
        _ = await controller.unlock(password: Data("pw".utf8))
        return (controller, opener.sessions.withLock { $0.last! })
    }

    func model(_ controller: VaultSessionController, failing reason: ImportError.Reason? = nil) -> ImportModel {
        let summary = summary
        return ImportModel(controller: controller) { _ throws(ImportError) in
            if let reason { throw ImportError(reason: reason) }
            return ImportPlan(summary: summary)
        }
    }

    @Test func aFileIsShownBeforeAnythingIsAdded() async {
        let (controller, session) = await unlocked()
        let model = model(controller)
        await model.read(Data("export".utf8))
        #expect(model.phase == .preview(summary))
        #expect(session.imports.withLock { $0 }.isEmpty)
    }

    @Test func confirmingAddsTheEntriesAndSavesTheVault() async {
        let (controller, session) = await unlocked()
        let model = model(controller)
        await model.read(Data("export".utf8))
        await model.confirm()
        #expect(model.phase == .done(ImportOutcome(entries: 3, groups: 1)))
        #expect(session.imports.withLock { $0 } == [3])
        #expect(!controller.hasUnsavedChanges)
        #expect(model.saveFailure == nil)
    }

    @Test func aFileKeepCannotReadSaysWhy() async {
        let (controller, _) = await unlocked()
        let encrypted = model(controller, failing: .encrypted)
        await encrypted.read(Data("x".utf8))
        #expect(encrypted.phase == .failed(.encrypted))

        let missing = model(controller)
        await missing.read(nil)
        #expect(missing.phase == .failed(.unreadable))
        missing.dismiss()
        await missing.read(Data(), tooLarge: true)
        #expect(missing.phase == .failed(.tooLarge))
    }

    @Test func aRefusedImportLeavesTheVaultAlone() async {
        let (controller, session) = await unlocked()
        session.importFailure.withLock { $0 = .invalidText }
        let model = model(controller)
        await model.read(Data("export".utf8))
        await model.confirm()
        #expect(model.phase == .failed(.failed))
        #expect(!controller.hasUnsavedChanges)
    }

    @Test func cancellingForgetsTheExport() async {
        let (controller, session) = await unlocked()
        let model = model(controller)
        await model.read(Data("export".utf8))
        model.dismiss()
        #expect(model.phase == .idle)
        await model.confirm()
        #expect(session.imports.withLock { $0 }.isEmpty)
    }
}
