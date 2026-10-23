import Foundation
import KeepCoreBindings

public enum VaultCreationError: Error, Equatable, Sendable {
    case emptyName
    case missingCredentials
    case invalidKeyFile
    case failed
}

public enum SecurityPreset: Sendable, CaseIterable {
    case balanced
    case stronger

    var dto: SecurityPresetDto { self == .balanced ? securityPresetBalanced() : securityPresetStronger() }

    /// Milliseconds one key derivation takes on this device.
    public func benchmarkMilliseconds() -> Int? {
        (try? benchmarkKdf(preset: dto)).map(Int.init)
    }
}

public enum VaultCreation {
    /// A new KDBX 4 file protected by `password`, a key file, or both.
    public static func make(name: String, password: String, keyFile: Data? = nil, preset: SecurityPreset = .balanced) throws(VaultCreationError) -> Data {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw .emptyName }
        guard !password.isEmpty || keyFile != nil else { throw .missingCredentials }
        let settings = preset.dto
        let config = CreateVaultConfigDto(name: trimmed, memoryKib: settings.memoryKib, iterations: settings.iterations, parallelism: settings.parallelism)
        let typed: PasswordDto? = password.isEmpty ? nil : .text(utf8: Data(password.utf8))
        let credentials = VaultCredentialsDto(password: typed, keyFile: keyFile, retainUnlockMaterial: false)
        do {
            return try createVault(config: config, credentials: credentials).bytes
        } catch {
            switch String(describing: error) {
            case "InvalidKeyFile": throw .invalidKeyFile
            case "EmptyName": throw .emptyName
            case "MissingCredentials": throw .missingCredentials
            default: throw .failed
            }
        }
    }

    /// A new KeePass XML key file made from the system CSPRNG.
    public static func generateKeyFile() throws(VaultCreationError) -> Data {
        do { return try KeepCoreBindings.generateKeyFile().utf8 } catch { throw .failed }
    }
}
