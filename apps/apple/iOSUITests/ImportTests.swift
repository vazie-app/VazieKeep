import XCTest

/// A made-up Bitwarden export goes into a vault. Needs `qa/history.kdbx` (the editable `rich` vector,
/// password `correct horse battery staple`) and `qa/export.json` in the app's Documents.
@MainActor
final class ImportTests: XCTestCase {
    override func setUp() async throws {
        continueAfterFailure = false
    }

    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func latinKeyboard(_ app: XCUIApplication) {
        app.secureTextFields["password"].tap()
        guard app.keyboards.firstMatch.waitForExistence(timeout: 3), !app.keys["a"].exists else { return }
        app.buttons["togglePassword"].tap()
        let globe = app.buttons["Next keyboard"]
        for _ in 0..<4 where !app.keys["a"].exists {
            guard globe.waitForExistence(timeout: 2) else { break }
            globe.tap()
        }
        app.buttons["togglePassword"].tap()
        let field = app.secureTextFields["password"]
        _ = field.waitForExistence(timeout: 2)
        if !app.keyboards.firstMatch.waitForExistence(timeout: 2) { field.tap() }
    }

    func testAnExportIsShownAndImported() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-VazieQAReset", "YES", "-VazieQAVault", "qa/history.kdbx", "-VazieQAImport", "qa/export.json"]
        app.launch()
        guard app.secureTextFields["password"].waitForExistence(timeout: 8) else { throw XCTSkip("qa/history.kdbx is not installed") }
        latinKeyboard(app)
        app.secureTextFields["password"].typeText("correct horse battery staple")
        app.buttons["unlock"].tap()

        let confirm = app.buttons["importConfirm"]
        guard confirm.waitForExistence(timeout: 25) else { throw XCTSkip("qa/export.json is not installed") }
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'Bitwarden'")).firstMatch.exists)
        shot("01 what the export holds")
        confirm.tap()

        let done = app.buttons["importDone"]
        XCTAssertTrue(done.waitForExistence(timeout: 20))
        XCTAssertFalse(app.buttons["importConfirm"].exists)
        shot("02 imported")
        done.tap()

        let folder = app.staticTexts["Imported"]
        XCTAssertTrue(folder.waitForExistence(timeout: 10))
        shot("03 the vault with the imported group")
        folder.tap()
        app.staticTexts["Servers"].tap()
        XCTAssertTrue(app.staticTexts["Import Server"].waitForExistence(timeout: 8))
    }
}
