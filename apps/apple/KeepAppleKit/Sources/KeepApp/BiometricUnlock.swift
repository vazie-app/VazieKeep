import Foundation
import KeepCore
import LocalAuthentication
import Observation

public enum BiometricAvailability: Equatable, Sendable {
    case available
    case noHardware
    case notEnrolled
    case passcodeNotSet
    /// The user turned Face ID off for this app.
    case denied
    case lockedOut
}

public enum BiometricStatus: Equatable, Sendable {
    case unavailable(BiometricAvailability)
    case off
    case on(keyFileRequired: Bool)
    /// A setup existed but can no longer be used: enrollment changed, the item is gone, or the
    /// vault no longer accepts it. Only the user's action clears this.
    case needsSetup
}

/// An authenticated `LAContext`, handed from the authenticator to the credential store so the
/// Keychain doesn't prompt a second time.
public struct BiometricAuthorization: @unchecked Sendable {
    public let context: LAContext?

    public init(context: LAContext?) {
        self.context = context
    }
}

public enum BiometricAuthResult: Sendable {
    case success(BiometricAuthorization)
    case cancelled
    /// The user chose "Use Master Password".
    case fallback
    case lockedOut
    case unavailable(BiometricAvailability)
    case failed
}

public protocol BiometricAuthenticator: Sendable {
    func availability() -> BiometricAvailability
    /// Changes whenever a face is added or removed; nil when biometry isn't set up.
    func enrollmentState() -> Data?
    func authenticate(reason: String) async -> BiometricAuthResult
}

public enum BiometricStoreError: Error, Equatable, Sendable {
    case notFound
    /// The access control can no longer be satisfied (enrollment changed).
    case invalidated
    case cancelled
    case interactionNotAllowed
    case failed(Int32)
}

public protocol BiometricCredentialStore: Sendable {
    func create(id: String, installation: Data, material: Data, authorization: BiometricAuthorization) throws(BiometricStoreError)
    func read(id: String, installation: Data, authorization: BiometricAuthorization) throws(BiometricStoreError) -> Data
    /// Never prompts and never reads the secret.
    func contains(id: String, installation: Data) -> Bool
    func remove(id: String)
    /// Deletes every item of this store without reading any. True if something was deleted.
    @discardableResult
    func removeAll() -> Bool
}

public struct BiometricSetup: Codable, Equatable, Sendable {
    public enum State: String, Codable, Sendable {
        case active
        case needsSetup
    }

    public var id: String
    public var installation: Data
    public var fingerprint: String
    public var keyFileRequired: Bool
    public var enrollment: Data?
    public var state: State

    public init(id: String, installation: Data, fingerprint: String, keyFileRequired: Bool, enrollment: Data?, state: State = .active) {
        self.id = id
        self.installation = installation
        self.fingerprint = fingerprint
        self.keyFileRequired = keyFileRequired
        self.enrollment = enrollment
        self.state = state
    }
}

public protocol BiometricSetupStore: Sendable {
    /// Random per installation; lives in the sandbox, so a reinstall gets a new one.
    func installationID() -> Data
    func load() -> BiometricSetup?
    func save(_ setup: BiometricSetup) throws
    func clear()
    /// Setups of the vaults that are not the chosen one, by vault id. `load` is always the chosen vault's.
    func loadOthers() -> [String: BiometricSetup]
    func saveOthers(_ setups: [String: BiometricSetup])
}

extension BiometricSetupStore {
    public func loadOthers() -> [String: BiometricSetup] { [:] }
    public func saveOthers(_ setups: [String: BiometricSetup]) {}
}

public enum BiometricEligibility: Equatable, Sendable {
    case ready
    case unavailable(BiometricAvailability)
    case locked
    /// Opened with a key file only: there is no password for Face ID to stand in for.
    case noPassword
    /// Opened before Face ID could be set up; a fresh unlock with the password is needed.
    case needsPasswordUnlock
}

public enum BiometricEnableResult: Equatable, Sendable {
    case enabled
    case cancelled
    case failed(BiometricEnableError)
}

public enum BiometricEnableError: Error, Equatable, Sendable {
    case notEligible(BiometricEligibility)
    case lockedOut
    case unavailable(BiometricAvailability)
    case failed
}

public enum BiometricUnlockResult: Equatable, Sendable {
    case success
    case cancelled
    case fallback
    case lockedOut
    case unavailable(BiometricAvailability)
    case needsSetup
    case keyFileRequired
    case failed
    case vaultError(UnlockError)
}

