package app.framelink.camera

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
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
class SelectionBatchesAcceptanceTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 1) val notifications = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private val context get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<CameraApp>()
    private val runtime get() = context.runtime

    @Test
    fun snapshotsAndImageContactSheetsRemainIndependentOfPhotos() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        val photos = fixtures(13)
        val repository = SelectionBatchRepository(context, db)
        var batchId: String? = null
        try {
            db.photos().saveAll(photos)
            val batch = repository.save("QA客户初选", photos.map { it.id } + photos.first().id)
            batchId = batch.id
            assertEquals(13, batch.photoIds().size)
            db.photos().save(photos.first().copy(name = "修改后的记录.jpg"))
            assertEquals(photos.first().name, db.batches().find(batch.id)!!.snapshots().first().name)
            repository.rename(batch.id, "QA精简选片")
            assertEquals(batch.createdAt, db.batches().find(batch.id)!!.createdAt)
            val pages = repository.contactSheets(db.batches().find(batch.id)!!)
            assertEquals(2, pages.size)
            pages.forEachIndexed { index, file ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                assertEquals(1800, bounds.outWidth); assertEquals(if (index == 0) 2280 else 1540, bounds.outHeight)
                assertNotNull(androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file))
            }
            repository.delete(batch.id)
            assertNull(db.batches().find(batch.id))
            assertTrue(photos.all { File(it.files().first().originalPath).isFile })
        } finally { batchId?.let { repository.delete(it) }; photos.forEach { File(it.files().first().originalPath).delete() }; db.close() }
    }

    @Test
    fun customerNamesCanChangeAndRecordsCanBeDeletedWithoutRemovingPhotos() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build()
        val photos = fixtures(1)
        try {
            db.photos().saveAll(photos)
            val repository = LocalReviewRepository(context, db)
            val review = repository.create("客户A", photos.map { it.id })
            repository.choose(review.id, photos.first().id, selected = true, comment = "保留这一张")
            repository.lock(review.id, true)
            repository.rename(review.id, "客户A · 13800000000")
            val renamed = db.reviews().find(review.id)!!
            assertEquals("客户A · 13800000000", renamed.title)
            assertTrue(renamed.locked)
            assertEquals("保留这一张", renamed.choices()[photos.first().id]!!.comment)
            repository.delete(review.id)
            assertNull(db.reviews().find(review.id))
            assertNotNull(db.photos().find(photos.first().id))
            assertTrue(File(photos.first().files().first().originalPath).isFile)
        } finally { photos.forEach { File(it.files().first().originalPath).delete() }; db.close() }
    }

    @Test
    fun versionThreeMigrationKeepsOriginalsAndCreatesEmptyBatchList() = runBlocking {
        val name = "qa-batches-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, LocalDatabase::class.java, name).build()
        val photo = fixtures(1).first()
        try {
            db.photos().save(photo)
            db.openHelper.writableDatabase.execSQL("DROP TABLE selection_batches")
            db.openHelper.writableDatabase.version = 3
            db.close()
            db = Room.databaseBuilder(context, LocalDatabase::class.java, name).addMigrations(LocalDatabase.MIGRATION_3_4).build()
            assertEquals(photo.filesJson, db.photos().find(photo.id)!!.filesJson)
            assertTrue(db.batches().all().isEmpty())
        } finally { db.close(); context.deleteDatabase(name); File(photo.files().first().originalPath).delete() }
    }

    @Test
    fun galleryCanSaveViewRestoreAndDeleteANamedBatch() {
        val photos = fixtures(2)
        val title = "QA选片-${UUID.randomUUID().toString().take(8)}"
        var batchId: String? = null
        try {
            runBlocking { runtime.database.photos().saveAll(photos) }
            compose.onNodeWithTag("tab-gallery").performClick()
            compose.onNodeWithText("已导入").performClick()
            compose.onNodeWithTag("gallery-grid").performScrollToIndex(0)
            enterGallerySelection(compose)
            photos.forEach { compose.onNodeWithContentDescription("选择 ${it.name}").performClick() }
            compose.onNodeWithText("存选片", substring = false).performClick()
            compose.onNode(hasSetTextAction() and hasText("选片名称")).performTextReplacement(title)
            compose.onNodeWithText("保存选片").performClick()
            compose.waitUntil(10000) { runBlocking { runtime.database.batches().all().any { it.title == title } } }
            batchId = runBlocking { runtime.database.batches().all().first { it.title == title }.id }
            compose.onNodeWithText("图文选片单").assertIsDisplayed()
            acceptanceScreenshot(context, "saved-selection-batch")
            compose.onNodeWithText("恢复这组选择").performClick()
            compose.onNodeWithText("本次已选 2 张",substring=false).assertIsDisplayed()
            enterGallerySelection(compose)
            photos.forEach { compose.onNodeWithContentDescription("选择 ${it.name}").assertIsOn() }
            finishGallerySelection(compose)
            compose.onNodeWithTag("tab-projects").performClick()
            compose.onNodeWithText(title).performScrollTo().performClick()
            compose.onNodeWithContentDescription("删除选片记录").performClick()
            compose.onNodeWithText("删除记录", substring = false).performClick()
            compose.waitUntil(10000) { runBlocking { runtime.database.batches().find(batchId!!) == null } }
            assertTrue(photos.all { File(it.files().first().originalPath).exists() })
        } finally {
            runBlocking {
                batchId?.let { runtime.batches.delete(it) }
                photos.forEach { p -> File(p.files().first().originalPath).delete(); runtime.database.photos().save(p.copy(localHidden = true, filesJson = "[]")) }
            }
        }
    }

    @Test
    fun projectCanStartAClientSessionThroughGalleryWithoutSharingPhotographerChoices(){
        val photos=fixtures(2)
        val title="QA新客户-${UUID.randomUUID().toString().take(8)}"
        var reviewId:String?=null
        try{
            runBlocking{runtime.database.photos().saveAll(photos)}
            compose.onNodeWithTag("tab-projects").performClick()
            compose.onNodeWithContentDescription("新建项目或场次").performClick()
            compose.onNodeWithText("新建客户场次",substring=false).performClick()
            compose.onNodeWithText("为这个客户选择照片",substring=false).assertIsDisplayed()
            compose.onNodeWithTag("gallery-grid").performScrollToIndex(0)
            compose.onNodeWithContentDescription("选择 ${photos[0].name}").performClick()
            compose.onNodeWithText("新建客户场次",substring=false).performClick()
            compose.onNode(hasSetTextAction()and hasText("客户姓名 / 手机号 / 场次名称")).performTextReplacement(title)
            compose.onNodeWithText("开始选片",substring=false).performClick()
            compose.waitUntil(10000){runBlocking{runtime.database.reviews().all().any{it.title==title}}}
            val review=runBlocking{runtime.database.reviews().all().first{it.title==title}}
            reviewId=review.id
            assertEquals(listOf(photos[0].id),review.photoIds())
            assertTrue("摄影师勾选不能代替客户选择",review.choices().isEmpty())
            compose.onNodeWithText("客户模式 · 无需登录").assertIsDisplayed()
        }finally{
            runBlocking{reviewId?.let{runtime.reviews.delete(it)};photos.forEach{photo->File(photo.files().first().originalPath).delete();runtime.database.photos().save(photo.copy(localHidden=true,filesJson="[]"))}}
        }
    }

    @Test
    fun clientRecordPreviewShowsCustomerChoicesWithoutChangingPhotographerSelection(){
        val photos=fixtures(2)
        var reviewId:String?=null
        try{
            val record=runBlocking{runtime.database.photos().saveAll(photos);runtime.reviews.create("QA客户结果-${UUID.randomUUID().toString().take(8)}",photos.map{it.id})}
            reviewId=record.id
            runBlocking{runtime.reviews.choose(record.id,photos[0].id,selected=true)}
            compose.onNodeWithTag("tab-projects").performClick()
            compose.onNodeWithText(record.title).performScrollTo().performClick()
            compose.onNodeWithText("进入客户选片").assertIsDisplayed()
            compose.onNodeWithTag("client-detail-photo-${photos[0].id}").performClick()
            compose.onNodeWithText("客户已选",substring=false).assertIsDisplayed()
            compose.onNodeWithContentDescription("选择当前照片").assertDoesNotExist()
            compose.onNodeWithTag("photo-viewer-pager").performTouchInput{swipeLeft()}
            compose.waitUntil(10000){compose.onAllNodesWithText("客户未选",substring=false).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("关闭照片").performClick()
            compose.onNodeWithText("进入客户选片").performClick()
            compose.onNodeWithText("客户模式 · 无需登录").assertIsDisplayed()
            assertTrue(runBlocking{runtime.database.reviews().find(record.id)!!.choices()[photos[0].id]!!.selected})
            assertTrue(photos.all{it.mark==PhotoMark.NONE.name})
        }finally{runBlocking{reviewId?.let{runtime.reviews.delete(it)};photos.forEach{photo->File(photo.files().first().originalPath).delete();runtime.database.photos().save(photo.copy(localHidden=true,filesJson="[]"))}}}
    }

    private fun fixtures(count: Int): List<Photo> {
        val id = UUID.randomUUID().toString().take(8)
        val base = System.currentTimeMillis() + 2_000_000
        val directory = File(context.filesDir, "media/qa-batches/$id").apply { mkdirs() }
        return (0 until count).map { n ->
            val file = File(directory, "QA_${id}_$n.jpg")
            val bitmap = Bitmap.createBitmap(90, 60, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xff000000.toInt() or (file.name.hashCode() and 0xffffff))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
            Photo("qa-batch:$id:$n", "qa-batch:$id:$n", file.name, PhotoSource.IMPORTED.name, base - n,
                Json.encode(listOf(CameraFile(name = file.name, originalPath = file.path, localUri = file.toURI().toString(), size = file.length()))))
        }
    }
}
