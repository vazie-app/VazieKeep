import Foundation
import KeepCore
import Observation

public enum VaultState: Equatable, Sendable {
    case locked
    case unlocked(VaultInfo)
}

public enum UnlockError: Error, Equatable, Sendable {
    case wrongPassword
    case wrongPasswordOrKeyFile
    case missingCredentials
    case keyFileUnavailable
    case keyFileTooLarge
    case invalidKeyFile
    case fileUnavailable
    case damaged
    case unsupported
    case notAVault
    case tooLarge
    /// Biometric unlock needs the vault's key file and none is chosen.
    case keyFileRequired
    /// The file now holds a different database than the one biometric unlock was set up for.
    case vaultMismatch
    case unknown
}

/// How the open vault was unlocked. Decides whether biometric unlock can be turned on.
public struct UnlockMethod: Equatable, Sendable {
    public var typedPassword: Bool
    public var biometric: Bool
    public var keyFile: Bool
    public var materialRetained: Bool

    public init(typedPassword: Bool, biometric: Bool = false, keyFile: Bool, materialRetained: Bool) {
        self.typedPassword = typedPassword
        self.biometric = biometric
        self.keyFile = keyFile
        self.materialRetained = materialRetained
    }
}

public enum UnlockOutcome: Equatable, Sendable {
    case success
    case failure(UnlockError)
    case alreadyRunning
    /// The vault was locked (backgrounded, for example) while the key derivation ran.
    case cancelled
}

public enum SaveFailure: Error, Equatable, Sendable {
    /// Someone else saved the vault since it was opened here. Nothing was written.
    case changedExternally
    /// Moved, deleted, offline, or not reachable. Nothing was written.
    case unavailable
    case notWritable
    case insufficientSpace
    case backupFailed
    case verificationFailed
    case encryptionFailed
    /// The newest cloud version isn't on this device yet. Nothing was written.
    case outOfDate
    case readOnly
    case locked
}

public enum SaveOutcome: Equatable, Sendable {
    case saved
    case failed(SaveFailure)
}

/// An edit is applied to the open session and then written to the file at once.
public enum EditResult<Value: Sendable>: Sendable {
    case saved(Value)
    /// The edit is in the session but the file still holds the previous version.
    case notSaved(Value, SaveFailure)
    case failed(VaultEditError)
}

public enum ChooseError: Error, Equatable, Sendable {
    case unavailable
    case notAVault
    case isVault
    /// Another vault needs Keep+.
    case needsPlus
}

/// The one owner of the open Rust session. Views and screen models never hold the session; they
/// ask this object, which closes it on lock, on timeout and when protected data goes away.
@MainActor
@Observable
public final class VaultSessionController {
    public private(set) var state: VaultState = .locked
    public private(set) var isUnlocking = false
    public private(set) var reference: VaultReference?
    /// Bumped whenever on-screen secrets must disappear: background, capture, lock.
    public private(set) var hideSecretsSignal = 0
    public private(set) var unlockMethod: UnlockMethod?
    /// Bumped after every edit, so lists reload.
    public private(set) var contentRevision = 0
    public private(set) var isSaving = false
    /// True when an edit is in the session but not in the file (a save failed).
    public private(set) var hasUnsavedChanges = false
    /// Changes that couldn't be saved before the last lock, kept as an encrypted copy.
    public private(set) var hasRecoveredCopy = false

