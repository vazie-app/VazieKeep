import XCTest

/// A group and an entry get an icon from the picker, found by typing its name. Needs `qa/history.kdbx`
/// (the editable `rich` vector, password `correct horse battery staple`) in the app's Documents.
@MainActor
final class IconTests: XCTestCase {
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

    private func pick(_ app: XCUIApplication, typing query: String, icon: Int) {
        let search = app.textFields["iconSearch"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText(query)
        let target = app.buttons["icon.\(icon)"]
        XCTAssertTrue(target.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["icon.48"].exists && icon != 48, "the search narrows the grid")
        target.tap()
    }

    func testSiteIconsAreFetchedAfterAYesAndShownOnTheEntries() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-VazieQAReset", "YES", "-VazieQAVault", "qa/history.kdbx", "-VazieQASiteIcons", "YES"]
        app.launch()
        guard app.secureTextFields["password"].waitForExistence(timeout: 8) else { throw XCTSkip("qa/history.kdbx is not installed") }
        latinKeyboard(app)
        app.secureTextFields["password"].typeText("correct horse battery staple")
        app.buttons["unlock"].tap()

        let add = app.buttons.matching(NSPredicate(format: "label IN %@", ["Add", "Добавить"])).firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 20))
        add.tap()
        let pass = app.buttons["assignIcons"].firstMatch
        XCTAssertTrue(pass.waitForExistence(timeout: 5))
        pass.tap()
        let yes = app.alerts.buttons["getIcons"].firstMatch
        XCTAssertTrue(yes.waitForExistence(timeout: 5), "nothing is fetched before the person agrees")
        shot("01 the question before anything is sent")
        yes.tap()
        let done = app.alerts.buttons["OK"].firstMatch
        XCTAssertTrue(done.waitForExistence(timeout: 15))
        shot("02 how many entries got an icon")
        done.tap()

        for group in ["Web", "Work"] {
            let row = app.staticTexts[group]
            XCTAssertTrue(row.waitForExistence(timeout: 10))
            row.tap()
        }
        XCTAssertTrue(app.staticTexts["Bank"].waitForExistence(timeout: 10))
        shot("03 entries with the icons of their sites")
        app.staticTexts["Bank"].tap()
        let glyph = app.buttons["entryIcon"]
        XCTAssertTrue(glyph.waitForExistence(timeout: 8))
        shot("04 the entry with its site icon")
        glyph.tap()
        XCTAssertTrue(app.buttons["icon.37"].waitForExistence(timeout: 5))
    }

    func testAGroupAndAnEntryGetAnIconFoundByName() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-VazieQAReset", "YES", "-VazieQAVault", "qa/history.kdbx"]
        app.launch()
        guard app.secureTextFields["password"].waitForExistence(timeout: 8) else { throw XCTSkip("qa/history.kdbx is not installed") }
        latinKeyboard(app)
        app.secureTextFields["password"].typeText("correct horse battery staple")
        app.buttons["unlock"].tap()

        let web = app.staticTexts["Web"]
        XCTAssertTrue(web.waitForExistence(timeout: 20))
        web.press(forDuration: 1.2)
        let change = app.buttons.matching(NSPredicate(format: "label IN %@", ["Change Icon", "Сменить иконку"])).firstMatch
        XCTAssertTrue(change.waitForExistence(timeout: 5))
        change.tap()
        shot("01 the picker")
        pick(app, typing: "mail", icon: 19)
        XCTAssertTrue(web.waitForExistence(timeout: 8))
        shot("02 the group with its icon")

        web.press(forDuration: 1.2)
        change.tap()
        XCTAssertTrue(app.buttons["icon.19"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["icon.19"].isSelected)
        app.buttons.matching(NSPredicate(format: "label IN %@", ["Cancel", "Отмена"])).firstMatch.tap()

        web.tap()
        let mail = app.staticTexts["Example Mail"]
        XCTAssertTrue(mail.waitForExistence(timeout: 8))
        mail.tap()
        let glyph = app.buttons["entryIcon"]
        XCTAssertTrue(glyph.waitForExistence(timeout: 8))
        glyph.tap()
        pick(app, typing: "bank", icon: 37)
        XCTAssertTrue(glyph.waitForExistence(timeout: 8))
        shot("03 the entry with its icon")
        glyph.tap()
        XCTAssertTrue(app.buttons["icon.37"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["icon.37"].isSelected)
    }
}
