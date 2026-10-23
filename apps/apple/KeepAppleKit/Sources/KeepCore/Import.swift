import Foundation
import KeepCoreBindings

public enum ImportSource: Equatable, Sendable {
    case csv
    case onePasswordCSV
    case onePassword1PUX
    case bitwardenJSON
    case bitwardenCSV
}

/// What an export holds, without any of its secrets.
public struct ImportSummary: Equatable, Sendable {
    public let source: ImportSource
    public let entries: Int
    public let folders: Int
    public let withTotp: Int
    public let skipped: Int

    public init(source: ImportSource, entries: Int, folders: Int, withTotp: Int, skipped: Int) {
        self.source = source
        self.entries = entries
        self.folders = folders
        self.withTotp = withTotp
        self.skipped = skipped
    }
}

public struct ImportOutcome: Equatable, Sendable {
    public let entries: Int
    public let groups: Int

    public init(entries: Int, groups: Int) {
        self.entries = entries
        self.groups = groups
    }
}

public struct ImportError: Error, Equatable, Sendable {
    public enum Reason: Equatable, Sendable {
        case empty
        case tooLarge
        case unrecognized
        case encrypted
        case malformed
    }

    public let reason: Reason

    public init(reason: Reason) {
        self.reason = reason
    }
}

/// A parsed export. Its passwords stay in the Rust core until a session takes them in.
public final class ImportPlan: Sendable, CustomStringConvertible {
    let handle: ImportPlanHandle?
    public let summary: ImportSummary

    init(handle: ImportPlanHandle) {
        self.handle = handle
        let dto = handle.summary()
        let source: ImportSource = switch dto.source {
        case .csv: .csv
        case .onePasswordCsv: .onePasswordCSV
        case .onePassword1pux: .onePassword1PUX
        case .bitwardenJson: .bitwardenJSON
        case .bitwardenCsv: .bitwardenCSV
        }
        summary = ImportSummary(
            source: source,
            entries: Int(dto.entries),
            folders: Int(dto.folders),
            withTotp: Int(dto.withTotp),
            skipped: Int(dto.skipped)
        )
    }

    /// A stand-in with no export behind it, for tests of the code around the core.
    public init(summary: ImportSummary) {
        handle = nil
        self.summary = summary
    }

    public var description: String { "ImportPlan(<opaque>)" }
}

extension RustKeepCore {
    /// Reads another password manager's export: CSV with a header row, 1Password (.1pux, CSV),
    /// Bitwarden (JSON, CSV). The bytes are the person's passwords in plain text.
    public func parseImport(_ data: Data) throws(ImportError) -> ImportPlan {
        do {
            return ImportPlan(handle: try KeepCoreBindings.parseImport(data: data))
        } catch let error as ImportFileError {
            let reason: ImportError.Reason = switch error {
            case .Empty: .empty
            case .TooLarge: .tooLarge
            case .Unrecognized: .unrecognized
            case .Encrypted: .encrypted
            case .Malformed: .malformed
            }
            throw ImportError(reason: reason)
        } catch {
            throw ImportError(reason: .unrecognized)
        }
    }
}
