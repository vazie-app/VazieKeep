import Foundation
import KeepCore
import Observation

public enum ImportProblem: Equatable, Sendable {
    case unreadable
    case tooLarge
    case empty
    case unrecognized
    case encrypted
    case malformed
    case failed
}

public enum ImportPhase: Equatable, Sendable {
    case idle
    case reading
    case preview(ImportSummary)
    case importing(ImportSummary)
    case done(ImportOutcome)
    case failed(ImportProblem)
}

/// Takes another password manager's export into the open vault. The file is the person's passwords
/// in plain text: it is read once, handed to the core and never kept here.
@MainActor
@Observable
public final class ImportModel {
    public private(set) var phase: ImportPhase = .idle
    /// Set when the entries are in the open vault but the file could not be written.
    public var saveFailure: SaveFailure?

    @ObservationIgnored private let controller: VaultSessionController
    @ObservationIgnored private let parse: @Sendable (Data) throws(ImportError) -> ImportPlan
    @ObservationIgnored private var plan: ImportPlan?

    public init(
        controller: VaultSessionController,
        parse: @escaping @Sendable (Data) throws(ImportError) -> ImportPlan = { data throws(ImportError) in try RustKeepCore().parseImport(data) }
    ) {
        self.controller = controller
        self.parse = parse
    }

    /// `data` is nil when the picked file could not be read.
    public func read(_ data: Data?, tooLarge: Bool = false) async {
        guard phase == .idle else { return }
        guard let data, !tooLarge else {
            phase = .failed(tooLarge ? .tooLarge : .unreadable)
            return
        }
        phase = .reading
        let parse = parse
        let result = await Task.detached(priority: .userInitiated) { () -> Result<ImportPlan, ImportError> in
            do throws(ImportError) {
                return .success(try parse(data))
            } catch {
                return .failure(error)
            }
        }.value
        guard phase == .reading else { return }
        switch result {
        case let .success(parsed):
            plan = parsed
            phase = .preview(parsed.summary)
        case let .failure(error):
            phase = .failed(Self.problem(error.reason))
        }
    }

    public func confirm() async {
        guard case let .preview(summary) = phase, let plan, case let .unlocked(info) = controller.state else { return }
        phase = .importing(summary)
        let result = await controller.edit { session throws(VaultEditError) in
            try session.importPlan(plan, into: info.rootGroupID)
        }
        self.plan = nil
        switch result {
        case let .saved(outcome):
            phase = .done(outcome)
        case let .notSaved(outcome, failure):
            saveFailure = failure
            phase = .done(outcome)
        case .failed:
            phase = .failed(.failed)
        }
    }

    public func dismiss() {
        plan = nil
        phase = .idle
    }

    private static func problem(_ reason: ImportError.Reason) -> ImportProblem {
        switch reason {
        case .empty: .empty
        case .tooLarge: .tooLarge
        case .unrecognized: .unrecognized
        case .encrypted: .encrypted
        case .malformed: .malformed
        }
    }
}
