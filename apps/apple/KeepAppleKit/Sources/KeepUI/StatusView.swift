import KeepCore
import SwiftUI

public struct StatusView: View {
    private let connection: CoreConnection
    private let platformName: String

    public init(connection: CoreConnection, platformName: String) {
        self.connection = connection
        self.platformName = platformName
    }

    public var body: some View {
        VStack(spacing: 16) {
            Text("Vazie Keep")
                .font(.largeTitle.weight(.semibold))
            VStack(spacing: 6) {
                Text("Core: \(coreLabel)")
                Text("Platform: \(platformName)")
            }
            .font(.body.monospaced())
            .foregroundStyle(.secondary)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var coreLabel: String {
        switch connection {
        case let .connected(version): "connected (\(version))"
        case .unavailable: "unavailable"
        }
    }
}

#Preview {
    StatusView(connection: .connected(version: "0.1.0"), platformName: "Preview")
}
