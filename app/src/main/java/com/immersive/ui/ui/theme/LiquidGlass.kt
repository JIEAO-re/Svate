package com.immersive.ui.ui.theme

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Real "liquid glass" (Apple-style) — not a translucent fade.
 *
 * The trick that makes it real: the scrolling conversation is recorded into a [GraphicsLayer]
 * ([recordBackdrop]); each glass surface re-draws that captured backdrop into its own layer and
 * runs a [RenderEffect] chain over it — a Gaussian blur, then an AGSL [RuntimeShader] that bends
 * the backdrop near the rounded edges (refraction / lensing) and adds a specular rim highlight.
 * The surface's own children (icons, text) are drawn sharp on top.
 *
 * AGSL + RuntimeShader require API 33 — the app's minSdk is 33, so no version guard is needed.
 */
private const val LIQUID_GLASS_SHADER = """
uniform shader content;
uniform float2 iSize;
uniform float iRadius;
uniform float iEdge;
uniform float iRefraction;

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

half4 main(float2 fragCoord) {
    float2 center = iSize * 0.5;
    float2 p = fragCoord - center;
    float2 halfSize = center;
    float d = sdRoundRect(p, halfSize, iRadius);
    if (d > 0.0) {
        return half4(0.0);
    }

    // Rim factor: 0 deep inside, →1 right at the edge.
    float rim = 1.0 - clamp(-d / iEdge, 0.0, 1.0);

    // Outward normal = gradient of the distance field (finite differences).
    float2 n = normalize(float2(
        sdRoundRect(p + float2(1.0, 0.0), halfSize, iRadius) - sdRoundRect(p - float2(1.0, 0.0), halfSize, iRadius),
        sdRoundRect(p + float2(0.0, 1.0), halfSize, iRadius) - sdRoundRect(p - float2(0.0, 1.0), halfSize, iRadius)
    ));

    // Refraction: near the rim, sample the backdrop displaced inward along the normal.
    float bend = rim * rim;
    float2 displaced = fragCoord - n * bend * iRefraction;
    half4 col = content.eval(displaced);

    // Frosted glass body tint so it reads as a pane even over a flat background.
    col.rgb = mix(col.rgb, half3(1.0), 0.17);

    // Broad diagonal sheen across the upper-left — the "lit pane" look.
    float sheen = clamp(1.0 - (fragCoord.x / iSize.x) * 0.6 - (fragCoord.y / iSize.y) * 0.85, 0.0, 1.0);
    col.rgb += half3(sheen) * 0.16;

    // Specular rim highlight: a bright lip on the top-left edge.
    float2 lightDir = normalize(float2(-0.5, -1.0));
    float spec = pow(clamp(dot(n, lightDir), 0.0, 1.0), 3.5) * rim;
    col.rgb += half3(spec) * 0.95;

    // Faint contact shade on the bottom-right rim for depth.
    float2 darkDir = normalize(float2(0.5, 1.0));
    float shade = pow(clamp(dot(n, darkDir), 0.0, 1.0), 4.0) * rim;
    col.rgb -= half3(shade) * 0.06;

    // 1px antialiased outer edge.
    col.a *= clamp(-d, 0.0, 1.0);
    return col;
}
"""

/**
 * Record this composable's drawing into [layer] (so a glass surface can blur it) while still
 * drawing it normally. Put this on the full-screen conversation behind the glass bars.
 */
fun Modifier.recordBackdrop(layer: GraphicsLayer): Modifier = this.drawWithContent {
    layer.record { this@drawWithContent.drawContent() }
    drawLayer(layer)
}

/**
 * A liquid-glass pane that refracts + blurs whatever [backdrop] captured behind it.
 * Place inside the same parent that owns [backdrop]; [content] is drawn sharp on top.
 */
@Composable
fun LiquidGlassSurface(
    backdrop: GraphicsLayer,
    backdropOrigin: Offset,
    shape: Shape,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    blurRadius: Dp = 22.dp,
    refraction: Dp = 14.dp,
    rim: Dp = 22.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    // Position of this surface in root coords; the backdrop was recorded at [backdropOrigin],
    // so translating the drawn layer by (backdropOrigin - surfacePos) aligns the captured
    // pixels exactly under the glass — no matter how deeply the surface is nested.
    var surfacePos by remember { mutableStateOf(Offset.Zero) }
    Box(modifier = modifier.onGloballyPositioned { surfacePos = it.localToRoot(Offset.Zero) }) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    val cr = cornerRadius.toPx()
                    val br = blurRadius.toPx().coerceAtLeast(0.01f)
                    val rf = refraction.toPx()
                    val rm = rim.toPx().coerceAtLeast(1f)
                    val agsl = RuntimeShader(LIQUID_GLASS_SHADER).apply {
                        setFloatUniform("iSize", size.width, size.height)
                        setFloatUniform("iRadius", cr)
                        setFloatUniform("iEdge", rm)
                        setFloatUniform("iRefraction", rf)
                    }
                    renderEffect = RenderEffect.createChainEffect(
                        RenderEffect.createRuntimeShaderEffect(agsl, "content"),
                        RenderEffect.createBlurEffect(br, br, Shader.TileMode.CLAMP),
                    ).asComposeRenderEffect()
                    clip = true
                    this.shape = shape
                }
                .drawWithContent {
                    translate(left = backdropOrigin.x - surfacePos.x, top = backdropOrigin.y - surfacePos.y) {
                        drawLayer(backdrop)
                    }
                },
        )
        content()
    }
}
