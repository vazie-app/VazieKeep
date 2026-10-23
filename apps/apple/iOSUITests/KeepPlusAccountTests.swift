import XCTest

/// Keep+ against a development backend, on a physical iPhone: sign in, wait for an operator to grant
/// Keep+ (the QA script does it from the Mac), refresh, see Keep+, sign out.
///
/// Needs `TEST_RUNNER_KEEP_QA_ACCOUNT_ORIGIN` and `TEST_RUNNER_KEEP_QA_KEEP_ORIGIN` in the environment of
/// `xcodebuild test`; without them the test skips.
@MainActor
final class KeepPlusAccountTests: XCTestCase {
    override func setUp() async throws {
        continueAfterFailure = false
    }

    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testSignInGrantRefreshAndSignOut() throws {
        let env = ProcessInfo.processInfo.environment
        guard let account = env["KEEP_QA_ACCOUNT_ORIGIN"], let keep = env["KEEP_QA_KEEP_ORIGIN"] else {
            throw XCTSkip("no development backend configured")
        }
        let app = XCUIApplication()
        app.launchArguments = ["-VazieQAReset", "YES", "-VazieAccountOrigin", account, "-VazieKeepOrigin", keep]
        app.launch()

        XCTAssertTrue(app.buttons["settings"].waitForExistence(timeout: 10))
        app.buttons["settings"].tap()
        let row = app.buttons["accountRow"]
        XCTAssertTrue(row.waitForExistence(timeout: 8))
        shot("01 settings with the account row")
        row.tap()

        let dev = app.buttons["accountDevSignIn"]
        XCTAssertTrue(dev.waitForExistence(timeout: 8))
        shot("02 signed out")
        dev.tap()

        let refresh = app.buttons["accountRefresh"]
        XCTAssertTrue(refresh.waitForExistence(timeout: 30), "signing in reaches the signed-in screen")
        XCTAssertTrue(app.staticTexts["Keep Free"].waitForExistence(timeout: 5))
        shot("03 signed in, Free")

        // The QA script grants Keep+ to the newest account while this loop waits.
        let plus = app.staticTexts["Keep+"]
        var granted = false
        for _ in 0..<40 where !granted {
            if refresh.isEnabled { refresh.tap() }
            granted = plus.waitForExistence(timeout: 3)
        }
        XCTAssertTrue(granted, "Keep+ shows after the backend grants it")
        shot("04 Keep+ active")

        // The lease survives a restart with no network use at all.
        app.terminate()
        app.launchArguments = ["-VazieAccountOrigin", "http://127.0.0.1:9", "-VazieKeepOrigin", "http://127.0.0.1:9"]
        app.launch()
        app.buttons["settings"].tap()
        app.buttons["accountRow"].tap()
        XCTAssertTrue(app.staticTexts["Keep+"].waitForExistence(timeout: 8), "an unreachable backend does not switch Keep+ off")
        app.buttons["accountRefresh"].tap()
        sleep(3)
        XCTAssertTrue(app.staticTexts["Keep+"].exists)
        shot("05 offline after restart")

        app.buttons["accountSignOut"].tap()
        app.buttons["Sign out"].firstMatch.tap()
        XCTAssertTrue(app.buttons["accountDevSignIn"].waitForExistence(timeout: 8), "signing out returns to the signed-out screen")
        shot("06 signed out again")
    }

