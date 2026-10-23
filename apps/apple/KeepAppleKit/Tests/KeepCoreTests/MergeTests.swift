import Foundation
import KeepCore
import Testing

/// Merging two versions of a database (ADR 0023), against the real Rust core.
struct MergeTests {
    private let core = RustKeepCore()
    private let password = "correct horse"

    private func credentials(_ text: String? = nil) -> VaultCredentials {
        VaultCredentials(password: Data((text ?? password).utf8))
    }

    private func draft(_ title: String, _ secret: String) -> EntryDraft {
        EntryDraft(title: title, username: "user", password: Data(secret.utf8))
    }

    private func open(_ bytes: Data) async throws -> KeepVaultSession {
        try await core.openVault(data: bytes, credentials: credentials())
    }

    private func saved(_ session: KeepVaultSession) throws -> Data {
        let snapshot = try session.prepareSave()
        #expect(try session.markSaved(generation: snapshot.generation))
        return snapshot.bytes
    }

    private func base(_ entries: [(String, String)]) async throws -> Data {
        let first = try await open(VaultCreation.make(name: "Shared", password: password))
        defer { first.close() }
        let root = try first.info().rootGroupID
        for (title, secret) in entries { _ = try first.createEntry(in: root, draft(title, secret)) }
        return try saved(first)
    }

    private func id(_ session: KeepVaultSession, _ title: String) throws -> EntryID {
        try #require(try session.entries().first { $0.title == .plain(title) }).id
    }

    private func secret(_ session: KeepVaultSession, _ id: EntryID) throws -> String {
        try session.password(for: id).withString { $0 }
    }

    /// Edits in the same second tie, and a tie is a clash.
    private func nextSecond() async throws {
        try await Task.sleep(for: .milliseconds(1100))
    }

    @Test func mergesASecondVersionAndMarksTheSessionDirty() async throws {
        let bytes = try await base([("Mail", "m0")])
        let mine = try await open(bytes)
        let theirs = try await open(bytes)
        defer { mine.close() }
        try await nextSecond()
        try theirs.updateEntry(try id(theirs, "Mail"), draft("Mail", "m1"))
        _ = try theirs.createEntry(in: try theirs.info().rootGroupID, draft("Shop", "s1"))
        let theirsBytes = try saved(theirs)
        theirs.close()
        #expect(try !mine.isDirty())

        let report = try mine.merge(theirsBytes, credentials: credentials())
        #expect(report.changed)
        #expect(report.entriesUpdated == 1 && report.entriesAdded == 1)
        #expect(report.conflicts.isEmpty)
        #expect(try mine.isDirty())
        #expect(try secret(mine, try id(mine, "Mail")) == "m1")

        let merged = try saved(mine)
        #expect(try !mine.merge(theirsBytes, credentials: credentials()).changed)
        #expect(try !mine.isDirty())
        let reopened = try await open(merged)
        defer { reopened.close() }
        #expect(try reopened.entries().count == 2)
    }

    @Test func aClashKeepsTheLoserAsACopyTheReportNames() async throws {
        let bytes = try await base([("Mail", "m0")])
        let mine = try await open(bytes)
        let theirs = try await open(bytes)
        defer { mine.close() }
        try await nextSecond()
        try mine.updateEntry(try id(mine, "Mail"), draft("Mail", "m-mine"))
        try await nextSecond()
        try theirs.updateEntry(try id(theirs, "Mail"), draft("Mail", "m-theirs"))
        let theirsBytes = try saved(theirs)
        theirs.close()

        let report = try mine.merge(theirsBytes, credentials: credentials(), options: MergeOptions(conflictSuffix: " (конфликт)"))
        let copy = try id(mine, "Mail (конфликт)")
        #expect(report.conflicts == [copy])
        #expect(try secret(mine, copy) == "m-mine")
        #expect(try secret(mine, try id(mine, "Mail")) == "m-theirs")
    }

    @Test func refusesWhatItCannotAndChangesNothing() async throws {
        let mine = try await open(try await base([("Mail", "m0")]))
        defer { mine.close() }
        let other = try VaultCreation.make(name: "Other", password: password)

        #expect(throws: VaultMergeError(reason: .invalidCredentials)) { try mine.merge(other, credentials: credentials("nope")) }
        #expect(throws: VaultMergeError(reason: .notAVault)) { try mine.merge(Data("not a vault".utf8), credentials: credentials()) }
        #expect(throws: VaultMergeError(reason: .differentVault)) { try mine.merge(other, credentials: credentials()) }
        #expect(try !mine.isDirty())

        let report = try mine.merge(other, credentials: credentials(), options: MergeOptions(allowUnrelated: true))
        #expect(!report.changed)

        mine.close()
        #expect(throws: VaultMergeError(reason: .sessionClosed)) { try mine.merge(other, credentials: credentials()) }
    }
}
