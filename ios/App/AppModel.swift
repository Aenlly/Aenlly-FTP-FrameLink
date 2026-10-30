import Camera
import CryptoKit
import Foundation
import FrameLinkCore
import UIKit

@MainActor
final class AppModel: ObservableObject {
  let library: LibraryStore
  let vault: CredentialVault
  lazy var connections = ConnectionProfiles(vault: vault)
  private var sessionFTP: [String: FTPProfile] = [:]
  private var currentFTP: FTPProfile?
  private var pendingRememberCredentials = false
  private var pendingBorrowedCredentials = false
  private var credentialResetConfig: CameraConfig?
  let camera = NativeTransport()
  @Published var cameraInfo: CameraInfo?
  @Published var cameraIDs: Set<String> = []
  @Published var cameraHasMore = false
  @Published var cameraLoading = false
  @Published var gallerySource = 0
  @Published var cameraMessage = "连接相机，浏览照片或开启取景"
  @Published var connecting = false
  @Published var needsConnectionSettings = false
  @Published var connectionFailure = ""
  @Published var identityPrompt: CameraIdentity?
  @Published var previousFingerprint = ""
  @Published var notice: String?
  @Published var properties: [CameraProperty] = []
  @Published var liveImage: UIImage?
  @Published var liveSize = ""
  @Published var liveRunning = false
  @Published var receiverRunning = false
  @Published private(set) var receiverSession: ReceiverSettings?
  @Published var receiverAddress = ""
  @Published var ftpEntries: [FTPEntry] = []
  @Published var ftpPath = "/"
  @Published var ftpConnected = false
  @Published var ftpBusy = false
  @Published var queueBusy = false
  @Published var propertyUpdating = false
  private var pendingConfig: CameraConfig?
  private var afterConnect: (() -> Void)?
  private var openSettingsAfterFailure = false
  private var monitor: Task<Void, Never>?
  private var liveTask: Task<Void, Never>?
  private var liveGeneration = UUID()
  private var generation = UUID()
  private var cursor: Int64 = 0
  private var receiver = FLBridgeNewFTPReceiver()!
  private var receiverEvents: NativeReceiveEvents?
  private var ftp = FLBridgeNewFTPClient()!
  private var queueTask: Task<Void, Never>?
  private var activeFTP: FLBridgeFTPClient?

