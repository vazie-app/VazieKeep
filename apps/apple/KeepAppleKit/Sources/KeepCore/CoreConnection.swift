public enum CoreConnection: Equatable, Sendable {
    case connected(version: String)
    case unavailable

    public static func probe(_ core: some KeepCoreClient) -> CoreConnection {
        let version = core.info().version
        return version.isEmpty ? .unavailable : .connected(version: version)
    }
}
