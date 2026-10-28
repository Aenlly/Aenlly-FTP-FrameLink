import FrameLinkCore
import ImageIO
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

nonisolated func loadPhotoImage(_ url: URL, maxPixel: Int) -> UIImage? {
  guard
    let source = CGImageSourceCreateWithURL(
      url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
    let image = CGImageSourceCreateThumbnailAtIndex(
      source, 0,
      [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceThumbnailMaxPixelSize: maxPixel,
      ] as CFDictionary)
  else { return nil }
  return UIImage(cgImage: image)
}

private struct PickedPhoto: Transferable {
  let url: URL
  static var transferRepresentation: some TransferRepresentation {
    FileRepresentation(importedContentType: .image) { received in
      let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
        "photo-picker/\(UUID().uuidString)")
      try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
      let url = directory.appendingPathComponent(received.file.lastPathComponent)
      try FileManager.default.copyItem(at: received.file, to: url)
      return PickedPhoto(url: url)
    }
  }
}

struct PhotoThumbnail: View {
  @EnvironmentObject var model: AppModel
  @EnvironmentObject var library: LibraryStore
  let photo: Photo
  var large = false
  @State private var image: UIImage?
  var body: some View {
    GeometryReader { geometry in
      ZStack {
        Color.gray.opacity(0.10)
        if let image {
          Image(uiImage: image).resizable().scaledToFill().frame(
            width: geometry.size.width, height: geometry.size.height)
        } else {
          Image(systemName: photo.files.contains(where: { $0.isRaw }) ? "camera.aperture" : "photo")
            .foregroundStyle(.secondary)
        }
      }.frame(width: geometry.size.width, height: geometry.size.height).clipped()
    }
    .clipped().task(
      id: photo.id + String(large)
        + (library.photos.first { $0.id == photo.id } ?? photo).files.compactMap(\.localPath)
        .joined()
    ) {
      if let url = await model.previewURL(photo, large: large) {
        image = await Task.detached(priority: .utility) {
          loadPhotoImage(url, maxPixel: large ? 2048 : 720)
        }.value
      }
    }
  }
}

enum GalleryStyle: String, CaseIterable {
  case small, medium, large, list
  var title: String {
    switch self {
    case .small: return "小图"
    case .medium: return "中图"
    case .large: return "大图"
    case .list: return "列表"
    }
  }
  var cellSize: CGFloat {
    switch self {
    case .small: return 92
    case .medium: return 135
    case .large: return 180
    case .list: return 84
    }
  }
}

private struct CellFrames: PreferenceKey {
  static var defaultValue: [String: CGRect] = [:]
  static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
    value.merge(nextValue(), uniquingKeysWith: { $1 })
  }
}