/// Coordinates Face ID, the Keychain item, the setup record, the key file and the Rust unlock.
/// Views only call this; they never see `LAContext` or the Keychain.
@MainActor
@Observable
public final class BiometricUnlock {
    public private(set) var status: BiometricStatus = .off {
        didSet { if status != oldValue { log("biometric status: \(status)") } }
    }
    public private(set) var availability: BiometricAvailability = .noHardware
    public private(set) var isBusy = false
    /// Version byte + SHA-256 of the password (`UnlockMaterial::encode`).
    static let materialLength = 33

    @ObservationIgnored private let controller: VaultSessionController
    @ObservationIgnored private let authenticator: any BiometricAuthenticator
    @ObservationIgnored private let credentials: any BiometricCredentialStore
    @ObservationIgnored private let setups: any BiometricSetupStore
    @ObservationIgnored private let newID: () -> String
    @ObservationIgnored private let log: (String) -> Void

    public init(
        controller: VaultSessionController,
        authenticator: any BiometricAuthenticator,
        credentials: any BiometricCredentialStore,
        setups: any BiometricSetupStore,
        newID: @escaping () -> String = { UUID().uuidString },
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.controller = controller
        self.authenticator = authenticator
        self.credentials = credentials
        self.setups = setups
        self.newID = newID
        self.log = log
        controller.shouldRetainUnlockMaterial = { [weak self] in self?.canBeSetUp ?? false }
        controller.onVaultChanged = { [weak self] in self?.forget() }
        refresh()
    }

    /// True when a password unlock now could be followed by turning Face ID unlock on.
    public var canBeSetUp: Bool {
        guard availability == .available else { return false }
        if case .on = status { return false }
        return true
    }

    public var eligibility: BiometricEligibility {
        guard availability == .available else { return .unavailable(availability) }
        guard let method = controller.unlockMethod, controller.isUnlocked else { return .locked }
        guard !method.biometric else { return .needsPasswordUnlock }
        guard method.typedPassword else { return .noPassword }
        guard method.materialRetained else { return .needsPasswordUnlock }
        return .ready
    }

    /// Re-reads availability and the setup without prompting. Invalidates a setup whose
    /// enrollment changed or whose Keychain item disappeared, and removes items left by an
    /// earlier installation or an abandoned setup.
    public func refresh() {
        availability = authenticator.availability()
        let installation = setups.installationID()
        var record = setups.load()
        if let current = record, current.installation != installation {
            setups.clear()
            record = nil
        }
        var others = setups.loadOthers()
        if others.values.contains(where: { $0.installation != installation }) {
            others = others.filter { $0.value.installation == installation }
            setups.saveOthers(others)
        }
        // Items can't be listed without Face ID, so with no setup left for any vault every item is
        // stale: this is what removes an earlier installation's item after a reinstall.
        if record?.state != .active, !others.values.contains(where: { $0.state == .active }), credentials.removeAll() {
            log("biometric: removed stale keychain items")
        }
        guard let record else {
            status = availability == .available ? .off : .unavailable(availability)
            return
        }
        guard record.state == .active else {
            status = .needsSetup
            return
        }
        if availability == .notEnrolled || availability == .passcodeNotSet {
            invalidate(record, because: "biometry no longer enrolled")
            return
        }
        if let saved = record.enrollment, let current = authenticator.enrollmentState(), saved != current {
            invalidate(record, because: "enrollment changed")
            return
        }
        if !credentials.contains(id: record.id, installation: installation) {
            invalidate(record, because: "keychain item missing")
            return
        }
        switch availability {
        case .noHardware, .denied: status = .unavailable(availability)
        default: status = .on(keyFileRequired: record.keyFileRequired)
        }
    }

    public func enable(reason: String) async -> BiometricEnableResult {
        guard !isBusy else { return .failed(.failed) }
        let eligibility = eligibility
        guard eligibility == .ready, case let .unlocked(info) = controller.state, let method = controller.unlockMethod else {
            return .failed(.notEligible(eligibility))
        }
        isBusy = true
        defer { isBusy = false }
        let authorization: BiometricAuthorization
        switch await authenticator.authenticate(reason: reason) {
        case let .success(granted): authorization = granted
        case .cancelled, .fallback: return .cancelled
        case .lockedOut: return .failed(.lockedOut)
        case let .unavailable(reason): return .failed(.unavailable(reason))
        case .failed: return .failed(.failed)
        }
        guard controller.isUnlocked, let material = controller.unlockMaterial() else { return .failed(.failed) }
        defer { material.wipe() }
        let id = newID()
        let installation = setups.installationID()
        do {
            try material.withBytes { try credentials.create(id: id, installation: installation, material: $0, authorization: authorization) }
        } catch {
            credentials.remove(id: id)
            log("biometric: enable failed to store the item")
            return .failed(.failed)
        }
        let previous = setups.load()
        let setup = BiometricSetup(
            id: id,
            installation: installation,
            fingerprint: info.fingerprint,
            keyFileRequired: method.keyFile,
            enrollment: authenticator.enrollmentState()
        )
        do {
            try setups.save(setup)
        } catch {
            credentials.remove(id: id)
            return .failed(.failed)
        }
        if let previous, previous.id != id { credentials.remove(id: previous.id) }
        refresh()
        return .enabled
    }