  init() {
    #if DEBUG
      vault = CredentialVault(service: ProcessInfo.processInfo.arguments.contains("--ui-acceptance") ? "app.framelink.qa.credentials" : "app.framelink.credentials")
    #else
      vault = CredentialVault()
    #endif
    let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    #if DEBUG
      let acceptance = ProcessInfo.processInfo.arguments.contains("--ui-acceptance")
      let directory = documents.appendingPathComponent(
        acceptance ? "FrameLink-QA" : "FrameLink", isDirectory: true)
    #else
      let directory = documents.appendingPathComponent("FrameLink", isDirectory: true)
    #endif
    #if DEBUG
      if acceptance && ProcessInfo.processInfo.arguments.contains("--reset-ui-fixtures") {
        try? FileManager.default.removeItem(at: directory)
        for key in ["camera", "ftp", "receiver", "connection-profiles-v1"] { try? vault.erase(key) }
      }
    #endif
    library = LibraryStore(root: directory)
    if let error = library.persistenceError { notice = error }
    recoverFTPReceipts()
    #if DEBUG
      if acceptance && library.photos.isEmpty {
        for i in 1...6 {
          let source = FileManager.default.temporaryDirectory.appendingPathComponent("QA_\(i).jpg")
          let image = UIGraphicsImageRenderer(size: CGSize(width: 320, height: 240)).image {
            context in
            UIColor(hue: CGFloat(i) / 8, saturation: 0.3, brightness: 0.85, alpha: 1).setFill()
            context.fill(CGRect(x: 0, y: 0, width: 320, height: 240))
            ("FrameLink \(i)" as NSString).draw(
              at: CGPoint(x: 30, y: 100),
              withAttributes: [
                .font: UIFont.systemFont(ofSize: 30), .foregroundColor: UIColor.white,
              ])
          }
          if let data = image.jpegData(compressionQuality: 0.85) {
            try? data.write(to: source)
            _ = try? library.importFile(source)
            try? FileManager.default.removeItem(at: source)
          }
        }
      }
      if acceptance && ProcessInfo.processInfo.arguments.contains("--ui-paired-fixture"),
        var photo = library.photos.first(where: { $0.name == "QA_6.jpg" }), photo.files.count == 1
      {
        photo.files.append(CameraFile(fileId: 2, name: "QA_6.ARW", size: 30_000_000))
        try? library.update(photo)
      }
      if acceptance && ProcessInfo.processInfo.arguments.contains("--ui-foundations") {
        do {
          for suffix in ["a", "b"] {
            var config = CameraConfig(); config.host = "qa-\(suffix).local"; config.user = "qa-user-\(suffix)"; config.password = "qa-password-\(suffix)"; config.fingerprint = "fp-\(suffix)"
            try connections.completeCamera(config, identity: config.fingerprint, remember: true)
          }
          if let photo = library.photos.first {
            var complete = TransferTask(title: "QA 已完成任务", kind: "camera-download", photoIds: [photo.id]); complete.state = .complete; try library.upsertTask(complete)
            var pending = TransferTask(title: "QA 待恢复任务", kind: "camera-download"); pending.state = .interrupted; try library.upsertTask(pending)
          }
        } catch { notice = error.localizedDescription }
      }
    #endif
  }
  func savedCamera() -> CameraConfig {
    credentialResetConfig ?? ((try? connections.lastCamera()) ?? CameraConfig())
  }
  func cameraRequiresCredentials() -> Bool { credentialResetConfig != nil || ((try? connections.cameraRequiresCredentials()) ?? true) }
  func savedFTP() -> FTPProfile { (try? connections.lastFTP()) ?? FTPProfile() }
  @discardableResult func reconnectSaved(onReady: @escaping () -> Void = {}) -> Bool {
    if connecting { return true }
    guard cameraInfo == nil else { return false }
    guard !cameraRequiresCredentials() else { return false }
    let saved = savedCamera()
    guard
      !saved.host.isEmpty
        && (saved.user.isEmpty || (!saved.password.isEmpty && !saved.fingerprint.isEmpty))
    else { return false }
    connect(saved, openSettingsOnFailure: true, onReady: onReady)
    return true
  }
  func connect(
    _ config: CameraConfig, openSettingsOnFailure: Bool = false, onReady: (() -> Void)? = nil, rememberCredentials: Bool? = nil
  ) {
    let remember = rememberCredentials ?? ((try? connections.isSaved(config)) ?? false)
    let borrowed = !config.fingerprint.isEmpty || ((try? connections.isSaved(config)) ?? false)
    credentialResetConfig = nil
    disconnect()
    afterConnect = onReady
    openSettingsAfterFailure = openSettingsOnFailure
    connectionFailure = ""
    let token = generation
    connecting = true
    cameraMessage = "正在连接相机"
    Task {
      do {
        var verified = config
        if !config.user.isEmpty {
          let identity = try await camera.identity(config.host)
          guard token == generation else { return }
          let stored = !config.fingerprint.isEmpty ? config.fingerprint : ((try? vault.read("trust:\(config.host)", as: String.self)) ?? "")
          if stored != identity.fingerprint {
            pendingConfig = config
            pendingRememberCredentials = remember
            pendingBorrowedCredentials = borrowed
            previousFingerprint = stored
            identityPrompt = identity
            connecting = false
            cameraMessage = "请确认相机身份"
            return
          }
          verified.fingerprint = identity.fingerprint
        }
        let info = try await camera.connect(verified)
        guard token == generation else { return }
        try connections.completeCamera(verified, identity: verified.fingerprint.isEmpty ? info.id : verified.fingerprint, remember: remember)
        let values = try await camera.properties()
        guard token == generation else { return }
        properties = values
        cameraInfo = info
        cameraMessage = "已连接 \(info.displayName)"
        connecting = false
        let ready = afterConnect
        afterConnect = nil
        ready?()
        monitor = Task {
          while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            if token == self.generation && !self.camera.connected() {
              self.disconnect()
              break
            }
          }
        }
        await loadCamera(reset: true)
      } catch {
        guard token == generation else { return }
        connecting = false
        camera.close()
        cameraMessage = "连接未完成，请检查相机地址、网络与遥控设置"
        if openSettingsOnFailure {
          connectionFailure = "未能直接重连，请检查保存的地址和相机设置"
          needsConnectionSettings = true
        } else {
          notice = error.localizedDescription
        }
      }
    }
  }
  func confirmIdentity() {
    guard let identity = identityPrompt, var config = pendingConfig else { return }
    do {
      try vault.write(identity.fingerprint, name: "trust:\(config.host)")
      config.fingerprint = identity.fingerprint
      identityPrompt = nil
      pendingConfig = nil
      if pendingBorrowedCredentials {
        config.user = ""; config.password = ""; credentialResetConfig = config
        cameraMessage = "相机身份已更换，请重新填写访问账号和密码"
        connectionFailure = cameraMessage; needsConnectionSettings = true
      } else {
        connect(config, openSettingsOnFailure: openSettingsAfterFailure, onReady: afterConnect, rememberCredentials: pendingRememberCredentials)
      }
    } catch { notice = error.localizedDescription }
  }
  func disconnect() {
    afterConnect = nil
    if gallerySource == 1 { library.selection = [] }
    generation = UUID()
    monitor?.cancel()
    monitor = nil
    stopLive()
    camera.close()
    cameraInfo = nil
    cameraIDs = []
    cameraHasMore = false
    cameraLoading = false
    properties = []
    connecting = false
    identityPrompt = nil
    pendingConfig = nil
    cameraMessage = "相机已断开，手机原片已保留"
  }
  func loadCamera(reset: Bool) async {
    guard cameraInfo?.canBrowse == true, !cameraLoading else { return }
    if !reset && !cameraHasMore { return }
    let token = generation
    cameraLoading = true
    defer { if token == generation { cameraLoading = false } }
    if reset {
      cursor = 0
      cameraIDs = []
    }
    do {
      let page = try await camera.page(cursor: cursor)
      guard token == generation else { return }
      try library.mergeCamera(page)
      cameraIDs.formUnion(page.items.map(\.id))
      cursor = page.nextCursor
      cameraHasMore = cursor != 0
    } catch {
      notice = error.localizedDescription
      if !camera.connected() { disconnect() }
    }
  }
  func readAllCameraPhotos() async throws {
    let token = generation
    while cameraLoading { try await Task.sleep(nanoseconds: 50_000_000) }
    while cameraHasMore {
      try Task.checkCancellation()
      guard token == generation, cameraInfo != nil else { throw CocoaError(.userCancelled) }
      let previous = cursor
      await loadCamera(reset: false)
      if previous == cursor && cameraHasMore { throw CocoaError(.fileReadUnknown) }
    }
    guard token == generation, cameraInfo != nil else { throw CocoaError(.userCancelled) }
  }
  func previewURL(_ snapshot: Photo, large: Bool = false) async -> URL? {
    let photo = library.photos.first { $0.id == snapshot.id } ?? snapshot
    if let path = photo.files.first(where: { !$0.isRaw && $0.localPath != nil })?.localPath,
      let url = try? library.fileURL(path), FileManager.default.fileExists(atPath: url.path)
    {
      return url
    }
    if !large, let path = photo.previewPath, let url = try? library.fileURL(path),
      FileManager.default.fileExists(atPath: url.path)
    {
      return url
    }
    let name = SHAKey(photo.id) + (large ? "-large.jpg" : ".jpg")
    let relative = "previews/\(name)"
    guard let target = try? library.fileURL(relative) else { return nil }
    if FileManager.default.fileExists(atPath: target.path) { return target }
    guard cameraInfo != nil, cameraIDs.contains(photo.id), photo.source == .camera,
      let file = photo.files.first(where: { !$0.isRaw }) ?? photo.files.first
    else { return photo.previewPath.flatMap { try? library.fileURL($0) } }
    do {
      try FileManager.default.createDirectory(
        at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
      try await camera.preview(photo, file: file, target: target, large: large)
      if var current = library.photos.first(where: { $0.id == photo.id }) {
        current.previewPath = relative
        try library.update(current)
      }
      return target
    } catch { return nil }
  }
  func startLive() {
    guard cameraInfo?.canLiveView == true else {
      notice = "该连接未提供取景能力，可使用照片浏览或FTP接收"
      return
    }
    stopLive()
    let token = generation
    let liveToken = liveGeneration
    liveTask = Task {
      do {
        try await camera.startLive()
        liveRunning = true
        UIApplication.shared.isIdleTimerDisabled = true
        while !Task.isCancelled && token == generation && liveToken == liveGeneration {
          let frame = try await camera.nextFrame()
          guard liveToken == liveGeneration, let image = UIImage(data: frame) else { continue }
          liveImage = image
          liveSize = "\(Int(image.size.width))×\(Int(image.size.height))"
        }
      } catch {
        if token == generation && liveToken == liveGeneration && !Task.isCancelled {
          notice = "取景已停止，请检查连接后重开"
        }
      }
      if liveToken == liveGeneration {
        liveRunning = false
        UIApplication.shared.isIdleTimerDisabled = receiverRunning
      }
    }
  }
  func stopLive() {
    liveGeneration = UUID()
    liveTask?.cancel()
    liveTask = nil
    camera.stopLive()
    liveRunning = false
    UIApplication.shared.isIdleTimerDisabled = receiverRunning
  }
  func setProperty(_ property: CameraProperty, value: Int64) {
    guard !propertyUpdating, property.writable,
      property.choices.contains(where: { $0.value == value })
    else { return }
    propertyUpdating = true
    Task {
      defer { propertyUpdating = false }
      do {
        try await camera.set(property, value: value)
        for _ in 0..<10 {
          properties = try await camera.properties()
          if properties.first(where: { $0.code == property.code })?.value == value { return }
          try await Task.sleep(nanoseconds: 150_000_000)
        }
        notice = "相机尚未确认该参数，请查看机身状态"
      } catch { notice = error.localizedDescription }
    }
  }
  func capture() {
    Task {
      do {
        try await camera.capture()
        notice = "快门已发送，请在相机列表刷新查看新照片"
      } catch { notice = "快门结果待确认，请先查看机身或照片列表" }
    }
  }
  func importFiles(_ urls: [URL], deleteSources: Bool = false) {
    Task {
      for url in urls {
        let access = url.startAccessingSecurityScopedResource()
        defer {
          if access { url.stopAccessingSecurityScopedResource() }
          if deleteSources { try? FileManager.default.removeItem(at: url) }
        }
        do {
          let root = library.root
          let prepared = try await Task.detached(priority: .userInitiated) {
            try LibraryStore.prepareImport(url, root: root)
          }.value
          _ = try library.commitImport(prepared)
        } catch { notice = error.localizedDescription }
      }
    }
  }
  func enqueueDownloads(ids: Set<String>, raw: Bool, all: Bool = false) {
    do {
      try library.upsertTask(
        TransferTask(
          title: "导入 \(ids.count) 张照片", kind: "camera-download", photoIds: Array(ids),
          payload: ["format": all ? "all" : raw ? "raw" : "jpeg"]))
      runQueue()
    } catch { notice = error.localizedDescription }
  }
  func runQueue() {
    guard !queueBusy else { return }
    queueBusy = true
    queueTask = Task {
      defer { queueBusy = false }
      for original in library.tasks.filter({ $0.state == .queued }) {
        if Task.isCancelled { break }
        var task = original
        task.state = .running
        try? library.upsertTask(task)
        do {
          if task.kind == "camera-download" {
            guard cameraInfo != nil else { throw URLError(.notConnectedToInternet) }
            let format = task.payload["format"] ?? "jpeg"
            task.total = library.photos.filter { task.photoIds.contains($0.id) }.flatMap(\.files)
              .filter {
                $0.localPath == nil && (format == "all" || (format == "raw" ? $0.isRaw : !$0.isRaw))
              }.reduce(Int64(0)) { $0 + $1.size }
            try library.upsertTask(task)
            for id in task.photoIds {
              try Task.checkCancellation()
              guard var photo = library.photos.first(where: { $0.id == id }) else {
                throw LibraryError.missingPhoto
              }
              guard cameraIDs.contains(id) else { throw LibraryError.missingPhoto }
              let format = task.payload["format"] ?? "jpeg"
              guard
                photo.files.contains(where: {
                  format == "all" || (format == "raw" ? $0.isRaw : !$0.isRaw)
                })
              else { throw LibraryError.missingPhoto }
              for i in photo.files.indices
              where photo.files[i].localPath == nil
                && (format == "all"
                  || (format == "raw" ? photo.files[i].isRaw : !photo.files[i].isRaw))
              {
                let file = photo.files[i]
                let relative = "media/camera/\(SHAKey(photo.id))/\(file.fileId)_\(file.name)"
                let target = try library.fileURL(relative)
                try FileManager.default.createDirectory(
                  at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                let progressTask = task
                let baseDone = task.done
                let receipt = try await camera.download(
                  photo, file: file, target: target,
                  update: { done, total in
                    Task { @MainActor in
                      guard
                        self.library.tasks.first(where: { $0.id == progressTask.id })?.state
                          == .running
                      else { return }
                      var progress = progressTask
                      progress.done = baseDone + done
                      progress.total = max(progressTask.total, baseDone + total)
                      try? self.library.upsertTask(progress)
                    }
                  })
                task.done = baseDone + receipt.size
                if var latest = library.photos.first(where: { $0.id == photo.id }),
                  let index = latest.files.firstIndex(where: {
                    $0.fileId == file.fileId && $0.name == file.name && $0.size == file.size
                  })
                {
                  latest.files[index].localPath = relative
                  latest.hidden = false
                  try library.update(latest)
                  photo = latest
                }
              }
            }
          } else if task.kind == "ftp-download" {
            let profile = try ftpProfile(for: task)
            let client = FLBridgeNewFTPClient()!
            activeFTP = client
            defer {
              client.close()
              activeFTP = nil
            }
            try await background { try client.connect(NativeTransport.json(profile)) }
            let name = task.payload["name"] ?? "file"
            let relative = "media/ftp-client/\(task.id)/\(name)"
            let target = try library.fileURL(relative)
            let rawReceipt = try await background {
              try NativeTransport.result {
                client.download(
                  task.payload["remote"]!, target: target.path, expectedSize: task.total,
                  progress: nil, error: $0)
              }
            }
            let receipt = try NativeTransport.decode(rawReceipt, as: FileReceipt.self)
            try library.receive(
              ReceiveReceiptValue(
                id: task.id, name: name, path: receipt.path, size: receipt.size,
                sha256: receipt.sha256
              ).receipt())
          } else if task.kind == "ftp-upload" {
            let client = FLBridgeNewFTPClient()!
            activeFTP = client
            defer {
              client.close()
              activeFTP = nil
            }
            let profile = try ftpProfile(for: task)
            try await background { try client.connect(NativeTransport.json(profile)) }
            try await background {
              try client.upload(
                task.payload["remote"]!, source: task.payload["source"]!, progress: nil)
            }
          }
          task.state = .complete
          task.done = task.total
          try library.upsertTask(task)
        } catch {
          task.state = Task.isCancelled ? .cancelled : .failed
          task.error = error.localizedDescription
          try? library.upsertTask(task)
        }
      }
    }
  }
  func retry(_ task: TransferTask) {
    var next = task
    next.state = .queued
    next.error = ""
    try? library.upsertTask(next)
    runQueue()
  }
  func cancelQueue() {
    queueTask?.cancel()
    camera.cancelTransfer()
    activeFTP?.close()
  }
  private func ftpProfile(for task: TransferTask) throws -> FTPProfile {
    try connections.resolveFTP(task.payload["connection"], session: sessionFTP)
  }
  func forgetFTP(_ id: String) throws {
    try connections.forgetFTP(id); sessionFTP.removeValue(forKey: id)
    if currentFTP?.connectionID == id { ftp.close(); currentFTP = nil; ftpConnected = false; ftpEntries = [] }
  }
  func connectFTP(_ profile: FTPProfile, rememberCredentials: Bool = false) {
    ftpBusy = true
    ftpConnected = false; currentFTP = nil
    do { if !rememberCredentials { try connections.forgetFTP(profile.connectionID) } } catch { notice = error.localizedDescription; ftpBusy = false; return }
    sessionFTP[profile.connectionID] = profile
    Task {
      do {
        ftp.close()
        try await background { try self.ftp.connect(NativeTransport.json(profile)) }
        try connections.completeFTP(profile, remember: rememberCredentials)
        currentFTP = profile
        ftpConnected = true
        await browseFTP("/")
      } catch { notice = error.localizedDescription }
      ftpBusy = false
    }
  }
  func browseFTP(_ path: String) async {
    ftpBusy = true
    defer { ftpBusy = false }
    do {
      let raw = try await background {
        try NativeTransport.result { self.ftp.list(path, error: $0) }
      }
      ftpEntries = try NativeTransport.decode(raw, as: [FTPEntry].self)
      ftpPath = path
    } catch { notice = error.localizedDescription }
  }
  func mkdirFTP(_ name: String) {
    let path = (ftpPath as NSString).appendingPathComponent(name)
    Task {
      do {
        try await background { try self.ftp.makeDirectory(path) }
        await browseFTP(ftpPath)
      } catch { notice = error.localizedDescription }
    }
  }
  func ftpDownload(_ entries: [FTPEntry]) {
    guard let profile = currentFTP else { notice = "请先连接原服务器"; return }
    do {
      for entry in entries where !entry.directory {
        var task = TransferTask(
          title: "FTP下载 · \(entry.name)", kind: "ftp-download",
          payload: ["remote": entry.path, "name": entry.name, "connection": profile.connectionID])
        task.total = entry.size
        try library.upsertTask(task)
      }
      runQueue()
    } catch { notice = error.localizedDescription }
  }
  func ftpUpload(_ urls: [URL]) {
    guard let profile = currentFTP else { notice = "请先连接原服务器"; return }
    do {
      for url in urls {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        let copy = try library.fileURL(
          "media/uploads/\(UUID().uuidString)/\(url.lastPathComponent)")
        try FileManager.default.createDirectory(
          at: copy.deletingLastPathComponent(), withIntermediateDirectories: true)
        try FileManager.default.copyItem(at: url, to: copy)
        try library.upsertTask(
          TransferTask(
            title: "FTP上传 · \(url.lastPathComponent)", kind: "ftp-upload",
            payload: [
              "remote": (ftpPath as NSString).appendingPathComponent(url.lastPathComponent),
              "source": copy.path,
              "connection": profile.connectionID,
            ]))
      }
      runQueue()
    } catch { notice = error.localizedDescription }
  }
  func startReceiver(port: Int, user: String, password: String) {
    do {
      let root = try library.fileURL("media/ftp")
      receiverEvents = NativeReceiveEvents { raw in
        Task { @MainActor in
          do {
            let receipt = try NativeTransport.decode(raw, as: ReceiveReceipt.self)
            try self.recordFTPReceipt(receipt, restoreHidden: true)
          } catch { self.notice = error.localizedDescription }
        }
      }
      try receiver.start(
        "0.0.0.0:\(port)", user: user, password: password, root: root.path, events: receiverEvents)
      receiverRunning = true
      receiverSession = ReceiverSettings(port: port, user: user, password: password)
      receiverAddress = receiver.address()
      UIApplication.shared.isIdleTimerDisabled = true
    } catch { notice = error.localizedDescription }
  }
  func stopReceiver() {
    receiver.stop()
    receiverRunning = false
    receiverSession = nil
    UIApplication.shared.isIdleTimerDisabled = liveRunning
  }
  private func recoverFTPReceipts() {
    guard let root = try? library.fileURL("media/ftp/receipts"),
      let files = try? FileManager.default.contentsOfDirectory(
        at: root, includingPropertiesForKeys: nil)
    else { return }
    for file in files where file.pathExtension == "json" {
      if let data = try? Data(contentsOf: file),
        let receipt = try? JSONDecoder().decode(ReceiveReceipt.self, from: data)
      {
        try? recordFTPReceipt(receipt, restoreHidden: false)
      }
    }
  }
  private func recordFTPReceipt(_ receipt: ReceiveReceipt, restoreHidden: Bool) throws {
    try library.receive(receipt, restoreHidden: restoreHidden)
    var task = TransferTask(title: "FTP接收 · \(receipt.name)", kind: "ftp-receive", photoIds: ["ftp:" + receipt.sha256])
    task.id = "ftp-receive:" + receipt.id; task.state = .complete
    task.total = receipt.size; task.done = receipt.size; task.createdAt = Date(timeIntervalSince1970: Double(receipt.receivedAt) / 1000)
    try library.upsertTask(task)
  }
  private func background<T>(_ work: @escaping () throws -> T) async throws -> T {
    try await withCheckedThrowingContinuation { continuation in
      DispatchQueue.global(qos: .userInitiated).async {
        do { continuation.resume(returning: try work()) } catch {
          continuation.resume(throwing: error)
        }
      }
    }
  }
}

private struct ReceiveReceiptValue: Encodable {
  let id: String
  let name: String
  let path: String
  let size: Int64
  let sha256: String
  var receivedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
  func receipt() throws -> ReceiveReceipt {
    try JSONDecoder().decode(ReceiveReceipt.self, from: JSONEncoder().encode(self))
  }
}
func SHAKey(_ value: String) -> String { importlessSHA(Data(value.utf8)) }
private func importlessSHA(_ value: Data) -> String {
  SHA256.hash(data: value).map { String(format: "%02x", $0) }.joined()
}
