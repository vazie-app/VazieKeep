import XCTest

/// Walks every redesigned screen on the iPhone in both themes and attaches a screenshot of each,
/// for comparison with `design/`. Needs `qa/rich.kdbx` in the app's Documents (see
/// PLATFORM_INTEGRATION.md); without it the tests skip.
@MainActor
final class DesignTourTests: XCTestCase {
    private let password = "correct horse battery staple"

    override func setUp() async throws {
        continueAfterFailure = false
    }

    func testLightTour() throws { try tour("light") }
    func testDarkTour() throws { try tour("dark") }

    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func launch(_ theme: String, vault: Bool) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-VazieQAReset", "YES", "-VazieQATheme", theme] + (vault ? ["-VazieQAVault", "qa/rich.kdbx"] : [])
        app.launch()
        return app
    }

    private func tour(_ theme: String) throws {
        let welcome = launch(theme, vault: false)
        XCTAssertTrue(welcome.buttons["chooseVault"].waitForExistence(timeout: 8))
        shot("\(theme)-01 welcome")
        welcome.terminate()

        let app = launch(theme, vault: true)
        let field = app.secureTextFields["password"]
        guard field.waitForExistence(timeout: 8) else { throw XCTSkip("qa/rich.kdbx is not installed") }
        sleep(1)
        shot("\(theme)-02 unlock")
        field.tap()
        field.typeText("wrong password")
        shot("\(theme)-02b unlock typed")
        XCTContext.runActivity(named: "secure field value: \(String(describing: field.value))") { _ in }
        app.buttons["togglePassword"].tap()
        sleep(1)
        shot("\(theme)-02c unlock typed visible")
        app.buttons["togglePassword"].tap()
        sleep(1)
        app.buttons["unlock"].tap()
        sleep(3)
        shot("\(theme)-03 unlock wrong password")
        let again = app.secureTextFields["password"]
        again.tap()
        again.typeText(XCUIKeyboardKey.delete.rawValue.isEmpty ? "" : String(repeating: XCUIKeyboardKey.delete.rawValue, count: 20))
        again.typeText(password)
        app.buttons["unlock"].tap()
        XCTAssertTrue(app.staticTexts["Web"].waitForExistence(timeout: 20))
        sleep(1)
        shot("\(theme)-04 vault")

        app.staticTexts["Web"].tap()
        sleep(1)
        shot("\(theme)-05 group")
        let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Example Mail'")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()
        sleep(1)
        shot("\(theme)-06 entry")
        app.buttons["reveal.password"].tap()
        sleep(1)
        shot("\(theme)-07 entry revealed")
        app.buttons["editEntry"].tap()
        sleep(1)
        shot("\(theme)-08 editor")
        app.buttons["generatePassword"].tap()
        sleep(1)
        shot("\(theme)-09 generator sheet")
        app.buttons["generatorCancel"].tap()
        sleep(1)
        app.buttons["editorCancel"].tap()
        sleep(1)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        sleep(1)
        app.buttons["back"].firstMatch.tap()
        sleep(1)
        app.buttons["dock.generator"].tap()
        sleep(1)
        shot("\(theme)-10 generator tab")
        app.buttons["dock.keep"].tap()
        sleep(1)
        app.buttons["settings"].firstMatch.tap()
        sleep(1)
        shot("\(theme)-11 settings")
        app.swipeUp()
        sleep(1)
        shot("\(theme)-12 settings more")
    }
}
