package app.framelink.camera

import java.util.Locale

/** 账号与密码只进入加密连接簿；最近地址单独保留，不含凭据 */
data class SavedCameraConnection(val id:String,val config:ConnectionConfig,val identity:String)
internal data class ConnectionBookData(val entries:List<SavedCameraConnection> = emptyList(),val last:ConnectionConfig=ConnectionConfig(),val lastId:String="",val authenticationRequired:Boolean=false)

class ConnectionBook(private val settings:SecureValueStore,private val key:String="camera.connections.v1",private val legacy:()->ConnectionConfig?={
    settings.get("camera.config").takeIf{it.isNotBlank()}?.let{Json.decode<ConnectionConfig>(it)}
},private val clearLegacy:()->Unit={settings.remove("camera.config")}){
    private fun endpoint(config:ConnectionConfig)=listOf(config.protocol,config.host.trim().trimEnd('.').lowercase(Locale.ROOT),config.port).joinToString("|")
    private fun sanitized(config:ConnectionConfig)=config.copy(user="",password="")
    @Synchronized private fun read():ConnectionBookData{
        val raw=settings.get(key)
        if(raw.isNotBlank())return Json.decode<ConnectionBookData>(raw).also{clearLegacy()}
        check(!settings.contains(key)){"连接簿暂不可读取，已保留原配置"}
        val old=legacy()
        val entry=old?.takeIf{it.user.isNotBlank()||it.password.isNotBlank()}?.let{
            SavedCameraConnection(MediaFiles.key(endpoint(it)+"|"+it.fingerprint.ifBlank{it.usbIdentity}),it,it.fingerprint.ifBlank{it.usbIdentity})
        }
        val book=ConnectionBookData(entry?.let{listOf(it)}?:emptyList(),old?.let(::sanitized)?:ConnectionConfig(),entry?.id.orEmpty(),old?.user?.isNotBlank()==true)
        write(book);clearLegacy();return book
    }
    private fun write(book:ConnectionBookData)=settings.put(key,Json.encode(book))
    @Synchronized fun entries():List<SavedCameraConnection> = read().entries
    @Synchronized fun last():ConnectionConfig{
        val book=read()
        return book.entries.firstOrNull{it.id==book.lastId&&endpoint(it.config)==endpoint(book.last)}?.config?:book.last
    }
    @Synchronized fun requiresCredentials():Boolean{val book=read();return book.authenticationRequired&&book.entries.none{it.id==book.lastId}}
    @Synchronized fun saved(config:ConnectionConfig)=read().entries.any{endpoint(it.config)==endpoint(config)&&it.config.user==config.user&&it.config.password==config.password&&it.config.fingerprint==config.fingerprint}
    @Synchronized fun forget(id:String){val book=read();write(book.copy(entries=book.entries.filterNot{it.id==id},lastId=book.lastId.takeUnless{it==id}.orEmpty()))}
    @Synchronized fun completed(config:ConnectionConfig,identity:String,remember:Boolean){
        val book=read()
        val id=MediaFiles.key(endpoint(config)+"|"+identity)
        val kept=book.entries.filterNot{it.id==id||(it.identity.isBlank()&&endpoint(it.config)==endpoint(config)&&it.config.user==config.user&&it.config.password==config.password)}
        val entries=if(remember)listOf(SavedCameraConnection(id,config,identity))+kept else kept
        write(book.copy(entries=entries,last=sanitized(config),lastId=if(remember)id else"",authenticationRequired=config.user.isNotBlank()))
    }
}