    /// The Keep+ screen with checkout open: signed out it asks to sign in first; after the website it says the
    /// payment is not seen yet. Needs `TEST_RUNNER_KEEP_QA_PLUS_ORIGIN`: one development backend for the account,
    /// the prices and the site, whose entitlement call fails. Reaches the screen from the vault list, so the phone
    /// needs one vault.
    func testCheckoutAsksToSignInAndSaysPaymentNotSeen() throws {
        guard let origin = ProcessInfo.processInfo.environment["KEEP_QA_PLUS_ORIGIN"] else {
            throw XCTSkip("no development backend configured")
        }
        addUIInterruptionMonitor(withDescription: "local network") { alert in
            let allow = alert.buttons.matching(NSPredicate(format: "label IN {'Allow', 'Разрешить', 'OK'}")).firstMatch
            guard allow.exists else { return false }
            allow.tap()
            return true
        }
        let app = XCUIApplication()
        app.launchArguments = ["-VazieAccountOrigin", origin, "-VazieKeepOrigin", origin, "-VazieSiteOrigin", origin]
        app.launch()
        signOutIfSignedIn(app)

        if app.buttons["databases"].waitForExistence(timeout: 5) { app.buttons["databases"].tap() }
        let about = app.buttons.matching(NSPredicate(format: "identifier == 'vaultsAboutPlus' OR label IN {'About Keep+', 'О Keep+'}")).firstMatch
        guard about.waitForExistence(timeout: 8) else {
            shot("00 no Keep+ in the vault list")
            print(app.debugDescription)
            throw XCTSkip("the vault list offers no Keep+ here")
        }
        about.tap()

        let month = app.buttons.matching(NSPredicate(format: "label CONTAINS '149'")).firstMatch
        XCTAssertTrue(month.waitForExistence(timeout: 15), "prices load")
        month.tap()
        XCTAssertTrue(app.buttons["plusSignIn"].waitForExistence(timeout: 5), "signed out, the screen asks to sign in")
        XCTAssertFalse(app.buttons["plusContinue"].isEnabled)
        shot("01 signed out: sign in first")

        app.buttons["plusSignIn"].tap()
        XCTAssertTrue(app.buttons["accountDevSignIn"].waitForExistence(timeout: 8))
        app.buttons["accountDevSignIn"].tap()
        XCTAssertTrue(app.buttons["accountRefresh"].waitForExistence(timeout: 15))
        back(app)
        XCTAssertTrue(app.buttons["plusContinue"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["plusSignIn"].exists)
        if !app.buttons["plusContinue"].isEnabled { month.tap() }
        XCTAssertTrue(app.buttons["plusContinue"].isEnabled)
        shot("02 signed in: continue")

        app.buttons["plusContinue"].tap()
        sleep(4)
        app.activate()
        let check = app.buttons["plusCheck"]
        XCTAssertTrue(check.waitForExistence(timeout: 10))
        shot("03 back from the site: checking")
        let done = NSPredicate(format: "isEnabled == true")
        expectation(for: done, evaluatedWith: check)
        waitForExpectations(timeout: 60)
        shot("04 payment not seen yet")

        app.terminate()
        app.launch()
        signOutIfSignedIn(app)
    }

    private func signOutIfSignedIn(_ app: XCUIApplication) {
        guard app.buttons["settings"].waitForExistence(timeout: 10) else { return }
        app.buttons["settings"].tap()
        guard app.buttons["accountRow"].waitForExistence(timeout: 8) else { return }
        app.buttons["accountRow"].tap()
        if app.buttons["accountSignOut"].waitForExistence(timeout: 5) {
            app.buttons["accountSignOut"].tap()
            app.buttons.matching(NSPredicate(format: "label IN {'Sign out', 'Выйти'}")).element(boundBy: 1).tap()
            XCTAssertTrue(app.buttons["accountSendCode"].waitForExistence(timeout: 8))
        }
        for _ in 0..<3 where !app.buttons["settingsDone"].waitForExistence(timeout: 2) {
            back(app)
        }
        if app.buttons["settingsDone"].exists { app.buttons["settingsDone"].tap() }
    }

    private func back(_ app: XCUIApplication) {
        let labelled = app.buttons.matching(NSPredicate(format: "identifier == 'back' OR label IN {'Back', 'Назад'}")).firstMatch
        if labelled.exists && labelled.isHittable { labelled.tap() } else { app.coordinate(withNormalizedOffset: CGVector(dx: 0.095, dy: 0.114)).tap() }
    }
}
