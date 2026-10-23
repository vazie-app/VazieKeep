import Foundation
import KeepApp
import KeepCore
import Synchronization

let kdbxHeader = Data([0x03, 0xD9, 0xA2, 0x9A, 0x67, 0xFB, 0x4B, 0xB5, 0x00, 0x00, 0x04, 0x00])

func reference(_ name: String) -> DocumentReference {
    DocumentReference(bookmark: Data(name.utf8), displayName: name)
}

private final class VectorBundleToken {}

/// On a device the vectors are bundled as resources; on the Mac they are read from the repository.
func vectorURL(_ path: String) -> URL {
    let parts = path.split(separator: "/").map(String.init)
    let file = parts.last ?? path
    let directory = parts.dropLast().joined(separator: "/")
    if let bundled = Bundle(for: VectorBundleToken.self).url(forResource: (file as NSString).deletingPathExtension, withExtension: (file as NSString).pathExtension, subdirectory: directory.isEmpty ? nil : directory) {
        return bundled
    }
    return URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .appending(path: "../../../../../shared/test-vectors/\(path)")
        .standardizedFileURL
}

/// Serves bytes by bookmark; bookmarks are just the file names here.
final class FakeDocuments: DocumentAccess {
    private let state = Mutex<[String: Data]>([:])
    private let reads = Mutex<[String]>([])
    let staleNames = Mutex<Set<String>>([])
    /// Makes the next `replace` fail this way, after the version check.
    let writeFailure = Mutex<WriteFailure?>(nil)
    let writes = Atomic<Int>(0)

    func put(_ name: String, _ data: Data) { state.withLock { $0[name] = data } }
    func remove(_ name: String) { state.withLock { $0[name] = nil } }
    var readLog: [String] { reads.withLock { $0 } }

    func reference(for url: URL) async throws(DocumentError) -> DocumentReference {
        let name = url.lastPathComponent
        guard state.withLock({ $0[name] }) != nil else { throw .unavailable }
        return DocumentReference(bookmark: Data(name.utf8), displayName: name)
    }

    func read(_ reference: DocumentReference, limit: Int) async throws(DocumentError) -> DocumentRead {
        let name = String(decoding: reference.bookmark, as: UTF8.self)
        reads.withLock { $0.append(name) }
        guard let data = state.withLock({ $0[name] }) else { throw .unavailable }
        if data.count > limit { throw .tooLarge }
        let stale = staleNames.withLock { $0.remove(name) != nil }
        return DocumentRead(data: data, refreshed: stale ? DocumentReference(bookmark: Data("\(name)".utf8), displayName: "\(name) (moved)") : nil)
    }

    func header(_ reference: DocumentReference) async throws(DocumentError) -> Data {
        try await read(reference, limit: .max).data.prefix(12)
    }

    func data(_ name: String) -> Data? { state.withLock { $0[name] } }

    func replace(_ reference: DocumentReference, with data: Data, expecting expected: FileVersion, backup: @escaping @Sendable (Data) -> Bool) async throws(WriteFailure) -> WriteResult {
        let name = String(decoding: reference.bookmark, as: UTF8.self)
        guard let current = state.withLock({ $0[name] }) else { throw .unavailable }
        guard FileVersion(of: current) == expected else { throw .changedExternally }
        guard backup(current) else { throw .backupFailed }
        if let failure = writeFailure.withLock({ $0.take() }) { throw failure }
        state.withLock { $0[name] = data }
        writes.add(1, ordering: .relaxed)
        return WriteResult(version: FileVersion(of: data))
    }
}

final class MemoryCopyStore: VaultCopyStore {
    let backups = Mutex<[String: Data]>([:])
    let unsavedCopies = Mutex<[String: Data]>([:])
    let backupsFail = Atomic<Bool>(false)

    func keepBackup(_ data: Data, fingerprint: String) -> Bool {
        guard !backupsFail.load(ordering: .relaxed) else { return false }
        backups.withLock { $0[fingerprint] = data }
        return true
    }

    func backup(fingerprint: String) -> Data? { backups.withLock { $0[fingerprint] } }
    func keepUnsaved(_ data: Data, fingerprint: String) -> Bool {
        unsavedCopies.withLock { $0[fingerprint] = data }
        return true
    }

    func unsaved(fingerprint: String) -> Data? { unsavedCopies.withLock { $0[fingerprint] } }
    func removeUnsaved(fingerprint: String) { unsavedCopies.withLock { $0[fingerprint] = nil } }
}

