import KeepCore
import KeepPlatform
import KeepUI
import SwiftUI

@main
struct VazieKeepApp: App {
    private let connection = CoreConnection.probe(RustKeepCore())

    var body: some Scene {
        WindowGroup {
            StatusView(connection: connection, platformName: PlatformInfo.current.name)
        }
    }
}
