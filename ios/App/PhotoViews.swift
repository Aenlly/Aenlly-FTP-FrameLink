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
  @State private var sourceURL: URL?
  @Environment(\.displayScale) private var displayScale
  private var resolvedPhoto: Photo { library.photos.first { $0.id == photo.id } ?? photo }
  private var sourceRevision: String {
    let logical = PhotoResourceIdentity(scope: "thumbnail", photo: resolvedPhoto).revision
    let representation = PhotoRepresentationRevision.key(photo: resolvedPhoto, cacheEpoch: model.previewRevision)
    return "\(logical):\(representation)"
  }
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
        .task(id: sourceRevision + (sourceURL?.absoluteString ?? "") + ":\(PhotoDecodePolicy.thumbnailPixel(width: geometry.size.width, height: geometry.size.height, displayScale: displayScale, large: large))") {
          guard let url = sourceURL else { image = nil; return }
          let target = PhotoDecodePolicy.thumbnailPixel(width: geometry.size.width, height: geometry.size.height, displayScale: displayScale, large: large)
          let decoded = await (large ? PhotoDisplayDecoder.shared : PhotoDisplayDecoder.thumbnails).decode(url, maxPixel: target)
          guard !Task.isCancelled else { return }
          image = decoded.map { UIImage(cgImage: $0.image) }
        }
    }
    .clipped().task(
      id: sourceRevision + String(large)
    ) {
      sourceURL = nil
      let url = await model.previewURL(resolvedPhoto, large: large)
      guard !Task.isCancelled else { return }
      sourceURL = url
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
  var columnCount: Int {
    switch self {
    case .small: return 5
    case .medium: return 3
    case .large: return 2
    case .list: return 1
    }
  }
  var targetWidth: Double { self == .small ? 68 : self == .medium ? 108 : 164 }
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
  var scrollAnchor: Binding<String?> = .constant(nil)
  @ScaledMetric(relativeTo: .body) private var tileScale = 1.0
  var onDragging: (Bool) -> Void = { _ in }
  var onNearEnd: () -> Void = {}
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
            columns: Array(repeating: GridItem(.flexible(), spacing: style == .small ? 2 : 8), count: style == .list ? 1 : PhotoWorkspaceLayout.gridColumns(width: viewport.size.width, target: style.targetWidth * tileScale, spacing: style == .small ? 2 : 8, padding: style == .small ? 2 : 12)), spacing: style == .small ? 2 : 16
          ) {
            ForEach(displayedPhotos) { photo in
              let itemLayout =
                style == .list
                ? AnyLayout(HStackLayout(alignment: .center, spacing: 14))
                : AnyLayout(VStackLayout(alignment: .leading, spacing: 7))
              itemLayout {
                ZStack(alignment: .bottomTrailing) {
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
                      selection.contains(photo.id) ? Color.blue : Color.black.opacity(0.4)
                    ).frame(width: 44, height: 44).contentShape(Rectangle())
                  }.buttonStyle(.plain).disabled(!selectionEnabled).accessibilityLabel(
                    "选择 \(photo.name)")
                }.aspectRatio(1, contentMode: .fit)
                  .frame(width: style == .list ? 84 : nil, height: style == .list ? 84 : nil)
                  .clipShape(RoundedRectangle(cornerRadius: style == .small ? 3 : 12, style: .continuous))
                  .overlay(alignment: .topLeading) {
                    Text(photo.formatTitle).font(.system(size: 9, weight: .medium)).foregroundStyle(
                      .white
                    ).padding(.horizontal, 6).padding(.vertical, 3).background(
                      .black.opacity(0.5), in: Capsule()
                    ).padding(6).allowsHitTesting(false)
                  }
                if style != .small { VStack(alignment: .leading, spacing: 5) {
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
                } }
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
                .onAppear { if photo.id == displayedPhotos.last?.id { onNearEnd() } }
            }
          }.scrollTargetLayout().padding(style == .small ? 2 : 12)
        }.scrollPosition(id: scrollAnchor, anchor: .top).coordinateSpace(name: "photo-grid").scrollDisabled(range != nil)
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
  @StateObject private var workspace = PhotoWorkspace()
  @ScaledMetric(relativeTo: .body) private var textScale = 1.0
  @State private var naming: NamePurpose?
  @State private var remove = false
  @State private var format = false
  @State private var selectingAll: Task<Void, Never>?
  @AppStorage("gallery.layout") private var layoutKey = "medium"
  private var layout: GalleryStyle { GalleryStyle(rawValue: layoutKey) ?? .medium }
  private var scopeKey: String { source == 0 ? "imported" : "camera:\(model.cameraInfo?.id ?? "offline"):\(model.cameraIndexState.scope?.slot ?? 1)" }
  private var candidates: [Photo] {
      source == 0
      ? library.importedPhotos
      : library.photos.filter { model.cameraIDs.contains($0.id) }.sorted {
        $0.orderingDate > $1.orderingDate
      }
  }
  private var photos: [Photo] {
    candidates.filter {
      filter == 0 || (filter == 1 && library.selection.contains($0.id))
        || (filter == 2 && $0.favorite) || (filter == 3 && $0.hasUnimportedFiles)
        || (filter == 4 && $0.files.contains(where: { $0.isRaw }))
    }
  }
  private var allSelected: Bool {
    !photos.isEmpty && photos.allSatisfy { library.selection.contains($0.id) }
      && !(source == 1 && model.cameraHasMore)
  }
  private var selectAllButton: some View {
    Button(allSelected ? "取消全选" : "全选", action: selectAll)
      .disabled(photos.isEmpty || selectingAll != nil)
  }
  private func selectAll() {
    let ids = Set(photos.map(\.id))
    if allSelected {
      library.selection.subtract(ids)
    } else if source == 1 && model.cameraHasMore {
      selectingAll = Task { @MainActor in
        defer { selectingAll = nil }
        do {
          try await model.readAllCameraPhotos()
          try Task.checkCancellation()
          library.selection.formUnion(photos.map(\.id))
        } catch is CancellationError { }
        catch { model.notice = "未能读取全部照片，请重试" }
      }
    } else { library.selection.formUnion(ids) }
  }
  private var galleryContent: some View {
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
        selectAllButton
        if source == 1 {
          if model.cameraInfo != nil && !model.cameraClientCapabilities.canBrowse { Text("当前连接未提供照片浏览能力") }
          else {
            let count = model.cameraIndexState.countText(matched: photos.count, filtered: filter != 0)
            VStack(alignment: .leading, spacing: 3) { Text(count.title); Text(count.detail).font(.caption2) }
          }
        } else { Text("\(photos.count) 张") }
        Spacer()
        if source == 1 {
          Button("刷新") { Task { await model.loadCamera(intent: .refresh) } }.disabled(
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
      } else if source == 1 && !model.cameraClientCapabilities.canBrowse {
        ContentUnavailableView("此连接未提供照片浏览", systemImage: "camera", description: Text("请核对机身已启用的功能及CCAPI端点，或使用FTP接收与本机导入。"))
      } else if source == 1 && photos.isEmpty && model.cameraLoading {
        ProgressView("正在读取照片索引").frame(maxWidth: .infinity, maxHeight: .infinity)
      } else if photos.isEmpty && !dragging {
        ContentUnavailableView(
          "暂无照片", systemImage: "photo",
          description: Text(source == 0 ? "使用右上角导入手机或读卡器文件。" : "可尝试刷新相机内容或调整筛选条件。"))
      } else {
        SelectablePhotoGrid(
          photos: photos, selection: $library.selection, style: layout,
          contextKey: "\(source):\(filter):\(layoutKey):\(model.cameraInfo?.id ?? ""):\(model.cameraIndexState.scope?.slot ?? 1):\(model.cameraIndexState.scope?.day ?? "")",
          scrollAnchor: $workspace.gridAnchor,
          onDragging: { dragging = $0 },
          onNearEnd: { if source == 1 && model.cameraIndexState.error == nil && model.cameraHasMore { Task { await model.loadCamera(intent: .automatic) } } }
        ) { workspace.open($0.id) }
      }
      if source == 1 && model.cameraInfo != nil && model.cameraHasMore {
        if let error = model.cameraIndexState.error {
          Button("读取中断，点此重试") { Task { await model.loadCamera(intent: .userRetry) } }.font(.footnote).help(error).padding(.bottom, 6)
        } else { Text(model.cameraLoading ? "正在补齐索引" : "正在准备照片列表").font(.footnote).foregroundStyle(.secondary).padding(.bottom, 6) }
      }
    }
      .safeAreaInset(edge: .bottom) {
        if !library.selection.isEmpty {
          GlassCapsule {
            ScrollView(.horizontal) {
            HStack(spacing: 18) {
              Button("已选 \(library.selection.count) ×") { library.selection = [] }.frame(minHeight: 44)
              Spacer()
              if source == 1 { Button("导入") { format = true }.frame(minHeight: 44) }
              Button("对比") { workspace.startComparison(candidates.filter { library.selection.contains($0.id) }.map(\.id)) }
                .disabled(candidates.filter { library.selection.contains($0.id) }.count != 2)
                .accessibilityLabel("对比所选照片")
              Menu {
                Button("保存这一批") { naming = .batch }
                Button("客户现场选片") { naming = .customer }
                if source == 0 { Button("移除", role: .destructive) { remove = true } }
              } label: {
                Image(systemName: "ellipsis")
              }.accessibilityLabel("选片操作")
            }.font(.subheadline)
            }.scrollIndicators(.hidden).frame(height: 52 * min(textScale, 1.7))
          }.padding(12)
        }
      }
  }
  var body: some View {
    GeometryReader { geometry in
      let window = PhotoWorkspaceLayout(window: PhotoWindow(width: geometry.size.width, height: geometry.size.height), detailOpen: workspace.isOpen, textScale: textScale)
      ZStack(alignment: .trailing) {
        HStack(spacing: 12) {
          galleryContent.frame(width: window.gridWidth).opacity(workspace.isOpen && !window.split ? 0 : 1)
            .allowsHitTesting(!workspace.isOpen || window.split).accessibilityHidden(workspace.isOpen && !window.split)
          if window.split { Spacer(minLength: 0) }
        }.frame(maxWidth: .infinity, alignment: .leading)
        if workspace.isOpen {
          PhotoDetailWorkspace(photos: candidates, workspace: workspace).frame(width: window.detailWidth)
            .clipShape(RoundedRectangle(cornerRadius: window.split ? 20 : 0, style: .continuous))
        }
      }.onAppear { workspace.reconcile(scope: scopeKey, available: Set(candidates.map(\.id))) }
        .onChange(of: scopeKey) { _, value in workspace.reconcile(scope: value, available: Set(candidates.map(\.id))) }
        .onChange(of: candidates.map(\.id)) { _, ids in workspace.reconcile(scope: scopeKey, available: Set(ids)) }
        .toolbar(window.hideNavigation ? .hidden : .visible, for: .navigationBar, .tabBar)
    }.accessibilityIdentifier("gallery-workspace").background(Palette.background).navigationTitle("图库").navigationBarTitleDisplayMode(.inline)
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
      .fileImporter(
        isPresented: $importing, allowedContentTypes: [.image, .rawImage],
        allowsMultipleSelection: true
      ) { if case .success(let urls) = $0 { model.importFiles(urls) } }
      .photosPicker(
        isPresented: $choosingPhotos, selection: $pickedPhotos, maxSelectionCount: nil,
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
      .onChange(of: source) { _, _ in selectingAll?.cancel() }
      .onChange(of: filter) { _, _ in selectingAll?.cancel() }
      .onDisappear { selectingAll?.cancel() }
      .alert("正在读取全部照片", isPresented: Binding(get: { selectingAll != nil }, set: { if !$0 { selectingAll?.cancel() } })) {
        Button("取消", role: .cancel) { selectingAll?.cancel() }
      } message: { Text("已读取\(model.cameraIDs.count)张，将按当前筛选全选") }
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
    model.removePhotos(library.selection, deleteFiles: files)
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

/// Existing record-page entry keeps the same presentation API and persistent workspace.
struct PhotoViewer: View {
  @Environment(\.dismiss) private var dismiss
  let photos: [Photo]
  let initial: String
  @StateObject private var workspace = PhotoWorkspace()
  var body: some View {
    PhotoDetailWorkspace(photos: photos, workspace: workspace)
      .onAppear {
        workspace.reconcile(scope: "record-viewer", available: Set(photos.map(\.id)))
        if workspace.currentID == nil { workspace.open(initial) }
      }
      .onChange(of: workspace.isOpen) { _, open in if !open { dismiss() } }
      .onChange(of: photos.map(\.id)) { _, ids in workspace.reconcile(scope: "record-viewer", available: Set(ids)) }
  }
}

struct PhotoDetailWorkspace: View {
  @EnvironmentObject var library: LibraryStore
  @EnvironmentObject var model: AppModel
  let photos: [Photo]
  @ObservedObject var workspace: PhotoWorkspace
  @State private var shared: SharedFiles?
  @ScaledMetric(relativeTo: .body) private var textScale = 1.0
  private func resolved(_ id: String?) -> Photo? { library.photos.first { $0.id == id } ?? photos.first { $0.id == id } }
  private var selected: Photo? { resolved(workspace.focusedID) }
  private var urls: [URL] {
    selected?.files.compactMap(\.localPath).compactMap { try? library.fileURL($0) }
      .filter { FileManager.default.fileExists(atPath: $0.path) } ?? []
  }
  var body: some View {
    GeometryReader { outer in
      let full = PhotoWorkspaceLayout(window: PhotoWindow(width: outer.size.width, height: outer.size.height), detailOpen: false, textScale: textScale)
      VStack(spacing: 0) {
        if workspace.toolsVisible { header }
        GeometryReader { area in
          let imageLayout = PhotoWorkspaceLayout(window: PhotoWindow(width: area.size.width, height: area.size.height), detailOpen: false, textScale: textScale)
          let panes = imageLayout.horizontalComparison ? AnyLayout(HStackLayout(spacing: 8)) : AnyLayout(VStackLayout(spacing: 8))
          ZStack(alignment: .topTrailing) {
            panes {
              ForEach(Array(workspace.visibleIDs.enumerated()), id: \.element) { side, id in
                if let photo = resolved(id) {
                  let identity = PhotoResourceIdentity(scope: workspace.scope, photo: photo)
                  VStack(spacing: 0) {
                    if workspace.comparison.count == 2 && workspace.toolsVisible {
                      Button {
                        workspace.focus(side)
                      } label: {
                        HStack { Text(imageLayout.horizontalComparison ? (side == 0 ? "左图" : "右图") : (side == 0 ? "上图" : "下图")); Text(photo.displayTitle).lineLimit(1); Spacer() }
                          .font(.caption).padding(8).frame(minHeight: 44)
                      }.foregroundStyle(workspace.focusedSide == side ? Color.yellow : Color.white)
                        .accessibilityLabel(side == 0 ? "查看第一幅照片信息" : "查看第二幅照片信息")
                    }
                    ZoomPhoto(photo: photo, identity: identity, record: workspace.record(for: identity),
                      resolver: workspace.previews, comparing: workspace.comparison.count == 2,
                      toggleTools: { workspace.toolsVisible.toggle() },
                      navigate: { workspace.step($0, orderedIDs: photos.map(\.id)) })
                      .id(identity)
                  }.frame(maxWidth: .infinity, maxHeight: .infinity)
                }
              }
            }
            if !workspace.toolsVisible {
              Button { workspace.toolsVisible = true } label: {
                Image(systemName: "ellipsis").frame(width: 44, height: 44).background(.ultraThinMaterial, in: Circle())
              }.accessibilityLabel("显示照片工具").padding(8)
            }
          }
        }
        if workspace.toolsVisible {
          controls
          if full.showsFilmstrip {
            ScrollView(.horizontal) {
              LazyHStack(spacing: 8) {
                ForEach(photos) { photo in
                  Button { workspace.tapThumbnail(photo.id) } label: {
                    PhotoThumbnail(photo: photo).frame(width: 52, height: 52)
                      .clipShape(RoundedRectangle(cornerRadius: 8))
                      .overlay(RoundedRectangle(cornerRadius: 8).stroke(workspace.focusedID == photo.id ? Color.yellow : .clear, lineWidth: 2))
                  }.accessibilityLabel("缩略图 " + photo.name)
                }
              }.padding(8)
            }.frame(height: 68).scrollIndicators(.hidden)
          }
        }
      }.background(.black).foregroundStyle(.white)
    }
    .accessibilityIdentifier("photo-detail-workspace")
    .sheet(item: $shared) { ShareSheet(items: $0.urls) }
    .sheet(isPresented: $workspace.filesInfo) {
      NavigationStack {
        List {
          if let selected {
            PhotoMetadataSection(photo: selected)
            Text(selected.importTitle).font(.footnote)
            ForEach(Array(selected.files.enumerated()), id: \.offset) { _, file in
              VStack(alignment: .leading, spacing: 6) {
                Text(file.name)
                Text(file.formatTitle + " · " + ByteCountFormatter.string(fromByteCount: file.size, countStyle: .file) + " · " + (file.localPath == nil ? "未导入" : "已导入")).font(.caption).foregroundStyle(.secondary)
                if let day = file.fileDay, !day.isEmpty { Text("文件修改日期：" + day + "（非拍摄时间）").font(.caption).foregroundStyle(.secondary) }
              }
            }
          }
        }.navigationTitle("原始文件").navigationBarTitleDisplayMode(.inline).toolbar {
          Button("完成") { workspace.filesInfo = false }
        }
      }.presentationDetents([.medium, .large])
    }
  }
  private var header: some View {
    HStack(spacing: 8) {
      Button { workspace.close() } label: { Image(systemName: "xmark").frame(width: 44, height: 44) }.accessibilityLabel("关闭照片")
      VStack(spacing: 3) {
        Text(selected?.displayTitle ?? "照片").font(.subheadline).lineLimit(1)
        Text(previewStatus).font(.caption2).foregroundStyle(.white.opacity(0.7)).lineLimit(1)
      }.frame(maxWidth: .infinity)
      Button { workspace.filesInfo = true } label: { Image(systemName: "doc.text").frame(width: 44, height: 44) }.accessibilityLabel("文件信息")
      Menu {
        if let selected { Button(selected.favorite ? "取消收藏" : "收藏") { try? library.favorite(selected.id) } }
        if !urls.isEmpty { Button("分享原片") { shared = SharedFiles(urls: urls) } }
        if workspace.comparison.count == 2 { Button("结束对比") { workspace.endComparison() } }
        else {
          Button("与下一张对比") {
            guard let id = workspace.currentID, let index = photos.firstIndex(where: { $0.id == id }), photos.count > 1 else { return }
            workspace.startComparison([id, photos[(index + 1) % photos.count].id])
          }.disabled(photos.count < 2)
        }
      } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }.accessibilityLabel("照片操作")
    }.padding(6).modifier(PhotoGlassChrome()).padding(6).environment(\.colorScheme, .dark)
  }
  private var controls: some View {
    ScrollView(.horizontal) {
      HStack(spacing: 14) {
        if workspace.comparison.isEmpty {
          Button { workspace.step(-1, orderedIDs: photos.map(\.id)) } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44) }.accessibilityLabel("上一张照片")
          Button { workspace.step(1, orderedIDs: photos.map(\.id)) } label: { Image(systemName: "chevron.right").frame(width: 44, height: 44) }.accessibilityLabel("下一张照片")
        } else { Button("结束对比") { workspace.endComparison() }.frame(minHeight: 44) }
        if let selected {
          Button { library.toggle(selected.id) } label: {
            Label(library.selection.contains(selected.id) ? "已选择" : "选择这张", systemImage: library.selection.contains(selected.id) ? "checkmark.circle.fill" : "circle")
              .padding(.horizontal, 14).frame(minHeight: 44).background(library.selection.contains(selected.id) ? Color.blue : .white.opacity(0.12), in: Capsule())
          }.accessibilityLabel("选择当前照片").accessibilityValue(library.selection.contains(selected.id) ? "已选择" : "未选择")
          if selected.files.contains(where: { $0.formatTitle == "JPEG" && $0.localPath == nil }) {
            Button {
              model.requestOriginalPreview(selected)
            } label: {
              if model.originalPreviewRequests.contains(selected.id) { ProgressView().tint(.white) }
              else { Text(model.originalPreviews[selected.id] == nil ? "查看原片" : "JPEG原片") }
            }.frame(minHeight: 44).disabled(model.originalPreviewRequests.contains(selected.id) || model.clearingPreviews)
          }
        }
      }.font(.subheadline).padding(8)
    }.scrollIndicators(.hidden).frame(height: 64 * min(textScale, 1.7)).modifier(PhotoGlassChrome()).padding(.horizontal, 6).environment(\.colorScheme, .dark)
  }
  private var previewStatus: String {
    guard let selected else { return "照片预览" }
    if selected.files.contains(where: { !$0.isRaw && $0.localPath != nil }) { return "已导入原片 · 显示预览" }
    if model.originalPreviews[selected.id] != nil { return selected.imported ? "RAW已导入 · 临时JPEG原片未导入" : "临时JPEG原片 · 未导入" }
    return selected.imported ? "已导入RAW原片 · 当前显示预览" : "相机预览 · 清晰度不代表原片"
  }
}