final class MemoryReferenceStore: VaultReferenceStore {
    private let state = Mutex<VaultReference?>(nil)

    init(_ reference: VaultReference? = nil) { state.withLock { $0 = reference } }

    var current: VaultReference? { state.withLock { $0 } }
    func load() -> VaultReference? { current }
    func save(_ reference: VaultReference) { state.withLock { $0 = reference } }
    func clear() { state.withLock { $0 = nil } }
}

/// Stand-in for `UnlockMaterial::encode`: version byte, then the password padded to 32 bytes, so
/// the fake opener can tell which password it stands for.
func fakeMaterial(_ password: String) -> Data {
    var bytes = Data([1]) + Data(password.utf8)
    bytes.append(Data(count: max(0, 33 - bytes.count)))
    return bytes
}

func passwordFromFakeMaterial(_ material: Data) -> String? {
    guard material.count == 33, material.first == 1 else { return nil }
    return String(decoding: material.dropFirst().prefix { $0 != 0 }, as: UTF8.self)
}

final class FakeSession: VaultSessionAccess {
    private let closed = Atomic<Bool>(false)
    let fingerprint: String
    let retained: Data?
    let reveals = Mutex<[EntryID]>([])
    let totpCode = Mutex<String?>(nil)

    let added = Mutex<[EntrySummary]>([])
    let favoriteChanges = Mutex<[(EntryID, Bool)]>([])
    let attachments = Mutex<[EntryID: [String: Data]]>([:])
    let versions = Mutex<[EntryID: [Int: String]]>([:])
    let restored = Mutex<[Int]>([])
    let generation = Mutex<(edits: UInt64, saved: UInt64)>((0, 0))
    let saveFailure = Mutex<VaultSaveError.Reason?>(nil)
    let initialExtra: [EntrySummary]
    var extra: [EntrySummary] { initialExtra + added.withLock { $0 } }

    init(fingerprint: String = "fp", retained: Data? = nil, extra: [EntrySummary] = []) {
        self.fingerprint = fingerprint
        self.retained = retained
        self.initialExtra = extra
    }

    func isDirty() throws(VaultSessionError) -> Bool {
        try check()
        return generation.withLock { $0.edits != $0.saved }
    }

    private func edited() throws(VaultEditError) {
        if isClosed { throw VaultEditError(reason: .sessionClosed) }
        generation.withLock { $0.edits += 1 }
    }

    func createEntry(in group: GroupID, _ draft: EntryDraft) throws(VaultEditError) -> EntryID {
        if draft.title.contains("\u{7}") { throw VaultEditError(reason: .invalidText) }
        try edited()
        let id = EntryID("new-\(added.withLock { $0.count })")
        added.withLock { $0.append(EntrySummary(id: id, groupID: group, title: .plain(draft.title), username: .plain(draft.username), url: .plain(draft.url), hasPassword: !draft.password.isEmpty)) }
        return id
    }

    func updateEntry(_ id: EntryID, _ draft: EntryDraft) throws(VaultEditError) { try edited() }
    func deleteEntry(_ id: EntryID, permanent: Bool) throws(VaultEditError) -> DeleteOutcome {
        try edited()
        return permanent ? .deleted : .movedToRecycleBin
    }

    func attachment(_ name: String, for id: EntryID) throws(VaultSessionError) -> SecretValue {
        try check()
        guard let data = attachments.withLock({ $0[id]?[name] }) else { throw VaultSessionError(reason: .fieldNotFound) }
        return SecretValue(utf8: data)
    }

    func addAttachment(_ name: String, data: Data, to id: EntryID, access: KeepAccess) throws(VaultEditError) -> String {
        guard access.plusFeatures else { throw VaultEditError(reason: .notEntitled) }
        try edited()
        attachments.withLock { $0[id, default: [:]][name] = data }
        return name
    }

    func removeAttachment(_ name: String, from id: EntryID) throws(VaultEditError) {
        guard attachments.withLock({ $0[id]?.removeValue(forKey: name) }) != nil else { throw VaultEditError(reason: .attachmentNotFound) }
        try edited()
    }

    func history(of id: EntryID, access: KeepAccess) throws(VaultHistoryError) -> [HistoryVersion] {
        if isClosed { throw VaultHistoryError(reason: .sessionClosed) }
        guard access.plusFeatures else { throw VaultHistoryError(reason: .notEntitled) }
        return versions.withLock { $0[id, default: [:]] }.keys.sorted(by: >).map {
            HistoryVersion(index: $0, modifiedAt: 1_700_000_000 + Int64($0), title: .plain("Example Mail"), username: .plain("alice"), differs: [.password])
        }
    }

