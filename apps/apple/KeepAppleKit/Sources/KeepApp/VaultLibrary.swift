import Foundation
import KeepCore
import Observation

/// What the account allows for the list of vaults. `unverified` is a Keep+ lease that ran out and has
/// not been checked with the backend yet: Keep+ is off, but nothing permanent is asked of the person.
public enum VaultPlan: Equatable, Sendable {
    case plus
    case free
    case unverified
}

extension KeepAccess {
    public var vaultPlan: VaultPlan {
        if plusFeatures { return .plus }
        return state == .free ? .free : .unverified
    }
}

/// The rule for several vaults without Keep+: every vault can be opened and read, one of them — the
/// main vault — can be edited, and no vault can be added. The main vault is chosen once and stays.
public struct VaultRules: Equatable, Sendable {
    /// Nil means every vault.
    public let editable: Set<String>?
    public let canAdd: Bool
    public let mustChooseMain: Bool
    public let mainID: String?

    public func canEdit(_ vaultID: String) -> Bool {
        editable?.contains(vaultID) ?? true
    }

    /// `fingerprints` names the database behind a vault, where it is known. Several files of one
    /// database (the same file opened twice, a copy of it) count as one vault.
    public static func of(plan: VaultPlan, vaultIDs: [String], storedMain: String?, fingerprints: [String: String] = [:]) -> VaultRules {
        let main = storedMain.flatMap { vaultIDs.contains($0) ? $0 : nil }
        if plan == .plus { return VaultRules(editable: nil, canAdd: true, mustChooseMain: false, mainID: main) }
        let database = { (id: String) in fingerprints[id] ?? id }
        let databases = Set(vaultIDs.map(database))
        if databases.count <= 1 { return VaultRules(editable: nil, canAdd: vaultIDs.isEmpty, mustChooseMain: false, mainID: main) }
        let same = { (id: String) in Set(vaultIDs.filter { database($0) == database(id) }) }
        switch plan {
        case .free:
            return VaultRules(editable: main.map(same) ?? [], canAdd: false, mustChooseMain: main == nil, mainID: main)
        case .plus, .unverified:
            // Until the backend answers, the first vault stands in for a main one that was never chosen.
            return VaultRules(editable: same(main ?? vaultIDs[0]), canAdd: false, mustChooseMain: false, mainID: main)
        }
    }
}

/// One vault the app knows. Nothing here is secret: a label, bookmarks and a local random id.
public struct KnownVault: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public var name: String
    public var reference: VaultReference
    public var lastUsedAt: Date?
    /// Which database the file holds, known after the first unlock. Copies of one database share it.
    public var fingerprint: String?

    public init(id: String = UUID().uuidString, name: String, reference: VaultReference, lastUsedAt: Date? = nil, fingerprint: String? = nil) {
        self.id = id
        self.name = name
        self.reference = reference
        self.lastUsedAt = lastUsedAt
        self.fingerprint = fingerprint
    }

    public var fileName: String { reference.vault.displayName }
}

public struct VaultListSnapshot: Codable, Equatable, Sendable {
    public var vaults: [KnownVault]
    public var activeID: String?
    public var mainID: String?

    public init(vaults: [KnownVault] = [], activeID: String? = nil, mainID: String? = nil) {
        self.vaults = vaults
        self.activeID = activeID
        self.mainID = mainID
    }
}

public protocol VaultListStore: Sendable {
    func load() -> VaultListSnapshot?
    func save(_ snapshot: VaultListSnapshot)
}

/// Bookmarks and labels only. Kept out of backups, like the single reference it grew from.
public struct FileVaultListStore: VaultListStore {
    private let url: URL

    public init(directory: URL) {
        url = directory.appending(path: "vault-list.json")
    }

    public static func shared() -> FileVaultListStore {
        FileVaultListStore(directory: SharedContainer.directory())
    }

