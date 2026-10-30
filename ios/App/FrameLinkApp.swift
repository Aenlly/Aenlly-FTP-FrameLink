import FrameLinkCore
import SwiftUI

@main
struct FrameLinkApp: App {
  @StateObject private var model = AppModel()
  @Environment(\.scenePhase) private var phase
  var body: some Scene {
    WindowGroup {
      RootView().environmentObject(model).environmentObject(model.library)
        .tint(Palette.accent)
        .onChange(of: phase) { _, value in
          // iOS suspends listeners in the background; never present a stale service as available.
          if value == .background {
            model.stopLive()
            model.stopReceiver()
            model.cancelQueue()
            model.disconnect()
          }
        }
    }
  }
}

enum Palette {
  static let background = Color(
    uiColor: UIColor { traits in
      traits.userInterfaceStyle == .dark
        ? .systemGroupedBackground
        : UIColor(red: 245 / 255, green: 245 / 255, blue: 247 / 255, alpha: 1)
    })
  static let accent = Color(red: 53 / 255, green: 102 / 255, blue: 136 / 255)
  static let ink = Color.primary
}

struct GlassCard<Content: View>: View {
  @ViewBuilder var content: Content
  var body: some View {
    content.padding(18).background(
      Color(uiColor: .secondarySystemGroupedBackground),
      in: RoundedRectangle(cornerRadius: 26, style: .continuous)
    )
    .overlay(
      RoundedRectangle(cornerRadius: 26, style: .continuous).stroke(
        .white.opacity(0.8), lineWidth: 1)
    )
    .shadow(color: .black.opacity(0.025), radius: 14, y: 5)
  }
}

struct GlassCapsule<Content: View>: View {
  @ViewBuilder var content: Content
  var body: some View {
    #if compiler(>=6.2)
      if #available(iOS 26.0, *) {
        content.padding(12).glassEffect(.regular, in: .capsule)
      } else {
        content.padding(12).background(.ultraThinMaterial, in: Capsule())
          .overlay(Capsule().stroke(.white.opacity(0.5), lineWidth: 0.7))
      }
    #else
      content.padding(12).background(.ultraThinMaterial, in: Capsule())
        .overlay(Capsule().stroke(.white.opacity(0.5), lineWidth: 0.7))
    #endif
  }
}

struct RootView: View {
  @EnvironmentObject var model: AppModel
  @State private var tab = 0
  @State private var connect = false
  var body: some View {
    TabView(selection: $tab) {
      NavigationStack {
        CameraHomeView(
          goGallery: {
            model.gallerySource = model.cameraInfo == nil ? 0 : 1
            model.library.selection = []
            tab = 1
          }, connect: $connect)
      }.tabItem {
        Label("相机", systemImage: "camera")
      }.tag(0)
      NavigationStack { GalleryView(connect: $connect) }.tabItem {
        Label("图库", systemImage: "photo.on.rectangle")
      }.tag(1)
      NavigationStack { ProjectsView() }.tabItem { Label("项目", systemImage: "folder") }.tag(2)
      NavigationStack { TransfersView() }.tabItem {
        Label("传输", systemImage: "arrow.left.arrow.right")
      }.tag(3)
    }
    .sheet(isPresented: $connect) { ConnectCameraView() }
    .onChange(of: model.needsConnectionSettings) { _, needed in
      if needed {
        model.needsConnectionSettings = false
        connect = true
      }
    }
    .alert(
      "确认相机身份",
      isPresented: Binding(
        get: { model.identityPrompt != nil }, set: { if !$0 { model.identityPrompt = nil } })
    ) {
      Button("信任并连接") { model.confirmIdentity() }
      Button("取消", role: .cancel) { model.disconnect() }
    } message: {
      Text(
        (model.previousFingerprint.isEmpty
          ? "首次连接，请核对相机显示的SHA256指纹。确认后会记住这台相机。\n\n" : "该地址的相机身份已变化，请确认后继续。\n\n")
          + (model.identityPrompt?.fingerprint ?? ""))
    }
    .alert(
      "提示",
      isPresented: Binding(get: { model.notice != nil }, set: { if !$0 { model.notice = nil } })
    ) {
      Button("好") { model.notice = nil }
    } message: {
      Text(model.notice ?? "")
    }
  }
}

