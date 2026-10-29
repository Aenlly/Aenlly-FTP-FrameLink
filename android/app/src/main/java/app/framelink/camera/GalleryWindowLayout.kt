package app.framelink.camera

/** 以应用窗口和图库内容区的dp宽度决定布局，分屏不沿用设备全屏尺寸 */
internal data class GalleryWindowLayout(val rail:Boolean,val preview:Boolean)

internal fun galleryWindowLayout(widthDp:Int)=GalleryWindowLayout(rail=widthDp>=600,preview=widthDp>=1000)

internal fun galleryColumnCount(widthDp:Int,layout:GalleryLayout,adaptive:Boolean=false):Int {
    if(layout==GalleryLayout.LIST)return 1
    if(widthDp<600&&!adaptive)return layout.columns
    val target=when(layout){GalleryLayout.SMALL->84;GalleryLayout.MEDIUM->132;else->200}
    return (widthDp/target).coerceAtLeast(1)
}