/// A long press starts a range; reversing the drag restores the selection before the gesture.
struct SelectablePhotoGrid: View {
  let photos: [Photo]
  @Binding var selection: Set<String>
  var selectionEnabled = true
  var style: GalleryStyle = .medium
  var contextKey = ""
  var onDragging: (Bool) -> Void = { _ in }
  var open: (Photo) -> Void
  @State private var frames: [String: CGRect] = [:]
  @State private var range: SelectionRange?
  @State private var edgeTask: Task<Void, Never>?
  @State private var lastPoint = CGPoint.zero
  @State private var dragPhotos: [Photo]?
  @State private var viewportHeight: CGFloat = 0
  @State private var tapSuppressed = false
  private var displayedPhotos: [Photo] { dragPhotos ?? photos }
  var body: some View {
    GeometryReader { viewport in
      ScrollViewReader { proxy in
        ScrollView {
          LazyVGrid(
            columns: style == .list
              ? [GridItem(.flexible())]
              : [GridItem(.adaptive(minimum: style.cellSize), spacing: 12)], spacing: 16
          ) {
            ForEach(displayedPhotos) { photo in
              let itemLayout =
                style == .list
                ? AnyLayout(HStackLayout(alignment: .center, spacing: 14))
                : AnyLayout(VStackLayout(alignment: .leading, spacing: 7))
              itemLayout {
                ZStack(alignment: .topTrailing) {
                  Button {
                    if range == nil && !tapSuppressed { open(photo) }
                  } label: {
                    PhotoThumbnail(photo: photo).aspectRatio(1, contentMode: .fit)
                  }.buttonStyle(.plain).accessibilityLabel("查看 \(photo.name)")
                  Button {
                    guard !tapSuppressed else { return }
                    selection.formSymmetricDifference([photo.id])
                  } label: {
                    Image(
                      systemName: selection.contains(photo.id)
                        ? "checkmark.circle.fill" : "circle"
                    ).font(.title2).symbolRenderingMode(.palette).foregroundStyle(
                      .white,
                      selection.contains(photo.id) ? Palette.accent : Color.black.opacity(0.4)
                    ).frame(width: 44, height: 44).contentShape(Rectangle())
                  }.buttonStyle(.plain).disabled(!selectionEnabled).accessibilityLabel(
                    "选择 \(photo.name)")
                }.aspectRatio(1, contentMode: .fit)
                  .frame(width: style == .list ? 84 : nil, height: style == .list ? 84 : nil)
                  .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                  .overlay(
                    RoundedRectangle(cornerRadius: 22).stroke(
                      selection.contains(photo.id) ? Palette.accent : .clear, lineWidth: 3)
                  )
                  .overlay(alignment: .bottomLeading) {
                    Text(photo.formatTitle).font(.system(size: 9, weight: .medium)).foregroundStyle(
                      .white
                    ).padding(.horizontal, 6).padding(.vertical, 3).background(
                      .black.opacity(0.5), in: Capsule()
                    ).padding(6).allowsHitTesting(false)
                  }
                VStack(alignment: .leading, spacing: 5) {
                  Text(photo.displayTitle).font(style == .list ? .subheadline : .caption).lineLimit(
                    1
                  ).foregroundStyle(.primary)
                  Text(photo.importTitle).font(.system(size: 10)).foregroundStyle(.secondary)
                    .lineLimit(2)
                  if style == .list {
                    Text(photo.files.map(\.name).joined(separator: " / ")).font(.caption2)
                      .foregroundStyle(.secondary).lineLimit(1)
                  }
                }.frame(maxWidth: .infinity, alignment: .leading).onTapGesture {
                  if range == nil && !tapSuppressed { open(photo) }
                }
              }.padding(style == .list ? 12 : 0).background(
                style == .list ? Color(uiColor: .secondarySystemGroupedBackground) : .clear,
                in: RoundedRectangle(cornerRadius: 18)
              ).id(photo.id).contentShape(Rectangle())
                .background(
                  GeometryReader { geometry in
                    Color.clear.preference(
                      key: CellFrames.self,
                      value: [photo.id: geometry.frame(in: .named("photo-grid"))])
                  })
            }
          }.padding(16)
        }.coordinateSpace(name: "photo-grid").scrollDisabled(range != nil)
          .onPreferenceChange(CellFrames.self) { frames = $0 }
          .simultaneousGesture(
            LongPressGesture(minimumDuration: 0.35).sequenced(
              before: DragGesture(minimumDistance: 0, coordinateSpace: .named("photo-grid"))
            ).onChanged { value in
              guard selectionEnabled, case .second(true, let drag?) = value else { return }
              if range == nil,
                let first = displayedPhotos.firstIndex(where: {
                  frames[$0.id]?.contains(drag.startLocation) == true
                })
              {
                dragPhotos = photos
                tapSuppressed = true
                onDragging(true)
                range = SelectionRange(
                  groups: photos.map { [$0.id] }, original: selection, anchor: first)
                UISelectionFeedbackGenerator().selectionChanged()
              }
              lastPoint = drag.location
              applyRange(drag.location)
              if edgeTask == nil {
                edgeTask = Task { @MainActor in
                  while !Task.isCancelled, range != nil {
                    try? await Task.sleep(nanoseconds: 100_000_000)
                    let direction =
                      lastPoint.y < 50 ? -1 : lastPoint.y > viewportHeight - 50 ? 1 : 0
                    if direction != 0 {
                      let visible = displayedPhotos.indices.filter { index in
                        guard let frame = frames[displayedPhotos[index].id] else { return false }
                        return frame.maxY > 0 && frame.minY < viewportHeight
                      }
                      if let edge = direction < 0 ? visible.min() : visible.max() {
                        let next = max(0, min(displayedPhotos.count - 1, edge + direction))
                        proxy.scrollTo(
                          displayedPhotos[next].id, anchor: direction < 0 ? .top : .bottom)
                        selection = range!.selection(at: next)
                      }
                    }
                  }
                }
              }
            }.onEnded { _ in
              range = nil
              dragPhotos = nil
              onDragging(false)
              edgeTask?.cancel()
              edgeTask = nil
              // 吸收长按释放时的原生Button点击，正常点按不经过此分支
              Task { @MainActor in
                try? await Task.sleep(nanoseconds: 150_000_000)
                tapSuppressed = false
              }
            }
          )
          .onAppear { viewportHeight = viewport.size.height }
          .onChange(of: viewport.size) { _, size in viewportHeight = size.height }
          .onDisappear {
            onDragging(false)
            edgeTask?.cancel()
            range = nil
            dragPhotos = nil
          }
          .onChange(of: contextKey) { _, _ in
            onDragging(false)
            range = nil
            dragPhotos = nil
            edgeTask?.cancel()
            edgeTask = nil
          }
      }
    }
  }
  private func applyRange(_ point: CGPoint) {
    if let range,
      let index = displayedPhotos.firstIndex(where: { frames[$0.id]?.contains(point) == true })
    {
      selection = range.selection(at: index)
    }
  }
}

