import FrameLinkCore
import SwiftUI

/// Sony values are translated before presentation; unknown vendor enums remain read-only.
enum PropertyText {
  static let names: [Int: String] = [
    0x5007: "光圈", 0x500D: "快门", 0xD20D: "快门", 0x500F: "感光度", 0xD21E: "感光度", 0x5010: "曝光补偿",
    0x5005: "白平衡", 0x500A: "对焦模式", 0x500B: "测光模式", 0xD22C: "对焦区域", 0x500C: "闪光模式", 0x500E: "曝光模式",
  ]
  static let enums: [Int: [Int64: String]] = [
    0x500A: [
      1: "手动对焦（MF）", 2: "单次自动对焦（AF-S）", 0x8004: "连续自动对焦（AF-C）", 0x8005: "自动切换（AF-A）",
      0x8006: "直接手动对焦（DMF）",
    ],
    0xD22C: [
      1: "广域", 2: "区", 3: "中间", 0x101: "点：小", 0x102: "点：中", 0x103: "点：大", 0x104: "扩展点",
      0x201: "跟踪：广域", 0x202: "跟踪：区", 0x203: "跟踪：中间", 0x204: "跟踪：点（小）", 0x205: "跟踪：点（中）",
      0x206: "跟踪：点（大）", 0x207: "跟踪：扩展点",
    ],
    0x500B: [
      1: "平均测光", 2: "中央重点平均", 3: "多点测光", 4: "中央点测光", 0x8001: "多重测光", 0x8002: "中央重点",
      0x8003: "整个屏幕平均", 0x8004: "点测光：标准", 0x8005: "点测光：大", 0x8006: "强光重点",
    ],
    0x5005: [
      2: "自动", 4: "日光", 6: "白炽灯", 7: "闪光灯", 0x8001: "荧光灯：暖白", 0x8002: "荧光灯：冷白", 0x8003: "荧光灯：日间白",
      0x8004: "荧光灯：日光", 0x8010: "阴天", 0x8011: "阴影", 0x8012: "色温 / 滤镜", 0x8020: "自定义1",
      0x8021: "自定义2", 0x8022: "自定义3", 0x8030: "水中自动",
    ],
    0x500C: [1: "自动闪光", 2: "关闭", 3: "强制闪光", 4: "防红眼自动", 5: "防红眼闪光", 6: "外接同步"],
    0x500E: [1: "手动曝光", 2: "程序自动", 3: "光圈优先", 4: "快门优先", 5: "创意程序", 6: "运动程序", 7: "人像"],
  ]
  static func value(_ property: CameraProperty, _ raw: Int64) -> String? {
    if let mapping = enums[property.code] { return mapping[raw] }
    switch property.code {
    case 0x5007: return raw > 0 ? String(format: "f/%.1f", Double(raw) / 100) : "自动"
    case 0x500F: return raw == 0 || raw == 0xFFFFFF ? "自动" : "ISO \(raw)"
    case 0xD21E:
      let iso = raw & 0xFFFFFF
      return raw >> 24 > 2 ? nil : iso == 0xFFFFFF ? "自动" : "ISO \(iso)"
    case 0x500D:
      let seconds = Double(raw) / 10000
      return seconds > 0 && seconds < 1
        ? "1/\(Int((1/seconds).rounded()))" : String(format: "%.1f秒", seconds)
    case 0xD20D:
      let numerator = raw >> 16
      let denominator = raw & 0xFFFF
      return raw == 0
        ? "B门"
        : denominator > 0
          ? numerator == 1
            ? "1/\(denominator)" : String(format: "%.1f秒", Double(numerator) / Double(denominator))
          : nil
    case 0x5010: return String(format: "%+.1f EV", Double(raw) / 1000)
    default: return nil
    }
  }
}