    public func load() -> VaultListSnapshot? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(VaultListSnapshot.self, from: data)
    }

    public func save(_ snapshot: VaultListSnapshot) {
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        #if os(iOS)
        try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try? data.write(to: url, options: [.atomic])
        #endif
        var excluded = URLResourceValues()
        excluded.isExcludedFromBackup = true
        var target = url
        try? target.setResourceValues(excluded)
    }
}

/// The vaults the app knows and which of them is chosen. The session controller still works with one
/// vault at a time — the chosen one; this keeps the others and applies the Keep+ rule to them.
@MainActor
@Observable
public final class VaultLibrary {
    public private(set) var vaults: [KnownVault]
    public private(set) var activeID: String?
    public private(set) var mainID: String?

    @ObservationIgnored private let controller: VaultSessionController
    @ObservationIgnored private let store: any VaultListStore
    @ObservationIgnored private let plan: () -> VaultPlan
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let location: (DocumentReference) async -> String?
    /// The chosen vault changed from one known vault (or none) to another.
    @ObservationIgnored public var onSwitched: (_ from: String?, _ to: String?) -> Void = { _, _ in }
    /// A vault left the list.
    @ObservationIgnored public var onForgotten: (_ id: String, _ wasChosen: Bool) -> Void = { _, _ in }

    public init(controller: VaultSessionController, store: any VaultListStore, plan: @escaping () -> VaultPlan, location: @escaping (DocumentReference) async -> String? = { _ in nil }, now: @escaping () -> Date = Date.init) {
        self.controller = controller
        self.location = location
        self.store = store
        self.plan = plan
        self.now = now
        var snapshot = store.load() ?? VaultListSnapshot()
        // The app knew one vault before it kept a list: that vault becomes the first entry.
        if let current = controller.reference, !snapshot.vaults.contains(where: { $0.reference.vault.bookmark == current.vault.bookmark }) {
            if let index = snapshot.vaults.firstIndex(where: { $0.id == snapshot.activeID }) {
                snapshot.vaults[index].reference = current
            } else {
                let adopted = KnownVault(name: Self.label(current.vault.displayName), reference: current)
                snapshot.vaults.append(adopted)
                snapshot.activeID = adopted.id
            }
        }
        if let current = controller.reference {
            snapshot.activeID = snapshot.vaults.first { $0.reference.vault.bookmark == current.vault.bookmark }?.id
        } else {
            snapshot.activeID = nil
        }
        vaults = snapshot.vaults
        activeID = snapshot.activeID
        mainID = snapshot.mainID
        persist()
        controller.onReferenceChanged = { [weak self] reference in self?.referenceChanged(reference) }
        controller.onReferenceRefreshed = { [weak self] reference in self?.referenceRefreshed(reference) }
        controller.knownVault = { [weak self] candidate in await self?.adopt(candidate) }
        controller.mayAddVault = { [weak self] candidate in
            guard let self else { return true }
            if let candidate, self.vaults.contains(where: { $0.reference.vault.bookmark == candidate.bookmark }) { return true }
            return self.rules.canAdd
        }
        controller.mayEdit = { [weak self] in self.map { !$0.activeIsReadOnly } ?? true }
    }

    public var rules: VaultRules {
        let fingerprints = vaults.reduce(into: [String: String]()) { $0[$1.id] = $1.fingerprint }
        return VaultRules.of(plan: plan(), vaultIDs: vaults.map(\.id), storedMain: mainID, fingerprints: fingerprints)
    }

    public var active: KnownVault? { vaults.first { $0.id == activeID } }

    /// True when the chosen vault opens for reading only because the account has no Keep+.
    public var activeIsReadOnly: Bool {
        guard let activeID else { return false }
        return !rules.canEdit(activeID)
    }

    public func activate(_ id: String) {
        guard let vault = vaults.first(where: { $0.id == id }) else { return }
        controller.activate(vault.reference)
        activeID = id
        persist()
    }

    public func rename(_ id: String, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= 80, let index = vaults.firstIndex(where: { $0.id == id }) else { return }
        vaults[index].name = trimmed
        persist()
    }

