import XCTest

/// Stars an entry, gives it tags and an expiry date, and checks the vault filters on the iPhone.
/// Needs `qa/totp-qa.kdbx` (password `qa-password`) in the app's Documents; skips without it.
@MainActor
final class FavoritesTagsExpiryTests: XCTestCase {
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

    func testFavouriteTagsAndExpiry() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-VazieQAReset", "YES", "-VazieQAVault", "qa/totp-qa.kdbx"]
        app.launch()
        let field = app.secureTextFields["password"]
        guard field.waitForExistence(timeout: 8) else { throw XCTSkip("qa/totp-qa.kdbx is not installed") }
        latinKeyboard(app)
        app.secureTextFields["password"].typeText("qa-password")
        app.buttons["unlock"].tap()

        let steam = app.staticTexts["Steam"]
        XCTAssertTrue(steam.waitForExistence(timeout: 20))
        steam.tap()
        let star = app.buttons["favoriteEntry"]
        XCTAssertTrue(star.waitForExistence(timeout: 5))
        if star.label == "Add to Favorites" || star.label == "Добавить в избранное" { star.tap() }
        sleep(2)
        shot("01 entry starred")

        app.buttons["editEntry"].tap()
        let tags = app.textFields["editTags"]
        XCTAssertTrue(tags.waitForExistence(timeout: 5))
        let removable = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Remove tag' OR label BEGINSWITH 'Удалить тег'"))
        while removable.count > 0 { removable.element(boundBy: 0).tap() }
        tags.tap()
        tags.typeText("games,")
        tags.typeText("valve\n")
        app.buttons["editExpiry"].tap()
        sleep(1)
        shot("02 expiry choices")
        let pick = app.buttons.matching(NSPredicate(format: "label IN %@", ["Choose a Date…", "Выбрать дату…"])).firstMatch
        XCTAssertTrue(pick.waitForExistence(timeout: 3))
        pick.tap()
        let previous = app.buttons.matching(NSPredicate(format: "label IN %@", ["Previous Month", "Предыдущий месяц"])).firstMatch
        XCTAssertTrue(previous.waitForExistence(timeout: 5))
        previous.tap()
        previous.tap()
        sleep(1)
        let days = app.datePickers.firstMatch.buttons.matching(NSPredicate(format: "label CONTAINS '1'"))
        if days.count > 0 { days.element(boundBy: 0).tap() }
        shot("03 date picker")
        app.buttons["expiryDone"].tap()
        sleep(1)
        shot("04 editor")
        let chips = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Remove tag' OR label BEGINSWITH 'Удалить тег'"))
        XCTAssertEqual(chips.count, 2, "games and valve, nothing in between")
        app.buttons["editSave"].tap()
        sleep(3)
        shot("05 entry saved")

        app.buttons["back"].firstMatch.exists ? app.buttons["back"].firstMatch.tap() : app.navigationBars.buttons.element(boundBy: 0).tap()
        let favorites = app.buttons["filter.favorites"]
        XCTAssertTrue(favorites.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["filter.expired"].exists)
        shot("06 vault filters")
        favorites.tap()
        sleep(1)
        shot("07 favourites")
        XCTAssertTrue(app.staticTexts["Steam"].exists)
        XCTAssertFalse(app.staticTexts["Bank (SHA-256, 8, 60 s)"].exists)
        app.buttons["filter.all"].tap()
    }
}
