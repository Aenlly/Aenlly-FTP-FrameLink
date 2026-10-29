package app.framelink.camera

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 四页工作区统一标题层级，业务动作由各页面提供 */
@Composable
internal fun WorkspaceHeader(title:String,subtitle:String,actions:@Composable RowScope.()->Unit={}){
    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){
            Text(title,fontSize=26.sp,fontWeight=FontWeight.SemiBold,color=Ink)
            Text(subtitle,fontSize=12.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
        }
        actions()
    }
}

@Composable
internal fun WorkspaceTool(title:String,subtitle:String,icon:ImageVector,status:String="",onClick:()->Unit,compact:Boolean=false){
    Row(Modifier.fillMaxWidth().heightIn(min=if(compact)56.dp else 64.dp).clickable(onClick=onClick).padding(horizontal=14.dp,vertical=if(compact)8.dp else 12.dp),verticalAlignment=Alignment.CenterVertically){
        Icon(icon,null,tint=Brand,modifier=Modifier.size(25.dp))
        Column(Modifier.weight(1f).padding(horizontal=12.dp)){
            Text(title,fontSize=14.sp,fontWeight=FontWeight.Medium,color=Ink)
            Text(subtitle,fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=3.dp))
        }
        if(status.isNotBlank())Text(status,fontSize=10.sp,color=Color(0xff348261),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=80.dp))
        Icon(Icons.Outlined.ChevronRight,null,tint=Muted,modifier=Modifier.size(18.dp))
    }
}

@Composable
internal fun WorkspaceSection(title:String,note:String="",action:(@Composable ()->Unit)?=null){
    Row(Modifier.fillMaxWidth().padding(top=6.dp,bottom=2.dp),verticalAlignment=Alignment.CenterVertically){
        Text(title,fontSize=16.sp,fontWeight=FontWeight.SemiBold,color=Ink)
        Spacer(Modifier.weight(1f))
        if(action!=null)action()else Text(note,fontSize=11.sp,color=Muted)
    }
}

internal enum class SelectionPurpose(val actionTitle:String){BATCH("保存选片"),CLIENT("新建客户场次")}

@Composable
internal fun WorkspaceRecord(runtime:CameraRuntime,title:String,description:String,cover:Photo?,status:String="",onClick:()->Unit,actions:@Composable ()->Unit={}){
    Surface(onClick=onClick,color=Color.White,shape=RoundedCornerShape(22.dp)){
        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){
            if(cover!=null)PhotoThumbnail(runtime,cover,Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)))
            else Box(Modifier.size(84.dp),contentAlignment=Alignment.Center){Icon(Icons.Outlined.Collections,null,tint=Muted)}
            Column(Modifier.weight(1f).padding(horizontal=12.dp)){
                Text(title,fontSize=16.sp,fontWeight=FontWeight.SemiBold,color=Ink,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(description,fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=5.dp))
                if(status.isNotBlank())Text(status,fontSize=11.sp,color=Brand,modifier=Modifier.padding(top=7.dp))
            }
            actions()
            Icon(Icons.Outlined.ChevronRight,null,tint=Muted,modifier=Modifier.size(18.dp))
        }
    }
}