struct CameraHomeView: View {
  @EnvironmentObject var model: AppModel
  @EnvironmentObject var library: LibraryStore
  var goGallery: () -> Void
  @Binding var connect: Bool
  @State private var live = false
  var body: some View {
    ScrollView {
      VStack(spacing: 16) {
        GlassCard {
          HStack(spacing: 14) {
            Button {
              connect = true
            } label: {
              Image(systemName: "camera").font(.system(size: 30)).foregroundStyle(Palette.accent)
            }.accessibilityLabel("相机连接设置")
            VStack(alignment: .leading, spacing: 5) {
              Text(model.cameraInfo?.displayName ?? "连接你的相机").font(.headline)
              Text(model.cameraMessage).font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 4)
            Button(model.connecting ? "连接中" : model.cameraInfo == nil ? "连接" : "管理") {
              if !model.reconnectSaved() { connect = true }
            }.disabled(model.connecting)
          }
          if model.cameraInfo != nil {
            Divider().padding(.vertical, 10)
            Button("断开相机", role: .destructive) { model.disconnect() }.font(.caption)
          }
        }
        HStack(spacing: 16) {
          HomeAction(
            title: "选片下载", subtitle: "先看照片，按需导入", icon: "photo.stack", primary: true,
            action: goGallery)
          HomeAction(
            title: "取景拍摄", subtitle: model.cameraProtocolID == CameraProtocol.canon ? "CCAPI实验通道 · 当前只读" : "画面优先，轻松遥控", icon: "viewfinder",
            action: {
              if model.cameraInfo != nil {
                live = model.cameraClientCapabilities.canLiveView
              } else if !model.reconnectSaved(onReady: { live = model.cameraClientCapabilities.canLiveView }) {
                connect = true
              }
            }).disabled(model.cameraInfo != nil && !model.cameraClientCapabilities.canLiveView)
        }
        GlassCard {
          VStack(spacing: 16) {
            NavigationLink {
              FTPReceiveView()
            } label: {
              Label("FTP 接收", systemImage: "wifi")
              Spacer()
              Text("边拍边传").foregroundStyle(.secondary)
              Image(systemName: "chevron.right").font(.caption)
            }
            Divider()
            NavigationLink {
              FTPClientView()
            } label: {
              Label("FTP 客户端", systemImage: "arrow.left.arrow.right")
              Spacer()
              Text("双向传输").foregroundStyle(.secondary)
              Image(systemName: "chevron.right").font(.caption)
            }
          }.font(.subheadline).foregroundStyle(Palette.ink)
        }
        HStack {
          Text("最近照片").font(.title3.bold())
          Spacer()
          Text("\(library.importedPhotos.count) 张已导入").font(.caption).foregroundStyle(.secondary)
        }.padding(.top, 8)
        if library.importedPhotos.isEmpty {
          ContentUnavailableView(
            "从第一张照片开始", systemImage: "photo", description: Text("浏览相机、使用FTP接收，或在图库导入手机文件。"))
        } else {
          ScrollView(.horizontal) {
            HStack(spacing: 12) {
              ForEach(library.importedPhotos.prefix(8)) { photo in
                PhotoThumbnail(photo: photo).frame(width: 136, height: 136).clipShape(
                  RoundedRectangle(cornerRadius: 22))
              }
            }
          }.scrollIndicators(.hidden)
        }
        Text("索尼无线连接按相机能力提供浏览与遥控。其他品牌可使用FTP接收或本地导入，具体取决于机型支持。")
          .font(.caption).foregroundStyle(.secondary).frame(
            maxWidth: .infinity, alignment: .leading)
      }.padding(16)
    }.background(Palette.background).navigationTitle("光屿").navigationBarTitleDisplayMode(.inline)
      .toolbar {
        NavigationLink {
          SettingsView()
        } label: {
          Image(systemName: "gearshape")
        }.accessibilityLabel("设置与帮助")
      }
      .fullScreenCover(isPresented: $live) {
        LiveCameraView(onGallery: {
          live = false
          goGallery()
        })
      }
  }
}

struct HomeAction: View {
  let title: String
  let subtitle: String
  let icon: String
  var primary = false
  var action: () -> Void
  var body: some View {
    Button(action: action) {
      VStack(alignment: .leading, spacing: 12) {
        Image(systemName: icon).font(.title2)
        Text(title).font(.headline)
        Text(subtitle).font(.caption).opacity(0.72)
      }.frame(maxWidth: .infinity, minHeight: 116, alignment: .leading).padding(18).foregroundStyle(
        primary ? .white : Palette.ink
      ).background(
        primary ? Palette.accent : Color(uiColor: .secondarySystemGroupedBackground),
        in: RoundedRectangle(cornerRadius: 26, style: .continuous))
    }.buttonStyle(.plain)
  }
}

