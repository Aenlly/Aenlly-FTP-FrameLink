package app.framelink.camera

import org.junit.Assert.*
import org.junit.Test

/** 有来源文本的属性投影约束，不是M4/M5的二进制协议回放 */
class SonyPublishedPropertyTest {
    private data class Entry(val model:String,val code:Int,val writable:Boolean,val value:Long,val choices:List<Long>){
        fun property()=CameraProperty(code,"",value,"",writable,choices.map{PropertyChoice(it,"")})
    }
    private data class Summary(val properties:List<Entry>,val missing:List<String>)
    private fun source():Summary=javaClass.classLoader!!.getResourceAsStream("sony/published-property-summaries.json")!!.bufferedReader().use{
        Json.decode(it.readText())
    }
    @Test fun m4RangeAndEmptyEnumsDoNotBecomeInventedWritableChoices(){
        val properties=source().properties.filter{it.model=="ILCE-7M4"}.map{it.property()}
        assertEquals("f/8",CameraPropertyText.label(properties.first{it.code==0x5007}))
        assertEquals("自动",CameraPropertyText.label(properties.first{it.code==0xd21e}))
        assertTrue(properties.all{!CameraPropertyText.editable(it)})
    }
    @Test fun m5RawValuesKeepReadOnlyAndUnknownBoundaries(){
        val properties=source().properties.filter{it.model=="ILCE-7M5"}.map{it.property()}
        val aperture=properties.first{it.code==0x5007}
        assertNull(CameraPropertyText.label(aperture))
        assertFalse(CameraPropertyText.controls(properties).any{it.code==0x5007})
        assertFalse(CameraPropertyText.editable(properties.first{it.code==0x500a}))
        assertEquals("1/50 秒",CameraPropertyText.label(properties.first{it.code==0xd20d}))
        val iso=properties.first{it.code==0xd21e}
        assertFalse(CameraPropertyText.choices(iso).any{it.value==268435506L})
        val area=properties.first{it.code==0xd22c}
        assertFalse(CameraPropertyText.choices(area).any{it.value==4353L})
        assertTrue(source().missing.any{it.contains("SSH")})
    }
}
