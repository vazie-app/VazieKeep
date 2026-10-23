import Foundation
import KeepCoreBindings

public enum TotpAlgorithm: Equatable, Sendable {
    case sha1, sha256, sha512
}

public struct TotpCode: CustomStringConvertible {
    public let code: SecretValue
    public let period: Int
    public let secondsRemaining: Int

    public init(code: SecretValue, period: Int, secondsRemaining: Int) {
        self.code = code
        self.period = period
        self.secondsRemaining = secondsRemaining
    }

    public var description: String { "TotpCode(period: \(period), secondsRemaining: \(secondsRemaining))" }
}

public struct TotpSetup: CustomStringConvertible {
    public let uri: SecretValue
    public let algorithm: TotpAlgorithm
    public let digits: Int
    public let period: Int
    public let isSteam: Bool
    public let issuer: String
    public let account: String

    public var description: String { "TotpSetup(digits: \(digits), period: \(period), steam: \(isSteam))" }
}

public struct TotpError: Error, Equatable, Sendable {
    public enum Reason: Sendable {
        case sessionNotFound, sessionClosed, entryNotFound, notEntitled, noCode, empty, tooLong, unsupportedType, missingSecret
        case invalidSecret, invalidAlgorithm, invalidDigits, invalidPeriod, migration, malformed, invalidText
    }

    public let reason: Reason

    public init(reason: Reason) {
        self.reason = reason
    }
}

public let otpFieldName = "otp"

private let totpFieldNames: Set<String> = [
    otpFieldName, "TimeOtp-Secret-Base32", "TimeOtp-Secret-Hex", "TimeOtp-Secret", "TimeOtp-Length",
    "TimeOtp-Period", "TimeOtp-Algorithm", "TOTP Seed", "TOTP Settings",
]

public func isTotpFieldName(_ name: String) -> Bool { totpFieldNames.contains(name) }

extension KeepVaultSession {
    public func totpCode(for id: EntryID, at unixSeconds: Int, access: KeepAccess) throws(TotpError) -> TotpCode {
        if isClosed { throw TotpError(reason: .sessionClosed) }
        do {
            let dto = try handleForEditing.totpCode(entryId: id.rawValue, unixSeconds: UInt64(max(0, unixSeconds)), access: access.dto)
            return TotpCode(code: SecretValue(utf8: dto.code.utf8), period: Int(dto.period), secondsRemaining: Int(dto.secondsRemaining))
        } catch let error as VaultTotpError {
            throw TotpError(reason: error.reason)
        } catch {
            throw TotpError(reason: .sessionNotFound)
        }
    }
}

extension RustKeepCore {
    public func normalizeTotp(_ input: Data, access: KeepAccess) throws(TotpError) -> TotpSetup {
        do {
            let dto = try totpNormalize(input: input, access: access.dto)
            let algorithm: TotpAlgorithm = switch dto.algorithm {
            case .sha1: .sha1
            case .sha256: .sha256
            case .sha512: .sha512
            }
            return TotpSetup(
                uri: SecretValue(utf8: dto.uri.utf8),
                algorithm: algorithm,
                digits: Int(dto.digits),
                period: Int(dto.period),
                isSteam: dto.steam,
                issuer: dto.issuer,
                account: dto.account
            )
        } catch let error as VaultTotpError {
            throw TotpError(reason: error.reason)
        } catch {
            throw TotpError(reason: .malformed)
        }
    }
}

extension VaultTotpError {
    var reason: TotpError.Reason {
        switch self {
        case .SessionNotFound: .sessionNotFound
        case .SessionClosed: .sessionClosed
        case .EntryNotFound: .entryNotFound
        case .NotEntitled: .notEntitled
        case .NoCode: .noCode
        case .Empty: .empty
        case .TooLong: .tooLong
        case .UnsupportedType: .unsupportedType
        case .MissingSecret: .missingSecret
        case .InvalidSecret: .invalidSecret
        case .InvalidAlgorithm: .invalidAlgorithm
        case .InvalidDigits: .invalidDigits
        case .InvalidPeriod: .invalidPeriod
        case .Migration: .migration
        case .Malformed: .malformed
        case .InvalidText: .invalidText
        }
    }
}
