package app.framelink.camera

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ComparisonPhoto(runtime:CameraRuntime,photo:Photo,selected:Boolean,onSelect:()->Unit,modifier:Modifier){
    Column(modifier.padding(8.dp).testTag("compare-photo-${photo.id}")){
        PhotoThumbnail(runtime,photo,Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)),contentScale=ContentScale.Fit)
        Row(verticalAlignment=Alignment.CenterVertically){
            Text(photo.name,color=Color(0xffa0a8b5),fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
            TextButton(onClick=onSelect){Text(if(selected)"已选择"else"选择这张",fontSize=11.sp)}
        }
    }
}
