import XCTest

/// An entry that holds a passkey says so, and removing the passkey is a step of its own that leaves the
/// entry. The passkey is made by the debug-only `-VazieQAPasskey`: only iOS itself can ask for a real one.
/// Needs `qa/history.kdbx` (the editable `rich` vector, password `correct horse battery staple`).
@MainActor
final class PasskeyTests: XCTestCase {
    override func setUp() async throws {
        continueAfterFailure = false
    }

    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testAnEntryShowsItsPasskeyAndRemovingItLeavesTheEntry() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-VazieQAReset", "YES", "-VazieQAVault", "qa/history.kdbx", "-VazieQAPasskey", "YES"]
        app.launch()
        guard app.secureTextFields["password"].waitForExistence(timeout: 8) else { throw XCTSkip("qa/history.kdbx is not installed") }
        app.secureTextFields["password"].tap()
        app.secureTextFields["password"].typeText("correct horse battery staple")
        app.buttons["unlock"].tap()

        let entry = app.staticTexts["Example Shop"]
        XCTAssertTrue(entry.waitForExistence(timeout: 25))
        entry.tap()
        let row = app.descendants(matching: .any)["passkeyRow"].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["shop.example.com · dana"].exists)
        shot("01 the entry with its passkey")

        app.buttons["removePasskey"].tap()
        let confirm = app.buttons.matching(NSPredicate(format: "label IN %@", ["Remove Passkey", "Удалить ключ доступа"])).firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        shot("02 the question")
        confirm.tap()
        XCTAssertTrue(app.buttons["removePasskey"].waitForNonExistence(timeout: 10))
        XCTAssertTrue(app.buttons["entryIcon"].exists, "the entry stays")
        shot("03 the entry without the passkey")
    }
}
