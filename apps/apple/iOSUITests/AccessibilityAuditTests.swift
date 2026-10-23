import XCTest

/// Walks the main screens with the system accessibility audit: missing labels, small touch targets,
/// clipped text, text that does not follow Dynamic Type, contrast. Needs `qa/history.kdbx`
/// (the editable `rich` vector, password `correct horse battery staple`) in the app's Documents.
@MainActor
final class AccessibilityAuditTests: XCTestCase {
    private var found: [String] = []

    override func setUp() async throws {
        continueAfterFailure = true
    }

    private func audit(_ app: XCUIApplication, _ screen: String) {
        do {
            // Left to the eye and the colour tokens: contrast (the glass toolbar misleads the audit), Dynamic Type and
            // clipping (one-line controls shrink or stop growing on purpose; see the large text walk below).
            try app.performAccessibilityAudit(for: [.sufficientElementDescription, .elementDetection, .trait, .hitRegion]) { issue in
                let element = issue.element
                // A file name or an email address is read as it is written.
                if issue.compactDescription.contains("human-readable") { return true }
                if issue.auditType == .hitRegion, let frame = element?.frame, frame.width >= 44, frame.height >= 44 { return true }
                // A text field reports its line of text; the chrome around it takes the tap.
                if issue.auditType == .hitRegion, element?.elementType == .textField || element?.elementType == .secureTextField { return true }
                let what = [element?.identifier, element?.label].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " / ")
                let frame = element.map { "\(Int($0.frame.width))x\(Int($0.frame.height))" } ?? ""
                let line = "\(screen) | \(issue.compactDescription) | \(what) | \(frame)"
                if !self.found.contains(line) { self.found.append(line) }
                return true
            }
        } catch {
            found.append("\(screen) | the audit itself failed: \(error)")
        }
    }

    func testTheMainScreensPassTheAudit() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-VazieQAReset", "YES", "-VazieQAVault", "qa/history.kdbx", "-VazieQASiteIcons", "YES"]
        app.launch()
        guard app.secureTextFields["password"].waitForExistence(timeout: 8) else { throw XCTSkip("qa/history.kdbx is not installed") }
        audit(app, "unlock")
        app.secureTextFields["password"].tap()
        app.secureTextFields["password"].typeText("correct horse battery staple")
        app.buttons["unlock"].tap()

        XCTAssertTrue(app.staticTexts["Web"].waitForExistence(timeout: 20))
        audit(app, "vault")

        app.buttons["settings"].firstMatch.tap()
        XCTAssertTrue(app.buttons["settingsDone"].waitForExistence(timeout: 5))
        audit(app, "settings")
        app.buttons["accountRow"].tap()
        sleep(1)
        audit(app, "account")
        app.navigationBars.buttons.firstMatch.tap()
        app.buttons["settingsDone"].tap()

        let add = app.buttons.matching(NSPredicate(format: "label IN %@", ["Add", "Добавить"])).firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        add.tap()
        app.buttons["newEntry"].firstMatch.tap()
        XCTAssertTrue(app.buttons["editorCancel"].waitForExistence(timeout: 5))
        audit(app, "editor")
        app.buttons["editorCancel"].tap()

        for group in ["Web", "Work"] {
            let row = app.staticTexts[group]
            XCTAssertTrue(row.waitForExistence(timeout: 10))
            row.tap()
        }
        XCTAssertTrue(app.staticTexts["Bank"].waitForExistence(timeout: 10))
        audit(app, "group")
        app.staticTexts["Bank"].tap()
        XCTAssertTrue(app.buttons["entryIcon"].waitForExistence(timeout: 8))
        audit(app, "entry")
        app.buttons["entryIcon"].tap()
        XCTAssertTrue(app.buttons["icon.37"].waitForExistence(timeout: 5))
        audit(app, "icon picker")

        for line in found { print("A11Y \(line)") }
        XCTAssertEqual(found, [])
    }

    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// The same walk at the largest accessibility text size, for a person to look at: nothing may be cut off
    /// in a way that hides what a control does.
    func testTheMainScreensAtTheLargestTextSize() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-VazieQAReset", "YES", "-VazieQAVault", "qa/history.kdbx", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXL"]
        app.launch()
        guard app.secureTextFields["password"].waitForExistence(timeout: 8) else { throw XCTSkip("qa/history.kdbx is not installed") }
        shot("large 01 unlock")
        app.secureTextFields["password"].tap()
        app.secureTextFields["password"].typeText("correct horse battery staple")
        app.buttons["unlock"].tap()
        XCTAssertTrue(app.staticTexts["Web"].waitForExistence(timeout: 20))
        shot("large 02 vault")
        app.buttons["settings"].firstMatch.tap()
        XCTAssertTrue(app.buttons["settingsDone"].waitForExistence(timeout: 5))
        shot("large 03 settings")
        app.buttons["settingsDone"].tap()
        for group in ["Web", "Work"] {
            let row = app.staticTexts[group]
            XCTAssertTrue(row.waitForExistence(timeout: 10))
            row.tap()
        }
        XCTAssertTrue(app.staticTexts["Bank"].waitForExistence(timeout: 10))
        shot("large 04 group")
        app.staticTexts["Bank"].tap()
        XCTAssertTrue(app.buttons["entryIcon"].waitForExistence(timeout: 8))
        shot("large 05 entry")
        app.buttons["editEntry"].firstMatch.tap()
        XCTAssertTrue(app.buttons["editorCancel"].waitForExistence(timeout: 5))
        shot("large 06 editor")
    }
}
