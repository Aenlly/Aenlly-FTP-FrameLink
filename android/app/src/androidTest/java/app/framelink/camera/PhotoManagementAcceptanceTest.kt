package app.framelink.camera

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoManagementAcceptanceTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val notifications = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val runtime get() = (compose.activity.application as CameraApp).runtime

    @Test
    fun listRemovalPreservesOriginalsAndSelectionHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        val file = original("list")
        try {
            val photo = photo(file)
            db.photos().save(photo)
            val reviews = LocalReviewRepository(context, db)
            val review = reviews.create("QA删除历史", listOf(photo.id))
            reviews.choose(review.id, photo.id, selected = true, comment = "保留客户留言")
            reviews.lock(review.id, true)
            val repository = PhotoRemovalRepository(context, db)
            val plan = repository.plan(listOf(photo.id))
            assertEquals(1, plan.reviewCount)
            val result = repository.remove(plan, false)
            assertEquals(listOf(photo.id), result.removedIds)
            assertTrue(file.isFile)
            assertTrue(db.photos().find(photo.id)!!.localHidden)
            assertFalse(db.photos().find(photo.id)!!.inLibrary)
            assertEquals("保留客户留言", db.reviews().find(review.id)!!.choices()[photo.id]!!.comment)
            val csv = reviews.export(review.id)
            assertTrue(csv.readText().contains(photo.name))
            csv.delete()
            Unit
        } finally { file.delete(); db.close() }
    }

    @Test
    fun groupedFilesAndAlbumCopiesAreDeletedWithoutCameraWrites() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        val file = original("group")
        val raw = File(file.parentFile, file.nameWithoutExtension + ".ARW").apply { writeBytes(byteArrayOf(0, 1, -1, 2)) }
        val preview = File(context.cacheDir, "previews/qa-${UUID.randomUUID()}.jpg").apply { parentFile!!.mkdirs(); writeBytes(file.readBytes()) }
        val album = MediaFiles.publish(context, file, file.name)
        try {
            val photo = photo(file).copy(
                source = PhotoSource.CAMERA.name,
                filesJson = Json.encode(listOf(
                    CameraFile(fileId = 1, name = file.name, cameraPath = "/DCIM/100MSDCF/QA.JPG", originalPath = file.path, localUri = album.toString()),
                    CameraFile(fileId = 2, name = raw.name, cameraPath = "/DCIM/100MSDCF/QA.ARW", originalPath = raw.path),
                )), previewPath = preview.path,
            )
            db.photos().save(photo)
            val repository = PhotoRemovalRepository(context, db)
            val result = repository.remove(repository.plan(listOf(photo.id)), true)
            assertTrue(result.failures.isEmpty())
            assertFalse(file.exists()); assertFalse(raw.exists()); assertFalse(preview.exists())
            assertFalse(context.contentResolver.query(album, null, null, null, null)?.use { it.moveToFirst() } ?: false)
            val removed = db.photos().find(photo.id)!!
            assertTrue(removed.localHidden)
            assertFalse(removed.imported)
            assertEquals("/DCIM/100MSDCF/QA.JPG", removed.files()[0].cameraPath)
            assertEquals("/DCIM/100MSDCF/QA.ARW", removed.files()[1].cameraPath)
        } finally { runCatching { context.contentResolver.delete(album, null, null) }; file.delete(); raw.delete(); preview.delete(); db.close() }
    }

    @Test
    fun sharedFilesActiveTransfersAndUnmanagedPathsAreProtected() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        val file = original("shared")
        val outside = File(context.cacheDir, "qa-outside-${UUID.randomUUID()}.jpg").apply { writeBytes(file.readBytes()) }
        try {
            val first = photo(file)
            val second = first.copy(id = "qa-${UUID.randomUUID()}", sourceKey = "qa-${UUID.randomUUID()}")
            db.photos().saveAll(listOf(first, second))
            val repository = PhotoRemovalRepository(context, db)
            val shared = repository.plan(listOf(first.id))
            assertTrue(shared.retainedShared > 0)
            assertTrue(repository.remove(shared, true).failures.isEmpty())
            assertTrue(file.isFile)
            assertTrue(db.photos().find(second.id)!!.inLibrary)
            val task = TransferTask("qa-${UUID.randomUUID()}", TransferKind.DOWNLOAD.name, second.id, "QA待下载")
            db.tasks().save(task)
            assertTrue(runCatching { repository.plan(listOf(second.id)) }.exceptionOrNull()?.message.orEmpty().contains("传输"))
            db.tasks().save(task.copy(state = TransferState.CANCELLED.name))
            val upload = task.copy(
                id = "qa-${UUID.randomUUID()}", kind = TransferKind.FTP_UPLOAD.name, photoId = "",
                payload = org.json.JSONObject().put("uri", file.toURI().toString()).toString(),
            )
            db.tasks().save(upload)
            assertTrue(runCatching { repository.plan(listOf(second.id)) }.isFailure)
            db.tasks().save(upload.copy(state = TransferState.COMPLETE.name))
            val album = MediaFiles.publish(context, file, file.name)
            try {
                val published = second.copy(filesJson = Json.encode(second.files().map { it.copy(localUri = album.toString()) }))
                db.photos().save(published)
                val documentUri = android.provider.DocumentsContract.buildDocumentUri("com.android.providers.media.documents", "image:${android.content.ContentUris.parseId(album)}")
                val queued = upload.copy(id = "qa-${UUID.randomUUID()}", payload = org.json.JSONObject().put("uri", documentUri.toString()).toString())
                db.tasks().save(queued)
                assertTrue(runCatching { repository.plan(listOf(second.id)) }.isFailure)
                db.tasks().save(queued.copy(state = TransferState.CANCELLED.name))
            } finally { context.contentResolver.delete(album, null, null) }
            val exported = android.provider.DocumentsContract.buildDocumentUriUsingTree(android.net.Uri.parse("content://qa.export.documents/tree/album"), "album/QA.jpg")
            db.tasks().save(TransferTask("qa-${UUID.randomUUID()}", TransferKind.FTP_RECEIVE.name, title = "QA导出副本",
                payload = org.json.JSONObject().put("photoKey", second.sourceKey).put("exportedUri", exported.toString()).toString(), state = TransferState.COMPLETE.name))
            val exportUpload = upload.copy(id = "qa-${UUID.randomUUID()}", payload = org.json.JSONObject().put("uri", android.provider.DocumentsContract.buildDocumentUri("qa.export.documents", "album/QA.jpg").toString()).toString())
            db.tasks().save(exportUpload)
            assertTrue(runCatching { repository.plan(listOf(second.id)) }.isFailure)
            db.tasks().save(exportUpload.copy(state = TransferState.CANCELLED.name))
            val third = photo(outside)
            db.photos().save(third)
            val plan = repository.plan(listOf(third.id))
            assertTrue(runCatching { repository.remove(plan, true) }.isFailure)
            assertTrue(outside.isFile)
            assertTrue(db.photos().find(third.id)!!.inLibrary)
            assertTrue(repository.remove(plan, false).failures.isEmpty())
            assertTrue(outside.isFile)
        } finally { file.delete(); outside.delete(); db.close() }
    }

    @Test
    fun undeletablePreviewReportsWarningAfterOriginalRemoval() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        val file = original("cache-permission")
        val directory = File(context.cacheDir, "previews/qa-locked-${UUID.randomUUID()}").apply { mkdirs() }
        val preview = File(directory, "preview.jpg").apply { writeBytes(file.readBytes()) }
        try {
            val photo = photo(file).copy(previewPath = preview.path)
            db.photos().save(photo)
            val repository = PhotoRemovalRepository(context, db)
            val plan = repository.plan(listOf(photo.id))
            assertTrue(directory.setWritable(false, false))
            val result = repository.remove(plan, true)
            assertTrue(result.failures.isEmpty())
            assertEquals(1, result.warnings.size)
            assertFalse(file.exists())
            assertTrue(db.photos().find(photo.id)!!.localHidden)
            assertTrue(preview.exists())
        } finally {
            directory.setWritable(true, true); preview.delete(); directory.delete(); file.delete(); db.close()
        }
    }

    @Test
    fun versionTwoMigrationRetainsExistingPhotoAndDefaultsToVisible() = runBlocking {
        val name = "qa-migration-${UUID.randomUUID()}.db"
        val file = original("migration")
        var db = Room.databaseBuilder(context, LocalDatabase::class.java, name).build()
        val photo = photo(file)
        try {
            db.photos().save(photo)
            db.openHelper.writableDatabase.execSQL("ALTER TABLE photos DROP COLUMN localHidden")
            db.openHelper.writableDatabase.version = 2
            db.close()
            db = Room.databaseBuilder(context, LocalDatabase::class.java, name).addMigrations(LocalDatabase.MIGRATION_2_3, LocalDatabase.MIGRATION_3_4).build()
            val restored = db.photos().find(photo.id)!!
            assertEquals(photo.filesJson, restored.filesJson)
            assertFalse(restored.localHidden)
            assertTrue(restored.inLibrary)
        } finally { db.close(); context.deleteDatabase(name); file.delete() }
    }

    @Test
    fun galleryAndViewerExposeBothRemovalChoicesAndCancelPreservesFiles() = runBlocking {
        val firstFile = original("ui-list")
        val secondFile = original("ui-full")
        val first = photo(firstFile).copy(capturedAt = Long.MAX_VALUE - 1)
        val second = photo(secondFile).copy(capturedAt = Long.MAX_VALUE - 2)
        runtime.database.photos().saveAll(listOf(first, second))
        try {
            compose.onNodeWithText("图库", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("gallery-grid").performScrollToIndex(0)
            enterGallerySelection(compose)
            compose.onNodeWithContentDescription("选择 ${first.name}").performClick()
            compose.onNodeWithContentDescription("更多选片操作").performClick()
            compose.onNodeWithText("移除所选照片").performClick()
            compose.onNodeWithText("仅从图库移除").assertIsDisplayed()
            compose.onNodeWithText("删除本机文件并移除").assertIsDisplayed()
            compose.waitForIdle()
            acceptanceScreenshot(context, "photo-removal-choices")
            compose.onNodeWithText("取消").performClick()
            assertTrue(firstFile.isFile)
            assertFalse(runtime.database.photos().find(first.id)!!.localHidden)
            compose.onNodeWithContentDescription("更多选片操作").performClick()
            compose.onNodeWithText("移除所选照片").performClick()
            compose.waitUntil(10000) { !compose.onNodeWithText("确认移除").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
            compose.onNodeWithText("确认移除").performClick()
            compose.waitUntil(10000) { runBlocking { runtime.database.photos().find(first.id)!!.localHidden } }
            assertTrue(firstFile.isFile)
            finishGallerySelection(compose)
            compose.onNodeWithTag("gallery-grid").performScrollToIndex(0)
            compose.onNodeWithTag("photo-${second.id}").performClick()
            compose.onNodeWithContentDescription("照片更多操作").performClick()
            compose.onNodeWithContentDescription("移除照片").performClick()
            compose.onNodeWithText("删除本机文件并移除").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("确认删除文件").fetchSemanticsNodes().singleOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false }
            compose.onNodeWithText("确认删除文件").performClick()
            compose.waitUntil(10000) { runBlocking { runtime.database.photos().find(second.id)!!.localHidden } }
            assertFalse(secondFile.exists())
            runtime.importUris(listOf(android.net.Uri.fromFile(firstFile))).join()
            assertTrue(runtime.database.photos().find(first.id)!!.inLibrary)
        } finally {
            firstFile.delete(); secondFile.delete()
            for (photo in listOf(first, second)) runtime.database.photos().find(photo.id)?.let {
                runtime.database.photos().save(it.copy(localHidden = true, filesJson = Json.encode(emptyList<CameraFile>())))
            }
        }
    }

    @Test
    fun importedAndDeduplicatedTasksKeepRealFilesNamesAndByteCounts()=runBlocking{
        val source=original("task-files")
        val bytes=source.readBytes()
        val id="local:"+MediaFiles.digest(source.inputStream())
        val before=runtime.database.tasks().all().map{it.id}.toSet()
        try{
            val uri=androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".files",source)
            runtime.importUris(listOf(uri)).join()
            val first=runtime.database.tasks().all().first{it.id !in before&&it.kind==TransferKind.IMPORT.name}
            assertEquals(TransferState.COMPLETE.name,first.state)
            assertEquals(id,first.photoId)
            assertEquals(source.name,first.title)
            assertEquals(bytes.size.toLong(),first.done)
            val saved=FtpStorage.taskFiles(context,first).single()
            assertEquals(source.name,saved.name)
            assertArrayEquals(bytes,saved.file.readBytes())
            runtime.importUris(listOf(uri)).join()
            val second=runtime.database.tasks().all().first{it.id !in before&&it.id!=first.id&&it.kind==TransferKind.IMPORT.name}
            assertEquals(TransferState.COMPLETE.name,second.state)
            assertEquals(bytes.size.toLong(),second.done)
            assertEquals(first.photoId,second.photoId)
            assertEquals(saved.file.canonicalPath,FtpStorage.taskFiles(context,second).single().file.canonicalPath)
            assertArrayEquals(bytes,saved.file.readBytes())
        }finally{
            runtime.database.photos().find(id)?.let{photo->photo.files().forEach{file->if(file.localUri.startsWith("content:"))runCatching{context.contentResolver.delete(android.net.Uri.parse(file.localUri),null,null)};File(file.originalPath).delete()};runtime.database.photos().save(photo.copy(localHidden=true,filesJson="[]"))}
            source.delete()
        }
    }

    @Test
    fun groupedTaskRequiresChoosingTheJpegOrRawBeforeOpening(){
        val jpeg=original("task-group")
        val raw=File(jpeg.parentFile,jpeg.nameWithoutExtension+".ARW").apply{writeBytes(byteArrayOf(1,2,3,4))}
        val record=photo(jpeg).copy(filesJson=Json.encode(listOf(CameraFile(1,jpeg.name,originalPath=jpeg.path,size=jpeg.length()),CameraFile(2,raw.name,originalPath=raw.path,size=raw.length()))))
        val files=org.json.JSONArray().put(org.json.JSONObject().put("name",jpeg.name).put("path",jpeg.path)).put(org.json.JSONObject().put("name",raw.name).put("path",raw.path))
        val task=TransferTask("qa-task-files:${UUID.randomUUID()}",TransferKind.DOWNLOAD.name,photoId=record.id,title="QA多格式任务",payload=org.json.JSONObject().put("files",files).toString(),state=TransferState.COMPLETE.name,createdAt=Long.MAX_VALUE-10)
        try{
            runBlocking{runtime.database.photos().save(record);runtime.database.tasks().save(task)}
            compose.onNodeWithTag("tab-transfers").performClick()
            compose.onNodeWithText("已完成",substring=false).performClick()
            compose.onNodeWithText(task.title).performScrollTo().assertIsDisplayed()
            compose.onNode(hasText("打开")and hasAnyAncestor(hasTestTag("task-${task.id}"))).performClick()
            compose.onNodeWithText("选择要打开的文件").assertIsDisplayed()
            compose.onNodeWithText(jpeg.name,substring=true).assertIsDisplayed()
            compose.onNodeWithText(raw.name,substring=true).assertIsDisplayed()
            compose.onNodeWithText("取消",substring=false).performClick()
            assertTrue(jpeg.isFile);assertTrue(raw.isFile)
            assertEquals(listOf(jpeg.name,raw.name),FtpStorage.taskFiles(context,task).map{it.name})
        }finally{runBlocking{runtime.database.tasks().save(task.copy(state=TransferState.CANCELLED.name,payload="{}",photoId=""));runtime.database.photos().save(record.copy(localHidden=true,filesJson="[]"))};jpeg.delete();raw.delete()}
    }

    private fun original(label: String): File {
        val directory = File(context.filesDir, "media/qa-photo-removal").apply { mkdirs() }
        val file = File(directory, "QA_${label}_${UUID.randomUUID()}.jpg")
        val image = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        image.eraseColor(0xff000000.toInt() or (file.name.hashCode() and 0x00ffffff))
        file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        image.recycle()
        return file
    }

    private fun photo(file: File): Photo {
        val id = "local:${MediaFiles.digest(file.inputStream())}"
        return Photo(id, id, file.name, PhotoSource.IMPORTED.name, System.currentTimeMillis(),
            Json.encode(listOf(CameraFile(name = file.name, originalPath = file.path, localUri = file.toURI().toString(), size = file.length()))))
    }
}