struct GalleryView: View {
  @EnvironmentObject var model: AppModel
  @EnvironmentObject var library: LibraryStore
  @Binding var connect: Bool
  private var source: Int { model.gallerySource }
  @State private var filter = 0
  @State private var dragging = false
  @State private var importing = false
  @State private var choosingPhotos = false
  @State private var pickedPhotos: [PhotosPickerItem] = []
  @State private var viewer: Photo?
  @State private var naming: NamePurpose?
  @State private var remove = false
  @State private var format = false
  @AppStorage("gallery.layout") private var layoutKey = "medium"
  private var layout: GalleryStyle { GalleryStyle(rawValue: layoutKey) ?? .medium }
  private var photos: [Photo] {
    let candidates =
      source == 0
      ? library.importedPhotos
      : library.photos.filter { model.cameraIDs.contains($0.id) }.sorted {
        $0.capturedAt > $1.capturedAt
      }
    return candidates.filter {
      filter == 0 || (filter == 1 && library.selection.contains($0.id))
        || (filter == 2 && $0.favorite) || (filter == 3 && $0.hasUnimportedFiles)
        || (filter == 4 && $0.files.contains(where: { $0.isRaw }))
    }
  }
  var body: some View {
    VStack(spacing: 0) {
      Picker(
        "照片来源",
        selection: Binding(
          get: { model.gallerySource },
          set: {
            model.gallerySource = $0
            library.selection = []
          })
      ) {
        Text("已导入").tag(0)
        Text("相机内").tag(1)
      }.pickerStyle(.segmented).padding(.horizontal, 16).padding(.vertical, 10)
      ScrollView(.horizontal) {
        HStack(spacing: 8) {
          ForEach(Array(["全部", "已选", "收藏", "未导入", "RAW"].enumerated()), id: \.offset) {
            index, title in
            Button(title) { filter = index }.font(.subheadline).buttonStyle(.bordered).tint(
              filter == index ? Palette.accent : .gray)
          }
        }.padding(.horizontal, 16)
      }.scrollIndicators(.hidden)
      HStack {
        Text("\(photos.count) 张")
        Spacer()
        if source == 1 {
          Button("刷新") { Task { await model.loadCamera(reset: true) } }.disabled(
            model.cameraInfo == nil)
        }
      }.font(.caption).foregroundStyle(.secondary).padding(16)
      if source == 1 && model.cameraInfo == nil {
        ContentUnavailableView {
          Label("相机已断开", systemImage: "wifi.slash")
        } description: {
          Text("重新连接后再读取相机照片。已导入原片仍在手机中。")
        } actions: {
          Button("连接相机") { if !model.reconnectSaved() { connect = true } }.buttonStyle(
            .borderedProminent)
        }
      } else if photos.isEmpty && !dragging {
        ContentUnavailableView(
          "暂无照片", systemImage: "photo",
          description: Text(source == 0 ? "使用右上角导入手机或读卡器文件。" : "可尝试刷新相机内容或调整筛选条件。"))
      } else {
        SelectablePhotoGrid(
          photos: photos, selection: $library.selection, style: layout,
          contextKey: "\(source):\(filter):\(layoutKey):\(model.cameraInfo?.id ?? "")",
          onDragging: { dragging = $0 }
        ) { viewer = $0 }
      }
      if source == 1 && model.cameraInfo != nil {
        Button("加载更多") { Task { await model.loadCamera(reset: false) } }.font(.footnote).padding(
          .bottom, 6)
      }
    }.background(Palette.background).navigationTitle("图库").navigationBarTitleDisplayMode(.inline)
      .toolbar {
        Menu {
          ForEach(GalleryStyle.allCases, id: \.rawValue) { option in
            Button {
              layoutKey = option.rawValue
            } label: {
              if option == layout {
                Label(option.title, systemImage: "checkmark")
              } else {
                Text(option.title)
              }
            }
          }
        } label: {
          Image(systemName: layout == .list ? "list.bullet" : "square.grid.2x2")
        }.accessibilityLabel("显示方式")
        Menu {
          Button("从照片图库选择") { choosingPhotos = true }
          Button("从文件或读卡器导入") { importing = true }
        } label: {
          Image(systemName: "photo.badge.plus")
        }
      }
      .safeAreaInset(edge: .bottom) {
        if !library.selection.isEmpty {
          GlassCapsule {
            HStack(spacing: 18) {
              Button("已选 \(library.selection.count) ×") { library.selection = [] }
              Spacer()
              if source == 1 { Button("导入") { format = true } }
              Menu {
                Button("保存这一批") { naming = .batch }
                Button("客户现场选片") { naming = .customer }
                if source == 0 { Button("移除", role: .destructive) { remove = true } }
              } label: {
                Image(systemName: "ellipsis")
              }.accessibilityLabel("选片操作")
            }.font(.subheadline)
          }.padding(12)
        }
      }
      .fileImporter(
        isPresented: $importing, allowedContentTypes: [.image, .rawImage],
        allowsMultipleSelection: true
      ) { if case .success(let urls) = $0 { model.importFiles(urls) } }
      .photosPicker(
        isPresented: $choosingPhotos, selection: $pickedPhotos, maxSelectionCount: 200,
        matching: .images, preferredItemEncoding: .current
      )
      .onChange(of: pickedPhotos) { _, items in
        Task {
          var files: [URL] = []
          for item in items {
            do {
              if let photo = try await item.loadTransferable(type: PickedPhoto.self) {
                files.append(photo.url)
              }
            } catch { model.notice = error.localizedDescription }
          }
          model.importFiles(files, deleteSources: true)
          pickedPhotos = []
        }
      }
      .fullScreenCover(item: $viewer) { photo in PhotoViewer(photos: photos, initial: photo.id) }
      .sheet(item: $naming) { purpose in
        NameEditor(title: purpose == .batch ? "保存这一批" : "客户现场选片", initial: "") { name in
          do {
            if purpose == .batch {
              _ = try library.saveBatch(title: name)
            } else {
              _ = try library.createReview(title: name, ids: library.selection)
            }
          } catch { model.notice = error.localizedDescription }
        }
      }
      .confirmationDialog("导入哪些文件？", isPresented: $format, titleVisibility: .visible) {
        Button("JPEG / HEIF") { model.enqueueDownloads(ids: library.selection, raw: false) }
        Button("RAW") { model.enqueueDownloads(ids: library.selection, raw: true) }
        Button("RAW + JPEG") {
          model.enqueueDownloads(ids: library.selection, raw: false, all: true)
        }
      }
      .confirmationDialog("移除所选照片", isPresented: $remove, titleVisibility: .visible) {
        Button("仅从列表移除") { removeSelection(false) }
        Button("删除App内原片与预览", role: .destructive) { removeSelection(true) }
      } message: {
        Text("仅操作手机App保存的副本，不删除相机卡或外部导入源。选片记录会保留文件名。")
      }
  }
  private func removeSelection(_ files: Bool) {
    do { try library.remove(library.selection, deleteFiles: files) } catch {
      model.notice = error.localizedDescription
    }
  }
}

