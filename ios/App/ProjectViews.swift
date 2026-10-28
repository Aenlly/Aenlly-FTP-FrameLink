import FrameLinkCore
import SwiftUI

struct ProjectsView: View {
  @EnvironmentObject var library: LibraryStore
  @EnvironmentObject var model: AppModel
  @State private var edit: RecordEdit?
  @State private var deleting: RecordEdit?
  var body: some View {
    List {
      Section("我的选片") {
        if library.batches.isEmpty {
          Text("在图库选择照片，保存为一批，随时回来继续。").font(.subheadline).foregroundStyle(.secondary)
        }
        ForEach(library.batches) { batch in
          NavigationLink {
            SelectionAlbum(batchID: batch.id)
          } label: {
            RecordRow(title: batch.title, photos: batch.photos, date: batch.updatedAt)
          }
          .swipeActions {
            Button("删除", role: .destructive) {
              deleting = RecordEdit(id: batch.id, title: batch.title, customer: false)
            }
            Button("改名") { edit = RecordEdit(id: batch.id, title: batch.title, customer: false) }
              .tint(Palette.accent)
          }
        }
      }
      Section("客户现场选片") {
        if library.reviews.isEmpty {
          Text("选择待看的照片，以客户姓名或手机号建立记录。客户选择与自己的选择分别保存。").font(.subheadline).foregroundStyle(
            .secondary)
        }
        ForEach(library.reviews) { review in
          NavigationLink {
            CustomerReviewView(reviewID: review.id)
          } label: {
            VStack(alignment: .leading, spacing: 6) {
              RecordRow(title: review.title, photos: review.photos, date: review.updatedAt)
              Text(
                "客户已选 \(review.choices.values.filter(\.selected).count) 张 · \(review.locked ? "已完成":"选片中")"
              ).font(.caption).foregroundStyle(.secondary)
            }
          }
          .swipeActions {
            Button("删除", role: .destructive) {
              deleting = RecordEdit(id: review.id, title: review.title, customer: true)
            }
            Button("改名") { edit = RecordEdit(id: review.id, title: review.title, customer: true) }
              .tint(Palette.accent)
          }
        }
      }
    }.scrollContentBackground(.hidden).background(Palette.background).navigationTitle("项目")
      .navigationBarTitleDisplayMode(.inline)
      .sheet(item: $edit) { record in
        NameEditor(title: "编辑名称", initial: record.title) { name in
          do {
            if record.customer {
              try library.renameReview(record.id, title: name)
            } else {
              try library.renameBatch(record.id, title: name)
            }
          } catch { model.notice = error.localizedDescription }
        }
      }
      .confirmationDialog(
        "删除选片记录？",
        isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
        titleVisibility: .visible
      ) {
        Button("删除记录", role: .destructive) {
          guard let record = deleting else { return }
          do {
            if record.customer {
              try library.deleteReview(record.id)
            } else {
              try library.deleteBatch(record.id)
            }
          } catch { model.notice = error.localizedDescription }
          deleting = nil
        }
      } message: {
        Text("只删除这次记录，不删除照片原片。")
      }
  }
}

private struct RecordEdit: Identifiable {
  let id: String
  let title: String
  let customer: Bool
}
private struct RecordRow: View {
  let title: String
  let photos: [Photo]
  let date: Date
  var body: some View {
    HStack(spacing: 12) {
      if let cover = photos.first {
        PhotoThumbnail(photo: cover).frame(width: 64, height: 64).clipShape(
          RoundedRectangle(cornerRadius: 14))
      }
      VStack(alignment: .leading, spacing: 6) {
        Text(title).font(.headline).lineLimit(2)
        Text("\(photos.count) 张 · \(date.formatted(date:.abbreviated,time:.shortened))").font(
          .caption
        ).foregroundStyle(.secondary)
      }
    }.padding(.vertical, 5)
  }
}

