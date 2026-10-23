import Foundation
import KeepCoreBindings

public struct CoreInfo: Equatable, Sendable {
    public let name: String
    public let version: String

    public init(name: String, version: String) {
        self.name = name
        self.version = version
    }
}

public enum VaultFormat: Equatable, Sendable {
    case kdbx(major: Int, minor: Int)
    case keePass1
    case unknown
}

/// KeePass composite-key input. Swift `Data` is copy-on-write and can't be wiped reliably, so the
/// core receives its own copy and the caller should drop this value right after `openVault`.
public struct VaultCredentials: Sendable, CustomStringConvertible {
    public var password: Data?
    /// The password component saved for biometric unlock (ADR 0010). Used instead of `password`.
    public var unlockMaterial: Data?
    public var keyFile: Data?
    /// Keep the password component in the Rust session so biometric unlock can be turned on later.
    public var retainUnlockMaterial: Bool

    public init(password: Data? = nil, unlockMaterial: Data? = nil, keyFile: Data? = nil, retainUnlockMaterial: Bool = false) {
        self.password = password
        self.unlockMaterial = unlockMaterial
        self.keyFile = keyFile
        self.retainUnlockMaterial = retainUnlockMaterial
    }

    public var description: String {
        "VaultCredentials(password: \(password != nil), unlockMaterial: \(unlockMaterial != nil), keyFile: \(keyFile != nil))"
    }
}

public protocol KeepCoreClient: Sendable {
    func info() -> CoreInfo
    func detectVaultFormat(header: Data) -> VaultFormat
    /// Runs the key derivation off the calling actor.
    func openVault(data: Data, credentials: VaultCredentials) async throws(VaultOpenError) -> KeepVaultSession
}

extension KeepCoreClient {
    public func openVault(data: Data, password: Data) async throws(VaultOpenError) -> KeepVaultSession {
        try await openVault(data: data, credentials: VaultCredentials(password: password))
    }
}

public struct RustKeepCore: KeepCoreClient {
    public init() {}

    public func info() -> CoreInfo {
        let dto = coreInfo()
        return CoreInfo(name: dto.name, version: dto.version)
    }

    public func detectVaultFormat(header: Data) -> VaultFormat {
        switch KeepCoreBindings.detectVaultFormat(header: header) {
        case let .kdbx(major, minor): .kdbx(major: Int(major), minor: Int(minor))
        case .keePass1: .keePass1
        case .unknown: .unknown
        }
    }

    public func openVault(data: Data, credentials: VaultCredentials) async throws(VaultOpenError) -> KeepVaultSession {
        let result = await Task.detached(priority: .userInitiated) { () -> Result<KeepVaultSession, VaultOpenError> in
            do {
                let password: PasswordDto? = if let material = credentials.unlockMaterial {
                    .unlockMaterial(bytes: material)
                } else {
                    credentials.password.map { .text(utf8: $0) }
                }
                let credentials = VaultCredentialsDto(
                    password: password,
                    keyFile: credentials.keyFile,
                    retainUnlockMaterial: credentials.retainUnlockMaterial
                )
                let handle = try KeepCoreBindings.openVault(data: data, credentials: credentials)
                return .success(KeepVaultSession(handle: handle))
            } catch let error as OpenVaultError {
                return .failure(VaultOpenError(reason: error.reason))
            } catch {
                return .failure(VaultOpenError(reason: .unavailable))
            }
        }.value
        return try result.get()
    }
}
