import Foundation

public enum SonyCreativeLookPreset: Int64, CaseIterable, Sendable {
  case st = 1, pt, nt, vv, vv2, fl, `in`, sh, bw, se
  public var code: String { [1:"ST",2:"PT",3:"NT",4:"VV",5:"VV2",6:"FL",7:"IN",8:"SH",9:"BW",10:"SE"][rawValue]! }
  public var chinese: String { [1:"标准",2:"人像",3:"中性",4:"鲜明",5:"鲜明2",6:"胶片",7:"哑光",8:"柔和高调",9:"黑白",10:"棕褐色"][rawValue]! }
}
public struct CreativeLookDescriptor: Codable, Sendable {
  public let code: Int
  public let dataType: Int?
  public let writable: Bool?
  public let enabled: Int?
  public let form: Int?
  public let current: Int64?
  public let settable: [Int64]?
}
public struct CreativeLookDiagnostics: Codable, Sendable {
  public let candidateCode: Int?
  public let replyCodes: [Int]?
  public let candidateDescriptors: [CreativeLookDescriptor]?
  public var descriptor: CreativeLookDescriptor? { candidateDescriptors?.first { $0.code == SonyCreativeLookPolicy.propertyCode } }
}
public enum SonyCreativeLookPolicy {
  public static let propertyCode = 0xD0FA
  public static func property(_ properties: [CameraProperty]) -> CameraProperty? { properties.first { $0.code == propertyCode } }
  public static func choices(_ property: CameraProperty?, diagnostics: CreativeLookDiagnostics?) -> [SonyCreativeLookPreset] {
    guard let property, property.code == propertyCode else { return [] }
    let presets: [SonyCreativeLookPreset] = property.choices.compactMap { choice -> SonyCreativeLookPreset? in
      guard diagnostics?.descriptor?.settable?.contains(choice.value) != false else { return nil }
      return SonyCreativeLookPreset(rawValue: choice.value)
    }
    return presets.reduce(into: [SonyCreativeLookPreset]()) { values, preset in if !values.contains(preset) { values.append(preset) } }
  }
  public static func rejection(_ property: CameraProperty?, target: Int64, diagnostics: CreativeLookDiagnostics?) -> String? {
    guard let property, property.code == propertyCode else { return "尚未取得现代创意外观信息，请重新读取" }
    guard SonyCreativeLookPreset(rawValue: property.value) != nil else { return "当前为未知或自定义外观，请在机身调整" }
    guard SonyCreativeLookPreset(rawValue: target) != nil else { return "此未知或自定义候选未开放编辑" }
    guard property.writable else { return "当前机身状态仅允许读取，请在机身调整" }
    if let candidate = diagnostics?.candidateCode, candidate != propertyCode { return "现代属性编号尚未确认，本次不发送设置" }
    if let type = property.dataType ?? diagnostics?.descriptor?.dataType, !(1...8).contains(type) { return "属性类型尚未确认，本次不发送设置" }
    if let form = property.form ?? diagnostics?.descriptor?.form, form != 2 { return "机身未提供枚举候选，本次不发送设置" }
    if let enabled = property.enabled ?? diagnostics?.descriptor?.enabled, enabled != 1 { return "当前模式未启用该项，请在机身调整" }
    if diagnostics?.descriptor?.writable == false { return "当前描述符为只读，请在机身调整" }
    if let codes = diagnostics?.replyCodes, !codes.contains(propertyCode) { return "最新属性表未返回该项，请重新读取" }
    if let current = diagnostics?.descriptor?.current, current != property.value { return "机身信息已变化，请重新读取" }
    guard choices(property, diagnostics: diagnostics).contains(where: { $0.rawValue == target }) else { return "此预设不在机身当前可用候选中" }
    return nil
  }
  public static func encodedWrite(properties: [CameraProperty], diagnostics: CreativeLookDiagnostics?, target: Int64, protocolID: String) throws -> String {
    guard protocolID == CameraProtocol.sony else { throw CreativeLookError.rejected("当前通道不允许写入Sony创意外观") }
    if let reason = rejection(property(properties), target: target, diagnostics: diagnostics) { throw CreativeLookError.rejected(reason) }
    return String(decoding: try JSONEncoder().encode(Write(code: propertyCode, value: target)), as: UTF8.self)
  }
  private struct Write: Encodable { let code: Int; let value: Int64 }
}
public enum CreativeLookError: LocalizedError {
  case rejected(String), readbackMismatch
  public var errorDescription: String? {
    switch self { case .rejected(let reason): return reason; case .readbackMismatch: return "机身尚未确认目标外观，已保留实际回读值" }
  }
}
public struct CreativeLookState: Sendable {
  public var connected = false
  public var protocolID = CameraProtocol.sony
  public var property: CameraProperty?
  public var diagnostics: CreativeLookDiagnostics?
  public var busy = false
  public var message = "尚未读取创意外观"
  public var confirmed = false
  public init() {}
  public var currentCode: String? { property.map { SonyCreativeLookPreset(rawValue: $0.value)?.code ?? "未知值 \($0.value)" } }
  public var choices: [SonyCreativeLookPreset] { SonyCreativeLookPolicy.choices(property, diagnostics: diagnostics) }
  public var availability: String? {
    guard connected else { return "相机未连接" }
    guard protocolID == CameraProtocol.sony else { return "当前通道不提供Sony创意外观" }
    guard let property else { return "尚未取得创意外观信息，可重新读取" }
    guard let first = choices.first else { return "尚未取得可编辑的已识别候选，请在机身查看" }
    return SonyCreativeLookPolicy.rejection(property, target: first.rawValue, diagnostics: diagnostics)
  }
}
public struct CreativeLookSelection: Sendable {
  fileprivate let generation: UUID
  fileprivate let target: Int64
}

