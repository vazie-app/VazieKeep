import Foundation
import KeepCore
import Observation

// MARK: Unlock

@MainActor
@Observable
public final class UnlockModel {
    /// Bound to the SecureField. Swift strings can't be wiped; it is emptied on every submit and
    /// lives only as long as the Unlock screen.
    public var password = ""
    public private(set) var error: UnlockError?
    public private(set) var keyFileError: ChooseError?
    /// Bumped after a failed unlock so the view puts focus back into the password field.
    public private(set) var refocusSignal = 0

    /// The last Face ID outcome worth telling the user about. Cancel is not one of them.
    public private(set) var biometricResult: BiometricUnlockResult?

    @ObservationIgnored private let controller: VaultSessionController
    @ObservationIgnored private let biometric: BiometricUnlock?

    public init(controller: VaultSessionController, biometric: BiometricUnlock? = nil) {
        self.controller = controller
        self.biometric = biometric
    }

    public var offersBiometricUnlock: Bool {
        if case .on = biometric?.status { return true }
        return false
    }

    public var biometricNeedsSetup: Bool { biometric?.status == .needsSetup }

    public func unlockWithBiometrics(reason: String) async {
        guard let biometric, !controller.isUnlocking else { return }
        error = nil
        biometricResult = nil
        let result = await biometric.unlock(reason: reason)
        switch result {
        case .success:
            break
        case .cancelled, .fallback:
            refocusSignal += 1
        case let .vaultError(failure):
            error = failure
            refocusSignal += 1
        default:
            biometricResult = result
            refocusSignal += 1
        }
    }

    public var vaultName: String? { controller.reference?.vault.displayName }
    public var keyFileName: String? { controller.reference?.keyFile?.displayName }
    public var isUnlocking: Bool { controller.isUnlocking }
    public var canUnlock: Bool { !password.isEmpty || keyFileName != nil }

    public func unlock() async {
        guard canUnlock, !controller.isUnlocking else { return }
        let typed = password.isEmpty ? nil : Data(password.utf8)
        password = ""
        error = nil
        biometricResult = nil
        switch await controller.unlock(password: typed) {
        case .success, .alreadyRunning, .cancelled:
            break
        case let .failure(failure):
            error = failure
            refocusSignal += 1
        }
    }

    public func chooseKeyFile(_ url: URL) async {
        switch await controller.chooseKeyFile(url) {
        case .success:
            keyFileError = nil
            if error.map(Self.isKeyFileError) == true { error = nil }
            if biometricResult == .keyFileRequired { biometricResult = nil }
        case let .failure(failure):
            keyFileError = failure
        }
    }

    public func removeKeyFile() {
        controller.removeKeyFile()
        keyFileError = nil
        if error.map(Self.isKeyFileError) == true { error = nil }
    }

    public func discardPassword() {
        password = ""
    }

    private static func isKeyFileError(_ error: UnlockError) -> Bool {
        error == .keyFileUnavailable || error == .keyFileTooLarge || error == .invalidKeyFile || error == .keyFileRequired
    }
}

// MARK: Browsing

public enum BrowserItem: Identifiable, Equatable, Sendable {
    case group(VaultGroup)
    case entry(EntrySummary)

    public var id: String {
        switch self {
        case let .group(group): "g:\(group.id.rawValue)"
        case let .entry(entry): "e:\(entry.id.rawValue)"
        }
    }
}

public enum VaultFilter: Hashable, Sendable {
    case all
    case favorites
    case expired
    case tag(String)
}

public struct FilterChoice: Hashable, Sendable {
    public let filter: VaultFilter
    public let count: Int

    public init(filter: VaultFilter, count: Int) {
        self.filter = filter
        self.count = count
    }
}

@MainActor
@Observable
public final class BrowserModel {
    public let groupID: GroupID?
    public private(set) var title = ""
    public private(set) var groups: [VaultGroup] = []
    public private(set) var entries: [EntrySummary] = []
    public private(set) var searchResults: [EntrySummary]?
    /// Only the vault root offers filters.
    public private(set) var filters: [FilterChoice] = []
    public private(set) var filter: VaultFilter = .all
    public var query = "" {
        didSet { scheduleSearch() }
    }

    @ObservationIgnored private let controller: VaultSessionController
    @ObservationIgnored private var searchTask: Task<Void, Never>?
    @ObservationIgnored private let debounce: Duration
    @ObservationIgnored private let now: () -> Date

    /// `groupID == nil` is the vault root.
    public init(controller: VaultSessionController, groupID: GroupID? = nil, debounce: Duration = .milliseconds(200), now: @escaping () -> Date = Date.init) {
        self.controller = controller
        self.groupID = groupID
        self.debounce = debounce
        self.now = now
    }

    public func select(_ filter: VaultFilter) {
        self.filter = filter
        load()
    }

