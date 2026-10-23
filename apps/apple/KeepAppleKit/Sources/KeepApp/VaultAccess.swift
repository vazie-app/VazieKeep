import Foundation
import KeepCore

public protocol VaultSessionAccess: AnyObject, Sendable {
    var isClosed: Bool { get }
    func info() throws(VaultSessionError) -> VaultInfo
    func groups() throws(VaultSessionError) -> [VaultGroup]
    func entries() throws(VaultSessionError) -> [EntrySummary]
    func search(_ query: String) throws(VaultSessionError) -> [EntrySummary]
    func entry(_ id: EntryID) throws(VaultSessionError) -> EntryDetail
    func reveal(_ field: SecretField, for id: EntryID) throws(VaultSessionError) -> SecretValue
    func totpCode(for id: EntryID, at unixSeconds: Int, access: KeepAccess) throws(TotpError) -> TotpCode
    func unlockMaterial() throws(VaultSessionError) -> SecretValue?
    func close()

    func isDirty() throws(VaultSessionError) -> Bool
    func createEntry(in group: GroupID, _ draft: EntryDraft) throws(VaultEditError) -> EntryID
    func updateEntry(_ id: EntryID, _ draft: EntryDraft) throws(VaultEditError)
    func deleteEntry(_ id: EntryID, permanent: Bool) throws(VaultEditError) -> DeleteOutcome
    func setFavorite(_ id: EntryID, _ favorite: Bool) throws(VaultEditError)
    func attachment(_ name: String, for id: EntryID) throws(VaultSessionError) -> SecretValue
    func addAttachment(_ name: String, data: Data, to id: EntryID, access: KeepAccess) throws(VaultEditError) -> String
    func removeAttachment(_ name: String, from id: EntryID) throws(VaultEditError)
    func history(of id: EntryID, access: KeepAccess) throws(VaultHistoryError) -> [HistoryVersion]
    func historyEntry(_ index: Int, of id: EntryID, access: KeepAccess) throws(VaultHistoryError) -> EntryDetail
    func revealHistory(_ field: SecretField, version index: Int, of id: EntryID, access: KeepAccess) throws(VaultHistoryError) -> SecretValue
    func restoreHistory(_ index: Int, of id: EntryID, access: KeepAccess) throws(VaultEditError)
    func importPlan(_ plan: ImportPlan, into group: GroupID) throws(VaultEditError) -> ImportOutcome
    func moveEntry(_ id: EntryID, to group: GroupID) throws(VaultEditError)
    func createGroup(in parent: GroupID, name: String) throws(VaultEditError) -> GroupID
    func renameGroup(_ id: GroupID, name: String) throws(VaultEditError)
    func setGroupIcon(_ id: GroupID, _ icon: Int) throws(VaultEditError)
    func setEntryIcon(_ id: EntryID, _ icon: Int) throws(VaultEditError)
    func assignIconsByURL() throws(VaultEditError) -> Int
    func passkeys(rpID: String?) throws(VaultSessionError) -> [EntrySummary]
    func signPasskey(_ id: EntryID, rpID: String, clientDataHash: Data) throws(VaultPasskeyError) -> PasskeyAssertion
    func createPasskey(in group: GroupID, rpID: String, rpName: String, userName: String, userHandle: Data) throws(VaultEditError) -> CreatedPasskey
    func removePasskey(_ id: EntryID) throws(VaultEditError)
    func siteIconHosts() throws(VaultSessionError) -> [String]
    func siteIconHost(for id: EntryID) throws(VaultSessionError) -> String?
    func setSiteIcons(_ icons: [SiteIcon]) throws(VaultEditError) -> Int
    func customIcon(_ id: String) throws(VaultSessionError) -> Data?
    func deleteGroup(_ id: GroupID, recursive: Bool, permanent: Bool) throws(VaultEditError) -> DeleteOutcome
    func prepareSave() throws(VaultSaveError) -> SaveSnapshot
    @discardableResult
    func markSaved(generation: UInt64) throws(VaultSessionError) -> Bool
}

extension KeepVaultSession: VaultSessionAccess {}

public protocol VaultOpening: Sendable {
    func detectFormat(header: Data) -> VaultFormat
    func open(data: Data, credentials: VaultCredentials) async throws(VaultOpenError) -> any VaultSessionAccess
}

public struct RustVaultOpener: VaultOpening {
    private let core = RustKeepCore()

    public init() {}

    public func detectFormat(header: Data) -> VaultFormat {
        core.detectVaultFormat(header: header)
    }

    public func open(data: Data, credentials: VaultCredentials) async throws(VaultOpenError) -> any VaultSessionAccess {
        try await core.openVault(data: data, credentials: credentials)
    }
}