    public func disable() {
        if let record = setups.load() { credentials.remove(id: record.id) }
        setups.clear()
        refresh()
    }

    public func unlock(reason: String) async -> BiometricUnlockResult {
        guard !isBusy else { return .failed }
        refresh()
        switch status {
        case .needsSetup: return .needsSetup
        case let .unavailable(reason): return .unavailable(reason)
        case .off: return .needsSetup
        case .on: break
        }
        guard let record = setups.load(), record.state == .active else { return .needsSetup }
        if availability == .lockedOut { return .lockedOut }
        isBusy = true
        defer { isBusy = false }

        var interrupted: BiometricUnlockResult?
        let installation = setups.installationID()
        let outcome = await controller.unlock(keyFileRequired: record.keyFileRequired, fingerprint: record.fingerprint) {
            switch await authenticator.authenticate(reason: reason) {
            case let .success(authorization):
                do throws(BiometricStoreError) {
                    let material = try credentials.read(id: record.id, installation: installation, authorization: authorization)
                    guard material.count == Self.materialLength else {
                        interrupted = .needsSetup
                        return nil
                    }
                    return material
                } catch {
                    switch error {
                    case .notFound, .invalidated: interrupted = .needsSetup
                    case .cancelled: interrupted = .cancelled
                    case .interactionNotAllowed, .failed: interrupted = .failed
                    }
                    return nil
                }
            case .cancelled: interrupted = .cancelled
            case .fallback: interrupted = .fallback
            case .lockedOut: interrupted = .lockedOut
            case let .unavailable(reason): interrupted = .unavailable(reason)
            case .failed: interrupted = .failed
            }
            return nil
        }
        switch outcome {
        case .success:
            return .success
        case .alreadyRunning:
            return .failed
        case .cancelled:
            let result = interrupted ?? .cancelled
            if result == .needsSetup { invalidate(record, because: "keychain refused the item") }
            if case .unavailable(.notEnrolled) = result { invalidate(record, because: "biometry no longer enrolled") }
            return result
        case let .failure(error):
            switch error {
            case .wrongPassword, .wrongPasswordOrKeyFile, .missingCredentials, .vaultMismatch:
                invalidate(record, because: "vault refused the material")
                return .needsSetup
            case .keyFileRequired:
                return .keyFileRequired
            default:
                return .vaultError(error)
            }
        }
    }

    /// Another vault became the chosen one. Each vault keeps its own setup and Keychain item: the one
    /// in use is put aside under the vault it belongs to, and the new vault's own setup, if it has
    /// one, takes its place. A setup is only ever used for the vault whose fingerprint it recorded.
    public func vaultSwitched(from old: String?, to new: String?) {
        var others = setups.loadOthers()
        if let record = setups.load() {
            if let old {
                others[old] = record
            } else {
                credentials.remove(id: record.id)
            }
        }
        setups.clear()
        if let new, let restored = others.removeValue(forKey: new) {
            do {
                try setups.save(restored)
            } catch {
                credentials.remove(id: restored.id)
            }
        }
        setups.saveOthers(others)
        refresh()
    }

    /// The vault left the list: its setup and Keychain item go with it.
    public func vaultForgotten(_ id: String, wasChosen: Bool) {
        if wasChosen {
            forget()
            return
        }
        var others = setups.loadOthers()
        guard let record = others.removeValue(forKey: id) else { return }
        credentials.remove(id: record.id)
        setups.saveOthers(others)
        refresh()
    }

    /// The vault reference changed: the setup belonged to the old vault.
    private func forget() {
        guard let record = setups.load() else { return }
        credentials.remove(id: record.id)
        setups.clear()
        refresh()
    }

    private func invalidate(_ record: BiometricSetup, because reason: String) {
        credentials.remove(id: record.id)
        var broken = record
        broken.state = .needsSetup
        broken.enrollment = nil
        do {
            try setups.save(broken)
        } catch {
            setups.clear()
        }
        status = .needsSetup
        log("biometric: invalidated, \(reason)")
    }
}
