import XCTest

final class FrameLinkUITests: XCTestCase {
  /// Prepared for an isolated iPad QA simulator; system split-window interaction remains a separate gate.
  func testPhotoWorkspaceKeepsPhotoZoomAndSelectionAcrossRotationAndComparison() throws {
    continueAfterFailure = false; XCUIDevice.shared.orientation = .portrait
    let app = XCUIApplication(); app.launchArguments = ["--ui-acceptance", "--reset-ui-fixtures"]; app.launch()
    app.buttons["图库"].tap(); app.buttons["查看 QA_6.jpg"].tap()
    let photo = app.images.matching(NSPredicate(format: "label == %@", "QA_6.jpg")).firstMatch
    XCTAssertTrue(photo.waitForExistence(timeout: 10)); photo.doubleTap()
    let value = String(describing: photo.value ?? "")
    XCTAssertTrue(value.contains("2.50"))
    XCUIDevice.shared.orientation = .landscapeLeft
    XCTAssertTrue(photo.waitForExistence(timeout: 5)); XCTAssertEqual(String(describing: photo.value ?? ""), value)
    app.buttons["选择当前照片"].tap()
    app.buttons["照片操作"].tap(); app.buttons["与下一张对比"].tap()
    XCTAssertEqual(app.images.matching(NSPredicate(format: "label BEGINSWITH %@", "QA_")).count, 2)
    app.buttons["结束对比"].tap()
    XCTAssertTrue(photo.waitForExistence(timeout: 5)); XCTAssertEqual(String(describing: photo.value ?? ""), value)
    app.buttons["关闭照片"].tap(); app.buttons["查看 QA_6.jpg"].tap()
    XCTAssertTrue(photo.waitForExistence(timeout: 5)); XCTAssertEqual(String(describing: photo.value ?? ""), value)
    captureBoundaryEvidence(app, name: "gallery-shared-photo-window-state")
  }
  /// Does not start discovery or require camera hardware; runtime UI execution is a separate gate.
  func testFujiAutoSaveEntryDefaultsOffWithoutStartingDiscovery() throws {
    continueAfterFailure = false
    let app = XCUIApplication(); app.launchArguments = ["--ui-acceptance", "--reset-ui-fixtures"]; app.launch()
    app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "富士 PC AutoSave")).firstMatch.tap()
    let consent = app.switches["启用本次实验接收"]
    XCTAssertTrue(consent.waitForExistence(timeout: 5)); XCTAssertEqual(String(describing: consent.value ?? ""), "0")
    XCTAssertFalse(app.buttons["开始等待传输邀请"].isEnabled)
    XCTAssertFalse(app.buttons["接收当前待传批次"].exists)
    consent.tap(); XCTAssertEqual(String(describing: consent.value ?? ""), "1")
    consent.tap(); XCTAssertEqual(String(describing: consent.value ?? ""), "0")
    XCTAssertTrue(app.staticTexts["接收监听已关闭"].exists)
    app.buttons["停止本次接收"].tap()
    XCTAssertTrue(app.staticTexts["接收监听已关闭"].exists)
    captureBoundaryEvidence(app, name: "fuji-autosave-default-off")
  }
  /// Requires the isolated local PTP/IP simulator started with -creative-look; never select a physical camera.
  func testSyntheticSonyCreativeLookShowsChineseChoicesAndConfirmedFLST() throws {
    continueAfterFailure = false; XCUIDevice.shared.orientation = .portrait
    let app = XCUIApplication(); app.launchArguments = ["--ui-acceptance", "--reset-ui-fixtures"]; app.launch()
    app.buttons["相机连接设置"].tap()
    let host = app.textFields["例如 192.168.1.20"]; host.tap()
    if host.value as? String != "127.0.0.1" { host.typeText("127.0.0.1") }
    app.buttons["收起键盘"].tap()
    let auth = app.switches["相机要求访问认证"]; if String(describing: auth.value ?? "") != "0" { auth.tap() }
    app.buttons["连接相机"].tap(); XCTAssertTrue(app.staticTexts["QA SIMULATED CAMERA"].waitForExistence(timeout: 20))
    app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "取景拍摄")).firstMatch.tap()
    app.buttons["创意外观"].firstMatch.tap()
    XCTAssertTrue(app.buttons["FL 胶片"].waitForExistence(timeout: 5))
    app.buttons["FL 胶片"].tap(); XCTAssertTrue(app.staticTexts["机身已确认 FL"].waitForExistence(timeout: 8))
    app.buttons["ST 标准"].tap(); XCTAssertTrue(app.staticTexts["机身已确认 ST"].waitForExistence(timeout: 8))
    captureBoundaryEvidence(app, name: "creative-look-synthetic-confirmed")
  }
  func testSelectingCanonProtocolClearsSonyCredentialsAndKeepsSaveOff() throws {
    continueAfterFailure = false; XCUIDevice.shared.orientation = .portrait
    let app = XCUIApplication(); app.launchArguments = ["--ui-acceptance", "--reset-ui-fixtures", "--ui-foundations"]; app.launch()
    app.buttons["相机连接设置"].tap(); app.buttons["qa-a.local:15740 · qa-user-a"].tap()
    app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "连接协议")).firstMatch.tap()
    app.buttons["Canon CCAPI（实验）"].tap()
    app.switches["相机要求访问认证"].tap()
    assertEmpty(app.textFields["相机用户名"]); assertEmpty(app.secureTextFields["相机密码"])
    XCTAssertEqual(String(describing: app.switches["保存此连接的账号和密码"].value ?? ""), "0")
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "HTTP认证")).firstMatch.exists)
    captureBoundaryEvidence(app, name: "canon-protocol-no-sony-credentials")
  }
  func testCameraIndexShowsRangeTotalAndFillsWithoutScrollingBeforeSelectAll() throws {
    continueAfterFailure = false; XCUIDevice.shared.orientation = .portrait
    let app = XCUIApplication(); app.launchArguments = ["--ui-acceptance", "--reset-ui-fixtures", "--ui-camera-index-fixture"]; app.launch()
    app.buttons["图库"].tap()
    XCTAssertTrue(app.staticTexts["共 250 张"].waitForExistence(timeout: 5))
    XCTAssertFalse(app.staticTexts["共 100 张"].exists)
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "索引已完整")).firstMatch.waitForExistence(timeout: 8))
    app.buttons["全选"].tap()
    XCTAssertTrue(app.buttons["已选 250 ×"].waitForExistence(timeout: 5))
    XCTAssertFalse(app.buttons["加载更多"].exists)
    captureBoundaryEvidence(app, name: "camera-index-total-250")
  }
  private func captureBoundaryEvidence(_ app: XCUIApplication, name: String) {
    let shot = XCUIScreen.main.screenshot()
    print("QA_WINDOW name=\(name) app=\(app.frame) imagePoints=\(shot.image.size) imageScale=\(shot.image.scale) os=\(ProcessInfo.processInfo.operatingSystemVersionString)")
    let attachment = XCTAttachment(screenshot: shot); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
  }
  func testOpenPhotoInformationRefreshesWhenSamePhotoGetsLocalOriginal() throws {
    continueAfterFailure = false
    XCUIDevice.shared.orientation = .portrait
    let app = XCUIApplication(); app.launchArguments = ["--ui-acceptance", "--reset-ui-fixtures", "--ui-metadata-fixture", "--ui-metadata-upgrade-fixture"]; app.launch()
    app.buttons["图库"].tap(); app.buttons["查看 QA_6.jpg"].tap(); app.buttons["文件信息"].tap()
    XCTAssertTrue(app.staticTexts["ISO 100"].waitForExistence(timeout: 5))
    XCTAssertTrue(app.staticTexts["0.8 秒"].exists)
    XCTAssertTrue(app.staticTexts["ISO 400"].waitForExistence(timeout: 8))
    XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "本机原片")).firstMatch.exists)
    captureBoundaryEvidence(app, name: "metadata-original-published")
  }
  func testPhotoMetadataUsesFileSettingsAndCacheClearKeepsImportedPhoto() throws {
    continueAfterFailure = false
    XCUIDevice.shared.orientation = .portrait
    let app = XCUIApplication(); app.launchArguments = ["--ui-acceptance", "--reset-ui-fixtures", "--ui-metadata-fixture"]; app.launch()
    app.buttons["图库"].tap(); app.buttons["查看 QA_6.jpg"].tap(); app.buttons["文件信息"].tap()
    XCTAssertTrue(app.staticTexts["ISO 400"].waitForExistence(timeout: 5))
    XCTAssertTrue(app.staticTexts["1/125 秒"].exists); XCTAssertTrue(app.staticTexts["f/2.8"].exists)
    XCTAssertTrue(app.staticTexts["QA Camera"].exists)
    app.buttons["完成"].tap(); app.buttons["关闭照片"].tap()
    app.buttons["相机"].tap(); app.buttons["设置与帮助"].tap(); app.buttons["清除预览缓存"].tap(); app.buttons["清除缓存"].tap()
    XCTAssertTrue(app.buttons["好"].waitForExistence(timeout: 5)); app.buttons["好"].tap()
    XCTAssertTrue(app.staticTexts["当前占用"].exists)
    app.navigationBars.buttons.element(boundBy: 0).tap(); app.buttons["图库"].tap()
    app.buttons["查看 QA_6.jpg"].tap(); app.buttons["文件信息"].tap()
    XCTAssertTrue(app.staticTexts["ISO 400"].waitForExistence(timeout: 5))
    captureBoundaryEvidence(app, name: "metadata-after-cache-clear")
  }
  private func assertEmpty(_ field: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
    XCTAssertTrue(["", field.placeholderValue ?? ""].contains(field.value as? String ?? ""), file: file, line: line)
  }
  func testCameraUnsavedEndpointAndAccountEditsClearCredentialsButSelectingBPreservesThem() throws {
    continueAfterFailure = false
    XCUIDevice.shared.orientation = .portrait
    let app = XCUIApplication(); app.launchArguments = ["--ui-acceptance", "--reset-ui-fixtures", "--ui-foundations"]; app.launch()
    app.buttons["相机连接设置"].tap(); app.buttons["qa-a.local:15740 · qa-user-a"].tap()
    app.switches["保存此连接的账号和密码"].tap()
    app.textFields["例如 192.168.1.20"].tap(); app.textFields["例如 192.168.1.20"].typeText("b")
    assertEmpty(app.textFields["相机用户名"]); assertEmpty(app.secureTextFields["相机密码"])
    app.buttons["收起键盘"].tap(); app.buttons["qa-b.local:15740 · qa-user-b"].tap()
    XCTAssertEqual(app.textFields["相机用户名"].value as? String, "qa-user-b")
    app.buttons["显示密码"].tap(); XCTAssertEqual(app.textFields["相机密码"].value as? String, "qa-password-b")
    app.switches["保存此连接的账号和密码"].tap()
    app.textFields["相机用户名"].tap(); app.textFields["相机用户名"].typeText("changed")
    assertEmpty(app.textFields["相机密码"])
    app.textFields["相机密码"].tap(); app.textFields["相机密码"].typeText("manual-secret")
    app.textFields["例如 192.168.1.20"].tap(); app.textFields["例如 192.168.1.20"].typeText("c")
    assertEmpty(app.textFields["相机用户名"]); assertEmpty(app.textFields["相机密码"])
    captureBoundaryEvidence(app, name: "camera-credential-boundary")
  }
  func testFTPUnsavedEndpointAndAccountEditsClearCredentialsButSelectingBPreservesThem() throws {
    continueAfterFailure = false
    XCUIDevice.shared.orientation = .portrait
    let app = XCUIApplication(); app.launchArguments = ["--ui-acceptance", "--reset-ui-fixtures", "--ui-foundations"]; app.launch()
    app.buttons["FTP 客户端"].tap(); app.buttons["ftp-qa-a.local:21 · qa-user-a"].tap()
    app.switches["保存此连接的账号和密码"].tap()
    app.textFields["服务器地址"].tap(); app.textFields["服务器地址"].typeText("b")
    assertEmpty(app.textFields["用户名"]); assertEmpty(app.secureTextFields["密码"])
    app.buttons["ftp-qa-b.local:21 · qa-user-b"].tap()
    XCTAssertEqual(app.textFields["用户名"].value as? String, "qa-user-b")
    app.buttons["显示密码"].tap(); XCTAssertEqual(app.textFields["密码"].value as? String, "qa-password-b")
    app.switches["保存此连接的账号和密码"].tap()
    app.textFields["用户名"].tap(); app.textFields["用户名"].typeText("changed")
    assertEmpty(app.textFields["密码"])
    app.textFields["密码"].tap(); app.textFields["密码"].typeText("manual-secret")
    app.switches["使用显式FTPS"].tap()
    assertEmpty(app.textFields["用户名"]); assertEmpty(app.textFields["密码"])
    captureBoundaryEvidence(app, name: "ftp-credential-boundary")
  }
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