    /// Removes the vault from this app only. The file is never touched.
    public func forget(_ id: String) {
        guard vaults.contains(where: { $0.id == id }) else { return }
        if id == activeID {
            controller.forgetVault()
        } else {
            vaults.removeAll { $0.id == id }
            persist()
            onForgotten(id, false)
        }
    }

    public func forgetAll() {
        let chosen = activeID
        let known = vaults.map(\.id)
        vaults = []
        activeID = nil
        mainID = nil
        persist()
        controller.forgetVault()
        for id in known { onForgotten(id, id == chosen) }
    }

    /// Only answers while the choice is being asked for; afterwards the main vault stays what it is.
    @discardableResult
    public func chooseMain(_ id: String) -> Bool {
        guard rules.mustChooseMain, vaults.contains(where: { $0.id == id }) else { return false }
        mainID = id
        persist()
        return true
    }

    public func vaultUnlocked(fingerprint: String? = nil) {
        guard let index = vaults.firstIndex(where: { $0.id == activeID }) else { return }
        vaults[index].lastUsedAt = now()
        if let fingerprint { vaults[index].fingerprint = fingerprint }
        persist()
    }

    /// Leaves one entry per file. An entry that pointed at the same file as another one is dropped;
    /// the chosen vault and the main vault are the ones that stay.
    public func mergeDuplicates() async {
        var places: [String: String] = [:]
        for vault in vaults {
            if let place = await location(vault.reference.vault) { places[vault.id] = place }
        }
        merge(places)
    }

    private func merge(_ places: [String: String]) {
        var kept: [String: String] = [:]
        let order = vaults.sorted { rank($0.id) < rank($1.id) }
        var dropped: [String] = []
        for vault in order {
            guard let place = places[vault.id] else { continue }
            if let keeper = kept[place] {
                dropped.append(vault.id)
                if mainID == vault.id { mainID = keeper }
            } else {
                kept[place] = vault.id
            }
        }
        guard !dropped.isEmpty else { return }
        vaults.removeAll { dropped.contains($0.id) }
        persist()
        for id in dropped { onForgotten(id, false) }
    }

    private func rank(_ id: String) -> Int {
        if id == activeID { return 0 }
        return id == mainID ? 1 : 2
    }

    private func adopt(_ candidate: DocumentReference) async -> VaultReference? {
        if vaults.contains(where: { $0.reference.vault.bookmark == candidate.bookmark }) { return nil }
        guard let place = await location(candidate) else { return nil }
        for vault in vaults where await location(vault.reference.vault) == place {
            guard let index = vaults.firstIndex(where: { $0.id == vault.id }) else { return nil }
            vaults[index].reference.vault = candidate
            persist()
            return vaults[index].reference
        }
        return nil
    }

    private func referenceRefreshed(_ reference: VaultReference) {
        guard let index = vaults.firstIndex(where: { $0.id == activeID }) else { return }
        vaults[index].reference = reference
        persist()
    }

    private func referenceChanged(_ reference: VaultReference?) {
        let previous = activeID
        guard let reference else {
            vaults.removeAll { $0.id == previous }
            activeID = nil
            persist()
            if let previous { onForgotten(previous, true) }
            return
        }
        if let index = vaults.firstIndex(where: { $0.reference.vault.bookmark == reference.vault.bookmark }) {
            vaults[index].reference = reference
            activeID = vaults[index].id
        } else {
            let added = KnownVault(name: Self.label(reference.vault.displayName), reference: reference)
            vaults.append(added)
            activeID = added.id
        }
        persist()
        if activeID != previous { onSwitched(previous, activeID) }
    }

    private func persist() {
        store.save(VaultListSnapshot(vaults: vaults, activeID: activeID, mainID: mainID))
    }

    private static func label(_ fileName: String) -> String {
        fileName.hasSuffix(".kdbx") ? String(fileName.dropLast(5)) : fileName
    }
}
