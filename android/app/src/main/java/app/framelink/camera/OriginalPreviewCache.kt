package app.framelink.camera

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** JPEG原片级预览独立暂存，不修改照片导入标记或正式文件路径 */
internal class OriginalPreviewCache(cacheRoot:File){
    companion object{private const val MAX_BYTES=2L*1024*1024*1024}
    val directory=File(cacheRoot,"original-previews")
    fun jpeg(photo:Photo)=photo.files().firstOrNull{it.formatTitle()=="JPEG"}
    fun target(photo:Photo):File=File(directory,photo.selectionResourceKey()+".jpg")
    fun cached(photo:Photo):File?{
        val file=jpeg(photo)?:return null
        return target(photo).takeIf{it.isFile&&it.length()==file.size&&file.size>0&&checksum(it).matches(Regex("[0-9a-f]{64}"))}
    }
    fun prepare(photo:Photo):File{
        val file=requireNotNull(jpeg(photo)){"相机未提供JPEG原片，可使用现有预览或另行导入RAW"}
        require(file.size in 1..MAX_BYTES){"JPEG过大，请使用正式导入查看"}
        directory.mkdirs()
        trim(file.size)
        return target(photo)
    }
    fun complete(file:File,sha256:String){
        require(sha256.matches(Regex("[0-9a-f]{64}"))){"原片预览校验信息无效"}
        File(file.path+".sha256").writeText(sha256)
        touch(file)
    }
    fun touch(file:File){file.setLastModified(System.currentTimeMillis())}
    private fun checksum(file:File)=runCatching{File(file.path+".sha256").readText().trim()}.getOrDefault("")

    fun verifiedForImport(photo:Photo,file:CameraFile):Boolean{
        val jpeg=jpeg(photo)?:return false
        if(file.formatTitle()!="JPEG"||!canReuseCameraFile(file,jpeg))return false
        val cached=cached(photo)?:return false
        return runCatching{MediaFiles.digest(cached.inputStream())==checksum(cached)}.getOrDefault(false)
    }

    /** 正式导入复用完整JPEG并重新校验字节，校验失败时交回相机通道读取 */
    fun copyForImport(photo:Photo,file:CameraFile,output:File,onProgress:(Long,Long)->Unit):Boolean{
        val jpeg=jpeg(photo)?:return false
        if(file.formatTitle()!="JPEG"||!canReuseCameraFile(file,jpeg))return false
        val cached=cached(photo)?:return false
        val expected=checksum(cached)
        val source=try{cached.inputStream()}catch(_:java.io.FileNotFoundException){return false}
        output.parentFile?.mkdirs()
        val part=File(output.path+".part")
        try{
            val digest=MessageDigest.getInstance("SHA-256")
            var bytes=0L
            source.use{input->FileOutputStream(part).use{target->
                val buffer=ByteArray(65536)
                while(true){
                    val count=input.read(buffer)
                    if(count<0)break
                    target.write(buffer,0,count);digest.update(buffer,0,count);bytes+=count
                    onProgress(bytes,file.size)
                }
                target.fd.sync()
            }}
            val actual=digest.digest().joinToString(""){"%02x".format(it)}
            if(bytes!=file.size||actual!=expected){
                cached.delete();File(cached.path+".sha256").delete()
                return false
            }
            Files.move(part.toPath(),output.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            touch(cached)
            return true
        }finally{part.delete()}
    }
    private fun trim(incoming:Long){
        directory.listFiles()?.filter{it.name.endsWith(".part")}?.forEach{it.delete()}
        val files=directory.listFiles()?.filter{it.isFile&&it.extension=="jpg"}?.sortedBy{it.lastModified()}.orEmpty()
        var bytes=files.sumOf{it.length()}
        for(file in files){
            if(bytes+incoming<=MAX_BYTES)break
            val size=file.length()
            if(file.delete()){bytes-=size;File(file.path+".sha256").delete()}
        }
        check(bytes+incoming<=MAX_BYTES){"预览缓存空间不足，请清理缓存后重试"}
    }
}
