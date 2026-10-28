package app.framelink.camera

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState

/** 导航文字保持稳定，整块玻璃选中层随当前入口平滑移动 */
@Composable
internal fun GlassNavigation(state: HazeState, entries: List<NavigationEntry>, selected: Int, onSelect: (Int) -> Unit) {
    GlassSurface(state, Modifier.fillMaxWidth(), radius = 32.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(5.dp).height(58.dp)) {
            val width = maxWidth / entries.size
            val offset by animateDpAsState(width * selected, spring(dampingRatio = .86f, stiffness = 500f), label = "navigation-selection")
            Box(Modifier.offset(x = offset).width(width).fillMaxHeight().clip(RoundedCornerShape(27.dp))
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .75f), Brand.copy(alpha = .13f))))
                .border(1.dp, Color.White.copy(alpha = .8f), RoundedCornerShape(27.dp)))
            Row(Modifier.fillMaxSize()) {
                entries.forEachIndexed { index, entry ->
                    val tint by animateColorAsState(if (index == selected) Brand else Color(0xff657080), label = "navigation-tint")
                    Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(27.dp))
                        .testTag(entry.tag).selectable(index == selected, onClick = { onSelect(index) }, role = Role.Tab),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
                        Icon(entry.icon, entry.title, Modifier.size(23.dp), tint = tint)
                        Text(entry.title, color = tint, fontSize = 10.sp, fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

internal data class NavigationEntry(val title: String, val icon: ImageVector, val tag: String)