struct LiveCameraView: View {
  @EnvironmentObject var model: AppModel
  @Environment(\.dismiss) private var dismiss
  var onGallery: () -> Void = {}
  @State private var controlsVisible = true
  @State private var professional = false
  @State private var showInfo = false
  @State private var grid = false
  @State private var countdown = 0
  @State private var remaining = 0
  @State private var timer: Task<Void, Never>?
  @State private var property: CameraProperty?
  private var properties: [CameraProperty] {
    model.properties.filter {
      PropertyText.names[$0.code] != nil && PropertyText.value($0, $0.value) != nil
    }.sorted {
      rank($0.code) == rank($1.code)
        ? ($0.code >= 0xD000 && $1.code < 0xD000) : rank($0.code) < rank($1.code)
    }.reduce(into: [CameraProperty]()) { values, item in
      if !values.contains(where: { PropertyText.names[$0.code] == PropertyText.names[item.code] }) {
        values.append(item)
      }
    }
  }
  var body: some View {
    GeometryReader { geometry in
      let landscape = geometry.size.width > geometry.size.height
      let insets =
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
        .first(where: \.isKeyWindow)?.safeAreaInsets ?? .zero
      let top = max(insets.top, geometry.safeAreaInsets.top)
      let bottom = max(insets.bottom, geometry.safeAreaInsets.bottom)
      let previewTop = !landscape && controlsVisible ? top + 56 : 0
      let previewBottom = !landscape && controlsVisible ? bottom + 194 : 0
      let pictureHeight = max(1, geometry.size.height - previewTop - previewBottom)
      ZStack {
        Color.black
        ZStack {
          if let image = model.liveImage {
            Image(uiImage: image).resizable().scaledToFit()
          } else {
            VStack(spacing: 12) {
              ProgressView().tint(.white)
              Text("等待相机取景画面").font(.caption)
            }
          }
          if grid, let image = model.liveImage {
            Canvas { context, size in
              let factor = min(size.width / image.size.width, size.height / image.size.height)
              let width = image.size.width * factor
              let height = image.size.height * factor
              let left = (size.width - width) / 2
              let top = (size.height - height) / 2
              var path = Path()
              for i in 1...2 {
                let part = CGFloat(i) / 3
                path.move(to: CGPoint(x: left + width * part, y: top))
                path.addLine(to: CGPoint(x: left + width * part, y: top + height))
                path.move(to: CGPoint(x: left, y: top + height * part))
                path.addLine(to: CGPoint(x: left + width, y: top + height * part))
              }
              context.stroke(path, with: .color(.white.opacity(0.2)), lineWidth: 0.5)
            }.allowsHitTesting(false)
          }
          if remaining > 0 {
            Text("\(remaining)").font(.system(size: 84, weight: .light)).shadow(radius: 6)
          }
        }
        .frame(width: geometry.size.width, height: pictureHeight)
        .contentShape(Rectangle())
        .onTapGesture { withAnimation(.easeInOut(duration: 0.15)) { controlsVisible.toggle() } }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(controlsVisible ? "取景画面，点按隐藏控制" : "显示取景控制")
        .accessibilityAddTraits(.isButton)
        .position(x: geometry.size.width / 2, y: previewTop + pictureHeight / 2)
        if controlsVisible && !landscape {
          VStack(spacing: 0) {
            HStack {
              Button {
                dismiss()
              } label: {
                Image(systemName: "chevron.left")
              }.accessibilityLabel("返回相机").frame(width: 44, height: 44)
              Spacer()
              Menu {
                ForEach([0, 3, 10], id: \.self) { value in
                  Button(value == 0 ? "无延时" : "\(value)秒") { countdown = value }
                }
              } label: {
                Image(systemName: "timer")
              }.accessibilityLabel("拍摄倒计时").frame(width: 44, height: 44)
              Button {
                grid.toggle()
              } label: {
                Image(systemName: "square.grid.3x3")
              }.accessibilityLabel("构图网格").frame(width: 44, height: 44)
              Button {
                controlsVisible = false
              } label: {
                Image(systemName: "arrow.up.left.and.arrow.down.right")
              }.accessibilityLabel("沉浸取景").frame(width: 44, height: 44)
              Button {
                showInfo = true
              } label: {
                Image(systemName: "ellipsis")
              }.accessibilityLabel("连接信息").frame(width: 44, height: 44)
            }.frame(height: 56)
            Spacer()
            VStack(spacing: 8) {
              quickProperties.frame(maxWidth: 440)
              HStack(spacing: 28) {
                Text("拍照").foregroundStyle(Color(red: 0.72, green: 0.84, blue: 0.94))
                Button("专业工具") { professional = true }.foregroundStyle(.white.opacity(0.75))
              }.font(.system(size: 14))
              HStack {
                Button(action: onGallery) {
                  if let photo = model.library.importedPhotos.first {
                    PhotoThumbnail(photo: photo).frame(width: 52, height: 52).clipShape(
                      RoundedRectangle(cornerRadius: 12))
                  } else {
                    Image(systemName: "photo.on.rectangle").font(.system(size: 26)).frame(
                      width: 64, height: 64)
                  }
                }.accessibilityLabel("查看相机照片")
                Spacer()
                shutter(size: 76)
                Spacer()
                Button {
                  model.startLive()
                } label: {
                  Image(systemName: "arrow.clockwise").font(.system(size: 26)).frame(
                    width: 64, height: 64
                  ).background(.white.opacity(0.09), in: Circle())
                }.accessibilityLabel("重新打开取景")
              }
            }.frame(height: 194)
          }.padding(.horizontal, 16).padding(.top, top).padding(.bottom, bottom)
        }
        if controlsVisible && landscape {
          VStack {
            HStack {
              GlassCapsule {
                HStack(spacing: 10) {
                  Button {
                    dismiss()
                  } label: {
                    Image(systemName: "chevron.left")
                  }.accessibilityLabel("返回相机")
                  Text("拍摄").font(.system(size: 12))
                }
              }
              Spacer()
              GlassCapsule {
                HStack(spacing: 16) {
                  Button {
                    showInfo = true
                  } label: {
                    Image(systemName: "ellipsis")
                  }.accessibilityLabel("连接信息")
                  Button {
                    controlsVisible = false
                  } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                  }.accessibilityLabel("沉浸取景")
                  Button("专业工具") { professional = true }.font(.system(size: 11))
                }
              }
            }
            Spacer()
            quickProperties.frame(maxWidth: 480)
          }.padding(.leading, max(insets.left, geometry.safeAreaInsets.leading) + 12).padding(
            .trailing, max(insets.right, geometry.safeAreaInsets.trailing) + 12
          ).padding(.top, top + 8).padding(.bottom, bottom + 12)
          HStack {
            Spacer()
            GlassCapsule {
              VStack(spacing: 16) {
                Menu {
                  ForEach([0, 3, 10], id: \.self) { value in
                    Button(value == 0 ? "无延时" : "\(value)秒") { countdown = value }
                  }
                } label: {
                  Text(countdown == 0 ? "无延时" : "\(countdown)秒").font(.system(size: 11))
                }
                shutter(size: 54)
                Button(action: onGallery) { Image(systemName: "photo.on.rectangle") }
                  .accessibilityLabel("查看相机照片")
              }
            }
          }.padding(.trailing, max(insets.right, geometry.safeAreaInsets.trailing) + 12)
        }
      }.foregroundStyle(.white)
        .onChange(of: landscape) { _, value in
          controlsVisible = !value
          professional = false
        }
        .task {
          controlsVisible = !landscape
          model.startLive()
        }
    }.ignoresSafeArea().preferredColorScheme(.dark).statusBarHidden().persistentSystemOverlays(
      .hidden
    )
    .onDisappear {
      timer?.cancel()
      model.stopLive()
    }
    .sheet(isPresented: $professional) {
      NavigationStack {
        List {
          Section("预览辅助") {
            Toggle("构图网格", isOn: $grid)
            Text("拍摄模式与参数以机身能力为准").font(.caption).foregroundStyle(.secondary)
          }
          Section("相机参数") {
            ForEach(properties) { item in
              NavigationLink {
                CameraPropertyPicker(property: item)
              } label: {
                HStack {
                  Text(PropertyText.names[item.code] ?? "机身设置")
                  Spacer()
                  Text(PropertyText.value(item, item.value) ?? "机身调整").foregroundStyle(.secondary)
                }
              }.disabled(!item.writable || model.propertyUpdating)
            }
          }
          Section {
            NavigationLink {
              CameraConnectionDetails()
            } label: {
              Text("连接信息与预览规格")
            }
          }
        }.scrollContentBackground(.hidden).navigationTitle("专业工具").navigationBarTitleDisplayMode(
          .inline
        ).toolbar { Button("收起工具") { professional = false } }
      }.presentationDetents([.medium, .large]).presentationBackground(.ultraThinMaterial)
        .presentationCornerRadius(28)
    }
    .sheet(isPresented: $showInfo) {
      NavigationStack { CameraConnectionDetails().toolbar { Button("完成") { showInfo = false } } }
        .presentationDetents([.medium, .large])
    }
    .sheet(item: $property) { item in
      NavigationStack { CameraPropertyPicker(property: item) }.presentationDetents([
        .medium, .large,
      ])
    }
  }
  private var quickProperties: some View {
    GlassCapsule {
      ScrollView(.horizontal) {
        HStack(spacing: 24) {
          ForEach(Array(properties.filter { rank($0.code) < 4 }.prefix(4))) { item in
            Button {
              property = item
            } label: {
              VStack(alignment: .leading, spacing: 4) {
                Text(PropertyText.names[item.code] ?? "参数").font(.system(size: 10)).foregroundStyle(
                  .white.opacity(0.65))
                Text(
                  model.propertyUpdating ? "应用中" : PropertyText.value(item, item.value) ?? "机身设置"
                ).font(.system(size: 13))
              }
            }.disabled(!item.writable || model.propertyUpdating)
          }
        }
      }.scrollIndicators(.hidden)
    }
  }
  private func shutter(size: CGFloat) -> some View {
    Button {
      if remaining > 0 {
        timer?.cancel()
        remaining = 0
      } else {
        timer = Task { @MainActor in
          remaining = countdown
          while remaining > 0 {
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            if Task.isCancelled { return }
            remaining -= 1
          }
          model.capture()
        }
      }
    } label: {
      Circle().fill(.white).padding(6).overlay(Circle().stroke(.white, lineWidth: 2)).frame(
        width: size, height: size)
    }.accessibilityLabel("拍摄").disabled(model.cameraInfo?.canCapture != true)
  }
  private func rank(_ code: Int) -> Int {
    [0x5007: 0, 0xD20D: 1, 0x500D: 1, 0xD21E: 2, 0x500F: 2, 0x5010: 3][code] ?? 10 + code
  }
}