struct SelectionAlbum: View {
  @EnvironmentObject var library: LibraryStore
  @EnvironmentObject var model: AppModel
  let batchID: String
  @State private var viewer: Photo?
  @State private var shared: SharedFiles?
  @State private var exporting = false
  private var batch: SelectionBatch? { library.batches.first { $0.id == batchID } }
  var body: some View {
    Group {
      if let batch {
        ScrollView {
          LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 12)], spacing: 16) {
            ForEach(batch.photos) { photo in
              VStack(alignment: .leading, spacing: 6) {
                PhotoThumbnail(photo: photo).aspectRatio(1, contentMode: .fit).clipShape(
                  RoundedRectangle(cornerRadius: 22))
                Text(photo.name).font(.caption).foregroundStyle(.secondary).lineLimit(1)
              }.onTapGesture { viewer = photo }
            }
          }.padding(16)
        }
        .safeAreaInset(edge: .bottom) {
          GlassCapsule {
            HStack {
              Button("恢复为本次选择") {
                model.gallerySource = 0
                library.selection = Set(
                  batch.photos.filter { saved in library.photos.contains { $0.id == saved.id } }
                    .map(\.id))
                model.notice = "已恢复选择，可返回图库继续"
              }
              Spacer()
              Button(exporting ? "生成中" : "分享选片图") { export(batch.title, batch.photos) }.disabled(
                exporting)
            }
          }.padding(12)
        }
      } else {
        ContentUnavailableView("记录已删除", systemImage: "folder")
      }
    }.background(Palette.background).navigationTitle(batch?.title ?? "选片记录")
      .navigationBarTitleDisplayMode(.inline)
      .fullScreenCover(item: $viewer) { photo in
        PhotoViewer(photos: batch?.photos ?? [], initial: photo.id)
      }
      .sheet(item: $shared) { ShareSheet(items: $0.urls) }
  }
  private func export(_ title: String, _ photos: [Photo]) {
    exporting = true
    Task {
      defer { exporting = false }
      do {
        shared = SharedFiles(
          urls: try await ContactSheets.make(title: title, photos: photos, model: model))
      } catch { model.notice = error.localizedDescription }
    }
  }
}

struct CustomerReviewView: View {
  @EnvironmentObject var library: LibraryStore
  @EnvironmentObject var model: AppModel
  let reviewID: String
  @State private var selectedOnly = false
  @State private var editName = false
  @State private var commentPhoto: Photo?
  @State private var shared: SharedFiles?
  @State private var exporting = false
  private var review: CustomerReview? { library.reviews.first { $0.id == reviewID } }
  private var selected: Set<String> {
    Set(review?.choices.filter { $0.value.selected }.map(\.key) ?? [])
  }
  var body: some View {
    Group {
      if let review {
        VStack(spacing: 0) {
          Toggle("只看客户已选", isOn: $selectedOnly).font(.subheadline).padding(16)
          SelectablePhotoGrid(
            photos: review.photos.filter { !selectedOnly || selected.contains($0.id) },
            selection: Binding(
              get: { selected },
              set: { next in
                guard !review.locked else { return }
                do {
                  for photo in review.photos
                  where selected.contains(photo.id) != next.contains(photo.id) {
                    try library.choose(
                      review: reviewID, photo: photo.id, selected: next.contains(photo.id),
                      comment: review.choices[photo.id]?.comment ?? "")
                  }
                } catch { model.notice = error.localizedDescription }
              }), selectionEnabled: !review.locked
          ) { commentPhoto = $0 }
        }.safeAreaInset(edge: .bottom) {
          GlassCapsule {
            HStack {
              Text("\(selected.count) 张客户已选").font(.subheadline)
              Spacer()
              if review.locked {
                Button("继续修改") { try? library.lockReview(reviewID, locked: false) }
              } else {
                Button("完成选择") { try? library.lockReview(reviewID, locked: true) }.buttonStyle(
                  .borderedProminent)
              }
            }
          }.padding(12)
        }
      } else {
        ContentUnavailableView("记录已删除", systemImage: "person")
      }
    }.background(Palette.background).navigationTitle(review?.title ?? "现场选片")
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        Button {
          editName = true
        } label: {
          Image(systemName: "pencil")
        }
        Button {
          guard let review else { return }
          exporting = true
          Task {
            defer { exporting = false }
            do {
              shared = SharedFiles(
                urls: try await ContactSheets.make(
                  title: review.title, photos: review.photos.filter { selected.contains($0.id) },
                  model: model))
            } catch { model.notice = error.localizedDescription }
          }
        } label: {
          Image(systemName: "square.and.arrow.up")
        }.disabled(selected.isEmpty || exporting)
      }
      .sheet(isPresented: $editName) {
        NameEditor(title: "客户姓名或手机号", initial: review?.title ?? "") {
          try? library.renameReview(reviewID, title: $0)
        }
      }
      .sheet(item: $commentPhoto) { photo in CustomerPhotoEditor(reviewID: reviewID, photo: photo) }
      .sheet(item: $shared) { ShareSheet(items: $0.urls) }
  }
}