    @ObservationIgnored private var session: (any VaultSessionAccess)?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var backgroundedAt: ContinuousClock.Instant?
    @ObservationIgnored private var pendingLock: Task<Void, Never>?
    /// What the file held when it was opened or last saved here.
    @ObservationIgnored private var baseline: FileVersion?
    @ObservationIgnored private var fingerprint: String?
    @ObservationIgnored private var saving: Task<SaveOutcome, Never>?
    @ObservationIgnored private let copies: any VaultCopyStore
    @ObservationIgnored private let opener: any VaultOpening
    @ObservationIgnored private let documents: any DocumentAccess
    @ObservationIgnored private let references: any VaultReferenceStore
    @ObservationIgnored private let settings: () -> SecuritySettings
    @ObservationIgnored private let now: () -> ContinuousClock.Instant
    @ObservationIgnored private let sleep: @Sendable (Duration) async -> Void
    @ObservationIgnored private let timing: (String, Duration) -> Void
    @ObservationIgnored public var onLock: () -> Void = {}
    /// Called after the user picks another vault or forgets it.
    @ObservationIgnored public var onVaultChanged: () -> Void = {}
    /// Called after every successful unlock.
    @ObservationIgnored public var onUnlock: () -> Void = {}
    /// Called after every successful save, e.g. to refresh the AutoFill index.
    @ObservationIgnored public var onSaved: () -> Void = {}
    /// Called whenever the chosen vault or its key file changes; nil when the vault is forgotten.
    @ObservationIgnored public var onReferenceChanged: (VaultReference?) -> Void = { _ in }
    /// Whether this vault may be taken on: nil asks about a brand new one. Without Keep+ the app keeps one vault.
    /// The chosen vault is the same one; only the way to its files was renewed.
    @ObservationIgnored public var onReferenceRefreshed: (VaultReference) -> Void = { _ in }
    /// A file picked again is the vault the app already knows: the answer is that vault's reference.
    @ObservationIgnored public var knownVault: (DocumentReference) async -> VaultReference? = { _ in nil }
    @ObservationIgnored public var mayAddVault: (DocumentReference?) -> Bool = { _ in true }
    /// Without Keep+ only the main vault is edited (VaultLibrary).
    @ObservationIgnored public var mayEdit: () -> Bool = { true }
    /// Whether a typed-password unlock should keep the password component in the Rust session.
    @ObservationIgnored public var shouldRetainUnlockMaterial: () -> Bool = { false }

    public init(
        opener: any VaultOpening = RustVaultOpener(),
        documents: any DocumentAccess = SecurityScopedDocumentAccess(),
        references: any VaultReferenceStore,
        copies: any VaultCopyStore = FileVaultCopyStore.applicationSupport(),
        settings: @escaping () -> SecuritySettings,
        now: @escaping () -> ContinuousClock.Instant = { ContinuousClock.now },
        sleep: @escaping @Sendable (Duration) async -> Void = { try? await Task.sleep(for: $0) },
        timing: @escaping (String, Duration) -> Void = { _, _ in }
    ) {
        self.opener = opener
        self.documents = documents
        self.references = references
        self.copies = copies
        self.settings = settings
        self.now = now
        self.sleep = sleep
        self.timing = timing
        reference = references.load()
    }

    public var isUnlocked: Bool {
        if case .unlocked = state { return true }
        return false
    }

    // MARK: Files

    public func chooseVault(_ url: URL) async -> Result<DocumentReference, ChooseError> {
        let candidate: DocumentReference
        let header: Data
        do {
            candidate = try await documents.reference(for: url)
            header = try await documents.header(candidate)
        } catch {
            return .failure(.unavailable)
        }
        guard case .kdbx = opener.detectFormat(header: header) else { return .failure(.notAVault) }
        let known = await knownVault(candidate)
        guard mayAddVault(candidate) else { return .failure(.needsPlus) }
        lock()
        let keyFile = known?.keyFile ?? (reference?.vault.bookmark == candidate.bookmark ? reference?.keyFile : nil)
        let updated = VaultReference(vault: candidate, keyFile: keyFile)
        reference = updated
        references.save(updated)
        onVaultChanged()
        onReferenceChanged(updated)
        return .success(candidate)
    }

    /// Makes a vault the app already knows the chosen one. The open session is closed first:
    /// a vault never sees what another one showed.
    public func activate(_ known: VaultReference) {
        lock()
        guard reference != known else { return }
        reference = known
        references.save(known)
        onVaultChanged()
        onReferenceChanged(known)
    }

    public func chooseKeyFile(_ url: URL) async -> Result<DocumentReference, ChooseError> {
        guard var current = reference else { return .failure(.unavailable) }
        let candidate: DocumentReference
        let header: Data
        do {
            candidate = try await documents.reference(for: url)
            header = try await documents.header(candidate)
        } catch {
            return .failure(.unavailable)
        }
        if opener.detectFormat(header: header) != .unknown { return .failure(.isVault) }
        current.keyFile = candidate
        reference = current
        references.save(current)
        onReferenceChanged(current)
        return .success(candidate)
    }

    public func removeKeyFile() {
        guard var current = reference else { return }
        current.keyFile = nil
        reference = current
        references.save(current)
        onReferenceChanged(current)
    }

    public func forgetVault() {
        lock()
        reference = nil
        references.clear()
        onVaultChanged()
        onReferenceChanged(nil)
    }