@MainActor public final class CreativeLookController {
  public private(set) var state = CreativeLookState()
  private let read: () async throws -> [CameraProperty]
  private let diagnose: () async throws -> CreativeLookDiagnostics?
  private let write: (CameraProperty, Int64) async throws -> Void
  private let wait: () async throws -> Void
  private let changed: (CreativeLookState, [CameraProperty]?) -> Void
  private var generation = UUID()
  private var operation: UUID?
  public init(read: @escaping () async throws -> [CameraProperty], diagnose: @escaping () async throws -> CreativeLookDiagnostics? = { nil }, write: @escaping (CameraProperty, Int64) async throws -> Void, wait: @escaping () async throws -> Void = { try await Task.sleep(nanoseconds: 150_000_000) }, changed: @escaping (CreativeLookState, [CameraProperty]?) -> Void = { _, _ in }) {
    self.read = read; self.diagnose = diagnose; self.write = write; self.wait = wait; self.changed = changed
  }
  public func connect(protocolID: String, properties: [CameraProperty]) {
    disconnect(); state.connected = true; state.protocolID = protocolID
    state.property = protocolID == CameraProtocol.sony ? SonyCreativeLookPolicy.property(properties) : nil
    state.message = protocolID == CameraProtocol.sony ? (state.property == nil ? "尚未取得创意外观信息，可重新读取" : "当前值来自机身回读") : "当前只读通道不提供Sony创意外观"
    changed(state, nil)
  }
  public func disconnect() { generation = UUID(); operation = nil; state = CreativeLookState(); changed(state, nil) }
  public func observe(_ properties: [CameraProperty]) {
    guard state.connected, state.protocolID == CameraProtocol.sony, !state.busy else { return }
    apply(properties, diagnostics: nil); state.confirmed = false; state.message = "当前值来自机身回读"; changed(state, nil)
  }
  public func selection(_ target: Int64) -> CreativeLookSelection { CreativeLookSelection(generation: generation, target: target) }
  public func select(_ request: CreativeLookSelection) async {
    guard request.generation == generation else { return }
    await select(request.target)
  }
  public func refresh() async {
    guard state.connected, state.protocolID == CameraProtocol.sony, !state.busy else { return }
    let epoch = generation; let id = UUID(); operation = id; state.busy = true; state.confirmed = false; state.message = "正在读取机身外观"; changed(state, nil)
    do {
      let properties = try await read(); try check(epoch, id)
      let diagnostics = try? await diagnose(); try check(epoch, id)
      apply(properties, diagnostics: diagnostics ?? nil); state.message = state.property == nil ? "尚未取得创意外观信息，请稍后重读或在机身查看" : "当前值来自机身回读"
    } catch { guard valid(epoch, id) else { return }; state.property = nil; state.diagnostics = nil; state.message = "读取未完成，请重新读取" }
    guard valid(epoch, id) else { return }; state.busy = false; operation = nil; changed(state, nil)
  }
  public func select(_ target: Int64) async {
    guard state.connected, state.protocolID == CameraProtocol.sony, !state.busy else { return }
    if let reason = SonyCreativeLookPolicy.rejection(state.property, target: target, diagnostics: state.diagnostics) { state.message = reason; state.confirmed = false; changed(state, nil); return }
    let epoch = generation; let id = UUID(); operation = id; state.busy = true; state.confirmed = false; state.message = "正在请求机身确认"; changed(state, nil)
    do {
      let properties = try await read(); try check(epoch, id)
      let diagnostics = try? await diagnose(); try check(epoch, id)
      apply(properties, diagnostics: diagnostics ?? nil)
      if let reason = SonyCreativeLookPolicy.rejection(state.property, target: target, diagnostics: state.diagnostics) { throw CreativeLookError.rejected(reason) }
      try await write(state.property!, target); try check(epoch, id)
      for attempt in 0..<10 {
        let actual = try await read(); try check(epoch, id); apply(actual, diagnostics: nil)
        if state.property?.value == target { state.confirmed = true; state.message = "机身已确认 " + (state.currentCode ?? ""); break }
        if attempt < 9 { try await wait(); try check(epoch, id) }
      }
      if !state.confirmed { throw CreativeLookError.readbackMismatch }
    } catch {
      guard valid(epoch, id) else { return }
      if let actual = try? await read() { guard valid(epoch, id) else { return }; apply(actual, diagnostics: nil) }
      else { guard valid(epoch, id) else { return }; state.property = nil; state.diagnostics = nil }
      state.confirmed = false; state.message = error.localizedDescription
    }
    guard valid(epoch, id) else { return }; state.busy = false; operation = nil; changed(state, nil)
  }
  private func apply(_ properties: [CameraProperty], diagnostics: CreativeLookDiagnostics?) { state.property = SonyCreativeLookPolicy.property(properties); state.diagnostics = diagnostics; changed(state, properties) }
  private func valid(_ epoch: UUID, _ id: UUID) -> Bool { generation == epoch && operation == id }
  private func check(_ epoch: UUID, _ id: UUID) throws { try Task.checkCancellation(); guard valid(epoch, id) else { throw CancellationError() } }
}
