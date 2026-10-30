import FrameLinkCore
import SwiftUI

/// Sony values are translated before presentation; unknown vendor enums remain read-only.
enum PropertyText {
  static let names: [Int: String] = [
    0x5007: "光圈", 0x500D: "快门", 0xD20D: "快门", 0x500F: "感光度", 0xD21E: "感光度", 0x5010: "曝光补偿",
    0x5005: "白平衡", 0x500A: "对焦模式", 0x500B: "测光模式", 0xD22C: "对焦区域", 0x500C: "闪光模式", 0x500E: "曝光模式",
    0xD0FA: "创意外观",
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
      return ExposureTimeText.standardPTPShutter(raw)
    case 0xD0FA: return SonyCreativeLookPreset(rawValue: raw)?.code
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
  @State private var assistants = false
  @State private var histogramVisible = false
  @State private var histogramBins = [Int](repeating: 0, count: 64)
  @State private var histogramUpdatedAt = Date.distantPast
  @State private var showInfo = false
  @State private var showCreativeLook = false
  @State private var grid = false
  @State private var countdown = 0
  @State private var remaining = 0
  @State private var timer: Task<Void, Never>?
  @State private var property: CameraProperty?
  private var properties: [CameraProperty] {
    model.properties.filter {
      $0.code != SonyCreativeLookPolicy.propertyCode && PropertyText.names[$0.code] != nil && PropertyText.value($0, $0.value) != nil
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
      let panelHeight = landscape
        ? geometry.size.height - top - bottom - 16 : min(360, geometry.size.height * 0.44)
      let panelWidth = landscape
        ? min(260, max(220, geometry.size.width * 0.28)) : min(480, geometry.size.width - 24)
      let previewTop = !landscape && controlsVisible ? top + 56 : 0
      let previewBottom = !landscape && controlsVisible
        ? bottom + (professional ? panelHeight + 12 : 194) : 0
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
        .onTapGesture {
          withAnimation(.easeInOut(duration: 0.15)) {
            controlsVisible.toggle()
            if !controlsVisible { professional = false }
          }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(controlsVisible ? "取景画面，点按隐藏控制" : "显示取景控制")
        .accessibilityAddTraits(.isButton)
        .position(x: geometry.size.width / 2, y: previewTop + pictureHeight / 2)
        if controlsVisible && histogramVisible {
          LiveHistogram(bins: histogramBins).frame(width: 104, height: 52).accessibilityElement(children: .ignore)
            .accessibilityLabel("预览亮度直方图").accessibilityIdentifier("live-histogram")
            .position(x: 64, y: previewTop + pictureHeight - 38).allowsHitTesting(false)
        }
        if controlsVisible && !landscape && !professional {
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
                creativeLookButton
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
        if controlsVisible && landscape && !professional {
          HStack {
            VStack {
              GlassCapsule {
                Button { dismiss() } label: { Image(systemName: "chevron.left") }
                  .accessibilityLabel("返回相机").frame(width: 24, height: 24)
              }
              Spacer()
              GlassCapsule {
                VStack(alignment: .leading, spacing: 14) {
                  ForEach(Array(properties.filter { rank($0.code) < 4 }.prefix(4))) { item in
                    Button { property = item } label: {
                      VStack(alignment: .leading, spacing: 4) {
                        Text(PropertyText.names[item.code] ?? "参数").font(.system(size: 10)).foregroundStyle(.secondary)
                        Text(PropertyText.value(item, item.value) ?? "机身设置").font(.system(size: 13))
                      }.frame(maxWidth: .infinity, alignment: .leading)
                    }.disabled(!item.writable || model.propertyUpdating || model.creativeLookState.busy)
                  }
                }
              }.frame(width: 108)
              Spacer()
            }
            Spacer()
            VStack {
              GlassCapsule {
                HStack(spacing: 20) {
                  Button { showInfo = true } label: { Image(systemName: "ellipsis") }
                    .accessibilityLabel("连接信息")
                  Button { controlsVisible = false } label: { Image(systemName: "arrow.up.left.and.arrow.down.right") }
                    .accessibilityLabel("沉浸取景")
                }.frame(height: 24)
              }
              Button("专业工具") { professional = true }.font(.system(size: 12)).frame(height: 44)
              creativeLookButton
              Spacer()
              GlassCapsule {
                VStack(spacing: 12) {
                  Menu {
                    ForEach([0, 3, 10], id: \.self) { value in
                      Button(value == 0 ? "无延时" : "\(value)秒") { countdown = value }
                    }
                  } label: { Text(countdown == 0 ? "无延时" : "\(countdown)秒").font(.system(size: 11)) }
                  shutter(size: 64)
                  Button(action: onGallery) { Image(systemName: "photo.on.rectangle").frame(height: 32) }
                    .accessibilityLabel("查看相机照片")
                }
              }
            }.frame(width: 112)
          }.padding(.leading, max(insets.left, geometry.safeAreaInsets.leading) + 12)
            .padding(.trailing, max(insets.right, geometry.safeAreaInsets.trailing) + 12)
            .padding(.top, top + 8).padding(.bottom, bottom + 8).buttonStyle(.plain)
        }
        if controlsVisible && professional {
          professionalPanel.frame(width: panelWidth, height: panelHeight)
            .accessibilityElement(children: .contain).accessibilityIdentifier("live-tools-panel")
            .position(
              x: landscape
                ? geometry.size.width - max(insets.right, geometry.safeAreaInsets.trailing) - 12 - panelWidth / 2
                : geometry.size.width / 2,
              y: landscape ? top + panelHeight / 2 + 8
                : geometry.size.height - bottom - 12 - panelHeight / 2)
        }
      }.foregroundStyle(.white)
        .onChange(of: model.liveImage) { _, image in
          guard controlsVisible, histogramVisible,
            Date().timeIntervalSince(histogramUpdatedAt) >= 0.5, let image else { return }
          histogramUpdatedAt = Date()
          histogramBins = previewHistogram(image)
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
    .sheet(isPresented: $showInfo) {
      NavigationStack { CameraConnectionDetails().toolbar { Button("完成") { showInfo = false } } }
        .presentationDetents([.medium, .large])
    }
    .sheet(item: $property) { item in
      NavigationStack { CameraPropertyPicker(property: item) }.presentationDetents([
        .medium, .large,
      ])
    }
    .sheet(isPresented: $showCreativeLook) {
      NavigationStack { CreativeLookPicker().toolbar { Button("完成") { showCreativeLook = false } } }.presentationDetents([.medium, .large])
    }
  }
  private var professionalPanel: some View {
    LiveToolGlass {
      VStack(spacing: 8) {
        HStack {
          Text("专业工具").font(.system(size: 14)).frame(maxWidth: .infinity, alignment: .leading)
          Button {
            professional = false
            controlsVisible = false
          } label: { Image(systemName: "arrow.up.left.and.arrow.down.right").frame(width: 44, height: 44) }
            .accessibilityLabel("沉浸取景")
          Button { professional = false } label: {
            Image(systemName: "xmark").frame(width: 44, height: 44)
          }.accessibilityLabel("收起工具")
        }
        HStack(spacing: 6) {
          Button { assistants = false } label: {
            Text("机身参数").frame(maxWidth: .infinity, minHeight: 32)
              .background(assistants ? .white.opacity(0.04) : .white.opacity(0.2), in: Capsule())
          }.accessibilityValue(assistants ? "" : "已选中")
          Button { assistants = true } label: {
            Text("预览辅助").frame(maxWidth: .infinity, minHeight: 32)
              .background(assistants ? .white.opacity(0.2) : .white.opacity(0.04), in: Capsule())
          }.accessibilityValue(assistants ? "已选中" : "")
        }.font(.system(size: 12)).buttonStyle(.plain)
        ScrollView {
          LazyVStack(spacing: 4) {
            if assistants {
              Toggle("构图网格", isOn: $grid).toggleStyle(.switch).frame(minHeight: 44)
              Toggle("直方图", isOn: $histogramVisible).toggleStyle(.switch).frame(minHeight: 44)
              Text("辅助分析基于取景预览").font(.caption2).foregroundStyle(.secondary)
            } else {
              creativeLookButton
              ForEach(properties) { item in
                Button { property = item } label: {
                  HStack {
                    Text(PropertyText.names[item.code] ?? "机身设置")
                    Spacer()
                    Text(PropertyText.value(item, item.value) ?? "机身调整").foregroundStyle(.secondary)
                    Image(systemName: "chevron.right").font(.system(size: 11))
                  }.font(.system(size: 12)).frame(minHeight: 44)
                }.disabled(!item.writable || model.propertyUpdating || model.creativeLookState.busy)
              }
              Button("连接信息与预览规格") { showInfo = true }.font(.caption).frame(minHeight: 44)
            }
          }
        }.scrollIndicators(.hidden).accessibilityIdentifier("live-properties")
        HStack {
          Button(action: onGallery) { Image(systemName: "photo.on.rectangle").frame(width: 44, height: 44) }
            .accessibilityLabel("查看相机照片")
          Spacer()
          shutter(size: 48)
          Spacer()
          Menu {
            ForEach([0, 3, 10], id: \.self) { value in
              Button(value == 0 ? "无延时" : "\(value)秒") { countdown = value }
            }
          } label: {
            Text(countdown == 0 ? "无延时" : "\(countdown)秒").font(.system(size: 11))
              .frame(minWidth: 44, minHeight: 44)
          }
        }
      }.padding(12)
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
            }.disabled(!item.writable || model.propertyUpdating || model.creativeLookState.busy)
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
    }.accessibilityLabel("拍摄").disabled(!model.cameraClientCapabilities.canCapture)
  }
  private func rank(_ code: Int) -> Int {
    [0x5007: 0, 0xD20D: 1, 0x500D: 1, 0xD21E: 2, 0x500F: 2, 0x5010: 3][code] ?? 10 + code
  }
  private var creativeLookButton: some View {
    Button { showCreativeLook = true } label: {
      HStack(spacing: 6) {
        if model.creativeLookState.busy && model.creativeLookState.property == nil { ProgressView().tint(.white) }
        else { Image(systemName: "paintpalette") }
        if let code = model.creativeLookState.currentCode { Text(code).font(.system(size: 12)).lineLimit(1) }
      }.frame(minWidth: 44, minHeight: 44)
    }.accessibilityLabel("创意外观").accessibilityValue(model.creativeLookState.currentCode ?? (model.creativeLookState.busy ? "读取中" : "未读取"))
  }
}

private struct CreativeLookPicker: View {
  @EnvironmentObject var model: AppModel
  var body: some View {
    let state = model.creativeLookState
    List {
      Section {
        Text("当前：" + (state.currentCode ?? "尚未读取"))
        Text(state.message).font(.footnote).foregroundStyle(state.confirmed ? Color.green : Color.secondary)
        if let reason = state.availability { Text(reason).font(.footnote).foregroundStyle(.secondary) }
        Button("重新读取") { Task { await model.refreshCreativeLook() } }.disabled(state.busy || !state.connected || state.protocolID != CameraProtocol.sony)
      }
      Section("机身当前候选") {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 76))], spacing: 14) {
          ForEach(state.choices, id: \.rawValue) { preset in
            Button { model.selectCreativeLook(preset.rawValue) } label: {
              VStack(spacing: 5) { Text(preset.code).font(.headline); Text(preset.chinese).font(.caption) }.frame(maxWidth: .infinity, minHeight: 58)
                .background(state.property?.value == preset.rawValue ? Color.white.opacity(0.16) : .clear, in: RoundedRectangle(cornerRadius: 16))
            }.disabled(state.busy || SonyCreativeLookPolicy.rejection(state.property, target: preset.rawValue, diagnostics: state.diagnostics) != nil)
              .accessibilityLabel(preset.code + " " + preset.chinese)
          }
        }
      }
      Text("仅开放机身提供且已识别的预设；未知、自定义与细调项请在机身操作。").font(.caption).foregroundStyle(.secondary)
    }.navigationTitle("创意外观").navigationBarTitleDisplayMode(.inline).task { await model.refreshCreativeLook() }
  }
}

