import XCTest
@testable import FrameLinkCore

@MainActor private final class LookCamera {
  var current: Int64 = 1
  var values: [Int64] = [1, 6, 9]
  var writable = true
  var missing = false
  var mismatch = false
  var writeFails = false
  var readFails = false
  var type = 4, form = 2, enabled = 1
  var advertised = true
  var reads = 0
  var writes: [Int64] = []
  var pauseRead = false, pauseWrite = false
  let suspended = XCTestExpectation(description: "look operation suspended")
  private var continuation: CheckedContinuation<Void, Never>?
  func properties() throws -> [CameraProperty] {
    if missing { return [] }
    let row: [String: Any] = ["code": 0xD0FA, "name": "Creative Look", "value": current, "label": "fixture", "writable": writable, "choices": values.map { ["value": $0, "label": "fixture"] }]
    return try JSONDecoder().decode([CameraProperty].self, from: JSONSerialization.data(withJSONObject: [row]))
  }
  func read() async throws -> [CameraProperty] {
    reads += 1; let snapshot = try properties()
    if pauseRead { pauseRead = false; await pause() }
    if readFails { throw URLError(.networkConnectionLost) }
    return snapshot
  }
  func diagnostics() throws -> CreativeLookDiagnostics {
    try JSONDecoder().decode(CreativeLookDiagnostics.self, from: JSONSerialization.data(withJSONObject: ["candidateCode": 0xD0FA, "replyCodes": missing || !advertised ? [] : [0xD0FA], "candidateDescriptors": missing ? [] : [["code": 0xD0FA, "dataType": type, "writable": writable, "enabled": enabled, "form": form, "current": current, "settable": values]]]))
  }
  func write(_ property: CameraProperty, _ target: Int64) async throws {
    XCTAssertEqual(property.code, 0xD0FA); writes.append(target)
    if pauseWrite { pauseWrite = false; await pause() }
    if writeFails { throw URLError(.networkConnectionLost) }
    if !mismatch { current = target }
  }
  private func pause() async { await withCheckedContinuation { continuation = $0; suspended.fulfill() } }
  func resume() { continuation?.resume(); continuation = nil }
  func controller() -> CreativeLookController {
    CreativeLookController(read: { try await self.read() }, diagnose: { try self.diagnostics() }, write: { try await self.write($0, $1) }, wait: {})
  }
}