    // MARK: Unlock and lock

    /// `password` is the typed password as UTF-8, or nil when the field is empty.
    public func unlock(password: Data?) async -> UnlockOutcome {
        guard !isUnlocking else { return .alreadyRunning }
        guard let current = reference else { return .failure(.fileUnavailable) }
        if password == nil && current.keyFile == nil { return .failure(.missingCredentials) }
        isUnlocking = true
        defer { isUnlocking = false }
        let started = ContinuousClock.now
        let ticket = generation

        let files: Files
        switch await read(current, keyFile: current.keyFile != nil) {
        case let .success(read): files = read
        case let .failure(error): return .failure(error)
        }
        timing("file read", ContinuousClock.now - started)
        let retain = password != nil && shouldRetainUnlockMaterial()
        var opened = await open(files, credentials: VaultCredentials(password: password, keyFile: files.keyFile, retainUnlockMaterial: retain))
        if password == nil, files.keyFile != nil, case .failure(.wrongPasswordOrKeyFile) = opened {
            opened = await open(files, credentials: VaultCredentials(password: Data(), keyFile: files.keyFile))
        }
        let method = UnlockMethod(typedPassword: password != nil, keyFile: files.keyFile != nil, materialRetained: retain)
        return finish(opened, ticket: ticket, method: method, version: files.version, started: started)
    }

    /// Biometric unlock. Files are read first, so an unreadable vault or key file never costs a
    /// Face ID prompt; `material` runs the prompt and returns the stored password component, or
    /// nil when there is none. The material is wiped after use, and a different database than
    /// `fingerprint` is closed at once.
    public func unlock(keyFileRequired: Bool, fingerprint: String, material: () async -> Data?) async -> UnlockOutcome {
        guard !isUnlocking else { return .alreadyRunning }
        guard let current = reference else { return .failure(.fileUnavailable) }
        if keyFileRequired && current.keyFile == nil { return .failure(.keyFileRequired) }
        isUnlocking = true
        defer { isUnlocking = false }
        let started = ContinuousClock.now
        let ticket = generation

        let files: Files
        switch await read(current, keyFile: keyFileRequired) {
        case let .success(read): files = read
        case let .failure(error): return .failure(error)
        }
        let filesRead = ContinuousClock.now
        timing("file read", filesRead - started)
        guard var bytes = await material() else { return .cancelled }
        defer { bytes.resetBytes(in: 0..<bytes.count) }
        guard ticket == generation else { return .cancelled }
        let promptDone = ContinuousClock.now
        timing("biometric prompt + keychain", promptDone - filesRead)
        let opened = await open(files, credentials: VaultCredentials(unlockMaterial: bytes, keyFile: files.keyFile))
        if case let .success(candidate) = opened, (try? candidate.info().fingerprint) != fingerprint {
            candidate.close()
            return .failure(.vaultMismatch)
        }
        let method = UnlockMethod(typedPassword: false, biometric: true, keyFile: files.keyFile != nil, materialRetained: false)
        return finish(opened, ticket: ticket, method: method, version: files.version, started: promptDone)
    }

    /// The retained password component of the open vault, for turning on biometric unlock.
    public func unlockMaterial() -> SecretValue? {
        guard unlockMethod?.materialRetained == true else { return nil }
        return try? withSession { session throws(VaultSessionError) in try session.unlockMaterial() }
    }

    private struct Files {
        var vault: Data
        var keyFile: Data?

        var version: FileVersion { FileVersion(of: vault) }
    }

    private func read(_ current: VaultReference, keyFile: Bool) async -> Result<Files, UnlockError> {
        let vault: DocumentRead
        do {
            vault = try await documents.read(current.vault, limit: maxVaultBytes)
        } catch {
            return .failure(error == .tooLarge ? .tooLarge : .fileUnavailable)
        }
        if let refreshed = vault.refreshed { updateVault(refreshed) }
        guard keyFile, let reference = current.keyFile else { return .success(Files(vault: vault.data)) }
        do {
            let read = try await documents.read(reference, limit: maxKeyFileBytes)
            if let refreshed = read.refreshed { updateKeyFile(refreshed) }
            return .success(Files(vault: vault.data, keyFile: read.data))
        } catch {
            return .failure(error == .tooLarge ? .keyFileTooLarge : .keyFileUnavailable)
        }
    }

