import Foundation
import KeepCore

public enum CreateVaultFailure: Error, Equatable, Sendable {
    case emptyName
    case missingCredentials
    case invalidKeyFile
    case writeFailed
    case failed
    case openFailed
    /// Another vault needs Keep+.
    case needsPlus
}

extension VaultSessionController {
    /// Makes a new vault file in `directory` and selects it, ready to unlock.
    public func createVault(name: String, password: String, keyFile: (data: Data, url: URL)? = nil, preset: SecurityPreset = .balanced, in directory: URL) async -> Result<DocumentReference, CreateVaultFailure> {
        guard mayAddVault(nil) else { return .failure(.needsPlus) }
        let made = await Task.detached(priority: .userInitiated) { () -> Result<Data, VaultCreationError> in
            do throws(VaultCreationError) { return .success(try VaultCreation.make(name: name, password: password, keyFile: keyFile?.data, preset: preset)) } catch { return .failure(error) }
        }.value
        let bytes: Data
        switch made {
        case let .success(data): bytes = data
        case .failure(.emptyName): return .failure(.emptyName)
        case .failure(.missingCredentials): return .failure(.missingCredentials)
        case .failure(.invalidKeyFile): return .failure(.invalidKeyFile)
        case .failure(.failed): return .failure(.failed)
        }
        let url: URL
        do {
            url = try Self.write(bytes, named: name, in: directory)
        } catch {
            return .failure(.writeFailed)
        }
        switch await chooseVault(url) {
        case let .success(reference):
            if let keyFile { _ = await chooseKeyFile(keyFile.url) }
            return .success(reference)
        case .failure: return .failure(.openFailed)
        }
    }

    nonisolated static func write(_ bytes: Data, named name: String, in directory: URL) throws -> URL {
        let manager = FileManager.default
        try manager.createDirectory(at: directory, withIntermediateDirectories: true)
        let forbidden = CharacterSet(charactersIn: "/\\:*?\"<>|").union(.controlCharacters)
        let base = name.trimmingCharacters(in: .whitespacesAndNewlines).components(separatedBy: forbidden).joined(separator: "-")
        var url = directory.appending(path: "\(base).kdbx")
        var counter = 2
        while manager.fileExists(atPath: url.path) {
            url = directory.appending(path: "\(base) \(counter).kdbx")
            counter += 1
        }
        try bytes.write(to: url, options: [.atomic, .completeFileProtection])
        return url
    }
}
