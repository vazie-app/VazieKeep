import Foundation

public struct PlatformInfo: Equatable, Sendable {
    public let name: String
    public let osVersion: String

    public static var current: PlatformInfo {
        #if os(iOS)
        let name = "iOS"
        #elseif os(macOS)
        let name = "macOS"
        #else
        let name = "Apple"
        #endif
        let version = ProcessInfo.processInfo.operatingSystemVersion
        return PlatformInfo(
            name: name,
            osVersion: "\(version.majorVersion).\(version.minorVersion).\(version.patchVersion)"
        )
    }
}