    private func open(_ files: Files, credentials: VaultCredentials) async -> Result<any VaultSessionAccess, UnlockError> {
        let started = ContinuousClock.now
        defer { timing("open (KDF + decrypt + parse)", ContinuousClock.now - started) }
        do {
            return .success(try await opener.open(data: files.vault, credentials: credentials))
        } catch {
            return .failure(Self.map(error.reason, keyFile: files.keyFile != nil))
        }
    }

    private func finish(_ opened: Result<any VaultSessionAccess, UnlockError>, ticket: Int, method: UnlockMethod, version: FileVersion, started: ContinuousClock.Instant) -> UnlockOutcome {
        let candidate: any VaultSessionAccess
        switch opened {
        case let .success(session): candidate = session
        case let .failure(error): return .failure(error)
        }
        guard ticket == generation else {
            candidate.close()
            return .cancelled
        }
        let info: VaultInfo
        do {
            info = try candidate.info()
        } catch {
            candidate.close()
            return .failure(.unknown)
        }
        session?.close()
        session = candidate
        unlockMethod = method
        baseline = version
        fingerprint = info.fingerprint
        hasUnsavedChanges = false
        hasRecoveredCopy = copies.unsaved(fingerprint: info.fingerprint) != nil
        state = .unlocked(info)
        timing("unlock total", ContinuousClock.now - started)
        onUnlock()
        return .success
    }

    /// Closes the session. Changes that never reached the file are kept as an encrypted copy in
    /// the app (auto-lock can't wait for an answer), unless the user chose to discard them.
    public func lock(discardingChanges: Bool = false) {
        if !discardingChanges, hasUnsavedChanges, let session, let fingerprint,
           let snapshot = try? session.prepareSave(), copies.keepUnsaved(snapshot.bytes, fingerprint: fingerprint) {
            timing("unsaved changes kept", .zero)
        }
        generation += 1
        pendingLock?.cancel()
        pendingLock = nil
        backgroundedAt = nil
        hideSecretsSignal += 1
        let closing = session
        session = nil
        unlockMethod = nil
        baseline = nil
        hasUnsavedChanges = false
        hasRecoveredCopy = false
        saving = nil
        closing?.close()
        if state != .locked {
            state = .locked
            onLock()
        }
    }

    // MARK: Editing and saving

    /// Applies `body` to the open session, then writes the vault (edit = save). A failed save
    /// leaves the edit in the session and the file untouched.
    public func edit<Value: Sendable>(_ body: (any VaultSessionAccess) throws(VaultEditError) -> Value) async -> EditResult<Value> {
        guard let session, isUnlocked else { return .failed(VaultEditError(reason: .sessionClosed)) }
        guard mayEdit() else { return .failed(VaultEditError(reason: .readOnly)) }
        let value: Value
        do {
            value = try body(session)
        } catch {
            return .failed(error)
        }
        contentRevision += 1
        hasUnsavedChanges = (try? session.isDirty()) ?? true
        guard hasUnsavedChanges else { return .saved(value) }
        switch await save() {
        case .saved: return .saved(value)
        case let .failed(failure): return .notSaved(value, failure)
        }
    }

    /// Writes the session to the file if it holds unsaved edits. Saves run one at a time.
    @discardableResult
    public func save() async -> SaveOutcome {
        let previous = saving
        let task = Task { @MainActor [weak self] () -> SaveOutcome in
            _ = await previous?.value
            guard let self else { return .failed(.locked) }
            return await self.write()
        }
        saving = task
        return await task.value
    }

    /// Encrypted bytes of the current session, for "Save a Copy". Nothing is written here.
    public func snapshotForCopy() async -> Result<Data, SaveFailure> {
        guard let session else { return .failure(.locked) }
        let result = await Task.detached(priority: .userInitiated) { () -> Result<SaveSnapshot, VaultSaveError> in
            do throws(VaultSaveError) {
                return .success(try session.prepareSave())
            } catch {
                return .failure(error)
            }
        }.value
        switch result {
        case let .success(snapshot): return .success(snapshot.bytes)
        case let .failure(error): return .failure(Self.map(error))
        }
    }

    public func recoveredCopy() -> Data? {
        fingerprint.flatMap { copies.unsaved(fingerprint: $0) }
    }

    public func discardRecoveredCopy() {
        guard let fingerprint else { return }
        copies.removeUnsaved(fingerprint: fingerprint)
        hasRecoveredCopy = false
    }

