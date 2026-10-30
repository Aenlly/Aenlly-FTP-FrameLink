import XCTest
@testable import FrameLinkCore

private actor SuspendedFujiConnect {
  private var continuation: CheckedContinuation<String, Error>?
  func wait() async throws -> String { try await withCheckedThrowingContinuation { continuation = $0 } }
  var waiting: Bool { continuation != nil }
  func finish(fails: Bool) {
    if fails { continuation?.resume(throwing: CancellationError()) }
    else { continuation?.resume(returning: "old-camera") }
    continuation = nil
  }
}

/// Exercises the production gate used by AppModel for consent, publication and navigation.
@MainActor final class FujiConnectionBoundaryTests: XCTestCase {
  private func cancelledAttempt(fails: Bool, reason: Int) async throws {
    var nativeOpen = true; var closes = 0; var published: String?; var navigations = 0; var errors = 0
    let boundary = FujiConnectionBoundary(cancelConnection: { nativeOpen = false; closes += 1 })
    let page = UUID(); boundary.open(page); boundary.setConsent(true, page: page)
    let token = try XCTUnwrap(boundary.begin(page: page, onReady: { navigations += 1 }))
    let gate = SuspendedFujiConnect()
    let task = Task {
      do { let info = try await gate.wait(); boundary.publish(token) { published = info } }
      catch { boundary.fail(token) { errors += 1 } }
    }
    for _ in 0..<200 { if await gate.waiting { break }; try await Task.sleep(nanoseconds: 1_000_000) }
    let waiting = await gate.waiting; XCTAssertTrue(waiting)
    switch reason {
    case 0: boundary.leave(page)
    case 1: boundary.stop()
    default: boundary.setConsent(false, page: page)
    }
    XCTAssertEqual(closes, 1); XCTAssertFalse(nativeOpen)
    await gate.finish(fails: fails); await task.value
    XCTAssertNil(published); XCTAssertEqual(navigations, 0); XCTAssertEqual(errors, 0)
    XCTAssertFalse(boundary.permits(page))
  }
  func testLeaveStopAndConsentRevocationRejectLateSuccessfulConnect() async throws {
    for reason in 0...2 { try await cancelledAttempt(fails: false, reason: reason) }
  }
  func testLeaveStopAndConsentRevocationSuppressLateConnectError() async throws {
    for reason in 0...2 { try await cancelledAttempt(fails: true, reason: reason) }
  }
  func testSuccessfulPublicationNavigatesAndPageDisappearanceKeepsSession() throws {
    var closes = 0; var published = false; var navigations = 0
    let boundary = FujiConnectionBoundary(cancelConnection: { closes += 1 })
    let page = UUID(); boundary.open(page); boundary.setConsent(true, page: page)
    let token = try XCTUnwrap(boundary.begin(page: page, onReady: {
      XCTAssertTrue(published); navigations += 1
      boundary.leave(page)
    }))
    XCTAssertTrue(boundary.publish(token) { published = true })
    XCTAssertEqual(navigations, 1); XCTAssertEqual(closes, 0)
    XCTAssertFalse(boundary.publish(token) { XCTFail("duplicate connection publication") })
  }
  func testExplicitRestartOnlyPublishesNewAttemptAndItsNavigation() throws {
    var closes = 0; var values: [String] = []; var navigation: [String] = []
    let boundary = FujiConnectionBoundary(cancelConnection: { closes += 1 })
    let page = UUID(); boundary.open(page); boundary.setConsent(true, page: page)
    let old = try XCTUnwrap(boundary.begin(page: page, onReady: { navigation.append("old") }))
    let next = try XCTUnwrap(boundary.begin(page: page, onReady: { navigation.append("new") }))
    XCTAssertEqual(closes, 1)
    XCTAssertFalse(boundary.publish(old) { values.append("old") })
    XCTAssertFalse(boundary.fail(old) { values.append("old-error") })
    XCTAssertTrue(boundary.publish(next) { values.append("new") })
    XCTAssertEqual(values, ["new"]); XCTAssertEqual(navigation, ["new"])
  }
  func testConsentAndPageOwnerAreRecheckedWhenAccepting() throws {
    var closes = 0; let boundary = FujiConnectionBoundary(cancelConnection: { closes += 1 })
    let oldPage = UUID(); boundary.open(oldPage)
    XCTAssertNil(boundary.begin(page: oldPage, onReady: { XCTFail("off page accepted invitation") }))
    boundary.setConsent(true, page: oldPage); boundary.setConsent(false, page: oldPage)
    XCTAssertNil(boundary.begin(page: oldPage, onReady: nil))
    let newPage = UUID(); boundary.open(newPage); boundary.setConsent(true, page: newPage)
    let token = try XCTUnwrap(boundary.begin(page: newPage, onReady: nil))
    boundary.leave(oldPage); boundary.setConsent(false, page: oldPage)
    XCTAssertNil(boundary.begin(page: oldPage, onReady: nil)); XCTAssertEqual(closes, 0)
    XCTAssertTrue(boundary.publish(token) {})
  }
  func testActualSessionCloseInvalidatesPendingCallbackWithoutRecursiveClose() throws {
    var closes = 0; var navigations = 0
    let boundary = FujiConnectionBoundary(cancelConnection: { closes += 1 })
    let page = UUID(); boundary.open(page); boundary.setConsent(true, page: page)
    let token = try XCTUnwrap(boundary.begin(page: page, onReady: { navigations += 1 }))
    boundary.sessionClosed()
    XCTAssertFalse(boundary.publish(token) {}); XCTAssertFalse(boundary.fail(token) {})
    boundary.leave(page); XCTAssertEqual(closes, 0); XCTAssertEqual(navigations, 0)
  }
}