enum NamePurpose: String, Identifiable {
  case batch, customer
  var id: String { rawValue }
}
struct NameEditor: View {
  @Environment(\.dismiss) private var dismiss
  let title: String
  let initial: String
  let save: (String) -> Void
  @State private var name = ""
  var body: some View {
    NavigationStack {
      Form {
        TextField("客户姓名、手机号或场次名称", text: $name)
        Text("填写后方便查找这一批选片，最多160字。").font(.caption).foregroundStyle(.secondary)
      }
      .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
        ToolbarItem(placement: .confirmationAction) {
          Button("保存") {
            save(name.trimmingCharacters(in: .whitespacesAndNewlines))
            dismiss()
          }.disabled(
            name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || name.count > 160)
        }
      }
    }.onAppear { name = initial }.presentationDetents([.medium, .large])
  }
}

struct PhotoViewer: View {
  @EnvironmentObject var library: LibraryStore
  @EnvironmentObject var model: AppModel
  @Environment(\.dismiss) private var dismiss
  let photos: [Photo]
  let initial: String
  @State private var current = ""
  @State private var shared: SharedFiles?
  @State private var filesInfo = false
  var body: some View {
    ZStack {
      Color.black.ignoresSafeArea()
      TabView(selection: $current) {
        ForEach(photos) { photo in
          ZoomPhoto(photo: library.photos.first { $0.id == photo.id } ?? photo).tag(photo.id)
        }
      }.tabViewStyle(.page(indexDisplayMode: .never))
    }
    .safeAreaInset(edge: .top) {
      HStack {
        Button {
          dismiss()
        } label: {
          Image(systemName: "xmark")
        }.accessibilityLabel("关闭照片")
        Spacer()
        VStack(spacing: 4) {
          Text(selected?.displayTitle ?? "照片").lineLimit(1)
          Text(
            selected?.imported == true
              ? (selected?.files.contains { !$0.isRaw && $0.localPath != nil } == true
                ? "已导入原片" : "已导入RAW原片 · 当前显示预览") : "相机预览 · 清晰度不代表原片"
          ).font(.caption)
            .foregroundStyle(.white.opacity(0.7))
        }
        Spacer()
        Button {
          filesInfo = true
        } label: {
          Image(systemName: "doc.text")
        }.accessibilityLabel("文件信息")
        Button {
          if let selected { try? library.favorite(selected.id) }
        } label: {
          Image(systemName: selected?.favorite == true ? "heart.fill" : "heart")
        }
        if let selected {
          let urls = selected.files.compactMap { $0.localPath }.compactMap {
            try? library.fileURL($0)
          }.filter { FileManager.default.fileExists(atPath: $0.path) }
          if !urls.isEmpty {
            Button {
              shared = SharedFiles(urls: urls)
            } label: {
              Image(systemName: "square.and.arrow.up")
            }.accessibilityLabel("分享原片")
          }
        }
      }.foregroundStyle(.white).padding(18).background(.black.opacity(0.7))
    }
    .safeAreaInset(edge: .bottom) {
      HStack {
        Text("\(photos.firstIndex(where:{$0.id==current}).map{$0+1} ?? 1) / \(photos.count)").font(
          .caption
        ).foregroundStyle(.white.opacity(0.7))
        Spacer()
        Button {
          library.toggle(current)
        } label: {
          Label(
            library.selection.contains(current) ? "已选择" : "选择这张",
            systemImage: library.selection.contains(current) ? "checkmark.circle.fill" : "circle"
          ).font(.headline).padding(.horizontal, 22).padding(.vertical, 13).background(
            library.selection.contains(current) ? Palette.accent : Color.white.opacity(0.15),
            in: Capsule()
          ).foregroundStyle(.white)
        }
      }.padding(18).background(.black.opacity(0.7))
    }
    .onAppear { current = initial }
    .sheet(item: $shared) { ShareSheet(items: $0.urls) }
    .sheet(isPresented: $filesInfo) {
      NavigationStack {
        List {
          if let selected {
            Text(selected.importTitle).font(.footnote)
            ForEach(Array(selected.files.enumerated()), id: \.offset) { _, file in
              VStack(alignment: .leading, spacing: 6) {
                Text(file.name)
                Text(
                  file.formatTitle + " · "
                    + ByteCountFormatter.string(fromByteCount: file.size, countStyle: .file) + " · "
                    + (file.localPath == nil ? "未导入" : "已导入")
                ).font(.caption).foregroundStyle(.secondary)
              }
            }
          }
        }.navigationTitle("原始文件").navigationBarTitleDisplayMode(.inline).toolbar {
          Button("完成") { filesInfo = false }
        }
      }.presentationDetents([.medium, .large])
    }
  }
  private var selected: Photo? {
    library.photos.first { $0.id == current } ?? photos.first { $0.id == current }
  }
}

