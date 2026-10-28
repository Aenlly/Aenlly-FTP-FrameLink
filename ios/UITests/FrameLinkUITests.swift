import XCTest

final class FrameLinkUITests: XCTestCase {
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