    /// Where new entries and groups go: this group, or the vault root.
    public var targetGroup: GroupID? {
        guard case let .unlocked(info) = controller.state else { return nil }
        return groupID ?? info.rootGroupID
    }

    public func load() {
        guard case let .unlocked(info) = controller.state else { return }
        let target = groupID ?? info.rootGroupID
        do {
            let all = try controller.groups()
            title = groupID == nil ? info.name : (all.first { $0.id == target }?.name ?? "")
            let everything = try controller.entries()
            if groupID == nil {
                let live = everything.filter { !Self.isInBin($0.groupID, bin: info.recycleBinID, groups: all) }
                filters = Self.filterChoices(live, now: now())
                if !filters.contains(where: { $0.filter == filter }) { filter = .all }
                if filter != .all {
                    groups = []
                    entries = live.filter { $0.matches(filter, now: now()) }.sorted { $0.sortKey < $1.sortKey }
                    return
                }
            }
            groups = all.filter { $0.parentID == target }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
            entries = everything.filter { $0.groupID == target }.sorted { $0.sortKey < $1.sortKey }
        } catch {
            groups = []
            entries = []
        }
    }

    static func filterChoices(_ entries: [EntrySummary], now: Date) -> [FilterChoice] {
        var extra: [FilterChoice] = []
        let favorites = entries.filter(\.favorite).count
        if favorites > 0 { extra.append(FilterChoice(filter: .favorites, count: favorites)) }
        let expired = entries.filter { $0.isExpired(at: now) }.count
        if expired > 0 { extra.append(FilterChoice(filter: .expired, count: expired)) }
        var counts: [String: Int] = [:]
        for entry in entries {
            for tag in Set(entry.tags) { counts[tag, default: 0] += 1 }
        }
        let tags = counts.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key.lowercased() < $1.key.lowercased() }
        extra += tags.map { FilterChoice(filter: .tag($0.key), count: $0.value) }
        return extra.isEmpty ? [] : [FilterChoice(filter: .all, count: entries.count)] + extra
    }

    private static func isInBin(_ group: GroupID, bin: GroupID?, groups: [VaultGroup]) -> Bool {
        guard let bin else { return false }
        var current: GroupID? = group
        while let id = current {
            if id == bin { return true }
            current = groups.first { $0.id == id }?.parentID
        }
        return false
    }

    public func search(now text: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            searchResults = nil
            return
        }
        do {
            let results = try await controller.search(trimmed)
            if query.trimmingCharacters(in: .whitespacesAndNewlines) == trimmed { searchResults = results }
        } catch {
            searchResults = []
        }
    }

    private func scheduleSearch() {
        searchTask?.cancel()
        let text = query
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            searchResults = nil
            return
        }
        let debounce = debounce
        searchTask = Task { [weak self] in
            try? await Task.sleep(for: debounce)
            guard !Task.isCancelled else { return }
            await self?.search(now: text)
        }
    }
}

extension EntrySummary {
    public var displayTitle: String {
        if case let .plain(value) = title, !value.isEmpty { return value }
        return ""
    }

    var sortKey: String { displayTitle.lowercased() }

    func matches(_ filter: VaultFilter, now: Date) -> Bool {
        switch filter {
        case .all: true
        case .favorites: favorite
        case .expired: isExpired(at: now)
        case let .tag(name): tags.contains(name)
        }
    }
}

// MARK: Entry

public struct RevealedSecret: Equatable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public let field: SecretField
    public let text: String

    public var description: String { "RevealedSecret(<redacted>)" }
    public var debugDescription: String { description }
    public var customMirror: Mirror { Mirror(reflecting: description) }
}

@MainActor
public protocol SecretClipboard: AnyObject {
    /// Returns true when the system itself will remove the item after `expiresAfter`.
    func copy(_ text: String, expiresAfter: Duration?) -> Bool
    /// Clears the pasteboard only if it still holds our last item.
    func clearIfOwned()
}

public enum CopyFeedback: Equatable, Sendable {
    case copied(clearsAfter: Duration?)
    case failed
}

public enum TotpDisplay: Equatable, Sendable, CustomStringConvertible {
    case none
    case locked
    case waiting
    case active(code: String, secondsRemaining: Int, period: Int)

    public var description: String {
        switch self {
        case .none: "none"
        case .locked: "locked"
        case .waiting: "waiting"
        case let .active(_, secondsRemaining, period): "active(secondsRemaining: \(secondsRemaining), period: \(period))"
        }
    }
}

@MainActor
@Observable
public final class EntryModel {
    public let entryID: EntryID
    public private(set) var detail: EntryDetail?
    public private(set) var totp: TotpDisplay = .none
    public private(set) var revealed: RevealedSecret?
    public private(set) var unavailable = false

