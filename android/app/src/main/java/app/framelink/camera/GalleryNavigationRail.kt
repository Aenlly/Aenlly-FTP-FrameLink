package app.framelink.camera

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** 宽窗口统一导航，各入口复用已有业务页面 */
@Composable
internal fun GalleryNavigationRail(entries:List<NavigationEntry>,selected:Int,onSelect:(Int)->Unit,onSettings:()->Unit){
    NavigationRail(Modifier.width(84.dp).fillMaxHeight().navigationBarsPadding().testTag("gallery-navigation-rail"),containerColor=CanvasColor){
        Spacer(Modifier.height(20.dp))
        entries.forEachIndexed{index,entry->
            NavigationRailItem(selected=index==selected,onClick={onSelect(index)},icon={Icon(entry.icon,null,Modifier.size(24.dp))},label={Text(entry.title)},modifier=Modifier.testTag(entry.tag))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick=onSettings){Icon(Icons.Outlined.Settings,"设置与空间")}
    }
}
