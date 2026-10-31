package app.framelink.camera

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** 仅在独立模拟器使用隔离数据库、合成文件及加密偏好域，不清理用户历史 */
class ConnectionAndHistoryAcceptanceTest{
    private fun guard(){check(Build.HARDWARE in setOf("ranchu","goldfish"))}
    @Test fun pairingVaultEncryptsTokensAndSeparatesProtocolNamespaces(){
        guard()
        val app=ApplicationProvider.getApplicationContext<Context>()
        val name="qa-pairing-"+UUID.randomUUID()
        val isolated=object:ContextWrapper(app){override fun getSharedPreferences(key:String,mode:Int)=app.getSharedPreferences(name,mode)}
        val store=SecureSettings(isolated)
        val vault=SecureCameraPairingStore(store)
        val address="00:11:22:33:44:55"
        try{
            vault.saveToken(CameraProtocols.FUJI_PHONE,address,byteArrayOf(1,2,3,4))
            vault.saveToken(CameraProtocols.SONY,address,byteArrayOf(5,6,7,8))
            val recreated=SecureCameraPairingStore(SecureSettings(isolated))
            assertArrayEquals(byteArrayOf(1,2,3,4),recreated.loadToken(CameraProtocols.FUJI_PHONE,address))
            assertArrayEquals(byteArrayOf(5,6,7,8),recreated.loadToken(CameraProtocols.SONY,address))
            val raw=app.getSharedPreferences(name,Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(raw.contains("01020304"));assertFalse(raw.contains("05060708"))
            recreated.forget(CameraProtocols.FUJI_PHONE,address)
            assertNull(recreated.loadToken(CameraProtocols.FUJI_PHONE,address))
            assertArrayEquals(byteArrayOf(5,6,7,8),recreated.loadToken(CameraProtocols.SONY,address))
        }finally{app.deleteSharedPreferences(name)}
    }

    @Test fun encryptedBookPersistsOnlyChosenProfilesAndNoSaveSurvivesRecreation(){
        guard();val context=ApplicationProvider.getApplicationContext<Context>();val token="qa-book-"+UUID.randomUUID();val settings=SecureSettings(context)
        val book=ConnectionBook(settings,token,legacy={null},clearLegacy={})
        val a=ConnectionConfig(host="qa-a.local",user="qa-user-a",password="qa-secret-a",fingerprint="fp-a")
        val b=a.copy(host="qa-b.local",user="qa-user-b",password="qa-secret-b",fingerprint="fp-b")
        try{
            book.completed(a,a.fingerprint,true);book.completed(b,b.fingerprint,true)
            val encrypted=context.getSharedPreferences("framelink.settings",Context.MODE_PRIVATE).getString(token,"")!!
            assertFalse(encrypted.contains(a.password));assertFalse(encrypted.contains(b.user))
            val recreated=ConnectionBook(SecureSettings(context),token,legacy={null},clearLegacy={})
            assertEquals(2,recreated.entries().size);assertEquals(b.password,recreated.last().password)
            recreated.forget(recreated.entries().first{it.config.host==a.host}.id)
            assertEquals(listOf(b.host),recreated.entries().map{it.config.host})
            recreated.completed(b,b.fingerprint,false)
            val restarted=ConnectionBook(SecureSettings(context),token,legacy={null},clearLegacy={})
            assertEquals("",restarted.last().password);assertEquals("",restarted.last().user);assertTrue(restarted.entries().isEmpty())
            assertFalse(settings.get(token).contains(b.password))
        }finally{settings.remove(token)}
    }

    private fun historyCheck(block:suspend(CameraRuntime)->Unit)=runBlocking{
        guard();val context=ApplicationProvider.getApplicationContext<Context>();val root=File(context.cacheDir,"qa-history-"+UUID.randomUUID()).apply{mkdirs()}
        val isolated=object:ContextWrapper(context){override fun getFilesDir()=File(root,"files").apply{mkdirs()};override fun getCacheDir()=File(root,"cache").apply{mkdirs()}}
        val db=Room.inMemoryDatabaseBuilder(context,LocalDatabase::class.java).build()
        val runtime=CameraRuntime(isolated,db,SecureSettings(context));runtime.scope.coroutineContext[Job]?.cancelAndJoin()
        try{block(runtime)}finally{db.close();root.deleteRecursively()}
    }
    @Test fun clearPreservesOriginalsAndActiveTasksAndRejectsLateUpserts()=historyCheck{runtime->
        val file=File(runtime.context.filesDir,"media/retained.txt").apply{parentFile!!.mkdirs();writeText("original-bytes")}
        val photo=Photo("qa-original","qa-original","retained.txt",PhotoSource.IMPORTED.name,1,Json.encode(listOf(CameraFile(name=file.name,originalPath=file.path,size=file.length()))))
        runtime.database.photos().save(photo)
        val copy=File(runtime.context.filesDir,"media/retained_share.jpg").apply{writeText("compressed-copy")}
        val external="content://qa-provider/external-copy"
        val tasks=TransferState.entries.map{state->TransferTask("qa-"+state.name,TransferKind.FTP_DOWNLOAD.name,title=state.name,payload=org.json.JSONObject().put("name",file.name).put("path",file.path).put("compressedUri",android.net.Uri.fromFile(copy).toString()).put("exportedUri",external).toString(),state=state.name)}
        tasks.forEach{runtime.database.tasks().save(it)}
        val digest=MediaFiles.digest(file.inputStream())
        assertEquals(2,TransferHistory.clear(runtime,tasks.map{it.id}))
        assertEquals(tasks.filterNot(TransferHistory::removable).map{it.id}.toSet(),runtime.database.tasks().all().map{it.id}.toSet())
        assertEquals(photo,runtime.database.photos().find(photo.id));assertEquals(digest,MediaFiles.digest(file.inputStream()))
        val retained=runtime.database.tasks().savedFiles().first()
        assertEquals(3,retained.size);assertTrue(retained.any{it.uri==external});assertTrue(retained.any{it.path==copy.canonicalPath})
        val ended=tasks.first{it.state==TransferState.COMPLETE.name}
        runtime.database.tasks().save(ended.copy(state=TransferState.RUNNING.name));runtime.database.tasks().start(ended)
        assertNull(runtime.database.tasks().find(ended.id))
    }
    @Test fun receiverAuthenticatesWithoutKeepingTemporaryCredentialFile()=historyCheck{runtime->
        val port=java.net.ServerSocket(0).use{it.localPort}
        val receiver=FtpReceiver(runtime.context,runtime.database,runtime.scope,kotlinx.coroutines.flow.MutableStateFlow(""),kotlinx.coroutines.flow.MutableStateFlow(""))
        try{
            receiver.start("127.0.0.1",port,"qa-receiver","qa-receiver-secret")
            assertFalse(File(runtime.context.cacheDir,"ftp-users.properties").exists())
            val profile=FtpProfile(host="127.0.0.1",port=port,username="qa-receiver",password="qa-receiver-secret")
            FtpConnection(profile).use{it.connect();assertTrue(it.list("/").isEmpty())}
            FtpConnection(profile.copy(password="incorrect")).use{assertTrue(runCatching{it.connect()}.isFailure)}
        }finally{receiver.stop()}
    }
    @Test fun confirmedScopeIsRecheckedAndLargeHistoryUsesBoundedBatches()=historyCheck{runtime->
        val dao=runtime.database.tasks();val changed=TransferTask("qa-changed",TransferKind.DOWNLOAD.name,title="changed",state=TransferState.COMPLETE.name)
        dao.save(changed);dao.save(changed.copy(state=TransferState.QUEUED.name))
        val later=changed.copy(id="qa-later");dao.save(later)
        assertEquals(0,TransferHistory.clear(runtime,listOf(changed.id)))
        assertNotNull(dao.find(later.id));assertEquals(TransferState.QUEUED.name,dao.find(changed.id)!!.state)
        val ids=(1..1100).map{"qa-large-$it"}
        runtime.database.withTransaction{ids.forEach{dao.save(changed.copy(id=it))}}
        assertEquals(1100,TransferHistory.clear(runtime,ids))
        assertNotNull(dao.find(changed.id));assertNotNull(dao.find(later.id))
    }
}
