import KeepCore
import KeepPlatform
import KeepUI
import SwiftUI

@main
struct VazieKeepApp: App {
    private let connection = CoreConnection.probe(RustKeepCore())

    var body: some Scene {
        WindowGroup("Vazie Keep") {
            StatusView(connection: connection, platformName: PlatformInfo.current.name)
                .frame(minWidth: 420, minHeight: 280)
        }
        .windowResizability(.contentMinSize)
    }
}