    @ObservationIgnored private let controller: VaultSessionController
    @ObservationIgnored private let clipboard: any SecretClipboard
    @ObservationIgnored private let clipboardTimeout: () -> Duration?
    @ObservationIgnored private let autoHide: Duration
    @ObservationIgnored private let sleep: @Sendable (Duration) async -> Void
    @ObservationIgnored private var hideTask: Task<Void, Never>?
    @ObservationIgnored private let access: () -> KeepAccess
    @ObservationIgnored private let now: () -> Int
    @ObservationIgnored private let autoTick: Bool
    @ObservationIgnored private var totpTask: Task<Void, Never>?
    @ObservationIgnored private var totpVisible = true

    public init(
        entryID: EntryID,
        controller: VaultSessionController,
        clipboard: any SecretClipboard,
        clipboardTimeout: @escaping () -> Duration?,
        autoHide: Duration = .seconds(15),
        sleep: @escaping @Sendable (Duration) async -> Void = { try? await Task.sleep(for: $0) },
        access: @escaping () -> KeepAccess = { .free },
        now: @escaping () -> Int = { Int(Date().timeIntervalSince1970) },
        autoTick: Bool = true
    ) {
        self.access = access
        self.now = now
        self.autoTick = autoTick
        self.entryID = entryID
        self.controller = controller
        self.clipboard = clipboard
        self.clipboardTimeout = clipboardTimeout
        self.autoHide = autoHide
        self.sleep = sleep
    }

    public func load() {
        do {
            detail = try controller.entry(entryID)
            restartTotp()
        } catch {
            unavailable = true
            stopTotp()
        }
    }

    public func setTotpVisible(_ visible: Bool) {
        guard totpVisible != visible else { return }
        totpVisible = visible
        if detail != nil { restartTotp() }
    }

    public func stopTotp() {
        totpTask?.cancel()
        totpTask = nil
    }

    /// Reads the code for the current second. Returns false when there is nothing more to show.
    @discardableResult
    public func refreshTotp() -> Bool {
        do {
            let code = try controller.totpCode(for: entryID, at: now(), access: access())
            defer { code.code.wipe() }
            let text = try code.code.withString { $0 }
            totp = .active(code: text, secondsRemaining: code.secondsRemaining, period: code.period)
            return true
        } catch let error as TotpError {
            totp = error.reason == .notEntitled ? .locked : .none
            return false
        } catch {
            totp = .none
            return false
        }
    }

    public func copyTotp() -> CopyFeedback {
        guard let code = try? controller.totpCode(for: entryID, at: now(), access: access()) else { return .failed }
        defer { code.code.wipe() }
        guard let text = try? code.code.withString({ $0 }) else { return .failed }
        let timeout = clipboardTimeout()
        let systemExpires = clipboard.copy(text, expiresAfter: timeout)
        return .copied(clearsAfter: systemExpires ? timeout : nil)
    }

    private func restartTotp() {
        stopTotp()
        guard detail?.summary.hasTotp == true else {
            totp = .none
            return
        }
        guard totpVisible else {
            totp = .waiting
            return
        }
        guard refreshTotp() else { return }
        guard autoTick else { return }
        let sleep = sleep
        totpTask = Task { [weak self] in
            while !Task.isCancelled {
                await sleep(.seconds(1))
                guard !Task.isCancelled, let self else { return }
                if !self.refreshTotp() { return }
            }
        }
    }

    public func toggle(_ field: SecretField) {
        if revealed?.field == field {
            hide()
        } else {
            reveal(field)
        }
    }

    public func reveal(_ field: SecretField) {
        guard let secret = try? controller.reveal(field, for: entryID) else { return }
        defer { secret.wipe() }
        guard let text = try? secret.withString({ $0 }) else { return }
        revealed = RevealedSecret(field: field, text: text)
        hideTask?.cancel()
        let sleep = sleep
        let autoHide = autoHide
        hideTask = Task { [weak self] in
            await sleep(autoHide)
            guard !Task.isCancelled else { return }
            self?.hide()
        }
    }

    public func hide() {
        hideTask?.cancel()
        hideTask = nil
        revealed = nil
    }

    /// The attachment's bytes, for writing to a file the person chose.
    public func attachmentData(_ name: String) -> Data? {
        guard let secret = try? controller.attachment(name, for: entryID) else { return nil }
        defer { secret.wipe() }
        return try? secret.withBytes { Data($0) }
    }

    public func copy(_ field: SecretField) -> CopyFeedback {
        guard let secret = try? controller.reveal(field, for: entryID) else { return .failed }
        defer { secret.wipe() }
        guard let text = try? secret.withString({ $0 }) else { return .failed }
        let timeout = clipboardTimeout()
        let systemExpires = clipboard.copy(text, expiresAfter: timeout)
        return .copied(clearsAfter: systemExpires ? timeout : nil)
    }
}