private struct LiveToolGlass<Content: View>: View {
  @ViewBuilder var content: Content
  var body: some View {
    #if compiler(>=6.2)
    if #available(iOS 26.0, *) {
      content.glassEffect(.regular, in: .rect(cornerRadius: 24))
    } else {
      content.background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 24))
        .overlay(RoundedRectangle(cornerRadius: 24).stroke(.white.opacity(0.3), lineWidth: 0.7))
    }
    #else
      content.background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 24))
        .overlay(RoundedRectangle(cornerRadius: 24).stroke(.white.opacity(0.3), lineWidth: 0.7))
    #endif
  }
}

private struct LiveHistogram: View {
  let bins: [Int]
  var body: some View {
    Canvas { context, size in
      let peak = max(1, bins.max() ?? 1)
      let step = size.width / CGFloat(bins.count)
      var path = Path()
      for (index, value) in bins.enumerated() {
        path.addRect(CGRect(x: CGFloat(index) * step, y: size.height * (1 - CGFloat(value) / CGFloat(peak)),
          width: step, height: size.height * CGFloat(value) / CGFloat(peak)))
      }
      context.fill(path, with: .color(.white.opacity(0.85)))
    }.padding(6).background(.black.opacity(0.48), in: RoundedRectangle(cornerRadius: 8))
  }
}

/// 预览亮度参考图按低分辨率采样，不使用原片或RAW数据
private func previewHistogram(_ image: UIImage) -> [Int] {
  var bins = [Int](repeating: 0, count: 64)
  guard let image = image.cgImage else { return bins }
  let width = 128
  let height = 96
  var pixels = [UInt8](repeating: 0, count: width * height * 4)
  pixels.withUnsafeMutableBytes { buffer in
    guard let context = CGContext(data: buffer.baseAddress, width: width, height: height,
      bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return }
    context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
    for index in stride(from: 0, to: buffer.count, by: 4) {
      let luminance = (Int(buffer[index]) * 54 + Int(buffer[index + 1]) * 183 + Int(buffer[index + 2]) * 19) >> 8
      bins[min(63, luminance >> 2)] += 1
    }
  }
  return bins
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