private struct PhotoGlassChrome: ViewModifier {
  @ViewBuilder func body(content: Content) -> some View {
    #if compiler(>=6.2)
      if #available(iOS 26.0, *) {
        content.glassEffect(.regular, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
      } else { content.background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18)) }
    #else
      content.background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18))
    #endif
  }
}

private struct ZoomPhoto: View {
  @EnvironmentObject var model: AppModel
  let photo: Photo
  let identity: PhotoResourceIdentity
  @ObservedObject var record: PhotoViewportRecord
  let resolver: PhotoPreviewResolver
  let comparing: Bool
  var toggleTools: () -> Void
  var navigate: (Int) -> Void
  @State private var image: UIImage?
  @State private var loading = true
  private var representation: String {
    PhotoRepresentationRevision.key(photo: photo, cacheEpoch: model.previewRevision)
  }
  var body: some View {
    ZStack {
      Color.black
      if let image {
        PhotoZoomCanvas(image: image, viewport: $record.value, label: photo.name, tap: toggleTools, navigate: comparing ? { _ in } : navigate)
      } else if loading { ProgressView().tint(.white) }
      else { Text(photo.source == .camera && model.cameraInfo == nil ? "相机未连接，预览尚未缓存" : "此格式暂不能预览，可在照片操作中分享原片").font(.caption).foregroundStyle(.white.opacity(0.7)).multilineTextAlignment(.center).padding(20) }
    }
    .task(id: representation + ":\(PhotoDecodePolicy.maxPixel(comparing: comparing))") {
      image = nil; loading = true
      let url = await resolver.resolve(identity: identity, representation: representation) { await model.previewURL(photo, large: true) }
      guard !Task.isCancelled else { return }
      if let url {
        let decoded = await PhotoDisplayDecoder.shared.decode(url, maxPixel: PhotoDecodePolicy.maxPixel(comparing: comparing))
        guard !Task.isCancelled else { return }
        image = decoded.map { UIImage(cgImage: $0.image) }
      }
      loading = false
    }
  }
}

