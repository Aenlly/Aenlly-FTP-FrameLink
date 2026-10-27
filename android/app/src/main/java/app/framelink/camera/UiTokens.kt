package app.framelink.camera

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 摄影内容、工作卡片和浮动控件分别采用稳定尺寸，不随照片比例改变 */
internal object UiTokens {
    val card = RoundedCornerShape(18.dp)
    val control = RoundedCornerShape(12.dp)
    val photo = RoundedCornerShape(8.dp)
    val page = 16.dp
    val gap = 12.dp
    val icon = 22.dp
    val darkColors =
        darkColorScheme(
            primary = Color(0xffb1ccdf),
            onPrimary = Color(0xff193347),
            secondary = Color(0xffb1ccdf),
            background = Color(0xff111318),
            surface = Color(0xff20252d),
            onSurface = Color(0xfff1f3f6),
            onBackground = Color(0xfff1f3f6),
            outline = Color(0xff68717d),
        )
    val shapes =
        Shapes(
            extraSmall = control,
            small = control,
            medium = card,
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        )
    val typography =
        Typography(
            titleLarge =
                TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            titleMedium =
                TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
            bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge =
                TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
            labelMedium =
                TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
            labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
        )
}

@Composable
internal fun DarkScene(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = UiTokens.darkColors,
        typography = UiTokens.typography,
        shapes = UiTokens.shapes,
        content = content,
    )
}
