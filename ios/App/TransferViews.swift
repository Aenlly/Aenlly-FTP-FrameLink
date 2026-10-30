import Darwin
import FrameLinkCore
import Network
import SwiftUI
import UniformTypeIdentifiers

struct TransfersView: View {
  @EnvironmentObject var library: LibraryStore
  @EnvironmentObject var model: AppModel
  @State private var clearing: Set<String> = []
  var body: some View {
    List {
      Section {
        NavigationLink {
          FTPReceiveView()
        } label: {
          Label("FTP 接收", systemImage: "wifi")
        }
        NavigationLink {
          FTPClientView()
        } label: {
          Label("FTP 客户端", systemImage: "arrow.left.arrow.right")
        }
      }
      Section("传输任务") {
        if library.tasks.isEmpty {
          ContentUnavailableView(
            "暂无传输", systemImage: "arrow.down.circle", description: Text("相机导入和FTP收发会显示在这里。"))
        }
        ForEach(library.tasks) { task in
          VStack(alignment: .leading, spacing: 9) {
            HStack {
              Text(task.title).font(.subheadline).lineLimit(2)
              Spacer()
              Text(stateName(task.state)).font(.caption).foregroundStyle(
                task.state == .failed ? .red : .secondary)
            }
            if task.state == .running {
              ProgressView(value: task.total > 0 ? Double(task.done) / Double(task.total) : 0)
            }
            if !task.error.isEmpty { Text(task.error).font(.caption).foregroundStyle(.secondary) }
            HStack {
              Text(
                ByteCountFormatter.string(fromByteCount: task.done, countStyle: .file)
                  + (task.total > 0
                    ? " / "
                      + ByteCountFormatter.string(fromByteCount: task.total, countStyle: .file)
                    : "")
              ).font(.caption).foregroundStyle(.secondary)
              Spacer()
              if [.failed, .interrupted, .cancelled].contains(task.state) {
                Button("重试") { model.retry(task) }.font(.caption)
              }
            }
          }.padding(.vertical, 6)
            .swipeActions { if task.state.canClearHistory { Button("删除记录", role: .destructive) { clearing = [task.id] } } }
        }
      }
    }.navigationTitle("传输").navigationBarTitleDisplayMode(.inline).toolbar {
      Menu {
        NavigationLink("已保存文件") { SavedTransferFilesView() }
        Button("清除已结束记录") { clearing = Set(library.tasks.filter { $0.state.canClearHistory }.map(\.id)) }
          .disabled(!library.tasks.contains(where: { $0.state.canClearHistory }))
      } label: { Image(systemName: "ellipsis.circle").accessibilityLabel("传输更多操作") }
      if model.queueBusy {
        Button("取消当前任务") { model.cancelQueue() }
      } else if library.tasks.contains(where: { $0.state == .queued }) {
        Button("继续队列") { model.runQueue() }
      }
    }.confirmationDialog("清除 \(clearing.count) 条已结束记录？", isPresented: Binding(get: { !clearing.isEmpty }, set: { if !$0 { clearing = [] } }), titleVisibility: .visible) {
      Button("清除记录", role: .destructive) {
        do { let count = try library.clearTaskHistory(clearing); model.notice = "已清除 \(count) 条记录" } catch { model.notice = error.localizedDescription }
        clearing = []
      }
      Button("取消", role: .cancel) { clearing = [] }
    } message: { Text("仅清除已完成和已取消的历史。照片、缓存、选片和待处理任务保留，文件仍可从已保存文件访问。") }
  }
  private func stateName(_ state: TransferState) -> String {
    switch state {
    case .queued: return "等待中"
    case .running: return "传输中"
    case .complete: return "已完成"
    case .failed: return "失败"
    case .interrupted: return "已中断"
    case .cancelled: return "已取消"
    }
  }
}

