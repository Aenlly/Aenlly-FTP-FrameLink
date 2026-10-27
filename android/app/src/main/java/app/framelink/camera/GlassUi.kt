package app.framelink.camera

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.roundToInt

/** 玻璃层只捕获下方内容，文字和按钮保持清晰；旧系统使用高对比半透明材质 */
@Composable
internal fun GlassSurface(
    state: HazeState,
    modifier: Modifier = Modifier,
    radius: Dp = 28.dp,
    dark: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    val layer = rememberGraphicsLayer()
    var position by remember { mutableStateOf(Offset.Zero) }
    val radiusPx = with(LocalDensity.current) { radius.toPx() }
    val blurPx = with(LocalDensity.current) { (if (dark) 4.dp else 12.dp).toPx() }
    val optics = remember {
        if (Build.VERSION.SDK_INT >= 33) runCatching { GlassOptics() }.getOrNull() else null
    }
    val tint = if (dark) Color(0xff182130).copy(alpha = .66f) else Color.White.copy(alpha = .82f)
    val fallback =
        if (dark) Color(0xff202938).copy(alpha = .94f) else Color(0xfff5f7fb).copy(alpha = .94f)
    val blur =
        HazeStyle(
            backgroundColor = if (dark) Color(0xff111318) else CanvasColor,
            tint = HazeTint(tint),
            blurRadius = 18.dp,
            noiseFactor = .015f,
            fallbackTint = HazeTint(fallback),
        )
    val effect =
        if (optics == null) Modifier.hazeEffect(state, blur)
        else
            Modifier.drawWithContent {
                val area = state.areas.firstOrNull { it.contentLayer != null }
                val source = area?.contentLayer
                if (source != null) {
                    val offset = position - area.positionOnScreen
                    layer.record(
                        size =
                            IntSize(
                                size.width.roundToInt().coerceAtLeast(1),
                                size.height.roundToInt().coerceAtLeast(1),
                            )
                    ) {
                        translate(-offset.x, -offset.y) { drawLayer(source) }
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        layer.renderEffect =
                            optics.effect(size.width, size.height, radiusPx, dark, blurPx)
                    }
                    drawLayer(layer)
                } else drawRect(fallback)
                drawContent()
            }
    Box(
        modifier
            .shadow(
                6.dp,
                shape,
                ambientColor = Color(0xff18263a).copy(alpha = .07f),
                spotColor = Color(0xff18263a).copy(alpha = .05f),
            )
            .clip(shape)
            .onGloballyPositioned { position = it.positionOnScreen() }
            .then(effect)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (dark) .025f else .10f),
                        tint.copy(alpha = .025f),
                    )
                )
            )
            .border(
                BorderStroke(
                    .5.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = if (dark) .30f else .55f),
                            Color.White.copy(alpha = .06f),
                            Color.White.copy(alpha = if (dark) .12f else .22f),
                        )
                    ),
                ),
                shape,
            ),
        content = content,
    )
}

/** Android13以上对捕获图层进行圆角边缘折射和少量多点采样，不复制照片到CPU */
@androidx.annotation.RequiresApi(33)
private class GlassOptics {
    private val shader =
        android.graphics.RuntimeShader(
            """
        uniform shader scene;
        uniform float2 resolution;
        uniform float corner;
        uniform float dark;
        half4 main(float2 p) {
            float2 halfSize = resolution * 0.5;
            float2 q = p - halfSize;
            float r = min(corner, min(halfSize.x, halfSize.y));
            float2 v = abs(q) - halfSize + r;
            float sd = length(max(v, 0.0)) + min(max(v.x, v.y), 0.0) - r;
            float band = 1.0 - smoothstep(0.0, 14.0, max(-sd, 0.0));
            float2 n = normalize(q / max(halfSize, float2(1.0)) + float2(0.0001));
            float2 uv = p - n * band * 3.5;
            half4 c = scene.eval(uv) * 0.28;
            c += scene.eval(uv + float2(3.0, 0.0)) * 0.18;
            c += scene.eval(uv - float2(3.0, 0.0)) * 0.18;
            c += scene.eval(uv + float2(0.0, 3.0)) * 0.18;
            c += scene.eval(uv - float2(0.0, 3.0)) * 0.18;
            half3 tint = mix(half3(1.0), half3(0.06, 0.09, 0.14), half(dark));
            c.rgb = mix(c.rgb, tint, half(mix(0.82, 0.38, dark)));
            float gleam = band * (0.01 + max(-n.y, 0.0) * 0.045);
            c.rgb += half3(gleam);
            float luminance = dot(float3(c.rgb), float3(0.2126, 0.7152, 0.0722));
            c.rgb *= half(mix(1.0, min(1.0, 0.40 / max(luminance, 0.001)), dark));
            c.a = 1.0;
            return c;
        }
    """
                .trimIndent()
        )
    private var renderEffect: androidx.compose.ui.graphics.RenderEffect? = null
    private var parameters = emptyList<Float>()

    fun effect(
        width: Float,
        height: Float,
        corner: Float,
        dark: Boolean,
        blur: Float,
    ): androidx.compose.ui.graphics.RenderEffect {
        val current = listOf(width, height, corner, if (dark) 1f else 0f, blur)
        if (parameters == current) return checkNotNull(renderEffect)
        shader.setFloatUniform("resolution", width, height)
        shader.setFloatUniform("corner", corner)
        shader.setFloatUniform("dark", if (dark) 1f else 0f)
        // RenderEffect会快照shader参数，尺寸或模式变化时必须在设置参数后重建
        val effect =
            android.graphics.RenderEffect.createChainEffect(
                    android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "scene"),
                    android.graphics.RenderEffect.createBlurEffect(
                        blur,
                        blur,
                        android.graphics.Shader.TileMode.CLAMP,
                    ),
                )
                .asComposeRenderEffect()
        parameters = current
        renderEffect = effect
        return effect
    }
}
