import XCTest

/// Two vaults and no Keep+: the app asks for the main vault once, edits it, and opens the other one
/// for reading. Needs `qa/history.kdbx` and `qa/second.kdbx` (copies of the editable `rich` vector,
/// password `correct horse battery staple`) in the app's Documents, and an account without Keep+.
@MainActor
final class MultiVaultTests: XCTestCase {
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

    private func unlock(_ app: XCUIApplication) {
        XCTAssertTrue(app.secureTextFields["password"].waitForExistence(timeout: 8))
        latinKeyboard(app)
        app.secureTextFields["password"].typeText("correct horse battery staple")
        app.buttons["unlock"].tap()
    }

    func testMainVaultIsChosenOnceAndTheOtherOpensForReading() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-VazieQAReset", "YES", "-VazieQAVaults", "qa/history.kdbx,qa/second.kdbx"]
        app.launch()

        let confirm = app.buttons["mainVaultConfirm"]
        guard confirm.waitForExistence(timeout: 10) else { throw XCTSkip("the vaults are not installed, or this account has Keep+") }
        XCTAssertFalse(confirm.isEnabled)
        shot("01 choose the main vault")
        app.buttons["mainVault.second"].tap()
        XCTAssertTrue(confirm.isEnabled)
        confirm.tap()

        let databases = app.buttons["databases"]
        XCTAssertTrue(databases.waitForExistence(timeout: 8))
        XCTAssertFalse(confirm.exists)
        databases.tap()
        XCTAssertTrue(app.descendants(matching: .any)["vaultsNeedPlus"].firstMatch.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["listChooseVault"].exists)
        shot("02 the list")
        app.buttons["vault.history"].tap()

        unlock(app)
        XCTAssertTrue(app.descendants(matching: .any)["readOnlyByPlan"].firstMatch.waitForExistence(timeout: 20))
        shot("03 a vault open for reading")
        for group in ["Web", "Work"] {
            let row = app.staticTexts[group]
            XCTAssertTrue(row.waitForExistence(timeout: 10))
            row.tap()
        }
        app.staticTexts["Bank"].tap()
        XCTAssertTrue(app.buttons["copy.password"].waitForExistence(timeout: 8))
        XCTAssertFalse(app.buttons["editEntry"].exists)
        XCTAssertFalse(app.buttons["deleteEntry"].exists)
        shot("04 an entry without editing")

        app.terminate()
        app.launchArguments = []
        app.launch()
        XCTAssertTrue(app.buttons["databases"].waitForExistence(timeout: 8))
        XCTAssertFalse(app.buttons["mainVaultConfirm"].exists, "the main vault is asked for once")
        app.buttons["databases"].tap()
        app.buttons["vault.second"].tap()
        unlock(app)
        let web = app.staticTexts["Web"]
        XCTAssertTrue(web.waitForExistence(timeout: 20))
        XCTAssertFalse(app.descendants(matching: .any)["readOnlyByPlan"].firstMatch.exists)
        shot("05 the main vault")
    }
}