struct PasswordField: View {
  let title: String
  @Binding var text: String
  @State private var visible = false
  var body: some View {
    HStack {
      Group {
        if visible { TextField(title, text: $text) } else { SecureField(title, text: $text) }
      }.textInputAutocapitalization(.never).autocorrectionDisabled()
      Button {
        visible.toggle()
      } label: {
        Image(systemName: visible ? "eye.slash" : "eye")
      }.accessibilityLabel(visible ? "隐藏密码" : "显示密码")
    }
  }
}

struct ConnectCameraView: View {
  @EnvironmentObject var model: AppModel
  @Environment(\.dismiss) private var dismiss
  @State private var config = CameraConfig()
  @State private var secure = true
  @State private var profiles: [SavedConnection<CameraConfig>] = []
  @State private var selectedProfile = ""
  @State private var rememberCredentials = false
  var body: some View {
    NavigationStack {
      Form {
        if !profiles.isEmpty {
          Section("已保存的连接") {
            ForEach(profiles) { record in
              HStack {
                Button(record.config.host + ":" + String(record.config.port) + " · " + record.config.user) { config = record.config; selectedProfile = record.id; rememberCredentials = true; secure = !config.user.isEmpty }
                Text(CameraProtocol.title(record.config.protocolID)).font(.caption2).foregroundStyle(.secondary)
                Spacer()
                Button { do { try model.connections.forgetCamera(record.id); profiles = try model.connections.cameras(); if selectedProfile == record.id { selectedProfile = ""; config.user = ""; config.password = ""; rememberCredentials = false } } catch { model.notice = error.localizedDescription } } label: { Image(systemName: "xmark.circle").accessibilityLabel("忘记连接 " + record.config.host) }
              }
            }
          }.disabled(model.connecting || model.identityPrompt != nil)
        }
        Section("连接通道") {
          Picker("连接协议", selection: Binding(get: { config.protocolID }, set: { value in
            config = ConnectionFormEditing.cameraProtocol(config, protocolID: value)
            selectedProfile = ""; rememberCredentials = false; secure = value == CameraProtocol.sony
          })) {
            Text("Sony网络").tag(CameraProtocol.sony)
            Text("Canon CCAPI（实验）").tag(CameraProtocol.canon)
            if !CameraProtocol.supported(config.protocolID) { Text("未识别协议，请重新选择").tag(config.protocolID) }
          }.disabled(model.connecting || model.identityPrompt != nil)
          Text(config.protocolID == CameraProtocol.canon ? "在机身启用CCAPI，手机与相机加入同一网络。当前仅开放浏览、预览与原片导入，EOS850D实机仍待验。" : config.protocolID == CameraProtocol.sony ? "手机和相机接入同一个Wi-Fi，在相机开启电脑遥控或相应的无线控制功能。" : "此协议未接入，请重新选择；已有配置保留。").font(.subheadline)
        }
        if !model.connectionFailure.isEmpty {
          Section { Text(model.connectionFailure).font(.footnote).foregroundStyle(.red) }
        }
        Section("相机地址") {
          TextField("例如 192.168.1.20", text: Binding(get: { config.host }, set: { editEndpoint(host: $0, port: config.port) })).textInputAutocapitalization(.never)
            .autocorrectionDisabled().keyboardType(.URL)
          TextField("端口", value: Binding(get: { config.port }, set: { editEndpoint(host: config.host, port: $0) }), format: .number).keyboardType(.numberPad)
        }
        Section {
          Toggle("相机要求访问认证", isOn: $secure)
          if secure {
            TextField("相机用户名", text: Binding(get: { config.user }, set: { user in
              guard config.user != user else { return }
              config = ConnectionFormEditing.cameraAccount(config, user: user)
              selectedProfile = ""; rememberCredentials = false
            })).textInputAutocapitalization(.never)
              .autocorrectionDisabled()
            PasswordField(title: "相机密码", text: $config.password)
          }
        } footer: {
          Text(config.protocolID == CameraProtocol.canon ? "按机身设置填写HTTP认证账号密码；不使用索尼SSH指纹。未勾选保存时仅用于本次连接。" : "首次读取相机身份后确认。未勾选保存时，账号密码仅用于本次连接。")
        }
        Toggle("保存此连接的账号和密码", isOn: Binding(get: { rememberCredentials }, set: { value in
          if !value && !selectedProfile.isEmpty { do { try model.connections.forgetCamera(selectedProfile); profiles = try model.connections.cameras(); selectedProfile = "" } catch { model.notice = error.localizedDescription; return } }
          rememberCredentials = value
        })).disabled(model.connecting || model.identityPrompt != nil)
        Section {
          Button("连接相机") {
            if !secure {
              config.user = ""
              config.password = ""
            }
            model.connect(config, rememberCredentials: rememberCredentials)
            dismiss()
          }.disabled(
            config.host.trimmingCharacters(in: .whitespaces).isEmpty
              || !CameraProtocol.supported(config.protocolID)
              || !(1...65535).contains(config.port)
              || (secure
                && (config.user.trimmingCharacters(in: .whitespaces).isEmpty
                  || config.password.isEmpty))
          )
        }
        Section {
          Text("CCAPI依机型、固件和机身启用条件而定，不代表所有佳能相机均可连接。也可使用FTP接收或本地导入。").font(.caption).foregroundStyle(
            .secondary)
        }
      }.navigationTitle("连接相机").navigationBarTitleDisplayMode(.inline).toolbar {
        ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
        ToolbarItemGroup(placement: .keyboard) {
          Spacer()
          Button("收起键盘") {
            UIApplication.shared.sendAction(
              #selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
          }
        }
      }.onAppear {
        config = model.savedCamera()
        profiles = (try? model.connections.cameras()) ?? []
        selectedProfile = profiles.first { $0.config.protocolID == config.protocolID && $0.config.host == config.host && $0.config.port == config.port && $0.config.fingerprint == config.fingerprint && $0.config.user == config.user && $0.config.password == config.password }?.id ?? ""
        rememberCredentials = !selectedProfile.isEmpty
        secure = !config.user.isEmpty || (config.protocolID == CameraProtocol.sony && config.host.isEmpty) || model.cameraRequiresCredentials()
      }
    }
  }
  private func editEndpoint(host: String, port: Int) {
    guard config.host != host || config.port != port else { return }
    config = ConnectionFormEditing.cameraEndpoint(config, host: host, port: port)
    selectedProfile = ""; rememberCredentials = false
  }
}

struct SettingsView: View {
  @EnvironmentObject var model: AppModel
  @State private var previewBytes: Int64 = 0
  @State private var clearPreviews = false
  var body: some View {
    List {
      Section("FrameLink") {
        LabeledContent("版本", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "未知")
        Text("本地拍摄与选片无需登录。原片保存在App文件目录，卸载前请先导出。").font(.footnote)
      }
      Section("连接能力") {
        Text("索尼无线：相机内容、原片导入、实时取景与参数控制按设备能力显示。")
        Text("Canon CCAPI实验：按机身HTTP端点提供只读浏览、预览及JPEG／CR3导入，取景和拍摄未接入iOS。")
        Text("FTP接收：适用于支持FTP上传的相机，不限定品牌。iOS接收需保持App前台。")
        Text("FTP客户端：支持FTP及显式FTPS，可浏览、上传、下载和新建文件夹。")
        Text("蓝牙快门与云端选片目前在Android端提供，iOS端尚未接入。")
      }
      Section("预览缓存") {
        LabeledContent("当前占用", value: ByteCountFormatter.string(fromByteCount: previewBytes, countStyle: .file))
        Text("上限约2GB，包含缩略图和临时JPEG原片。正式导入会校验并复用缓存；清理不会删除已导入照片或选片记录。").font(.footnote).foregroundStyle(.secondary)
        Button("清除预览缓存", role: .destructive) { clearPreviews = true }.disabled(model.clearingPreviews)
      }
      Section {
        Link("源码与使用说明", destination: URL(string: "https://github.com/Aenlly/Aenlly-FTP-FrameLink")!)
        Text("基于Aenlly-FTP，使用GPL-3.0许可证。相机协议来自alpha2go，具体署名见源码。").font(.caption).foregroundStyle(
          .secondary)
      }
    }.navigationTitle("设置与帮助")
      .task { previewBytes = (try? await model.previewCache?.usage()) ?? 0 }
      .alert("清除预览缓存？", isPresented: $clearPreviews) {
        Button("取消", role: .cancel) {}
        Button("清除缓存", role: .destructive) { Task { await model.clearPreviewCache(); previewBytes = (try? await model.previewCache?.usage()) ?? 0 } }
      } message: { Text("仅清理可重新生成的预览和临时JPEG原片，保留正式导入文件与选择记录。") }
  }
}