private struct ZoomPhoto: View {
  @EnvironmentObject var model: AppModel
  let photo: Photo
  @State private var image: UIImage?
  @State private var loading = true
  @State private var scale: CGFloat = 1
  @GestureState private var magnification: CGFloat = 1
  var body: some View {
    GeometryReader { geometry in
      ScrollView([.horizontal, .vertical]) {
        Group {
          if let image {
            Image(uiImage: image).resizable().scaledToFit()
          } else if loading {
            ProgressView().tint(.white)
          } else {
            Text(
              photo.source == .camera && model.cameraInfo == nil
                ? "相机未连接，预览尚未缓存" : "此格式暂不能预览，可通过右上角分享原片"
            ).font(.caption).foregroundStyle(.white.opacity(0.7)).multilineTextAlignment(.center)
              .padding(24)
          }
        }.frame(
          width: geometry.size.width * min(6, max(1, scale * magnification)),
          height: geometry.size.height * min(6, max(1, scale * magnification)))
      }.gesture(
        MagnifyGesture().updating($magnification) { value, state, _ in state = value.magnification }
          .onEnded { scale = min(6, max(1, scale * $0.magnification)) }
      ).onTapGesture(count: 2) {
        withAnimation(.easeInOut(duration: 0.2)) { scale = scale > 1 ? 1 : 2.5 }
      }
    }.task(id: photo.id + photo.files.compactMap(\.localPath).joined()) {
      loading = true
      if let url = await model.previewURL(photo, large: true) {
        image = await Task.detached(priority: .userInitiated) {
          loadPhotoImage(url, maxPixel: 4096)
        }
        .value
      }
      loading = false
    }
  }
}
