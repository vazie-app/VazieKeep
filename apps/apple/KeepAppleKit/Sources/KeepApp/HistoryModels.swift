import Foundation
import KeepCore
import Observation

public enum HistoryPhase: Equatable, Sendable {
    case loading
    case loaded
    case needsPlus
    case unavailable
}

/// The earlier versions of one entry, newest first.
@MainActor
@Observable
public final class HistoryModel {
    public let entryID: EntryID
    public private(set) var phase: HistoryPhase = .loading
    public private(set) var versions: [HistoryVersion] = []

    @ObservationIgnored private let controller: VaultSessionController
    @ObservationIgnored private let access: () -> KeepAccess

    public init(entryID: EntryID, controller: VaultSessionController, access: @escaping () -> KeepAccess) {
        self.entryID = entryID
        self.controller = controller
        self.access = access
    }

    public func load() {
        do {
            versions = try controller.history(of: entryID, access: access())
            phase = .loaded
        } catch {
            versions = []
            phase = error.reason == .notEntitled ? .needsPlus : .unavailable
        }
    }
}

public enum RestoreOutcome: Equatable, Sendable {
    case restored
    case notSaved(SaveFailure)
    case failed
}

/// One earlier version: its fields, its secrets on request, and the way back to it.
@MainActor
@Observable
public final class HistoryVersionModel {
    public let entryID: EntryID
    public let index: Int
    public private(set) var phase: HistoryPhase = .loading
    public private(set) var version: HistoryVersion?
    public private(set) var detail: EntryDetail?
    public private(set) var revealed: RevealedSecret?

    @ObservationIgnored private let controller: VaultSessionController
    @ObservationIgnored private let clipboard: any SecretClipboard
    @ObservationIgnored private let clipboardTimeout: () -> Duration?
    @ObservationIgnored private let autoHide: Duration
    @ObservationIgnored private let sleep: @Sendable (Duration) async -> Void
    @ObservationIgnored private let access: () -> KeepAccess
    @ObservationIgnored private var hideTask: Task<Void, Never>?

    public init(
        entryID: EntryID,
        index: Int,
        controller: VaultSessionController,
        clipboard: any SecretClipboard,
        clipboardTimeout: @escaping () -> Duration?,
        autoHide: Duration = .seconds(15),
        sleep: @escaping @Sendable (Duration) async -> Void = { try? await Task.sleep(for: $0) },
        access: @escaping () -> KeepAccess
    ) {
        self.entryID = entryID
        self.index = index
        self.controller = controller
        self.clipboard = clipboard
        self.clipboardTimeout = clipboardTimeout
        self.autoHide = autoHide
        self.sleep = sleep
        self.access = access
    }

    public func load() {
        do {
            let current = access()
            version = try controller.history(of: entryID, access: current).first { $0.index == index }
            detail = try controller.historyEntry(index, of: entryID, access: current)
            phase = version == nil ? .unavailable : .loaded
        } catch {
            hide()
            version = nil
            detail = nil
            phase = error.reason == .notEntitled ? .needsPlus : .unavailable
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
        guard let secret = try? controller.revealHistory(field, version: index, of: entryID, access: access()) else { return }
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

    public func copy(_ field: SecretField) -> CopyFeedback {
        guard let secret = try? controller.revealHistory(field, version: index, of: entryID, access: access()) else { return .failed }
        defer { secret.wipe() }
        guard let text = try? secret.withString({ $0 }) else { return .failed }
        let timeout = clipboardTimeout()
        let systemExpires = clipboard.copy(text, expiresAfter: timeout)
        return .copied(clearsAfter: systemExpires ? timeout : nil)
    }

    /// Makes this version current again and saves the vault. The entry as it is now stays in the history.
    public func restore() async -> RestoreOutcome {
        hide()
        let current = access()
        let id = entryID
        let index = index
        let result = await controller.edit { session throws(VaultEditError) in
            try session.restoreHistory(index, of: id, access: current)
        }
        switch result {
        case .saved: return .restored
        case let .notSaved(_, failure): return .notSaved(failure)
        case let .failed(error):
            if error.reason == .notEntitled { phase = .needsPlus }
            return .failed
        }
    }
}
