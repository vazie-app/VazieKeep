import Foundation

public enum SecretValueError: Error, Equatable, Sendable {
    case wiped
    case invalidUTF8
}

public final class SecretValue: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    private var bytes: Data?

    public init(utf8: Data) {
        bytes = utf8
    }

    deinit {
        wipe()
    }

    public var isWiped: Bool { bytes == nil }

    public func withBytes<R>(_ body: (Data) throws -> R) throws -> R {
        guard let bytes else { throw SecretValueError.wiped }
        return try body(bytes)
    }

    public func withString<R>(_ body: (String) throws -> R) throws -> R {
        guard let bytes else { throw SecretValueError.wiped }
        guard let text = String(data: bytes, encoding: .utf8) else { throw SecretValueError.invalidUTF8 }
        return try body(text)
    }

    public func wipe() {
        guard var data = bytes else { return }
        bytes = nil
        data.resetBytes(in: 0..<data.count)
    }

    public var description: String { "SecretValue(<redacted>)" }
    public var debugDescription: String { description }
    public var customMirror: Mirror { Mirror(reflecting: description) }
}