private struct SavedTransferFilesView: View {
  @EnvironmentObject var library: LibraryStore
  var body: some View {
    List {
      if library.savedFiles.isEmpty { Text("清除传输记录后，文件入口会保留在这里。").foregroundStyle(.secondary) }
      ForEach(library.savedFiles) { file in
        if let url = try? library.fileURL(file.path), (try? url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true {
          ShareLink(item: url) { Label(file.name, systemImage: "doc") }
        } else { Label(file.name + " · 文件已不在本机", systemImage: "doc.badge.ellipsis") }
      }
    }.navigationTitle("已保存文件")
  }
}

struct FTPReceiveView: View {
  @EnvironmentObject var model: AppModel
  @State private var port = 2121
  @State private var user = "camera"
  @State private var password = ""
  @State private var addresses: [String] = []
  @State private var profiles: [SavedConnection<ReceiverSettings>] = []
  @State private var selectedProfile = ""
  @State private var rememberCredentials = false
  var body: some View {
    Form {
      Section {
        Label(
          model.receiverRunning ? "正在接收相机文件" : "FTP 接收未启动",
          systemImage: model.receiverRunning ? "wifi" : "wifi.slash"
        ).foregroundStyle(Palette.accent)
        Text("让相机向手机发送照片，适用于支持FTP上传的机型。手机和相机需在同一网络。").font(.subheadline)
      }
      Section("接收设置") {
        ForEach(profiles) { record in
          HStack {
            Button(record.config.user + " · " + String(record.config.port)) { port = record.config.port; user = record.config.user; password = record.config.password; selectedProfile = record.id; rememberCredentials = true }
            Spacer()
            Button { do { try model.connections.forgetReceiver(record.id); profiles = try model.connections.receiverProfiles(); if selectedProfile == record.id { selectedProfile = ""; user = "camera"; password = ""; rememberCredentials = false } } catch { model.notice = error.localizedDescription } } label: { Image(systemName: "xmark.circle").accessibilityLabel("忘记接收配置 " + record.config.user) }
          }
        }
        TextField("账号", text: $user).textInputAutocapitalization(.never).autocorrectionDisabled()
        PasswordField(title: "至少8位密码", text: $password)
        TextField("端口", value: $port, format: .number).keyboardType(.numberPad)
        Toggle("保存此连接的账号和密码", isOn: Binding(get: { rememberCredentials }, set: { value in
          if !value && !selectedProfile.isEmpty { do { try model.connections.forgetReceiver(selectedProfile); profiles = try model.connections.receiverProfiles(); selectedProfile = "" } catch { model.notice = error.localizedDescription; return } }; rememberCredentials = value
        }))
      }.disabled(model.receiverRunning)
      if model.receiverRunning {
        Section("填写到相机的FTP设置") {
          ForEach(addresses, id: \.self) { address in
            LabeledContent("服务器地址", value: address).textSelection(.enabled)
          }
          LabeledContent("端口", value: String(port))
          LabeledContent("账号", value: user)
          LabeledContent("传输方式", value: "被动模式 / 二进制")
          Text("照片保存后自动进入图库，重名文件会分别保留。").font(.caption)
        }
      }
      Section {
        Button(
          model.receiverRunning ? "停止接收" : "开始接收", role: model.receiverRunning ? .destructive : nil
        ) {
          if model.receiverRunning {
            model.stopReceiver()
          } else {
            addresses = localIPv4()
            do {
              try model.connections.completeReceiver(ReceiverSettings(port: port, user: user, password: password), remember: rememberCredentials)
              model.startReceiver(port: port, user: user, password: password)
            } catch { model.notice = error.localizedDescription }
          }
        }.disabled(
          !model.receiverRunning
            && (password.count < 8 || user.isEmpty || !(1024...65535).contains(port)))
      } footer: {
        Text("保持App在前台。切到后台后iOS会暂停接收，返回后请重新启动。此接收通道使用局域网FTP，服务器和相机之间不提供TLS加密。")
      }
    }.navigationTitle("FTP 接收").navigationBarTitleDisplayMode(.inline).onAppear {
      if let saved = model.receiverSession ?? (try? model.connections.lastReceiver()) {
        port = saved.port
        user = saved.user
        password = saved.password
      }
      profiles = (try? model.connections.receiverProfiles()) ?? []
      selectedProfile = profiles.first { $0.config.port == port && $0.config.user == user && $0.config.password == password }?.id ?? ""
      rememberCredentials = !selectedProfile.isEmpty
      addresses = localIPv4()
    }
  }
}

private func localIPv4() -> [String] {
  var head: UnsafeMutablePointer<ifaddrs>?
  guard getifaddrs(&head) == 0 else { return [] }
  defer { freeifaddrs(head) }
  var values: [String] = []
  var next = head
  while let node = next {
    defer { next = node.pointee.ifa_next }
    guard let addr = node.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
      String(cString: node.pointee.ifa_name).hasPrefix("en")
    else { continue }
    var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
    if getnameinfo(
      addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
      == 0
    {
      values.append(String(cString: host))
    }
  }
  return values
}

struct FTPClientView: View {
  @EnvironmentObject var model: AppModel
  @State private var profile = FTPProfile()
  @State private var settings = true
  @State private var selection: Set<String> = []
  @State private var importing = false
  @State private var folder = false
  @State private var folderName = ""
  @State private var profiles: [SavedConnection<FTPProfile>] = []
  @State private var selectedProfile = ""
  @State private var rememberCredentials = false
  var body: some View {
    List {
      Section {
        DisclosureGroup("连接设置", isExpanded: $settings) {
          ForEach(profiles) { record in
            HStack {
              Button(record.config.host + ":" + String(record.config.port) + " · " + record.config.user) { profile = record.config; selectedProfile = record.id; rememberCredentials = true }
              Spacer()
              Button { do { try model.forgetFTP(record.id); profiles = try model.connections.ftpProfiles(); if selectedProfile == record.id { selectedProfile = ""; profile.user = ""; profile.password = ""; rememberCredentials = false } } catch { model.notice = error.localizedDescription } } label: { Image(systemName: "xmark.circle").accessibilityLabel("忘记FTP连接 " + record.config.host) }
            }
          }.disabled(model.ftpBusy)
          TextField("服务器地址", text: $profile.host).textInputAutocapitalization(.never)
            .autocorrectionDisabled()
          TextField("端口", value: $profile.port, format: .number).keyboardType(.numberPad)
          TextField("用户名", text: $profile.user).textInputAutocapitalization(.never)
            .autocorrectionDisabled()
          PasswordField(title: "密码", text: $profile.password)
          Toggle("使用显式FTPS", isOn: $profile.tls)
          Toggle("保存此连接的账号和密码", isOn: Binding(get: { rememberCredentials }, set: { value in
            if !value && !selectedProfile.isEmpty { do { try model.forgetFTP(selectedProfile); profiles = try model.connections.ftpProfiles(); selectedProfile = "" } catch { model.notice = error.localizedDescription; return } }; rememberCredentials = value
          })).disabled(model.ftpBusy)
          Text("FTPS会验证服务器证书与主机名。不支持SFTP或隐式FTPS。").font(.caption).foregroundStyle(.secondary)
          Button(model.ftpBusy ? "连接中" : "连接服务器") {
            model.connectFTP(profile, rememberCredentials: rememberCredentials)
            settings = false
          }.disabled(profile.host.isEmpty || model.ftpBusy)
        }
      }
      if model.ftpConnected {
        Section {
          HStack {
            Text(model.ftpPath).font(.caption).lineLimit(2)
            Spacer()
            Button {
              Task {
                await model.browseFTP(
                  (model.ftpPath as NSString).deletingLastPathComponent.isEmpty
                    ? "/" : (model.ftpPath as NSString).deletingLastPathComponent)
              }
            } label: {
              Image(systemName: "arrow.up")
            }.disabled(model.ftpPath == "/")
          }
          ForEach(model.ftpEntries) { entry in
            Button {
              if entry.directory {
                selection = []
                Task { await model.browseFTP(entry.path) }
              } else {
                selection.formSymmetricDifference([entry.id])
              }
            } label: {
              HStack {
                Image(systemName: entry.directory ? "folder" : "doc").foregroundStyle(
                  Palette.accent)
                VStack(alignment: .leading, spacing: 5) {
                  Text(entry.name).foregroundStyle(.primary)
                  if !entry.directory {
                    Text(ByteCountFormatter.string(fromByteCount: entry.size, countStyle: .file))
                      .font(.caption).foregroundStyle(.secondary)
                  }
                }
                Spacer()
                Image(
                  systemName: entry.directory
                    ? "chevron.right"
                    : selection.contains(entry.id) ? "checkmark.circle.fill" : "circle"
                ).foregroundStyle(Palette.accent)
              }
            }
          }
        } footer: {
          Text("\(model.ftpEntries.count) 项 · 上传同名文件时会停止，避免覆盖服务器原文件。")
        }
      }
    }.navigationTitle("FTP 客户端").navigationBarTitleDisplayMode(.inline)
      .toolbar {
        if model.ftpConnected {
          Button {
            Task { await model.browseFTP(model.ftpPath) }
          } label: {
            Image(systemName: "arrow.clockwise")
          }
        }
      }
      .safeAreaInset(edge: .bottom) {
        if model.ftpConnected {
          GlassCapsule {
            HStack(spacing: 22) {
              Button("上传文件") { importing = true }
              Button("新建文件夹") { folder = true }
              Spacer()
              Button("下载 \(selection.count)") {
                model.ftpDownload(model.ftpEntries.filter { selection.contains($0.id) })
                selection = []
              }.disabled(selection.isEmpty)
            }
          }.padding(12)
        }
      }
      .fileImporter(
        isPresented: $importing, allowedContentTypes: [.item], allowsMultipleSelection: true
      ) { if case .success(let urls) = $0 { model.ftpUpload(urls) } }
      .alert("新建文件夹", isPresented: $folder) {
        TextField("文件夹名称", text: $folderName)
        Button("创建") {
          model.mkdirFTP(folderName)
          folderName = ""
        }.disabled(
          folderName.isEmpty || folderName.contains("/") || folderName == ".." || folderName == ".")
        Button("取消", role: .cancel) {}
      }
      .onAppear {
        profile = model.savedFTP()
        profiles = (try? model.connections.ftpProfiles()) ?? []
        selectedProfile = profiles.first { $0.id == profile.connectionID }?.id ?? ""
        rememberCredentials = !selectedProfile.isEmpty
        settings = !model.ftpConnected
      }
      .onChange(of: profile.host) { _, _ in resetEndpointIfChanged() }
      .onChange(of: profile.port) { _, _ in resetEndpointIfChanged() }
      .onChange(of: profile.tls) { _, _ in resetEndpointIfChanged() }
      .onChange(of: profile.user) { _, _ in
        if let record = profiles.first(where: { $0.id == selectedProfile }), record.config.user != profile.user { selectedProfile = ""; rememberCredentials = false; profile.password = "" }
      }
  }
  private func resetEndpointIfChanged() {
    guard let record = profiles.first(where: { $0.id == selectedProfile }), record.config.host != profile.host || record.config.port != profile.port || record.config.tls != profile.tls else { return }
    selectedProfile = ""; rememberCredentials = false; profile.user = ""; profile.password = ""
  }
}