@MainActor final class SonyCreativeLookTests: XCTestCase {
  func testReadsAndConfirmsSTFLBWFromActualReadback() async throws {
    let camera = LookCamera(); let controller = camera.controller()
    controller.connect(protocolID: CameraProtocol.sony, properties: try camera.properties()); await controller.refresh()
    XCTAssertEqual(controller.state.currentCode, "ST"); XCTAssertFalse(controller.state.confirmed)
    for (value, code) in [(Int64(6), "FL"), (9, "BW"), (1, "ST")] {
      await controller.select(value); XCTAssertEqual(controller.state.currentCode, code); XCTAssertTrue(controller.state.confirmed)
    }
    XCTAssertEqual(camera.writes, [6, 9, 1])
  }
  func testDoesNotShowTargetOrSuccessBeforeWriteAndReadbackFinish() async throws {
    let camera = LookCamera(); camera.pauseWrite = true; let controller = camera.controller()
    controller.connect(protocolID: CameraProtocol.sony, properties: try camera.properties())
    let task = Task { await controller.select(6) }; await fulfillment(of: [camera.suspended], timeout: 3)
    XCTAssertEqual(controller.state.currentCode, "ST"); XCTAssertFalse(controller.state.confirmed); XCTAssertTrue(controller.state.busy)
    camera.resume(); await task.value
    XCTAssertEqual(controller.state.currentCode, "FL"); XCTAssertTrue(controller.state.confirmed)
  }
  func testMismatchDoesNotRetryWriteOrClaimSuccess() async throws {
    let camera = LookCamera(); camera.mismatch = true; let controller = camera.controller()
    controller.connect(protocolID: CameraProtocol.sony, properties: try camera.properties()); await controller.select(6)
    XCTAssertEqual(controller.state.currentCode, "ST"); XCTAssertFalse(controller.state.confirmed)
    XCTAssertEqual(camera.writes, [6]); XCTAssertTrue(controller.state.message.contains("尚未确认"))
  }
  func testFailureRefreshesActualStateAndReadFailureClearsUnknownValue() async throws {
    let camera = LookCamera(); camera.writeFails = true; let controller = camera.controller()
    controller.connect(protocolID: CameraProtocol.sony, properties: try camera.properties()); await controller.select(6)
    XCTAssertFalse(controller.state.confirmed); XCTAssertEqual(controller.state.currentCode, "ST"); XCTAssertGreaterThanOrEqual(camera.reads, 2)
    camera.readFails = true; await controller.refresh()
    XCTAssertNil(controller.state.currentCode); XCTAssertFalse(controller.state.confirmed); XCTAssertTrue(controller.state.message.contains("读取未完成"))
  }
  func testReadonlyDisabledMalformedUnadvertisedAndUnknownAreRejected() async throws {
    for variant in ["readonly", "disabled", "display-only", "type", "range", "missing", "unknown-current", "unadvertised", "reply-not-advertised"] {
      let camera = LookCamera()
      switch variant {
      case "readonly": camera.writable = false
      case "disabled": camera.enabled = 0
      case "display-only": camera.enabled = 2
      case "type": camera.type = 0xFFFF
      case "range": camera.form = 1
      case "missing": camera.missing = true
      case "unknown-current": camera.current = 256
      case "reply-not-advertised": camera.advertised = false
      default: camera.values = [1, 9]
      }
      let controller = camera.controller(); controller.connect(protocolID: CameraProtocol.sony, properties: try camera.properties())
      await controller.refresh(); await controller.select(6)
      XCTAssertTrue(camera.writes.isEmpty, variant); XCTAssertFalse(controller.state.confirmed, variant)
    }
  }
  func testOnlyTenKnownAdvertisedPresetsHaveChineseLabelsAndUnknownEightAreProtected() async throws {
    let camera = LookCamera(); camera.values = Array(1...10) + Array(256...263)
    let controller = camera.controller(); controller.connect(protocolID: CameraProtocol.sony, properties: try camera.properties()); await controller.refresh()
    XCTAssertEqual(controller.state.choices.count, 10)
    XCTAssertEqual(controller.state.choices.map(\.code), ["ST", "PT", "NT", "VV", "VV2", "FL", "IN", "SH", "BW", "SE"])
    XCTAssertEqual(controller.state.choices.map(\.chinese), ["标准", "人像", "中性", "鲜明", "鲜明2", "胶片", "哑光", "柔和高调", "黑白", "棕褐色"])
    for unknown in 256...263 { await controller.select(Int64(unknown)) }
    XCTAssertTrue(camera.writes.isEmpty)
    let property = try camera.properties()[0]; XCTAssertNil(property.enabled); XCTAssertNil(property.dataType); XCTAssertNil(property.form)
    XCTAssertNotNil(SonyCreativeLookPolicy.rejection(nil, target: 1, diagnostics: nil))
  }
  func testLateOldConnectionReadAndWriteCannotReplaceNewCameraState() async throws {
    for writing in [false, true] {
      let camera = LookCamera(); camera.pauseRead = !writing; camera.pauseWrite = writing
      let controller = camera.controller(); controller.connect(protocolID: CameraProtocol.sony, properties: try camera.properties())
      let old = Task { if writing { await controller.select(6) } else { await controller.refresh() } }
      await fulfillment(of: [camera.suspended], timeout: 3)
      camera.current = 9; controller.connect(protocolID: CameraProtocol.sony, properties: try camera.properties())
      let reads = camera.reads; camera.resume(); await old.value
      XCTAssertEqual(controller.state.currentCode, "BW"); XCTAssertFalse(controller.state.confirmed); XCTAssertFalse(controller.state.busy)
      XCTAssertEqual(camera.reads, reads)
    }
  }
  func testDisconnectClearsStateAndCanonNeverReadsOrWritesSonyProperty() async throws {
    let camera = LookCamera(); let controller = camera.controller()
    controller.connect(protocolID: CameraProtocol.sony, properties: try camera.properties()); controller.disconnect()
    XCTAssertNil(controller.state.currentCode); XCTAssertFalse(controller.state.connected)
    controller.connect(protocolID: CameraProtocol.canon, properties: try camera.properties()); await controller.refresh(); await controller.select(6)
    XCTAssertEqual(camera.reads, 0); XCTAssertTrue(camera.writes.isEmpty); XCTAssertNil(controller.state.currentCode)
  }
  func testSelectionQueuedForPreviousConnectionCannotWriteToNewConnection() async throws {
    let camera = LookCamera(); let controller = camera.controller()
    controller.connect(protocolID: CameraProtocol.sony, properties: try camera.properties())
    let queued = controller.selection(6)
    camera.current = 9; controller.connect(protocolID: CameraProtocol.sony, properties: try camera.properties())
    await controller.select(queued)
    XCTAssertEqual(camera.reads, 0); XCTAssertTrue(camera.writes.isEmpty); XCTAssertEqual(controller.state.currentCode, "BW")
  }
  func testPTPShutterPreservesSubsecondDurationsWithoutChangingSpecialValueHandling() {
    for (raw, expected) in [(Int64(8000), "0.8秒"), (6000, "0.6秒"), (4000, "0.4秒"), (3000, "0.3秒"), (80, "1/125"), (0, "0.0秒"), (10000, "1.0秒")] {
      XCTAssertEqual(ExposureTimeText.standardPTPShutter(raw), expected)
    }
    XCTAssertEqual(ExposureTimeText.seconds(0.8), "0.8 秒")
  }
}