    func historyEntry(_ index: Int, of id: EntryID, access: KeepAccess) throws(VaultHistoryError) -> EntryDetail {
        _ = try versionPassword(index, of: id, access: access)
        return EntryDetail(
            summary: EntrySummary(id: id, groupID: GroupID("root"), title: .plain("Example Mail"), username: .plain("alice"), url: .plain(""), hasPassword: true),
            notes: .plain(""),
            customFields: []
        )
    }

    func revealHistory(_ field: SecretField, version index: Int, of id: EntryID, access: KeepAccess) throws(VaultHistoryError) -> SecretValue {
        let password = try versionPassword(index, of: id, access: access)
        guard field == .password else { throw VaultHistoryError(reason: .fieldNotFound) }
        return SecretValue(utf8: Data(password.utf8))
    }

    private func versionPassword(_ index: Int, of id: EntryID, access: KeepAccess) throws(VaultHistoryError) -> String {
        if isClosed { throw VaultHistoryError(reason: .sessionClosed) }
        guard access.plusFeatures else { throw VaultHistoryError(reason: .notEntitled) }
        guard let password = versions.withLock({ $0[id]?[index] }) else { throw VaultHistoryError(reason: .versionNotFound) }
        return password
    }

    func restoreHistory(_ index: Int, of id: EntryID, access: KeepAccess) throws(VaultEditError) {
        guard access.plusFeatures else { throw VaultEditError(reason: .notEntitled) }
        guard versions.withLock({ $0[id]?[index] }) != nil else { throw VaultEditError(reason: .versionNotFound) }
        try edited()
        restored.withLock { $0.append(index) }
    }

    let imports = Mutex<[Int]>([])
    let importFailure = Mutex<VaultEditError.Reason?>(nil)

    func importPlan(_ plan: ImportPlan, into group: GroupID) throws(VaultEditError) -> ImportOutcome {
        if let reason = importFailure.withLock({ $0 }) { throw VaultEditError(reason: reason) }
        try edited()
        imports.withLock { $0.append(plan.summary.entries) }
        return ImportOutcome(entries: plan.summary.entries, groups: plan.summary.folders)
    }

    func setFavorite(_ id: EntryID, _ favorite: Bool) throws(VaultEditError) {
        try edited()
        favoriteChanges.withLock { $0.append((id, favorite)) }
    }

    func moveEntry(_ id: EntryID, to group: GroupID) throws(VaultEditError) { try edited() }
    func createGroup(in parent: GroupID, name: String) throws(VaultEditError) -> GroupID {
        try edited()
        return GroupID("group-\(name)")
    }

    func renameGroup(_ id: GroupID, name: String) throws(VaultEditError) { try edited() }
    let iconChanges = Mutex<[(String, Int)]>([])
    func setGroupIcon(_ id: GroupID, _ icon: Int) throws(VaultEditError) {
        try edited()
        iconChanges.withLock { $0.append((id.rawValue, icon)) }
    }

    let iconHosts = Mutex<[String]>([])
    let images = Mutex<[String: Data]>([:])
    func siteIconHosts() throws(VaultSessionError) -> [String] { iconHosts.withLock { $0 } }
    func setSiteIcons(_ icons: [SiteIcon]) throws(VaultEditError) -> Int {
        let known = icons.filter { icon in iconHosts.withLock { $0.contains(icon.host) } }
        guard !known.isEmpty else { return 0 }
        try edited()
        images.withLock { for icon in known { $0[icon.host] = icon.png } }
        iconHosts.withLock { hosts in hosts.removeAll { host in known.contains { $0.host == host } } }
        return known.count
    }

    func customIcon(_ id: String) throws(VaultSessionError) -> Data? { images.withLock { $0[id] } }
    let passkeyEntries = Mutex<[EntrySummary]>([])
    func passkeys(rpID: String?) throws(VaultSessionError) -> [EntrySummary] {
        passkeyEntries.withLock { $0.filter { rpID == nil || $0.passkey?.rpID == rpID } }
    }

    func signPasskey(_ id: EntryID, rpID: String, clientDataHash: Data) throws(VaultPasskeyError) -> PasskeyAssertion {
        guard let held = passkeyEntries.withLock({ $0.first { $0.id == id } })?.passkey, held.rpID == rpID else { throw VaultPasskeyError(reason: .noPasskey) }
        return PasskeyAssertion(credentialID: held.credentialIDData ?? Data(), userHandle: held.userHandleData ?? Data(), authenticatorData: Data(rpID.utf8), signature: clientDataHash + Data(id.rawValue.utf8))
    }