private struct CustomerPhotoEditor: View {
  @EnvironmentObject var library: LibraryStore
  @EnvironmentObject var model: AppModel
  @Environment(\.dismiss) private var dismiss
  let reviewID: String
  let photo: Photo
  @State private var comment = ""
  @State private var chosen = false
  private var locked: Bool { library.reviews.first { $0.id == reviewID }?.locked ?? true }
  var body: some View {
    NavigationStack {
      ScrollView {
        VStack(spacing: 20) {
          PhotoThumbnail(photo: photo, large: true).aspectRatio(1, contentMode: .fit).clipShape(
            RoundedRectangle(cornerRadius: 22))
          Toggle("客户选择这张", isOn: $chosen).disabled(locked)
          TextField("给摄影师留言", text: $comment, axis: .vertical).lineLimit(3...8).padding(14)
            .background(Palette.background, in: RoundedRectangle(cornerRadius: 16)).disabled(locked)
        }.padding(16)
      }.navigationTitle(photo.name).navigationBarTitleDisplayMode(.inline)
        .toolbar {
          Button(locked ? "完成" : "保存") {
            if locked {
              dismiss()
              return
            }
            do {
              try library.choose(
                review: reviewID, photo: photo.id, selected: chosen, comment: comment)
              dismiss()
            } catch { model.notice = error.localizedDescription }
          }
        }
    }.onAppear {
      let choice = library.reviews.first { $0.id == reviewID }?.choices[photo.id]
      comment = choice?.comment ?? ""
      chosen = choice?.selected ?? false
    }
  }
}

struct SharedFiles: Identifiable {
  let id = UUID()
  let urls: [URL]
}
struct ShareSheet: UIViewControllerRepresentable {
  let items: [URL]
  func makeUIViewController(context: Context) -> UIActivityViewController {
    UIActivityViewController(activityItems: items, applicationActivities: nil)
  }
  func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

@MainActor enum ContactSheets {
  static func make(title: String, photos: [Photo], model: AppModel) async throws -> [URL] {
    guard !photos.isEmpty else { throw LibraryError.missingPhoto }
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(
      "selection-sheets/\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    var result: [URL] = []
    for start in stride(from: 0, to: photos.count, by: 12) {
      let page = Array(photos[start..<min(start + 12, photos.count)])
      var images: [String: UIImage] = [:]
      for photo in page {
        if let url = await model.previewURL(photo), let image = loadPhotoImage(url, maxPixel: 720) {
          images[photo.id] = image
        }
      }
      let columns = min(3, page.count)
      let cell = CGFloat(1680 / columns)
      let rows = (page.count + columns - 1) / columns
      let size = CGSize(width: 1800, height: CGFloat(rows) * (cell + 62) + 220)
      let image = UIGraphicsImageRenderer(size: size).image { context in
        UIColor.white.setFill()
        context.fill(CGRect(origin: .zero, size: size))
        (title as NSString).draw(
          in: CGRect(x: 60, y: 38, width: 1680, height: 80),
          withAttributes: [
            .font: UIFont.boldSystemFont(ofSize: 38), .foregroundColor: UIColor.black,
          ])
        for (i, photo) in page.enumerated() {
          let frame = CGRect(
            x: 60 + CGFloat(i % columns) * cell, y: 140 + CGFloat(i / columns) * (cell + 62),
            width: cell - 18, height: cell - 18)
          UIColor.systemGray6.setFill()
          context.fill(frame)
          if let source = images[photo.id] {
            let ratio = min(frame.width / source.size.width, frame.height / source.size.height)
            let bounds = CGRect(
              x: frame.midX - source.size.width * ratio / 2,
              y: frame.midY - source.size.height * ratio / 2, width: source.size.width * ratio,
              height: source.size.height * ratio)
            source.draw(in: bounds)
          }
          (photo.name as NSString).draw(
            in: CGRect(x: frame.minX, y: frame.maxY + 10, width: frame.width, height: 48),
            withAttributes: [
              .font: UIFont.systemFont(ofSize: 22), .foregroundColor: UIColor.darkGray,
            ])
        }
        ("FrameLink · 选片预览 · \(start/12+1)/\((photos.count+11)/12)" as NSString).draw(
          at: CGPoint(x: 60, y: size.height - 50),
          withAttributes: [.font: UIFont.systemFont(ofSize: 20), .foregroundColor: UIColor.gray])
      }
      let url = directory.appendingPathComponent("选片-\(start/12+1).jpg")
      guard let data = image.jpegData(compressionQuality: 0.88) else {
        throw CocoaError(.fileWriteUnknown)
      }
      try data.write(to: url, options: .atomic)
      result.append(url)
    }
    return result
  }
}