private final class PhotoScrollCanvas: UIScrollView {
  var resized: ((CGSize) -> Void)?
  var geometryChanging: ((Bool) -> Void)?
  private var lastBounds = CGSize.zero
  override func layoutSubviews() {
    let changed = lastBounds != bounds.size; lastBounds = bounds.size
    if changed { geometryChanging?(true) }
    super.layoutSubviews()
    if changed { geometryChanging?(false) }
    if bounds.width > 0 && bounds.height > 0 { resized?(bounds.size) }
  }
}
private struct PhotoZoomCanvas: UIViewRepresentable {
  let image: UIImage
  @Binding var viewport: PhotoViewport
  var label: String
  var tap: () -> Void
  var navigate: (Int) -> Void
  func makeCoordinator() -> Coordinator { Coordinator(self) }
  func makeUIView(context: Context) -> PhotoScrollCanvas {
    let scroll = PhotoScrollCanvas()
    scroll.backgroundColor = .black; scroll.delegate = context.coordinator
    scroll.minimumZoomScale = 1; scroll.maximumZoomScale = 6
    scroll.showsHorizontalScrollIndicator = false; scroll.showsVerticalScrollIndicator = false
    scroll.addSubview(context.coordinator.photo)
    scroll.resized = { [weak coordinator = context.coordinator, weak scroll] size in if let scroll { coordinator?.apply(scroll, size: size) } }
    scroll.geometryChanging = { [weak coordinator = context.coordinator] in coordinator?.resizing = $0 }
    let double = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.doubleTap(_:))); double.numberOfTapsRequired = 2
    let single = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.singleTap)); single.require(toFail: double)
    scroll.addGestureRecognizer(single); scroll.addGestureRecognizer(double)
    return scroll
  }
  func updateUIView(_ scroll: PhotoScrollCanvas, context: Context) {
    context.coordinator.parent = self
    context.coordinator.apply(scroll, size: scroll.bounds.size)
  }
  final class Coordinator: NSObject, UIScrollViewDelegate {
    var parent: PhotoZoomCanvas
    let photo = UIImageView()
    private var applying = false
    var resizing = false
    private var lastSize = CGSize.zero
    private var lastValue = PhotoViewport()
    init(_ parent: PhotoZoomCanvas) { self.parent = parent; super.init(); photo.contentMode = .scaleAspectFit; photo.isAccessibilityElement = true; photo.accessibilityTraits = .image }
    func viewForZooming(in scrollView: UIScrollView) -> UIView? { photo }
    func apply(_ scroll: UIScrollView, size: CGSize) {
      guard size.width > 0, size.height > 0, !applying else { return }
      let imageChanged = photo.image !== parent.image
      guard imageChanged || size != lastSize || parent.viewport != lastValue else { return }
      applying = true; defer { applying = false }
      lastSize = size; lastValue = parent.viewport; photo.image = parent.image; photo.accessibilityLabel = parent.label
      photo.accessibilityValue = String(format: "缩放%.2f倍", parent.viewport.scale)
      let presentation = parent.viewport.presentation(image: PhotoWindow(width: parent.image.size.width, height: parent.image.size.height), viewport: PhotoWindow(width: size.width, height: size.height))
      scroll.setZoomScale(1, animated: false)
      photo.frame = CGRect(x: 0, y: 0, width: presentation.baseWidth, height: presentation.baseHeight)
      scroll.contentSize = photo.bounds.size
      scroll.setZoomScale(parent.viewport.scale, animated: false)
      scroll.contentInset = UIEdgeInsets(top: presentation.insetY, left: presentation.insetX, bottom: presentation.insetY, right: presentation.insetX)
      scroll.setContentOffset(CGPoint(x: presentation.offsetX, y: presentation.offsetY), animated: false)
    }
    private func record(_ scroll: UIScrollView) {
      guard !applying, !resizing, scroll.bounds.width > 0, scroll.contentSize.width > 0, scroll.contentSize.height > 0 else { return }
      let value = PhotoViewport(scale: scroll.zoomScale, centerX: (scroll.contentOffset.x + scroll.bounds.width / 2) / scroll.contentSize.width, centerY: (scroll.contentOffset.y + scroll.bounds.height / 2) / scroll.contentSize.height)
      lastValue = value
      photo.accessibilityValue = String(format: "缩放%.2f倍", value.scale)
      if parent.viewport != value { parent.viewport = value }
    }
    func scrollViewDidScroll(_ scrollView: UIScrollView) { if scrollView.isDragging || scrollView.isDecelerating { record(scrollView) } }
    func scrollViewDidZoom(_ scrollView: UIScrollView) {
      guard !applying else { return }
      scrollView.contentInset = UIEdgeInsets(top: max(0, (scrollView.bounds.height - scrollView.contentSize.height) / 2), left: max(0, (scrollView.bounds.width - scrollView.contentSize.width) / 2), bottom: max(0, (scrollView.bounds.height - scrollView.contentSize.height) / 2), right: max(0, (scrollView.bounds.width - scrollView.contentSize.width) / 2))
      if scrollView.isZooming { record(scrollView) }
    }
    func scrollViewDidEndZooming(_ scrollView: UIScrollView, with view: UIView?, atScale scale: CGFloat) { record(scrollView) }
    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { record(scrollView) }
    func scrollViewWillEndDragging(_ scrollView: UIScrollView, withVelocity velocity: CGPoint, targetContentOffset: UnsafeMutablePointer<CGPoint>) {
      if scrollView.zoomScale <= 1.01 && abs(velocity.x) > 0.8 && abs(velocity.x) > abs(velocity.y) { parent.navigate(velocity.x > 0 ? 1 : -1) }
      else { record(scrollView) }
    }
    @objc func singleTap() { parent.tap() }
    @objc func doubleTap(_ gesture: UITapGestureRecognizer) {
      if parent.viewport.scale > 1 { parent.viewport = PhotoViewport() }
      else {
        let point = gesture.location(in: photo)
        parent.viewport = PhotoViewport(scale: 2.5, centerX: point.x / max(1, photo.bounds.width), centerY: point.y / max(1, photo.bounds.height))
      }
    }
  }
}