    func createPasskey(in group: GroupID, rpID: String, rpName: String, userName: String, userHandle: Data) throws(VaultEditError) -> CreatedPasskey {
        try edited()
        let id = EntryID("passkey-\(passkeyEntries.withLock { $0.count })")
        let info = PasskeyInfo(rpID: rpID, userName: userName, credentialID: PasskeyInfo.encode(Data("cred".utf8)), userHandle: PasskeyInfo.encode(userHandle))
        passkeyEntries.withLock { $0.append(EntrySummary(id: id, groupID: group, title: .plain(rpName), username: .plain(userName), url: .plain("https://\(rpID)"), hasPassword: false, passkey: info)) }
        return CreatedPasskey(entryID: id, credentialID: Data("cred".utf8), attestationObject: Data([3]))
    }

    func removePasskey(_ id: EntryID) throws(VaultEditError) {
        try edited()
        passkeyEntries.withLock { $0.removeAll { $0.id == id } }
    }

    let entryIconHost = Mutex<String?>(nil)
    func siteIconHost(for id: EntryID) throws(VaultSessionError) -> String? { entryIconHost.withLock { $0 } }

    let iconsToAssign = Mutex<Int>(0)
    func assignIconsByURL() throws(VaultEditError) -> Int {
        let count = iconsToAssign.withLock { $0 }
        if count > 0 { try edited() }
        return count
    }

    func setEntryIcon(_ id: EntryID, _ icon: Int) throws(VaultEditError) {
        try edited()
        iconChanges.withLock { $0.append((id.rawValue, icon)) }
    }
    func deleteGroup(_ id: GroupID, recursive: Bool, permanent: Bool) throws(VaultEditError) -> DeleteOutcome {
        if !recursive { throw VaultEditError(reason: .groupNotEmpty) }
        try edited()
        return .deleted
    }

    func prepareSave() throws(VaultSaveError) -> SaveSnapshot {
        if isClosed { throw VaultSaveError(reason: .sessionClosed) }
        if let reason = saveFailure.withLock({ $0 }) { throw VaultSaveError(reason: reason) }
        let edits = generation.withLock { $0.edits }
        return SaveSnapshot(bytes: kdbxHeader + Data("saved-\(edits)".utf8), generation: edits)
    }

    func markSaved(generation saved: UInt64) throws(VaultSessionError) -> Bool {
        try check()
        return generation.withLock {
            if saved > $0.saved && saved <= $0.edits { $0.saved = saved }
            return $0.edits == $0.saved
        }
    }
    let searchGate = Mutex<CheckedContinuation<Void, Never>?>(nil)
    static let root = GroupID("root")
    static let web = GroupID("web")
    static let mail = EntryID("mail")

    var isClosed: Bool { closed.load(ordering: .acquiring) }

    func info() throws(VaultSessionError) -> VaultInfo {
        try check()
        return VaultInfo(name: "Fake Vault", formatMajor: 4, formatMinor: 0, rootGroupID: Self.root, groupCount: 2, entryCount: 2, fingerprint: fingerprint)
    }

    func groups() throws(VaultSessionError) -> [VaultGroup] {
        try check()
        return [VaultGroup(id: Self.root, parentID: nil, name: "Root"), VaultGroup(id: Self.web, parentID: Self.root, name: "Web")]
    }

    func entries() throws(VaultSessionError) -> [EntrySummary] {
        try check()
        return [
            EntrySummary(id: EntryID("root-entry"), groupID: Self.root, title: .plain("Root Entry"), username: .plain("root-user"), url: .plain(""), hasPassword: true),
            EntrySummary(id: Self.mail, groupID: Self.web, title: .plain("Example Mail"), username: .plain("alice"), url: .plain("https://mail.example.org"), hasPassword: true, hasTotp: totpCode.withLock { $0 } != nil),
        ] + extra
    }

    func search(_ query: String) throws(VaultSessionError) -> [EntrySummary] {
        try check()
        return try entries().filter { $0.displayTitle.localizedCaseInsensitiveContains(query) }
    }

