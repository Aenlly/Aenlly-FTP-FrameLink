package app.framelink.camera

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 整行可点选，账号与密码保存由用户明确选择 */
@Composable
internal fun CredentialSaveToggle(checked:Boolean,onChange:(Boolean)->Unit,enabled:Boolean=true,label:String="保存此连接的账号和密码"){
    Row(Modifier.fillMaxWidth().heightIn(min=44.dp).toggleable(checked,enabled=enabled,role=Role.Checkbox,onValueChange=onChange)
        .semantics{contentDescription=label},verticalAlignment=Alignment.CenterVertically){
        Checkbox(checked,null,enabled=enabled)
        Spacer(Modifier.width(8.dp))
        Text(label,fontSize=13.sp)
    }
}
