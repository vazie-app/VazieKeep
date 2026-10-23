import Foundation
import KeepCore
import Testing

struct RustKeepCoreTests {
    private let core = RustKeepCore()

    @Test func reportsCoreVersion() {
        let info = core.info()
        #expect(info.name == "keep-core")
        #expect(!info.version.isEmpty)
        #expect(CoreConnection.probe(core) == .connected(version: info.version))
    }

    @Test func detectsKdbx4Header() {
        let header = Data([0x03, 0xD9, 0xA2, 0x9A, 0x67, 0xFB, 0x4B, 0xB5, 0x01, 0x00, 0x04, 0x00])
        #expect(core.detectVaultFormat(header: header) == .kdbx(major: 4, minor: 1))
    }

    @Test func rejectsForeignFile() {
        #expect(core.detectVaultFormat(header: Data("PK\u{3}\u{4}".utf8)) == .unknown)
    }
}