    func entry(_ id: EntryID) throws(VaultSessionError) -> EntryDetail {
        guard let summary = try entries().first(where: { $0.id == id }) else { throw VaultSessionError(reason: .entryNotFound) }
        let files = attachments.withLock { $0[id] ?? [:] }.map { AttachmentInfo(name: $0.key, size: Int64($0.value.count)) }.sorted { $0.name < $1.name }
        return EntryDetail(summary: summary, notes: .plain(""), customFields: [], attachments: files)
    }

    func reveal(_ field: SecretField, for id: EntryID) throws(VaultSessionError) -> SecretValue {
        try check()
        reveals.withLock { $0.append(id) }
        if field == .password, extra.contains(where: { $0.id == id }) { return SecretValue(utf8: Data("secret-\(id.rawValue)".utf8)) }
        guard field == .password, id == Self.mail else { throw VaultSessionError(reason: .fieldNotFound) }
        return SecretValue(utf8: Data("hunter2-synthetic".utf8))
    }

    func totpCode(for id: EntryID, at unixSeconds: Int, access: KeepAccess) throws(TotpError) -> TotpCode {
        if isClosed { throw TotpError(reason: .sessionClosed) }
        guard access.plusFeatures else { throw TotpError(reason: .notEntitled) }
        guard id == Self.mail, let code = totpCode.withLock({ $0 }) else { throw TotpError(reason: .noCode) }
        return TotpCode(code: SecretValue(utf8: Data(code.utf8)), period: 30, secondsRemaining: 30 - unixSeconds % 30)
    }

    func unlockMaterial() throws(VaultSessionError) -> SecretValue? {
        try check()
        return retained.map { SecretValue(utf8: $0) }
    }

    func close() { closed.store(true, ordering: .releasing) }

    private func check() throws(VaultSessionError) {
        if isClosed { throw VaultSessionError(reason: .sessionClosed) }
    }
}

struct Seen: Equatable, Sendable {
    let password: String?
    let keyFile: String?
    var viaMaterial = false
    var retain = false
}

/// Accepts credentials according to `accepts`, records what it saw, and can be held open.
final class FakeOpener: VaultOpening {
    let accepts = Mutex<@Sendable (String?, String?) -> Bool>({ _, _ in true })
    let failure = Mutex<VaultOpenError.Reason?>(nil)
    let seen = Mutex<[Seen]>([])
    let gate = Mutex<CheckedContinuation<Void, Never>?>(nil)
    let holdNext = Atomic<Bool>(false)
    let sessions = Mutex<[FakeSession]>([])
    let fingerprint = Mutex("fp")
    let extraEntries = Mutex<[EntrySummary]>([])

    func detectFormat(header: Data) -> VaultFormat {
        header == kdbxHeader ? .kdbx(major: 4, minor: 0) : .unknown
    }

    func open(data: Data, credentials: VaultCredentials) async throws(VaultOpenError) -> any VaultSessionAccess {
        let viaMaterial = credentials.unlockMaterial != nil
        let password = credentials.unlockMaterial.map { passwordFromFakeMaterial($0) ?? "<malformed>" }
            ?? credentials.password.map { String(decoding: $0, as: UTF8.self) }
        let keyFile = credentials.keyFile.map { String(decoding: $0, as: UTF8.self) }
        seen.withLock { $0.append(Seen(password: password, keyFile: keyFile, viaMaterial: viaMaterial, retain: credentials.retainUnlockMaterial)) }
        if holdNext.exchange(false, ordering: .acquiringAndReleasing) {
            await withCheckedContinuation { continuation in gate.withLock { $0 = continuation } }
        }
        if let reason = failure.withLock({ $0 }) { throw VaultOpenError(reason: reason) }
        if password == nil && keyFile == nil { throw VaultOpenError(reason: .missingCredentials) }
        guard accepts.withLock({ $0 })(password, keyFile) else { throw VaultOpenError(reason: .invalidCredentials) }
        let retained = credentials.retainUnlockMaterial && !viaMaterial ? password.map(fakeMaterial) : nil
        let session = FakeSession(fingerprint: fingerprint.withLock { $0 }, retained: retained, extra: extraEntries.withLock { $0 })
        sessions.withLock { $0.append(session) }
        return session
    }

    /// Waits until `open` is actually parked on the gate, then lets it continue.
    func release() async {
        while gate.withLock({ $0 == nil }) { await Task.yield() }
        gate.withLock { $0?.resume(); $0 = nil }
    }
}

@MainActor
final class FakeClipboard: SecretClipboard {
    var items: [(String, Duration?)] = []
    var clears = 0
    var systemExpires = true

