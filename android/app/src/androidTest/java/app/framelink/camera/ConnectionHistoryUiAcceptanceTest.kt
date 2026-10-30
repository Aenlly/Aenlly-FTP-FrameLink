package app.framelink.camera

import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 独立模拟器上的连接选择、未保存表单重建和单条历史清除 */
class ConnectionHistoryUiAcceptanceTest{
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val runtime get()=(compose.activity.application as CameraApp).runtime
    private fun field(label:String)=compose.onNode(hasSetTextAction()and hasText(label))
    private fun openConnections(){compose.onNodeWithTag("tab-camera").performClick();compose.onNodeWithContentDescription("相机更多操作").performClick();compose.onNodeWithText("相机连接设置").performClick()}

    @Test fun selectingAndForgettingKeepsOtherCameraAndRecreationDropsUnsavedCredentials(){
        check(Build.HARDWARE in setOf("ranchu","goldfish"))
        val keys=listOf("camera.connections.v1","camera.config");val before=keys.associateWith{runtime.settings.get(it)}
        val a=ConnectionConfig(host="qa-profile-a.local",user="qa-profile-user-a",password="qa-profile-secret-a",fingerprint="fp-a")
        val b=a.copy(host="qa-profile-b.local",user="qa-profile-user-b",password="qa-profile-secret-b",fingerprint="fp-b")
        try{
            runtime.disconnect();runtime.settings.remove("camera.connections.v1");runtime.settings.remove("camera.config")
            runtime.connections.completed(a,a.fingerprint,true);runtime.connections.completed(b,b.fingerprint,true)
            openConnections()
            compose.onNodeWithText(a.host+":"+a.port+" · "+a.user).performScrollTo().performClick()
            assertEquals(a.user,field("访问认证账号").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            compose.onNodeWithContentDescription("显示密码").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(a.password,field("访问认证密码").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            compose.onNodeWithContentDescription("忘记连接 "+a.host).performScrollTo().performClick()
            assertEquals(listOf(b.host),runtime.connections.entries().map{it.config.host})
            compose.onNodeWithText(b.host+":"+b.port+" · "+b.user).performClick()
            field("相机IP地址").performScrollTo().performTextReplacement("qa-unsaved.local")
            compose.onNodeWithContentDescription("保存此连接的账号和密码").performScrollTo().assertIsOff()
            field("访问认证账号").performScrollTo().performTextReplacement("qa-unsaved-user")
            field("访问认证密码").performTextReplacement("qa-unsaved-secret")
            assertFalse(runtime.settings.get("camera.connections.v1").contains("qa-unsaved-secret"))
            compose.activityRule.scenario.recreate();compose.waitForIdle();openConnections()
            field("访问认证密码").performScrollTo()
            val password=field("访问认证密码").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
            assertFalse(password.contains("qa-unsaved-secret"))
            if(field("相机IP地址").fetchSemanticsNode().config[SemanticsProperties.EditableText].text=="qa-unsaved.local")assertEquals("",password)
            acceptanceScreenshot(compose.activity,"connection-no-save-recreated-"+androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("qaRunTag"))
        }finally{keys.forEach{key->before[key]?.takeIf{it.isNotBlank()}?.let{runtime.settings.put(key,it)}?:runtime.settings.remove(key)}}
    }

    @Test fun singleHistoryDeletionRequiresConfirmationAndKeepsSavedFileEntry(){
        check(Build.HARDWARE in setOf("ranchu","goldfish"))
        val id="qa-history-ui-"+UUID.randomUUID()
        val file=File(compose.activity.filesDir,"media/$id.txt").apply{parentFile!!.mkdirs();writeText("retained-$id")}
        val task=TransferTask(id,TransferKind.FTP_DOWNLOAD.name,title=id,payload=org.json.JSONObject().put("name",file.name).put("path",file.path).toString(),state=TransferState.COMPLETE.name)
        val before=runBlocking{runtime.database.tasks().all().map{it.id}.toSet()}
        try{
            runBlocking{runtime.database.tasks().save(task)}
            compose.onNodeWithTag("tab-transfers").performClick();compose.onNodeWithText("已完成",substring=false).performClick()
            compose.onNodeWithContentDescription("文件与任务详情 "+id).performScrollTo().performClick()
            compose.onNodeWithText("删除这条记录").performClick()
            assertNotNull(runBlocking{runtime.database.tasks().find(id)})
            compose.onNodeWithText("清除记录",substring=false).performClick()
            compose.waitUntil(10000){runBlocking{runtime.database.tasks().find(id)==null}}
            assertTrue(file.isFile);assertTrue(runBlocking{runtime.database.tasks().all().map{it.id}.containsAll(before)})
            compose.onNodeWithContentDescription("传输更多操作").performClick();compose.onNodeWithText("已保存文件",substring=false).performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText(file.name,substring=false).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText(file.name,substring=false).performScrollTo().assertIsDisplayed()
            acceptanceScreenshot(compose.activity,"history-retained-file-entry-"+androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("qaRunTag"))
        }finally{
            val db=runtime.database.openHelper.writableDatabase
            db.execSQL("DELETE FROM tasks WHERE id=?",arrayOf(id));db.execSQL("DELETE FROM cleared_transfer_tasks WHERE id=?",arrayOf(id))
            db.execSQL("DELETE FROM saved_transfer_files WHERE id=?",arrayOf(MediaFiles.key(file.canonicalPath)));file.delete()
        }
    }
}
