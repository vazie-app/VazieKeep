import XCTest

/// Adds a file from the app's own folder to an entry and saves it. Needs `qa/totp-qa.kdbx`
/// (password `qa-password`) and `qa/vazie-attach.txt` in the app's Documents, and a build that
/// can add attachments (Keep+); otherwise it skips.
@MainActor
final class AttachmentsTests: XCTestCase {
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

    func testAddAttachmentFromFiles() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-VazieQAReset", "YES", "-VazieQAVault", "qa/totp-qa.kdbx"]
        app.launch()
        guard app.secureTextFields["password"].waitForExistence(timeout: 8) else { throw XCTSkip("qa/totp-qa.kdbx is not installed") }
        latinKeyboard(app)
        app.secureTextFields["password"].typeText("qa-password")
        app.buttons["unlock"].tap()

        let entry = app.staticTexts["No code here"]
        XCTAssertTrue(entry.waitForExistence(timeout: 20))
        entry.tap()
        app.buttons["editEntry"].tap()
        let add = app.buttons["addAttachment"]
        guard add.waitForExistence(timeout: 5) else { throw XCTSkip("this build cannot add attachments") }
        app.swipeUp()
        add.tap()
        sleep(2)
        shot("01 picker")
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("vazie-attach")
        sleep(3)
        shot("02 search")
        let target = app.cells.matching(NSPredicate(format: "label BEGINSWITH 'vazie-attach'")).firstMatch
        XCTAssertTrue(target.waitForExistence(timeout: 5))
        target.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)).tap()
        sleep(3)
        shot("02b after pick")
        let pending = app.staticTexts["vazie-attach.txt"]
        XCTAssertTrue(pending.waitForExistence(timeout: 5))
        shot("03 editor")
        app.buttons["editSave"].tap()
        let export = app.buttons["exportAttachment.vazie-attach.txt"]
        XCTAssertTrue(export.waitForExistence(timeout: 8))
        export.firstMatch.swipeUp()
        shot("04 entry")
        export.tap()
        sleep(2)
        shot("05 exporter")
        let cancel = app.buttons.matching(NSPredicate(format: "label IN %@", ["Cancel", "Отменить", "Back", "Назад"])).firstMatch
        if cancel.exists { cancel.tap() }
    }
}