    func copy(_ text: String, expiresAfter: Duration?) -> Bool {
        items.append((text, expiresAfter))
        return systemExpires && expiresAfter != nil
    }

    func clearIfOwned() { clears += 1 }
}

// MARK: Biometrics

final class FakeAuthenticator: BiometricAuthenticator {
    let availabilityValue = Mutex(BiometricAvailability.available)
    let enrollment = Mutex<Data?>(Data("face-1".utf8))
    /// Results handed out in order; when empty, every prompt succeeds.
    let results = Mutex<[BiometricAuthResult]>([])
    let prompts = Atomic<Int>(0)

    func availability() -> BiometricAvailability { availabilityValue.withLock { $0 } }
    func enrollmentState() -> Data? { enrollment.withLock { $0 } }

    func authenticate(reason: String) async -> BiometricAuthResult {
        prompts.add(1, ordering: .relaxed)
        return results.withLock { $0.isEmpty ? .success(BiometricAuthorization(context: nil)) : $0.removeFirst() }
    }

    var promptCount: Int { prompts.load(ordering: .relaxed) }
}

final class FakeCredentialStore: BiometricCredentialStore {
    let items = Mutex<[String: (installation: Data, material: Data)]>([:])
    let readError = Mutex<BiometricStoreError?>(nil)
    let createError = Mutex<BiometricStoreError?>(nil)

    var count: Int { items.withLock { $0.count } }
    func material(_ id: String) -> Data? { items.withLock { $0[id]?.material } }

    func create(id: String, installation: Data, material: Data, authorization: BiometricAuthorization) throws(BiometricStoreError) {
        if let error = createError.withLock({ $0 }) { throw error }
        items.withLock { $0[id] = (installation, material) }
    }

    func read(id: String, installation: Data, authorization: BiometricAuthorization) throws(BiometricStoreError) -> Data {
        if let error = readError.withLock({ $0 }) { throw error }
        guard let item = items.withLock({ $0[id] }), item.installation == installation else { throw .notFound }
        return item.material
    }

    func contains(id: String, installation: Data) -> Bool {
        items.withLock { $0[id]?.installation == installation }
    }

    func remove(id: String) { items.withLock { $0[id] = nil } }

    func removeAll() -> Bool {
        items.withLock { items in
            defer { items.removeAll() }
            return !items.isEmpty
        }
    }
}

final class MemorySetupStore: BiometricSetupStore {
    let installation = Mutex(Data(repeating: 7, count: 16))
    let record = Mutex<BiometricSetup?>(nil)

    func installationID() -> Data { installation.withLock { $0 } }
    func load() -> BiometricSetup? { record.withLock { $0 } }
    func save(_ setup: BiometricSetup) throws { record.withLock { $0 = setup } }
    func clear() { record.withLock { $0 = nil } }

    let others = Mutex<[String: BiometricSetup]>([:])
    func loadOthers() -> [String: BiometricSetup] { others.withLock { $0 } }
    func saveOthers(_ setups: [String: BiometricSetup]) { others.withLock { $0 = setups } }
}

// MARK: AutoFill

final class FakeRegistry: CredentialIdentityRegistry {
    let enabled = Mutex(true)
    let stored = Mutex<[AutoFillIdentity]>([])
    let replacements = Atomic<Int>(0)

    let storedPasskeys = Mutex<[PasskeyIdentity]>([])

    func replace(with identities: [AutoFillIdentity], passkeys: [PasskeyIdentity]) async -> Bool {
        guard enabled.withLock({ $0 }) else { return false }
        replacements.add(1, ordering: .relaxed)
        stored.withLock { $0 = identities }
        storedPasskeys.withLock { $0 = passkeys }
        return true
    }

    func removeAll() async { stored.withLock { $0 = [] } }
    func isEnabled() async -> Bool { enabled.withLock { $0 } }
    func count() async -> Int { stored.withLock { $0.count } }
}

@MainActor
final class FakeAutoFillCompletion: AutoFillCompletion {
    var filled: [(user: String, password: String)] = []
    var cancelled = 0

    func complete(user: String, password: String) { filled.append((user, password)) }
    var assertions: [(PasskeyAssertion, PasskeyQuery)] = []
    var registrations: [(CreatedPasskey, PasskeyRegistrationQuery)] = []
    func complete(assertion: PasskeyAssertion, query: PasskeyQuery) { assertions.append((assertion, query)) }
    func complete(registration: CreatedPasskey, query: PasskeyRegistrationQuery) { registrations.append((registration, query)) }
    func cancel() { cancelled += 1 }
}