private struct CameraPropertyPicker: View {
  @EnvironmentObject var model: AppModel
  @Environment(\.dismiss) private var dismiss
  let property: CameraProperty
  var body: some View {
    List(property.choices.filter { PropertyText.value(property, $0.value) != nil }, id: \.value) {
      choice in
      Button {
        model.setProperty(property, value: choice.value)
        dismiss()
      } label: {
        HStack {
          Text(PropertyText.value(property, choice.value) ?? "机身设置")
          Spacer()
          if choice.value == property.value { Image(systemName: "checkmark") }
        }
      }
    }
    .navigationTitle(PropertyText.names[property.code] ?? "机身设置").navigationBarTitleDisplayMode(
      .inline)
  }
}
private struct CameraConnectionDetails: View {
  @EnvironmentObject var model: AppModel
  var body: some View {
    List {
      LabeledContent("机型", value: model.cameraInfo?.displayName ?? "未连接")
      LabeledContent("协议型号", value: model.cameraInfo?.model ?? "")
      LabeledContent("固件", value: model.cameraInfo?.firmware ?? "")
      LabeledContent("实收预览", value: model.liveSize)
      Text("当前尺寸来自相机连接通道，不代表原片分辨率。").font(.caption).foregroundStyle(.secondary)
    }.navigationTitle("连接信息").navigationBarTitleDisplayMode(.inline)
  }
}