    private func write() async -> SaveOutcome {
        guard let session, let current = reference, let expected = baseline, let fingerprint else { return .failed(.locked) }
        guard (try? session.isDirty()) == true else {
            hasUnsavedChanges = false
            return .saved
        }
        isSaving = true
        defer { isSaving = false }
        let started = ContinuousClock.now
        let ticket = generation
        let prepared = await Task.detached(priority: .userInitiated) { () -> Result<SaveSnapshot, VaultSaveError> in
            do throws(VaultSaveError) {
                return .success(try session.prepareSave())
            } catch {
                return .failure(error)
            }
        }.value
        let snapshot: SaveSnapshot
        switch prepared {
        case let .success(value): snapshot = value
        case let .failure(error): return .failed(Self.map(error))
        }
        timing("save: serialize + encrypt + verify", ContinuousClock.now - started)
        let copies = copies
        let written: WriteResult
        do {
            written = try await documents.replace(current.vault, with: snapshot.bytes, expecting: expected, backup: { copies.keepBackup($0, fingerprint: fingerprint) })
        } catch {
            timing("save failed", ContinuousClock.now - started)
            return .failed(Self.map(error))
        }
        timing("save total", ContinuousClock.now - started)
        guard ticket == generation else { return .saved }
        baseline = written.version
        if let refreshed = written.refreshed { updateVault(refreshed) }
        try? session.markSaved(generation: snapshot.generation)
        hasUnsavedChanges = (try? session.isDirty()) ?? false
        onSaved()
        return .saved
    }

    public static func map(_ error: VaultSaveError) -> SaveFailure {
        switch error.reason {
        case .readOnly: .readOnly
        case .verificationFailed: .verificationFailed
        case .sessionClosed: .locked
        case .encryptionFailed, .randomUnavailable: .encryptionFailed
        }
    }

    public static func map(_ failure: WriteFailure) -> SaveFailure {
        switch failure {
        case .changedExternally: .changedExternally
        case .unavailable: .unavailable
        case .notWritable: .notWritable
        case .insufficientSpace: .insufficientSpace
        case .backupFailed: .backupFailed
        case .verificationFailed: .verificationFailed
        case .outOfDate: .outOfDate
        }
    }

    // MARK: Session access

    public func groups() throws(VaultSessionError) -> [VaultGroup] {
        try withSession { session throws(VaultSessionError) in try session.groups() }
    }

    public func entries() throws(VaultSessionError) -> [EntrySummary] {
        try withSession { session throws(VaultSessionError) in try session.entries() }
    }

    public func entry(_ id: EntryID) throws(VaultSessionError) -> EntryDetail {
        try withSession { session throws(VaultSessionError) in try session.entry(id) }
    }

    public func attachment(_ name: String, for id: EntryID) throws(VaultSessionError) -> SecretValue {
        try withSession { session throws(VaultSessionError) in try session.attachment(name, for: id) }
    }

    public func reveal(_ field: SecretField, for id: EntryID) throws(VaultSessionError) -> SecretValue {
        try withSession { session throws(VaultSessionError) in try session.reveal(field, for: id) }
    }

    public func history(of id: EntryID, access: KeepAccess) throws(VaultHistoryError) -> [HistoryVersion] {
        try withHistory { session throws(VaultHistoryError) in try session.history(of: id, access: access) }
    }

    public func historyEntry(_ index: Int, of id: EntryID, access: KeepAccess) throws(VaultHistoryError) -> EntryDetail {
        try withHistory { session throws(VaultHistoryError) in try session.historyEntry(index, of: id, access: access) }
    }

    public func revealHistory(_ field: SecretField, version index: Int, of id: EntryID, access: KeepAccess) throws(VaultHistoryError) -> SecretValue {
        try withHistory { session throws(VaultHistoryError) in try session.revealHistory(field, version: index, of: id, access: access) }
    }

    private func withHistory<T>(_ body: (any VaultSessionAccess) throws(VaultHistoryError) -> T) throws(VaultHistoryError) -> T {
        guard let session else { throw VaultHistoryError(reason: .sessionClosed) }
        do {
            return try body(session)
        } catch {
            if error.reason == .sessionClosed { lock() }
            throw error
        }
    }

    public func totpCode(for id: EntryID, at unixSeconds: Int, access: KeepAccess) throws(TotpError) -> TotpCode {
        guard let session else { throw TotpError(reason: .sessionClosed) }
        return try session.totpCode(for: id, at: unixSeconds, access: access)
    }

