import XCTest

final class FrameLinkUITests: XCTestCase {
  func testSavedConnectionsForgetIndependentlyAndUnsavedInputDoesNotReturn() throws {
    continueAfterFailure = false
    let app = XCUIApplication(); app.launchArguments = ["--ui-acceptance", "--reset-ui-fixtures", "--ui-foundations"]; app.launch()
    app.buttons["相机连接设置"].tap()
    app.buttons["qa-a.local:15740 · qa-user-a"].tap()
    XCTAssertEqual(app.textFields["相机用户名"].value as? String, "qa-user-a")
    app.buttons["显示密码"].tap(); XCTAssertEqual(app.textFields["相机密码"].value as? String, "qa-password-a")
    app.buttons["忘记连接 qa-a.local"].tap()
    XCTAssertFalse(app.buttons["qa-a.local:15740 · qa-user-a"].exists)
    XCTAssertTrue(app.buttons["qa-b.local:15740 · qa-user-b"].exists)
    app.buttons["qa-b.local:15740 · qa-user-b"].tap()
    let host = app.textFields["例如 192.168.1.20"]; host.tap(); host.press(forDuration: 1)
    if app.menuItems["全选"].exists { app.menuItems["全选"].tap() } else if app.menuItems["Select All"].exists { app.menuItems["Select All"].tap() }
    host.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 80) + "qa-unsaved.local")
    app.buttons["收起键盘"].tap()
    XCTAssertEqual(String(describing: app.switches["保存此连接的账号和密码"].value ?? ""), "0")
    app.buttons["完成"].tap(); app.buttons["相机连接设置"].tap()
    XCTAssertNotEqual(app.textFields["例如 192.168.1.20"].value as? String, "qa-unsaved.local")
    let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = "credentials-saved-and-forgotten"; attachment.lifetime = .keepAlways; add(attachment)
  }
  func testClearEndedHistoryKeepsPendingTaskAndSavedFileAccess() throws {
    continueAfterFailure = false
    let app = XCUIApplication(); app.launchArguments = ["--ui-acceptance", "--reset-ui-fixtures", "--ui-foundations"]; app.launch()
    app.buttons["传输"].tap(); XCTAssertTrue(app.staticTexts["QA 已完成任务"].waitForExistence(timeout: 5))
    app.buttons["传输更多操作"].tap(); app.buttons["清除已结束记录"].tap()
    XCTAssertTrue(app.staticTexts["QA 已完成任务"].exists)
    app.buttons["清除记录"].tap()
    XCTAssertFalse(app.staticTexts["QA 已完成任务"].exists); XCTAssertTrue(app.staticTexts["QA 待恢复任务"].exists)
    app.buttons["传输更多操作"].tap(); app.buttons["已保存文件"].tap()
    XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "QA_")).firstMatch.waitForExistence(timeout: 5))
    let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = "history-retained-file"; attachment.lifetime = .keepAlways; add(attachment)
  }
  func testGalleryLayoutsPreservePairedFormatInformation() throws {
    continueAfterFailure = false
    XCUIDevice.shared.orientation = .portrait
    let app = XCUIApplication()
    app.launchArguments = ["--ui-acceptance", "--reset-ui-fixtures", "--ui-paired-fixture"]
    app.launch()
    app.buttons["图库"].tap()
    XCTAssertTrue(app.staticTexts["JPEG + RAW"].waitForExistence(timeout: 10))
    XCTAssertTrue(app.staticTexts["JPEG 已导入 · RAW 未导入"].exists)
    for title in ["小图", "中图", "大图", "列表"] {
      app.buttons["显示方式"].tap()
      app.buttons[title].tap()
      XCTAssertTrue(app.buttons["选择 QA_6.jpg"].exists)
    }
    app.buttons["查看 QA_6.jpg"].tap()
    app.buttons["文件信息"].tap()
    XCTAssertTrue(app.staticTexts["QA_6.ARW"].waitForExistence(timeout: 5))
    app.buttons["完成"].tap()
    app.buttons["关闭照片"].tap()
    app.buttons["全选"].tap()
    XCTAssertTrue(app.buttons["已选 6 ×"].waitForExistence(timeout: 5))
    app.buttons["取消全选"].tap()
    XCTAssertFalse(app.buttons["已选 6 ×"].exists)
  }

  func testDragSelectionCanDeselectInsideSelectedFilter() throws {
    continueAfterFailure = false
    XCUIDevice.shared.orientation = .portrait
    let app = XCUIApplication()
    app.launchArguments = ["--ui-acceptance", "--reset-ui-fixtures"]
    app.launch()
    app.buttons["图库"].tap()
    XCTAssertTrue(app.buttons["选择 QA_6.jpg"].waitForExistence(timeout: 10))
    app.buttons["选择 QA_6.jpg"].tap()
    app.buttons["选择 QA_5.jpg"].tap()
    XCTAssertTrue(app.buttons["已选 2 ×"].waitForExistence(timeout: 5))
    app.buttons["已选"].tap()
    app.buttons["查看 QA_6.jpg"].press(forDuration: 0.6, thenDragTo: app.buttons["查看 QA_5.jpg"])
    XCTAssertTrue(app.staticTexts["暂无照片"].waitForExistence(timeout: 8))
    XCTAssertFalse(app.buttons["已选 1 ×"].exists)
  }

  func testSimulatedSonyConnectionBrowsingDownloadAndLiveView() throws {
    continueAfterFailure = false
    XCUIDevice.shared.orientation = .portrait
    let app = XCUIApplication()
    app.launchArguments = ["--ui-acceptance", "--reset-ui-fixtures"]
    app.launch()
    app.buttons["相机"].tap()
    app.buttons["相机连接设置"].tap()
    let host = app.textFields["例如 192.168.1.20"]
    XCTAssertTrue(host.waitForExistence(timeout: 5))
    host.tap()
    if host.value as? String != "127.0.0.1" { host.typeText("127.0.0.1") }
    app.buttons["收起键盘"].tap()
    let auth = app.switches["相机要求访问认证"]
    if String(describing: auth.value ?? "") != "0" {
      auth.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
    }
    XCTAssertEqual(String(describing: auth.value ?? ""), "0")
    XCTAssertTrue(app.buttons["连接相机"].isEnabled)
    app.buttons["连接相机"].tap()
    XCTAssertTrue(app.staticTexts["QA SIMULATED CAMERA"].waitForExistence(timeout: 20))
    app.buttons["断开相机"].tap()
    app.buttons["连接"].tap()
    XCTAssertTrue(app.staticTexts["QA SIMULATED CAMERA"].waitForExistence(timeout: 20))
    XCTAssertFalse(host.exists)
    app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "选片下载")).firstMatch.tap()
    let photo = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "选择 QA_DSC"))
      .firstMatch
    XCTAssertTrue(photo.waitForExistence(timeout: 15))
    photo.tap()
    app.buttons["导入"].tap()
    app.buttons["JPEG / HEIF"].tap()
    app.buttons["传输"].tap()
    XCTAssertTrue(app.staticTexts["已完成"].firstMatch.waitForExistence(timeout: 20))
    app.buttons["相机"].tap()
    app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "取景拍摄")).firstMatch.tap()
    app.buttons["连接信息"].tap()
    XCTAssertTrue(
      app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "1200×900")).firstMatch
        .waitForExistence(timeout: 20))
    app.buttons["完成"].tap()
    app.buttons["沉浸取景"].tap()
    app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    XCTAssertTrue(app.buttons["专业工具"].waitForExistence(timeout: 5))
    let attachment = XCTAttachment(screenshot: app.screenshot())
    attachment.name = "ios-live-glass"
    attachment.lifetime = .keepAlways
    add(attachment)
    app.buttons["专业工具"].tap()
    XCUIDevice.shared.orientation = .landscapeLeft
    let panel = app.descendants(matching: .any).matching(identifier: "live-tools-panel").firstMatch
    XCTAssertTrue(panel.waitForExistence(timeout: 5))
    XCTAssertLessThanOrEqual(panel.frame.width, app.frame.width * 0.4)
    app.buttons["预览辅助"].tap()
    let histogramSwitch = app.switches["直方图"]
    XCTAssertTrue(histogramSwitch.waitForExistence(timeout: 5), app.debugDescription)
    if !histogramSwitch.isHittable { app.scrollViews["live-properties"].swipeUp() }
    histogramSwitch.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
    XCTAssertEqual(String(describing: histogramSwitch.value ?? ""), "1")
    app.buttons["收起工具"].tap()
    let histogram = app.descendants(matching: .any).matching(identifier: "live-histogram").firstMatch
    XCTAssertTrue(histogram.waitForExistence(timeout: 5), app.debugDescription)
    XCTAssertLessThanOrEqual(histogram.frame.width, app.frame.width * 0.18)
    XCTAssertLessThanOrEqual(histogram.frame.height, app.frame.height * 0.25)
    let compact = XCTAttachment(screenshot: app.screenshot())
    compact.name = "ios-live-small-histogram"
    compact.lifetime = .keepAlways
    add(compact)
    XCUIDevice.shared.orientation = .portrait
    app.buttons["返回相机"].tap()
  }

  func testGalleryAndSavedCustomerRecords() throws {
    continueAfterFailure = false
    let app = XCUIApplication()
    app.launchArguments = ["--ui-acceptance", "--reset-ui-fixtures"]
    app.launch()
    XCTAssertTrue(app.buttons["图库"].waitForExistence(timeout: 15))
    app.buttons["图库"].tap()
    let first = app.buttons["选择 QA_6.jpg"]
    XCTAssertTrue(first.waitForExistence(timeout: 10))
    first.tap()
    XCTAssertTrue(app.buttons["已选 1 ×"].waitForExistence(timeout: 5))
    app.staticTexts["QA_6.jpg"].firstMatch.tap()
    XCTAssertTrue(app.buttons["已选择"].waitForExistence(timeout: 5))
    app.buttons["已选择"].tap()
    app.buttons["选择这张"].tap()
    app.buttons["关闭照片"].tap()
    app.buttons["选片操作"].tap()
    app.buttons["保存这一批"].tap()
    let name = app.textFields["客户姓名、手机号或场次名称"]
    XCTAssertTrue(name.waitForExistence(timeout: 5))
    name.tap()
    name.typeText("QA batch")
    app.buttons["保存"].tap()
    app.buttons["项目"].tap()
    XCTAssertTrue(app.staticTexts["QA batch"].waitForExistence(timeout: 5))
    let attachment = XCTAttachment(screenshot: app.screenshot())
    attachment.name = "ios-saved-selection"
    attachment.lifetime = .keepAlways
    add(attachment)
    XCUIDevice.shared.orientation = .landscapeLeft
    XCTAssertTrue(app.buttons["图库"].exists)
    XCUIDevice.shared.orientation = .portrait
  }

  func testFTPEntryAndPasswordVisibility() throws {
    let app = XCUIApplication()
    app.launchArguments = ["--ui-acceptance", "--reset-ui-fixtures"]
    app.launch()
    app.buttons["相机"].tap()
    app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "FTP 客户端")).firstMatch.tap()
    XCTAssertTrue(app.textFields["服务器地址"].waitForExistence(timeout: 5))
    XCTAssertTrue(app.secureTextFields["密码"].exists)
    app.buttons["显示密码"].tap()
    XCTAssertTrue(app.textFields["密码"].exists)
    XCUIDevice.shared.orientation = .landscapeLeft
    XCTAssertTrue(app.textFields["服务器地址"].exists)
    XCUIDevice.shared.orientation = .portrait
  }
}
