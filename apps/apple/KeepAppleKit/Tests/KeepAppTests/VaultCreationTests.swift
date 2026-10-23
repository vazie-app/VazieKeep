import Foundation
import Testing
@testable import KeepApp
@testable import KeepCore

@Suite("Vault creation")
struct VaultCreationTests {
    @Test func makesAKdbxFileAndRefusesMissingInput() throws {
        let bytes = try VaultCreation.make(name: "Home", password: "correct horse")
        #expect(bytes.prefix(4) == Data([0x03, 0xD9, 0xA2, 0x9A]))
        #expect(throws: VaultCreationError.emptyName) { try VaultCreation.make(name: "  ", password: "x") }
        let keyFile = try VaultCreation.generateKeyFile()
        #expect(try VaultCreation.make(name: "Keyed", password: "", keyFile: keyFile, preset: .balanced).count > 0)
        #expect(throws: VaultCreationError.missingCredentials) { try VaultCreation.make(name: "Home", password: "") }
    }

    @Test func namesTheFileSafelyAndNeverOverwritesAnother() throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let first = try VaultSessionController.write(Data([1]), named: "a/b: c", in: directory)
        let second = try VaultSessionController.write(Data([2]), named: "a/b: c", in: directory)
        #expect(first.lastPathComponent == "a-b- c.kdbx")
        #expect(second.lastPathComponent == "a-b- c 2.kdbx")
        #expect(try Data(contentsOf: first) == Data([1]))
    }
}
