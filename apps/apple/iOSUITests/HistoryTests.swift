import XCTest

/// Entry history on a vault whose "Bank" entry has two earlier versions. Needs `qa/history.kdbx`
/// (a copy of the editable `rich` vector, password `correct horse battery staple`) in the app's
/// Documents. Without Keep+ the row opens the Keep+ page; with it the test reads a version and
/// goes back to it.
@MainActor
final class HistoryTests: XCTestCase {
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

    func testHistoryOfAnEntry() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-VazieQAReset", "YES", "-VazieQAVault", "qa/history.kdbx"]
        app.launch()
        guard app.secureTextFields["password"].waitForExistence(timeout: 8) else { throw XCTSkip("qa/history.kdbx is not installed") }
        latinKeyboard(app)
        app.secureTextFields["password"].typeText("correct horse battery staple")
        app.buttons["unlock"].tap()

        for group in ["Web", "Work"] {
            let row = app.staticTexts[group]
            XCTAssertTrue(row.waitForExistence(timeout: 20))
            row.tap()
        }
        let bank = app.staticTexts["Bank"]
        XCTAssertTrue(bank.waitForExistence(timeout: 10))
        bank.tap()

        let row = app.descendants(matching: .any)["entryHistory"].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 8))
        app.swipeUp()
        shot("01 entry with history row")
        row.tap()

        let first = app.descendants(matching: .any)["historyVersion.0"].firstMatch
        guard first.waitForExistence(timeout: 5) else {
            shot("02 Keep+ page for a free account")
            XCTAssertFalse(app.descendants(matching: .any)["historyVersion.0"].exists)
            return
        }
        XCTAssertTrue(app.descendants(matching: .any)["historyVersion.1"].firstMatch.exists)
        XCTAssertFalse(app.descendants(matching: .any)["historyVersion.2"].firstMatch.exists)
        shot("02 versions")
        app.descendants(matching: .any)["historyVersion.1"].firstMatch.tap()

        let reveal = app.buttons["history.reveal.password"]
        XCTAssertTrue(reveal.waitForExistence(timeout: 5))
        shot("03 version, hidden")
        reveal.tap()
        XCTAssertEqual(app.descendants(matching: .any)["history.secret.password"].firstMatch.value as? String, "Shown on screen")
        shot("04 version, shown")
        reveal.tap()

        let restore = app.buttons["restoreVersion"]
        if !restore.isHittable { app.swipeUp() }
        restore.tap()
        shot("05 confirm")
        app.buttons.matching(NSPredicate(format: "label IN %@", ["Restore", "Вернуть"])).firstMatch.tap()

        XCTAssertTrue(app.descendants(matching: .any)["historyVersion.2"].firstMatch.waitForExistence(timeout: 10))
        shot("06 versions after restore")
    }
}
