import XCTest

/// Runs on a physical iPhone. Vault flows use synthetic vectors that QA copies into the app's
/// Documents/qa folder (see docs/architecture/PLATFORM_INTEGRATION.md); without them those tests skip.
@MainActor
final class VazieKeepUITests: XCTestCase {
    private let password = "correct horse battery staple"

    override func setUp() async throws {
        continueAfterFailure = false
    }

    private func launch(_ arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-VazieQAReset", "YES"] + arguments
        app.launch()
        return app
    }

    private func unlockScreen(_ app: XCUIApplication) throws -> XCUIElement {
        let field = app.secureTextFields["password"]
        guard field.waitForExistence(timeout: 5) else { throw XCTSkip("QA vectors are not installed in Documents/qa") }
        latinKeyboard(app)
        return field
    }

    /// The tests type Latin text, but the iPhone keeps the last keyboard layout, and a secure
    /// field has no globe key. Switch through the plain field when needed.
    private func latinKeyboard(_ app: XCUIApplication) {
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

    private func open(_ label: String, in app: XCUIApplication) {
        let button = app.buttons[label]
        XCTAssertTrue(button.waitForExistence(timeout: 15), "\(label) appears")
        for _ in 0..<3 where !button.isHittable { app.swipeUp() }
        let hittable = NSPredicate(format: "isHittable == true")
        wait(for: [XCTNSPredicateExpectation(predicate: hittable, object: button)], timeout: 5)
        button.tap()
    }

    /// Pops pushed screens until the root list shows. The vault browser draws its own back button;
    /// the entry screen uses the system navigation bar.
    private func popToRoot(_ app: XCUIApplication) {
        for _ in 0..<4 {
            let own = app.buttons["back"].firstMatch
            if own.exists, own.isHittable {
                own.tap()
            } else if app.navigationBars.buttons.element(boundBy: 0).exists, app.navigationBars.buttons.element(boundBy: 0).isHittable {
                app.navigationBars.buttons.element(boundBy: 0).tap()
            } else {
                return
            }
            sleep(1)
        }
    }

    private func screenshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testFreshLaunchShowsTheChooser() {
        let app = launch()
        XCTAssertTrue(app.buttons["chooseVault"].waitForExistence(timeout: 5))
        screenshot("chooser")
    }

    /// Runs without the QA reset, so the phone's list of vaults is left as it is.
    func testAboutShowsTheLinksAndSettingsTheFeedbackRow() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.buttons["settings"].waitForExistence(timeout: 10))
        app.buttons["settings"].tap()
        let feedback = app.buttons["feedbackRow"]
        for _ in 0..<6 where !feedback.isHittable { app.swipeUp() }
        XCTAssertTrue(feedback.exists, "the feedback row comes from the links")
        app.buttons["aboutRow"].tap()
        for key in ["developer", "folio", "channel", "support_email", "source", "behance"] {
            let link = app.buttons["link.\(key)"]
            for _ in 0..<4 where !link.exists { app.swipeUp() }
            XCTAssertTrue(link.exists, "link.\(key)")
        }
        screenshot("about links")
    }

    func testSettingsOpenAndClose() {
        let app = launch()
        XCTAssertTrue(app.buttons["chooseVault"].waitForExistence(timeout: 5))
        app.buttons["settings"].tap()
        XCTAssertTrue(app.buttons["settingsDone"].waitForExistence(timeout: 5))
        screenshot("settings")
        app.buttons["settingsDone"].tap()
        XCTAssertTrue(app.buttons["chooseVault"].waitForExistence(timeout: 3))
    }

    func testWrongPasswordKeepsFocusThenUnlockBrowseRevealLock() throws {
        let app = launch(["-VazieQAVault", "qa/aes256-argon2d.kdbx"])
        let field = try unlockScreen(app)
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 3), "field is focused on open")

        field.typeText("wrong\n")
        XCTAssertTrue(app.descendants(matching: .any)["unlockError"].waitForExistence(timeout: 15))
        let value = field.value as? String ?? ""
        XCTAssertTrue(value.isEmpty || value == field.placeholderValue, "password cleared")
        XCTAssertTrue(app.keyboards.firstMatch.exists, "keyboard stays up")
        screenshot("wrong password")

        // Typed into the app, not the field: this only works while the field still has focus.
        app.typeText(password + "\n")
        XCTAssertTrue(app.staticTexts["Web"].waitForExistence(timeout: 15))
        screenshot("vault root")
        open("Web", in: app)
        open("Example Mail, alice@example.org · https://mail.example.org/login", in: app)

        let secret = app.descendants(matching: .any)["secret.password"]
        XCTAssertTrue(secret.waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["hunter2-synthetic"].exists)
        app.buttons["reveal.password"].tap()
        sleep(1)
        let tree = app.debugDescription
        let dump = XCTAttachment(string: tree)
        dump.name = "accessibility tree after reveal"
        dump.lifetime = .keepAlways
        add(dump)
        XCTAssertFalse(tree.contains("hunter2-synthetic"), "a revealed secret never reaches accessibility")
        XCTAssertFalse(tree.contains(password), "the master password never reaches accessibility")
        screenshot("revealed")

        app.buttons["lock"].firstMatch.tap()
        XCTAssertTrue(app.secureTextFields["password"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.navigationBars.buttons.element(boundBy: 0).label == "Web")
    }

    func testBackgroundShortStaysOpenLongLocks() throws {
        let app = launch(["-VazieQAVault", "qa/aes256-argon2d.kdbx"])
        try unlockScreen(app).typeText(password + "\n")
        XCTAssertTrue(app.staticTexts["Web"].waitForExistence(timeout: 15))

        XCUIDevice.shared.press(.home)
        sleep(10)
        app.activate()
        XCTAssertTrue(app.staticTexts["Web"].waitForExistence(timeout: 5), "10 s in the background keeps the vault open")

        XCUIDevice.shared.press(.home)
        sleep(35)
        app.activate()
        XCTAssertTrue(app.secureTextFields["password"].waitForExistence(timeout: 5), "35 s in the background locks")
        XCTAssertFalse(app.staticTexts["Web"].exists)
    }

    func testRelaunchAfterTerminateIsLocked() throws {
        let app = launch(["-VazieQAVault", "qa/aes256-argon2d.kdbx"])
        try unlockScreen(app).typeText(password + "\n")
        XCTAssertTrue(app.staticTexts["Web"].waitForExistence(timeout: 15))
        app.terminate()
        let again = XCUIApplication()
        again.launch()
        XCTAssertTrue(again.secureTextFields["password"].waitForExistence(timeout: 5), "a new process starts locked")
        XCTAssertFalse(again.staticTexts["Web"].exists)
    }

    func testKeyFiles() throws {
        let app = launch(["-VazieQAVault", "qa/vault-password-plus-keyfile.kdbx", "-VazieQAKeyFile", "qa/keyfile-wrong.keyx"])
        let field = try unlockScreen(app)
        field.typeText(password + "\n")
        XCTAssertTrue(app.descendants(matching: .any)["unlockError"].waitForExistence(timeout: 15))
        app.terminate()

        let right = launch(["-VazieQAVault", "qa/vault-password-plus-keyfile.kdbx", "-VazieQAKeyFile", "qa/keyfile-v2.keyx"])
        try unlockScreen(right).typeText(password + "\n")
        XCTAssertTrue(right.staticTexts["Key File Entry"].waitForExistence(timeout: 15))
        screenshot("key file vault")
    }

    func testVaultThatDisappearedAsksToChooseAgain() throws {
        let app = launch(["-VazieQAVault", "qa/disappearing.kdbx"])
        try unlockScreen(app).typeText(password + "\n")
        XCTAssertTrue(app.buttons["lock"].firstMatch.waitForExistence(timeout: 15))
        app.buttons["lock"].firstMatch.tap()
        app.terminate()

        let again = XCUIApplication()
        again.launchArguments = ["-VazieQARemove", "qa/disappearing.kdbx"]
        again.launch()
        let field = again.secureTextFields["password"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        latinKeyboard(again)
        field.typeText(password + "\n")
        let error = again.descendants(matching: .any)["unlockError"]
        XCTAssertTrue(error.waitForExistence(timeout: 10))
        XCTAssertTrue(error.label.contains("Choose it again") || error.label.contains("заново"))
        screenshot("vault disappeared")
    }

    func testLaunchPerformance() {
        measure(metrics: [XCTApplicationLaunchMetric()]) {
            XCUIApplication().launch()
        }
    }

    /// Timings land in the app's Library/Caches/perf.log (debug builds), pulled by QA afterwards.
    func testUnlockCyclesForTimings() throws {
        for vault in ["qa/aes256-argon2d.kdbx", "qa/realistic-argon2d-64m.kdbx"] {
            let app = launch(["-VazieQAVault", vault])
            for cycle in 0..<3 {
                try unlockScreen(app).typeText(password + "\n")
                XCTAssertTrue(app.buttons["lock"].firstMatch.waitForExistence(timeout: 20))
                if cycle < 2 {
                    app.buttons["lock"].firstMatch.tap()
                } else {
                    app.textFields["search"].tap()
                    app.textFields["search"].typeText("mail")
                    sleep(2)
                }
            }
            app.terminate()
        }
    }

    func testLargestTextAndDarkTheme() throws {
        let app = XCUIApplication()
        app.launchArguments = [
            "-VazieQAReset", "YES", "-VazieQAVault", "qa/aes256-argon2d.kdbx",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL",
            "-appearance.theme", "dark",
        ]
        app.launch()
        let field = try unlockScreen(app)
        screenshot("AX5 dark unlock")
        field.typeText("wrong\n")
        XCTAssertTrue(app.descendants(matching: .any)["unlockError"].waitForExistence(timeout: 15))
        screenshot("AX5 dark wrong password")
        app.typeText(password + "\n")
        XCTAssertTrue(app.staticTexts["Web"].waitForExistence(timeout: 15))
        screenshot("AX5 dark vault")
        open("Web", in: app)
        open("Example Mail, alice@example.org · https://mail.example.org/login", in: app)
        XCTAssertTrue(app.buttons["reveal.password"].waitForExistence(timeout: 5))
        app.buttons["reveal.password"].tap()
        screenshot("AX5 dark entry revealed")
        app.buttons["copy.password"].tap()
        screenshot("AX5 dark copied")
        app.buttons["lock"].firstMatch.tap()
        XCTAssertTrue(app.buttons["settings"].firstMatch.waitForExistence(timeout: 5))
        app.buttons["settings"].firstMatch.tap()
        if app.buttons["settingsDone"].waitForExistence(timeout: 5) { screenshot("AX5 dark settings") }
    }

    // MARK: Manual QA helpers (run only with TEST_RUNNER_VAZIE_QA_MANUAL=1): they use the vault the
    // tester picked through the real Files picker, so they never reset the app.

    private func manualOnly() throws {
        if ProcessInfo.processInfo.environment["VAZIE_QA_MANUAL"] == nil { throw XCTSkip("manual QA helper") }
    }

    func testManualUnlockCurrentReference() throws {
        try manualOnly()
        let app = XCUIApplication()
        app.launch()
        let field = app.secureTextFields["password"]
        XCTAssertTrue(field.waitForExistence(timeout: 5), "starts locked at the Unlock screen")
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5), "cold start focuses the password field")
        screenshot("current reference, locked")
        latinKeyboard(app)
        field.typeText(password + "\n")
        let opened = app.buttons["lock"].firstMatch.waitForExistence(timeout: 20)
        let error = app.descendants(matching: .any)["unlockError"]
        let outcome = XCTAttachment(string: opened ? "opened" : (error.exists ? "error: \(error.label)" : "nothing"))
        outcome.name = "outcome"
        outcome.lifetime = .keepAlways
        add(outcome)
        screenshot("current reference, after unlock")
        if opened { app.buttons["lock"].firstMatch.tap() }
    }

    func testManualRestoreAutoLockDefault() throws {
        try manualOnly()
        let app = XCUIApplication()
        app.launch()
        app.buttons["settings"].firstMatch.tap()
        XCTAssertTrue(app.buttons["settingsDone"].waitForExistence(timeout: 5))
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Auto-lock'")).firstMatch.tap()
        let option = app.descendants(matching: .any).matching(NSPredicate(format: "label == '30 seconds'")).firstMatch
        XCTAssertTrue(option.waitForExistence(timeout: 3))
        option.tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS '30 seconds'")).firstMatch.waitForExistence(timeout: 3))
        screenshot("auto-lock restored")
        app.buttons["settingsDone"].tap()
    }

    // MARK: Face ID (manual: the tester looks at the iPhone, looks away or taps when asked)

    private func note(_ text: String, _ name: String = "outcome") {
        let attachment = XCTAttachment(string: text)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func waitForValue(_ element: XCUIElement, _ value: String, timeout: TimeInterval) -> Bool {
        let predicate = NSPredicate(format: "value == %@", value)
        return XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: predicate, object: element)], timeout: timeout) == .completed
    }

    private func turnOnFaceID(_ app: XCUIApplication) {
        app.buttons["settings"].firstMatch.tap()
        let toggle = app.switches["faceIDToggle"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        screenshot("settings before")
        toggle.switches.firstMatch.exists ? toggle.switches.firstMatch.tap() : toggle.tap()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        if springboard.alerts.firstMatch.waitForExistence(timeout: 4) { screenshot("face id permission") }
        XCTAssertTrue(waitForValue(toggle, "1", timeout: 90), "Face ID unlock turned on")
        screenshot("settings after")
        app.buttons["settingsDone"].tap()
    }

    /// Password unlock, turn Face ID on in Settings (look at the phone), lock, Face ID unlock (look again).
    func testManualFaceIDEnableAndUnlock() throws {
        try manualOnly()
        let app = launch(["-VazieQAVault", "qa/aes256-argon2d.kdbx"])
        try unlockScreen(app).typeText(password + "\n")
        XCTAssertTrue(app.staticTexts["Web"].waitForExistence(timeout: 15))
        turnOnFaceID(app)
        app.buttons["lock"].firstMatch.tap()
        let faceID = app.buttons["faceIDUnlock"]
        XCTAssertTrue(faceID.waitForExistence(timeout: 5), "the Unlock screen offers Face ID")
        sleep(1)
        XCTAssertFalse(app.keyboards.firstMatch.exists, "the keyboard doesn't cover the Face ID button")
        screenshot("unlock screen with face id")
        faceID.tap()
        let opened = app.staticTexts["Web"].waitForExistence(timeout: 90)
        note(opened ? "opened" : "not opened: \(app.descendants(matching: .any)["faceIDMessage"].label)")
        screenshot("after face id")
        XCTAssertTrue(opened)
        app.buttons["lock"].firstMatch.tap()
    }

    /// Face ID, then the tester makes it fail (looks away) and taps Cancel or Use Master Password.
    func testManualFaceIDCancelThenPassword() throws {
        try manualOnly()
        let app = XCUIApplication()
        app.launch()
        let faceID = app.buttons["faceIDUnlock"]
        XCTAssertTrue(faceID.waitForExistence(timeout: 5))
        // Time for the tester to cover the front camera, so Face ID fails and offers Cancel.
        sleep(10)
        faceID.tap()
        let keyboard = app.keyboards.firstMatch.waitForExistence(timeout: 120)
        sleep(1)
        let banner = app.descendants(matching: .any)["faceIDMessage"]
        note("keyboard: \(keyboard), banner: \(banner.exists ? banner.label : "none"), unlocked: \(app.staticTexts["Web"].exists)")
        screenshot("after cancel")
        XCTAssertTrue(keyboard, "the password field is focused after cancel")
        XCTAssertFalse(banner.exists, "cancel is not an error")
        XCTAssertFalse(app.staticTexts["Web"].exists, "still locked")
        XCTAssertTrue(app.buttons["faceIDUnlock"].exists, "cancel keeps Face ID unlock on")
        latinKeyboard(app)
        app.typeText(password + "\n")
        XCTAssertTrue(app.staticTexts["Web"].waitForExistence(timeout: 15), "the master password still works")
        app.buttons["lock"].firstMatch.tap()
    }

    /// Face ID unlock (look), then the M6 background rules: 10 s stays open, 35 s locks.
    func testManualFaceIDUnlockThenBackground() throws {
        try manualOnly()
        let app = XCUIApplication()
        app.launch()
        let faceID = app.buttons["faceIDUnlock"]
        XCTAssertTrue(faceID.waitForExistence(timeout: 5))
        faceID.tap()
        XCTAssertTrue(app.staticTexts["Web"].waitForExistence(timeout: 90))
        XCUIDevice.shared.press(.home)
        sleep(10)
        app.activate()
        XCTAssertTrue(app.staticTexts["Web"].waitForExistence(timeout: 5), "10 s keeps the vault open")
        XCUIDevice.shared.press(.home)
        sleep(35)
        app.activate()
        XCTAssertTrue(app.buttons["faceIDUnlock"].waitForExistence(timeout: 5), "35 s locks")
        XCTAssertFalse(app.staticTexts["Web"].exists)
        note("background rules hold after a Face ID unlock")
    }

    /// Key-file vault: Face ID stands in for the password only. Without the key file there is no
    /// prompt; with it, Face ID (look) opens the vault.
    func testManualFaceIDKeyFileVault() throws {
        try manualOnly()
        let app = launch(["-VazieQAVault", "qa/vault-password-plus-keyfile.kdbx", "-VazieQAKeyFile", "qa/keyfile-v2.keyx"])
        try unlockScreen(app).typeText(password + "\n")
        XCTAssertTrue(app.staticTexts["Key File Entry"].waitForExistence(timeout: 15))
        turnOnFaceID(app)
        app.buttons["lock"].firstMatch.tap()
        XCTAssertTrue(app.buttons["faceIDUnlock"].waitForExistence(timeout: 5))
        app.buttons["Remove"].tap()
        app.buttons["faceIDUnlock"].tap()
        let banner = app.descendants(matching: .any)["faceIDMessage"]
        XCTAssertTrue(banner.waitForExistence(timeout: 3), "asks for the key file before any prompt")
        screenshot("key file required")
        note("without key file: \(banner.label)", "no key file")
        app.terminate()

        let again = XCUIApplication()
        again.launchArguments = ["-VazieQAKeyFile", "qa/keyfile-v2.keyx"]
        again.launch()
        XCTAssertTrue(again.buttons["faceIDUnlock"].waitForExistence(timeout: 5))
        again.buttons["faceIDUnlock"].tap()
        let opened = again.staticTexts["Key File Entry"].waitForExistence(timeout: 90)
        note(opened ? "opened with Face ID + key file" : "not opened")
        XCTAssertTrue(opened)
        again.buttons["lock"].firstMatch.tap()
    }

    /// Records what the app shows now, without touching anything: for reboot, enrollment change
    /// and reinstall checks.
    func testManualFaceIDState() throws {
        try manualOnly()
        let app = XCUIApplication()
        app.launch()
        sleep(2)
        let banner = app.descendants(matching: .any)["faceIDMessage"]
        let state = "locked: \(app.secureTextFields["password"].exists || app.buttons["chooseVault"].exists), "
            + "faceIDButton: \(app.buttons["faceIDUnlock"].exists), banner: \(banner.exists ? banner.label : "none")"
        screenshot("unlock screen")
        app.buttons["settings"].firstMatch.tap()
        let toggle = app.switches["faceIDToggle"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        note(state + ", toggle: \(toggle.value ?? "?"), row: \(toggle.label)")
        screenshot("settings")
        app.buttons["settingsDone"].tap()
    }

    /// Unlock with Face ID right now (look at the phone); used after a reboot.
    func testManualFaceIDUnlockOnce() throws {
        try manualOnly()
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.secureTextFields["password"].waitForExistence(timeout: 5), "starts locked")
        let faceID = app.buttons["faceIDUnlock"]
        XCTAssertTrue(faceID.waitForExistence(timeout: 5))
        faceID.tap()
        let opened = app.staticTexts["Web"].waitForExistence(timeout: 90)
        note(opened ? "opened" : "not opened")
        XCTAssertTrue(opened)
        app.buttons["lock"].firstMatch.tap()
    }

    // MARK: Editing and saving (M9)

    private let mailRow = "Example Mail, alice@example.org · https://mail.example.org/login"

    private func replaceText(_ field: XCUIElement, with text: String) {
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        for _ in 0..<2 {
            field.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.5)).tap()
            let current = (field.value as? String) ?? ""
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count + 2) + text)
            if (field.value as? String) == text { return }
        }
        XCTFail("could not type into \(field.identifier)")
    }

    private func unlockAgain(_ app: XCUIApplication) throws {
        let field = try unlockScreen(app)
        field.typeText(password + "\n")
        XCTAssertTrue(app.staticTexts["Web"].waitForExistence(timeout: 15))
    }

    func testEditUsernameSavesToTheFileAndSurvivesALock() throws {
        let app = launch(["-VazieQAVault", "qa/edit-target.kdbx"])
        try unlockAgain(app)
        open("Web", in: app)
        open(mailRow, in: app)
        app.buttons["editEntry"].tap()
        replaceText(app.textFields["editUsername"], with: "phone-edited@example.org")
        screenshot("editor")
        app.buttons["editSave"].tap()
        XCTAssertTrue(app.staticTexts["phone-edited@example.org"].waitForExistence(timeout: 10), "detail shows the saved value")
        XCTAssertFalse(app.descendants(matching: .any)["unsavedBanner"].exists)
        app.buttons["lock"].firstMatch.tap()
        try unlockAgain(app)
        open("Web", in: app)
        XCTAssertTrue(app.buttons["Example Mail, phone-edited@example.org · https://mail.example.org/login"].waitForExistence(timeout: 5))
        screenshot("after relock")
    }

    func testNewEntryWithAGeneratedPassword() throws {
        let app = launch(["-VazieQAVault", "qa/edit-target.kdbx"])
        try unlockAgain(app)
        app.buttons["add"].tap()
        app.buttons["newEntry"].firstMatch.tap()
        replaceText(app.textFields["editTitle"], with: "Phone Entry")
        app.buttons["generatePassword"].tap()
        XCTAssertTrue(app.buttons["useGenerated"].waitForExistence(timeout: 5))
        screenshot("generator")
        app.buttons["useGenerated"].tap()
        app.buttons["editSave"].tap()
        XCTAssertTrue(app.staticTexts["Phone Entry"].waitForExistence(timeout: 10))
        app.buttons["lock"].firstMatch.tap()
        try unlockAgain(app)
        XCTAssertTrue(app.staticTexts["Phone Entry"].waitForExistence(timeout: 5))
        open("Phone Entry", in: app)
        app.buttons["reveal.password"].tap()
        let tree = app.debugDescription
        XCTAssertFalse(tree.contains("hunter2"), "no secret in the tree")
        screenshot("generated entry")
    }

    func testGroupsMoveRenameAndRecycleBin() throws {
        let app = launch(["-VazieQAVault", "qa/rich.kdbx"])
        try unlockAgain(app)
        app.buttons["add"].tap()
        app.buttons["newGroup"].firstMatch.tap()
        replaceText(app.textFields["groupName"], with: "Phone Group")
        app.buttons["groupNameDone"].tap()
        XCTAssertTrue(app.staticTexts["Phone Group"].waitForExistence(timeout: 10))

        open("Web", in: app)
        open("Example Mail, alice@example.org · https://mail.example.org/login", in: app)
        app.buttons["editEntry"].tap()
        let picker = app.buttons["editGroup"]
        for _ in 0..<6 where !picker.exists {
            app.swipeUp()
        }
        XCTAssertTrue(picker.waitForExistence(timeout: 5))
        picker.tap()
        let choice = app.buttons["Phone Group"].firstMatch
        XCTAssertTrue(choice.waitForExistence(timeout: 5))
        choice.tap()
        app.buttons["editSave"].tap()
        XCTAssertTrue(app.buttons["editEntry"].waitForExistence(timeout: 10))
        popToRoot(app)

        let group = app.staticTexts["Phone Group"]
        XCTAssertTrue(group.waitForExistence(timeout: 5))
        group.press(forDuration: 1.0)
        app.buttons["Rename"].tap()
        replaceText(app.textFields["groupName"], with: "Phone Renamed")
        app.buttons["groupNameDone"].tap()
        XCTAssertTrue(app.staticTexts["Phone Renamed"].waitForExistence(timeout: 10))

        open("Archive", in: app)
        let unicode = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Юникод'")).firstMatch
        XCTAssertTrue(unicode.waitForExistence(timeout: 5))
        unicode.tap()
        app.buttons["deleteEntry"].tap()
        app.buttons["Move to Recycle Bin"].tap()
        XCTAssertTrue(app.staticTexts["This group is empty"].waitForExistence(timeout: 10))
        app.buttons["back"].firstMatch.tap()

        app.buttons["lock"].firstMatch.tap()
        try unlockAgain(app)
        open("Phone Renamed", in: app)
        XCTAssertTrue(app.buttons["Example Mail, alice@example.org · https://mail.example.org/login"].waitForExistence(timeout: 5))
        app.buttons["back"].firstMatch.tap()
        open("Recycle Bin", in: app)
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Юникод'")).firstMatch.waitForExistence(timeout: 5))
        screenshot("recycle bin")
    }

    func testAChangeMadeElsewhereBlocksTheSave() throws {
        let app = launch(["-VazieQAVault", "qa/conflict-target.kdbx", "-VazieQAReplaceAfterUnlock", "qa/conflict-external.kdbx"])
        try unlockAgain(app)
        open("Web", in: app)
        open(mailRow, in: app)
        app.buttons["editEntry"].tap()
        replaceText(app.textFields["editUsername"], with: "mine@example.org")
        app.buttons["editSave"].tap()
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 10), "conflict reported")
        screenshot("conflict")
        XCTAssertTrue(alert.buttons["Reload"].exists && alert.buttons["Save a Copy…"].exists)
        XCTAssertFalse(alert.buttons["Try Again"].exists, "no overwrite-anyway")
        alert.buttons["Save a Copy…"].tap()
        let exporter = app.navigationBars["FullDocumentManagerViewControllerNavigationBar"]
        XCTAssertTrue(exporter.waitForExistence(timeout: 8), "the system exporter opens")
        screenshot("save a copy")
        exporter.swipeDown(velocity: .fast)
        XCTAssertTrue(app.navigationBars["Edit Entry"].waitForExistence(timeout: 5))
        sleep(1)
        if exporter.exists { app.swipeDown(velocity: .fast) }
        app.navigationBars["Edit Entry"].buttons["Cancel"].tap()
        XCTAssertTrue(app.buttons["lock"].firstMatch.waitForExistence(timeout: 5))
        app.buttons["lock"].firstMatch.tap()
        let discard = app.buttons["Discard Changes"]
        XCTAssertTrue(discard.waitForExistence(timeout: 5), "locking with unsaved changes asks first")
        screenshot("lock with unsaved changes")
        discard.tap()
        XCTAssertTrue(app.secureTextFields["password"].waitForExistence(timeout: 5))
    }

    /// Timings land in perf.log: save on the realistic Argon2d 64 MiB vault (no KDF on save).
    func testSaveTimingsOnARealisticVault() throws {
        let app = launch(["-VazieQAVault", "qa/realistic-argon2d-64m.kdbx"])
        try unlockAgain(app)
        open("Web", in: app)
        for round in 0..<3 {
            let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Example Mail'")).firstMatch
            XCTAssertTrue(row.waitForExistence(timeout: 5))
            row.tap()
            app.buttons["editEntry"].tap()
            replaceText(app.textFields["editUsername"], with: "round-\(round)@example.org")
            app.buttons["editSave"].tap()
            XCTAssertTrue(app.staticTexts["round-\(round)@example.org"].waitForExistence(timeout: 10))
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }
    }

    // MARK: iCloud Drive (manual: the tester picked iCloud Drive/VazieKeepQA/icloud-edit.kdbx)

    func testManualICloudEditAndSave() throws {
        try manualOnly()
        let app = XCUIApplication()
        app.launch()
        try unlockAgain(app)
        open("Web", in: app)
        open(mailRow, in: app)
        app.buttons["editEntry"].tap()
        replaceText(app.textFields["editUsername"], with: "icloud-edited@example.org")
        app.buttons["editSave"].tap()
        let saved = app.staticTexts["icloud-edited@example.org"].waitForExistence(timeout: 20)
        let alert = app.alerts.firstMatch
        note(saved ? "saved" : (alert.exists ? "alert: \(alert.label)" : "nothing"))
        screenshot("icloud saved")
        XCTAssertTrue(saved)
        app.buttons["lock"].firstMatch.tap()
    }

    /// While the editor is open, the Mac changes the same file in iCloud Drive. Save must refuse.
    func testManualICloudConflict() throws {
        try manualOnly()
        let app = XCUIApplication()
        app.launch()
        try unlockAgain(app)
        open("Web", in: app)
        let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Example Mail'")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()
        app.buttons["editEntry"].tap()
        replaceText(app.textFields["editUsername"], with: "iphone-version@example.org")
        note("editor open, waiting for the Mac", "phase")
        sleep(90)
        app.buttons["editSave"].tap()
        let alert = app.alerts.firstMatch
        let blocked = alert.waitForExistence(timeout: 20)
        note(blocked ? "blocked: \(alert.label)" : "saved without conflict")
        screenshot("icloud conflict")
        if blocked {
            alert.buttons["Cancel"].tap()
            app.navigationBars["Edit Entry"].buttons["Cancel"].tap()
            app.buttons["lock"].firstMatch.tap()
            if app.buttons["Discard Changes"].waitForExistence(timeout: 5) { app.buttons["Discard Changes"].tap() }
        }
        XCTAssertTrue(blocked)
    }

    func testEditorAtTheLargestTextKeepsSecretsOutOfTheTree() throws {
        let app = XCUIApplication()
        app.launchArguments = [
            "-VazieQAReset", "YES", "-VazieQAVault", "qa/aes256-argon2d.kdbx",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL",
            "-appearance.theme", "dark",
        ]
        app.launch()
        try unlockAgain(app)
        open("Web", in: app)
        open(mailRow, in: app)
        app.buttons["editEntry"].tap()
        XCTAssertTrue(app.buttons["editSave"].waitForExistence(timeout: 5))
        screenshot("AX5 editor")
        var tree = app.debugDescription
        XCTAssertFalse(tree.contains("hunter2-synthetic"), "a hidden password stays out of the tree")
        XCTAssertFalse(tree.contains("RC-0000-1111"), "a protected field stays out of the tree")
        for _ in 0..<8 { app.swipeUp() }
        screenshot("AX5 editor bottom")
        for _ in 0..<8 { app.swipeDown() }
        app.buttons["generatePassword"].tap()
        XCTAssertTrue(app.buttons["useGenerated"].waitForExistence(timeout: 5))
        screenshot("AX5 generator")
        tree = app.debugDescription
        XCTAssertTrue(tree.contains("Generated password"))
        app.navigationBars.buttons["Cancel"].firstMatch.tap()
        app.navigationBars["Edit Entry"].buttons["Cancel"].tap()
        XCTAssertTrue(app.buttons["editEntry"].waitForExistence(timeout: 5))
    }
}
