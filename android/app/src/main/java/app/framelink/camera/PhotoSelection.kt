package app.framelink.camera

/** 一次滑动固定照片顺序与初始选择，回滑会恢复范围外原有状态 */
internal class PhotoSelectionRange(
    val groups: List<List<String>>,
    initial: List<String>,
    private val anchor: Int,
) {
    private val original = initial.toSet()
    private val selecting: Boolean
    var lastIndex: Int = -1
        private set

    init {
        require(anchor in groups.indices && groups.all { it.isNotEmpty() })
        selecting = groups[anchor].any { it !in original }
    }

    fun selectionAt(index: Int): List<String> {
        require(index in groups.indices)
        lastIndex = index
        val affected = groups.subList(minOf(anchor, index), maxOf(anchor, index) + 1).flatten().toSet()
        return (if (selecting) original + affected else original - affected).toList()
    }
}
