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
  @State private var controlsVisible = true
  @State private var professional = false
  @State private var grid = false
  @State private var countdown = 0
  @State private var remaining = 0
  @State private var timer: Task<Void, Never>?
  @State private var property: CameraProperty?
  private var properties: [CameraProperty] {
    let known = model.properties.filter {
      PropertyText.names[$0.code] != nil && PropertyText.value($0, $0.value) != nil
    }.sorted { rank($0.code) < rank($1.code) }
    return professional ? known : Array(known.filter { rank($0.code) < 4 }.prefix(4))
  }
  var body: some View {
    GeometryReader { geometry in
      let landscape = geometry.size.width > geometry.size.height
      let nativeInsets =
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
        .first(where: \.isKeyWindow)?.safeAreaInsets ?? .zero
      ZStack {
        Color.black
        if let image = model.liveImage {
          Image(uiImage: image).resizable().scaledToFit().frame(
            width: geometry.size.width, height: geometry.size.height)
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
              let fraction = CGFloat(i) / 3
              path.move(to: CGPoint(x: left + width * fraction, y: top))
              path.addLine(to: CGPoint(x: left + width * fraction, y: top + height))
              path.move(to: CGPoint(x: left, y: top + height * fraction))
              path.addLine(to: CGPoint(x: left + width, y: top + height * fraction))
            }
            context.stroke(path, with: .color(.white.opacity(0.2)), lineWidth: 0.5)
          }.allowsHitTesting(false)
        }
        if remaining > 0 {
          Text("\(remaining)").font(.system(size: 84, weight: .light)).shadow(radius: 6)
        }
        Color.clear.contentShape(Rectangle()).onTapGesture {
          withAnimation(.easeInOut(duration: 0.18)) { controlsVisible.toggle() }
        }.accessibilityLabel(controlsVisible ? "取景画面，点按隐藏控制" : "显示取景控制").accessibilityAddTraits(
          .isButton)
        if controlsVisible {
          VStack(spacing: 0) {
            HStack(alignment: .top, spacing: 8) {
              GlassCapsule {
                HStack(spacing: 10) {
                  Button {
                    dismiss()
                  } label: {
                    Image(systemName: "chevron.left")
                  }.accessibilityLabel("返回相机")
                  VStack(alignment: .leading, spacing: 3) {
                    Text("取景拍摄").font(.system(size: 12, weight: .medium))
                    Text(
                      (model.cameraInfo?.model ?? "相机")
                        + (model.liveSize.isEmpty ? "" : " · " + model.liveSize)
                    ).font(.system(size: 9)).foregroundStyle(.white.opacity(0.7)).lineLimit(1)
                  }
                }
              }.frame(maxWidth: min(260, geometry.size.width - 170), alignment: .leading)
              Spacer(minLength: 0)
              GlassCapsule {
                HStack(spacing: 16) {
                  Button {
                    controlsVisible = false
                  } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                  }.accessibilityLabel("沉浸取景")
                  Button(professional ? "简洁布局" : "专业工具") { professional.toggle() }.font(
                    .system(size: 11))
                }
              }
            }
            Spacer()
            VStack(spacing: 8) {
              if let notice = model.notice {
                GlassCapsule { Text(notice).font(.caption).lineLimit(2) }
              }
              GlassCapsule {
                VStack(spacing: 4) {
                  if professional {
                    HStack(spacing: 20) {
                      Button(grid ? "网格 开" : "网格 关") { grid.toggle() }
                      Text("参数由机身决定").foregroundStyle(.white.opacity(0.65))
                    }.font(.system(size: 10))
                  }
                  ScrollView(.horizontal) {
                    HStack(spacing: 24) {
                      ForEach(properties) { item in
                        Button {
                          property = item
                        } label: {
                          VStack(alignment: .leading, spacing: 4) {
                            Text(PropertyText.names[item.code] ?? "参数").font(.system(size: 9))
                              .foregroundStyle(.white.opacity(0.65))
                            Text(
                              model.propertyUpdating
                                ? "应用中" : PropertyText.value(item, item.value) ?? "机身设置"
                            ).font(.system(size: 12)).lineLimit(1)
                          }
                        }.disabled(
                          model.propertyUpdating || !item.writable
                            || item.choices.compactMap { PropertyText.value(item, $0.value) }
                              .isEmpty
                        )
                      }
                    }
                  }.scrollIndicators(.hidden)
                }
              }.frame(maxWidth: landscape ? 520 : 440)
              if !landscape { shutterDock(vertical: false) }
            }
          }
          .padding(.leading, max(nativeInsets.left, geometry.safeAreaInsets.leading) + 12)
          .padding(.trailing, max(nativeInsets.right, geometry.safeAreaInsets.trailing) + 12)
          .padding(.top, max(nativeInsets.top, geometry.safeAreaInsets.top) + 8)
          .padding(.bottom, max(nativeInsets.bottom, geometry.safeAreaInsets.bottom) + 12)
          if landscape {
            HStack {
              Spacer()
              shutterDock(vertical: true)
            }.padding(.trailing, max(nativeInsets.right, geometry.safeAreaInsets.trailing) + 12)
          }
        }
      }.foregroundStyle(.white)
        .onChange(of: landscape) { _, value in controlsVisible = !value }
        .task {
          controlsVisible = !landscape
          model.startLive()
        }
    }.ignoresSafeArea().preferredColorScheme(.dark)
      .statusBarHidden().persistentSystemOverlays(.hidden)
      .onDisappear {
        timer?.cancel()
        model.stopLive()
      }
      .sheet(item: $property) { item in
        NavigationStack {
          List(item.choices.filter { PropertyText.value(item, $0.value) != nil }, id: \.value) {
            choice in
            Button {
              model.setProperty(item, value: choice.value)
              property = nil
            } label: {
              HStack {
                Text(PropertyText.value(item, choice.value) ?? "")
                Spacer()
                if item.value == choice.value { Image(systemName: "checkmark") }
              }
            }
          }
          .navigationTitle(PropertyText.names[item.code] ?? "相机参数").navigationBarTitleDisplayMode(
            .inline
          ).toolbar { Button("关闭") { property = nil } }
        }.presentationDetents([.medium, .large])
      }
  }
  @ViewBuilder private func shutterDock(vertical: Bool) -> some View {
    GlassCapsule {
      let buttons = Group {
        Menu {
          ForEach([0, 2, 5, 10], id: \.self) { seconds in
            Button(seconds == 0 ? "无延时" : "\(seconds)秒") { countdown = seconds }
          }
        } label: {
          Text(countdown == 0 ? "无延时" : "\(countdown)秒").font(.system(size: 11))
        }
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
          Circle().fill(.white).padding(5).overlay(Circle().stroke(.white, lineWidth: 2)).frame(
            width: 54, height: 54)
        }.accessibilityLabel("拍摄").disabled(model.cameraInfo?.canCapture != true)
        Button {
          model.startLive()
        } label: {
          Image(systemName: "arrow.clockwise").font(.system(size: 18))
        }
      }
      if vertical { VStack(spacing: 20) { buttons } } else { HStack(spacing: 28) { buttons } }
    }
  }
  private func rank(_ code: Int) -> Int {
    [0x5007: 0, 0xD20D: 1, 0x500D: 1, 0xD21E: 2, 0x500F: 2, 0x5010: 3][code] ?? 10 + code
  }
}
