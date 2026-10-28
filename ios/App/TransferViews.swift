import Darwin
import FrameLinkCore
import Network
import SwiftUI
import UniformTypeIdentifiers

struct TransfersView: View {
  @EnvironmentObject var library: LibraryStore
  @EnvironmentObject var model: AppModel
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
        }
      }
    }.navigationTitle("传输").navigationBarTitleDisplayMode(.inline).toolbar {
      if model.queueBusy {
        Button("取消当前任务") { model.cancelQueue() }
      } else if library.tasks.contains(where: { $0.state == .queued }) {
        Button("继续队列") { model.runQueue() }
      }
    }
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

struct FTPReceiveView: View {
  @EnvironmentObject var model: AppModel
  @State private var port = 2121
  @State private var user = "camera"
  @State private var password = ""
  @State private var addresses: [String] = []
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
        TextField("账号", text: $user).textInputAutocapitalization(.never).autocorrectionDisabled()
        PasswordField(title: "至少8位密码", text: $password)
        TextField("端口", value: $port, format: .number).keyboardType(.numberPad)
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
              try model.vault.write(
                ReceiverSettings(port: port, user: user, password: password), name: "receiver")
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
      if let saved = try? model.vault.read("receiver", as: ReceiverSettings.self) {
        port = saved.port
        user = saved.user
        password = saved.password
      }
      addresses = localIPv4()
    }
  }
}
private struct ReceiverSettings: Codable {
  let port: Int
  let user: String
  let password: String
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
  var body: some View {
    List {
      Section {
        DisclosureGroup("连接设置", isExpanded: $settings) {
          TextField("服务器地址", text: $profile.host).textInputAutocapitalization(.never)
            .autocorrectionDisabled()
          TextField("端口", value: $profile.port, format: .number).keyboardType(.numberPad)
          TextField("用户名", text: $profile.user).textInputAutocapitalization(.never)
            .autocorrectionDisabled()
          PasswordField(title: "密码", text: $profile.password)
          Toggle("使用显式FTPS", isOn: $profile.tls)
          Text("FTPS会验证服务器证书与主机名。不支持SFTP或隐式FTPS。").font(.caption).foregroundStyle(.secondary)
          Button(model.ftpBusy ? "连接中" : "连接服务器") {
            model.connectFTP(profile)
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
        settings = !model.ftpConnected
      }
  }
}