private struct PhotoMetadataSection: View {
  @EnvironmentObject var model: AppModel
  @EnvironmentObject var library: LibraryStore
  let photo: Photo
  @State private var metadata = PhotoMetadata()
  @State private var source = ""
  @State private var loading = true
  var body: some View {
    Section("拍摄信息") {
      if loading { ProgressView("读取照片参数") }
      else {
        LabeledContent("感光度", value: metadata.iso.map { "ISO " + $0 } ?? "未记录")
        LabeledContent("快门速度", value: metadata.shutter ?? "未记录")
        LabeledContent("光圈", value: metadata.aperture ?? "未记录")
        if let value = metadata.capturedAt { LabeledContent("拍摄时间", value: value) }
        if let value = metadata.camera { LabeledContent("相机", value: value) }
        if let value = metadata.lens { LabeledContent("镜头", value: value) }
        if let value = metadata.focalLength { LabeledContent("焦距", value: value) }
        if let value = metadata.equivalentFocalLength { LabeledContent("等效焦距", value: value) }
        if let value = metadata.exposureBias { LabeledContent("曝光补偿", value: value) }
        if let value = metadata.pixelSize { LabeledContent("像素尺寸", value: value) }
        if let latitude = metadata.latitude, let longitude = metadata.longitude {
          LabeledContent("GPS", value: String(format: "%.6f, %.6f", latitude, longitude))
          if let url = metadata.mapURL { Link("在地图中查看", destination: url) }
          else { Text("此坐标基准暂不支持地图定位").font(.caption).foregroundStyle(.secondary) }
        }
        if let value = metadata.coordinateDatum { LabeledContent("坐标基准", value: value) }
        if let value = metadata.altitude { LabeledContent("海拔", value: value) }
        Text(source.isEmpty ? "暂无可读取文件，预览照片或查看JPEG原片后再试。" : "来源：" + source + (metadata.readable ? "" : " · 此格式未读到参数")).font(.caption).foregroundStyle(.secondary)
      }
    }.task(id: PhotoMetadataRefreshKey(photo: sourcePhoto, previewRevision: model.previewRevision)) {
      loading = true; metadata = PhotoMetadata(); source = ""
      if let file = await model.metadataFile(sourcePhoto) {
        guard !Task.isCancelled else { return }
        let read = await Task.detached(priority: .utility) { PhotoMetadataReader.read(file.0) }.value
        guard !Task.isCancelled else { return }
        metadata = read; source = file.1
      }
      loading = false
    }
  }
  private var sourcePhoto: Photo { library.photos.first(where: { $0.id == photo.id }) ?? photo }
}