    /// Runs off the main actor. A lock during the search closes the session; the search then
    /// fails with `sessionClosed` and the caller shows nothing.
    public func search(_ query: String) async throws(VaultSessionError) -> [EntrySummary] {
        guard let session else { throw VaultSessionError(reason: .sessionClosed) }
        let started = ContinuousClock.now
        let result = await Task.detached(priority: .userInitiated) { () -> Result<[EntrySummary], VaultSessionError> in
            do throws(VaultSessionError) {
                return .success(try session.search(query))
            } catch {
                return .failure(error)
            }
        }.value
        timing("search", ContinuousClock.now - started)
        return try result.get()
    }

    public func siteIconHosts() -> [String] {
        (try? withSession { session throws(VaultSessionError) in try session.siteIconHosts() }) ?? []
    }

    public func passkeys(rpID: String?) -> [EntrySummary] {
        guard isUnlocked else { return [] }
        return (try? withSession { session throws(VaultSessionError) in try session.passkeys(rpID: rpID) }) ?? []
    }

    public func signPasskey(_ id: EntryID, rpID: String, clientDataHash: Data) throws(VaultPasskeyError) -> PasskeyAssertion {
        guard let session, isUnlocked else { throw VaultPasskeyError(reason: .sessionClosed) }
        return try session.signPasskey(id, rpID: rpID, clientDataHash: clientDataHash)
    }

    public func siteIconHost(for id: EntryID) -> String? {
        guard isUnlocked else { return nil }
        return (try? withSession { session throws(VaultSessionError) in try session.siteIconHost(for: id) }) ?? nil
    }

    public func customIcon(_ id: String) -> Data? {
        guard isUnlocked else { return nil }
        return try? withSession { session throws(VaultSessionError) in try session.customIcon(id) }
    }

    private func withSession<T>(_ body: (any VaultSessionAccess) throws(VaultSessionError) -> T) throws(VaultSessionError) -> T {
        guard let session else { throw VaultSessionError(reason: .sessionClosed) }
        do {
            return try body(session)
        } catch {
            if error.reason == .sessionClosed || error.reason == .sessionNotFound { lock() }
            throw error
        }
    }

    // MARK: Lifecycle

    public func sceneDidEnterBackground() {
        hideSecretsSignal += 1
        guard isUnlocked else { return }
        let timeout = settings().autoLock.duration
        if timeout == .zero {
            lock()
            return
        }
        backgroundedAt = now()
        pendingLock?.cancel()
        let sleep = sleep
        let ticket = generation
        pendingLock = Task { [weak self] in
            await sleep(timeout)
            guard !Task.isCancelled, let self, self.generation == ticket else { return }
            self.lock()
        }
    }

    public func sceneWillEnterForeground() {
        pendingLock?.cancel()
        pendingLock = nil
        defer { backgroundedAt = nil }
        // A suspended app's timer may never have fired: decide from the clock.
        guard let backgroundedAt, isUnlocked else { return }
        if now() - backgroundedAt >= settings().autoLock.duration { lock() }
    }

    public func protectedDataWillBecomeUnavailable() {
        hideSecretsSignal += 1
        if settings().lockWhenProtectedDataUnavailable { lock() }
    }

    public func screenCaptureStarted() {
        hideSecretsSignal += 1
    }

    // MARK: Helpers

    private func updateVault(_ refreshed: DocumentReference) {
        guard var current = reference else { return }
        current.vault = refreshed
        reference = current
        references.save(current)
        onReferenceRefreshed(current)
    }

    private func updateKeyFile(_ refreshed: DocumentReference) {
        guard var current = reference else { return }
        current.keyFile = refreshed
        reference = current
        references.save(current)
        onReferenceRefreshed(current)
    }

    static func map(_ reason: VaultOpenError.Reason, keyFile: Bool) -> UnlockError {
        switch reason {
        case .invalidCredentials: keyFile ? .wrongPasswordOrKeyFile : .wrongPassword
        case .missingCredentials: .missingCredentials
        case .invalidKeyFile: .invalidKeyFile
        case .damaged: .damaged
        case .unsupported: .unsupported
        case .notAVault: .notAVault
        case .tooLarge: .tooLarge
        case .tooManySessions, .unavailable: .unknown
        }
    }
}
