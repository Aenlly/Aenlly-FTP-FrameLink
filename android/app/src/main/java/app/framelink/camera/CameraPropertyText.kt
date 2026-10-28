package app.framelink.camera

import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.roundToInt

/** 仅翻译已核对的协议值；相机仍决定候选值与可写状态，未知选项不提供盲选 */
internal object CameraPropertyText {
    private val names = mapOf(
        0x5005 to "白平衡", 0x5007 to "光圈", 0x5008 to "焦距", 0x5009 to "对焦距离",
        0x500A to "对焦模式", 0x500B to "测光模式", 0x500C to "闪光模式", 0x500D to "快门速度",
        0x500E to "曝光模式", 0x500F to "感光度", 0x5010 to "曝光补偿", 0x501C to "对焦测光",
        0xD22C to "对焦区域", 0xD284 to "触摸对焦", 0xD285 to "取消触摸对焦",
        0xD20D to "快门速度", 0xD21E to "感光度",
    )
    val exposureOrder = listOf(0x5007, 0xD20D, 0x500D, 0xD21E, 0x500F, 0x5010)
    private val values = mapOf(
        0x500A to mapOf(1L to "手动对焦（MF）", 2L to "单次自动对焦（AF-S）", 3L to "微距自动对焦",
            0x8004L to "连续自动对焦（AF-C）", 0x8005L to "自动切换对焦（AF-A）", 0x8006L to "直接手动对焦（DMF）"),
        0xD22C to mapOf(1L to "广域", 2L to "区", 3L to "中间",
            0x101L to "点：小", 0x102L to "点：中", 0x103L to "点：大", 0x104L to "扩展点",
            0x201L to "跟踪：广域", 0x202L to "跟踪：区", 0x203L to "跟踪：中间",
            0x204L to "跟踪：点（小）", 0x205L to "跟踪：点（中）", 0x206L to "跟踪：点（大）", 0x207L to "跟踪：扩展点"),
        0x500B to mapOf(1L to "平均测光", 2L to "中央重点平均", 3L to "多点测光", 4L to "中央点测光",
            0x8001L to "多重测光", 0x8002L to "中央重点", 0x8003L to "全画面平均", 0x8004L to "点测光：标准", 0x8005L to "点测光：大", 0x8006L to "强光测光"),
        0x500C to mapOf(1L to "自动闪光", 2L to "关闭闪光", 3L to "强制闪光", 4L to "自动防红眼", 5L to "强制防红眼", 6L to "外接同步"),
        0x5005 to mapOf(1L to "手动白平衡", 2L to "自动白平衡", 3L to "一键自动白平衡", 4L to "日光", 5L to "荧光灯", 6L to "白炽灯", 7L to "闪光灯",
            0x8001L to "荧光灯：暖白", 0x8002L to "荧光灯：冷白", 0x8003L to "荧光灯：日间白", 0x8004L to "荧光灯：日光",
            0x8010L to "阴天", 0x8011L to "阴影", 0x8012L to "色温与滤镜", 0x8020L to "自定义一", 0x8021L to "自定义二", 0x8022L to "自定义三", 0x8030L to "水中自动"),
        0x500E to mapOf(1L to "手动曝光（M）", 2L to "程序自动（P）", 3L to "光圈优先（A）", 4L to "快门优先（S）", 5L to "创意程序", 6L to "运动程序", 7L to "人像"),
    )

    fun name(property: CameraProperty): String = names[property.code] ?: "机身设置"

    fun label(property: CameraProperty, value: Long = property.value): String? {
        values[property.code]?.let { return it[value] }
        return when (property.code) {
            0x5007 -> if (value > 0) "f/${decimal(value / 100.0)}" else "自动"
            0x500F -> if (value in setOf(0L, 0xffffffL, 0xffffffffL)) "自动" else "ISO $value"
            0x5010 -> (if (value > 0) "+" else "") + decimal(value / 1000.0) + " EV"
            0x500D -> {
                if (value <= 0) return null
                val seconds = value / 10000.0
                val inverse = 1 / seconds
                if (inverse >= 4 && abs(inverse - inverse.roundToInt()) / inverse < .02)
                    "1/${inverse.roundToInt()} 秒" else "${decimal(seconds)} 秒"
            }
            0xD20D -> {
                if (value == 0L) return "长时间曝光（B门）"
                val numerator = (value ushr 16) and 0xffff
                val denominator = value and 0xffff
                if (denominator == 0L) null
                else if (numerator == 1L) "1/$denominator 秒"
                else "${decimal(numerator.toDouble() / denominator)} 秒"
            }
            0xD21E -> {
                val noise = value ushr 24
                if (noise > 2) return null
                val base = if ((value and 0xffffff) == 0xffffffL) "自动" else "ISO ${value and 0xffffff}"
                base + when (noise) { 1L -> "（多帧降噪）"; 2L -> "（强多帧降噪）"; else -> "" }
            }
            0x5008 -> "${decimal(value / 100.0)} 毫米"
            else -> null
        }
    }

    fun choices(property: CameraProperty): List<PropertyChoice> = property.choices.mapNotNull { choice ->
        label(property, choice.value)?.let { choice.copy(label = it) }
    }

    fun recognized(property: CameraProperty): Boolean = property.code in names

    fun quick(properties: List<CameraProperty>): List<CameraProperty> {
        val ranked = properties.filter { it.code in exposureOrder }.sortedBy { exposureOrder.indexOf(it.code) }.distinctBy { name(it) }
        return (ranked + properties.filter { it.code in setOf(0x500A, 0xD22C, 0x5005) }).distinctBy { it.code }.take(4)
    }

    fun editable(property: CameraProperty): Boolean = property.writable && label(property) != null && choices(property).isNotEmpty()

    fun compact(property: CameraProperty): String = label(property)?.substringBefore('（') ?: "机身调整"

    private fun decimal(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
