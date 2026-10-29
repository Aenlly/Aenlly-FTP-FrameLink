package app.framelink.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun PhotoChoiceControl(selected:Boolean,enabled:Boolean,description:String,onSelect:(Boolean)->Unit,modifier:Modifier=Modifier){
    Box(modifier.size(44.dp).clip(CircleShape).toggleable(selected,enabled=enabled,role=Role.Checkbox,onValueChange=onSelect).semantics{contentDescription=description},contentAlignment=Alignment.Center){
        Box(Modifier.size(27.dp).background(if(selected)SelectionAccent else Color.Black.copy(alpha=.25f),CircleShape).border(2.dp,Color.White,CircleShape),contentAlignment=Alignment.Center){
            if(selected)Icon(Icons.Outlined.Check,null,tint=Color.White,modifier=Modifier.size(19.dp))
        }
    }
}
