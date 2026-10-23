import Foundation
import KeepCore
import Testing

private let marker = "VAZIE_KEEP_SECRET_DO_NOT_LEAK_42"

private final class CoreBundleToken {}

private func vector(_ name: String) -> Data {
    let bundled = Bundle(for: CoreBundleToken.self).url(forResource: (name as NSString).deletingPathExtension, withExtension: "kdbx", subdirectory: "kdbx4")
    let url = bundled ?? URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .appending(path: "../../../../../shared/test-vectors/kdbx4/\(name)")
        .standardizedFileURL
    return (try? Data(contentsOf: url)) ?? Data()
}

private func open(_ name: String, _ password: String) async throws -> KeepVaultSession {
    try await RustKeepCore().openVault(data: vector(name), password: Data(password.utf8))
}

struct VaultSessionTests {
    @Test func readsStructureAndSummariesWithoutSecrets() async throws {
        let session = try await open("aes256-argon2d.kdbx", "correct horse battery staple")
        defer { session.close() }
        let info = try session.info()
        #expect(info.name == "Synthetic Test Vault")
        let web = try #require(try session.groups().first { $0.name == "Web" && $0.parentID == info.rootGroupID })
        let mail = try #require(try session.entries().first { $0.groupID == web.id })
        #expect(mail.title == .plain("Example Mail"))
        #expect(mail.hasPassword && mail.hasProtectedFields)
        let detail = try session.entry(mail.id)
        #expect(detail.customFields.first { $0.name == "Recovery Code" }?.value == .hidden)
        #expect(try session.search("MAIL.example").map(\.id) == [mail.id])
    }

    @Test func revealsOneSecretOnDemandAndWipesIt() async throws {
        let session = try await open("leak-marker.kdbx", marker)
        defer { session.close() }
        let entry = try #require(try session.entries().first)
        let secret = try session.password(for: entry.id)
        #expect(try secret.withString { $0 } == marker)
        secret.wipe()
        #expect(secret.isWiped)
        #expect(throws: SecretValueError.wiped) { try secret.withBytes { $0 } }
        let field = try session.reveal(.custom("Marker Field"), for: entry.id)
        #expect(try field.withString { $0 } == marker)
    }

    @Test func formattedValuesNeverContainSecrets() async throws {
        let session = try await open("leak-marker.kdbx", marker)
        defer { session.close() }
        let entry = try #require(try session.entries().first)
        let values: [Any] = [session, try session.info(), try session.groups(), entry, try session.entry(entry.id), try session.password(for: entry.id)]
        var dumped = ""
        for value in values {
            dump(value, to: &dumped)
            dumped += String(describing: value) + String(reflecting: value)
        }
        #expect(!dumped.contains(marker))
    }

    @Test func closedSessionRejectsCalls() async throws {
        let session = try await open("aes256-argon2d.kdbx", "correct horse battery staple")
        let entry = try #require(try session.entries().first)
        session.close()
        session.close()
        #expect(session.isClosed)
        #expect(throws: VaultSessionError(reason: .sessionClosed)) { try session.password(for: entry.id) }
    }

    @Test func sessionsAreIndependent() async throws {
        let a = try await open("aes256-argon2d.kdbx", "correct horse battery staple")
        let b = try await open("chacha20-aeskdf-kdbx41.kdbx", "correct horse battery staple")
        a.close()
        defer { b.close() }
        let only = try #require(try b.entries().first)
        #expect(try b.password(for: only.id).withString { $0 } == "correct horse battery staple!")
    }

    @Test func invalidInputsMapToTypedErrors() async throws {
        let session = try await open("aes256-argon2d.kdbx", "correct horse battery staple")
        defer { session.close() }
        #expect(throws: VaultSessionError(reason: .invalidArgument)) { try session.entry(EntryID("entry[3]")) }
        #expect(throws: VaultSessionError(reason: .entryNotFound)) {
            try session.password(for: EntryID("00000000-0000-0000-0000-000000000000"))
        }
        await #expect(throws: VaultOpenError(reason: .invalidCredentials)) {
            try await open("aes256-argon2d.kdbx", "wrong")
        }
    }
}
