package com.phantom.recorder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

enum class G { WAVE, MARK, VOL, NOTE, CHEVRON, EARBUD, PAUSE }

@Composable
fun Glyph(k: G, tint: Color, modifier: Modifier = Modifier.size(22.dp), filled: Boolean = false, level: Int = 2) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val sw = w * 0.09f
        val st = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (k) {
            G.WAVE -> listOf(.3f, .7f, .45f, .9f, .55f, .25f).forEachIndexed { i, v ->
                val x = w * (.1f + i * .16f)
                drawLine(tint, Offset(x, h * (.5f - v / 2)), Offset(x, h * (.5f + v / 2)), sw, StrokeCap.Round)
            }
            G.MARK -> {
                val p = Path().apply {
                    moveTo(w * .25f, h * .08f); lineTo(w * .75f, h * .08f); lineTo(w * .75f, h * .92f)
                    lineTo(w * .5f, h * .7f); lineTo(w * .25f, h * .92f); close()
                }
                drawPath(p, tint, style = if (filled) Fill else st)
            }
            G.VOL -> {
                val p = Path().apply {
                    moveTo(w * .1f, h * .38f); lineTo(w * .3f, h * .38f); lineTo(w * .52f, h * .18f)
                    lineTo(w * .52f, h * .82f); lineTo(w * .3f, h * .62f); lineTo(w * .1f, h * .62f); close()
                }
                drawPath(p, tint)
                if (level >= 1) drawArc(tint, -45f, 90f, false, Offset(w * .45f, h * .27f), Size(w * .3f, h * .46f), style = st)
                if (level >= 2) drawArc(tint, -45f, 90f, false, Offset(w * .4f, h * .12f), Size(w * .5f, h * .76f), style = st)
                if (level == 0) {
                    drawLine(tint, Offset(w * .68f, h * .36f), Offset(w * .94f, h * .64f), sw, StrokeCap.Round)
                    drawLine(tint, Offset(w * .94f, h * .36f), Offset(w * .68f, h * .64f), sw, StrokeCap.Round)
                }
            }
            G.NOTE -> {
                drawCircle(tint, w * .16f, Offset(w * .35f, h * .8f))
                drawLine(tint, Offset(w * .51f, h * .8f), Offset(w * .51f, h * .12f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * .51f, h * .12f), Offset(w * .85f, h * .32f), sw, StrokeCap.Round)
            }
            G.CHEVRON -> {
                val p = Path().apply { moveTo(w * .15f, h * .35f); lineTo(w * .5f, h * .7f); lineTo(w * .85f, h * .35f) }
                drawPath(p, tint, style = st)
            }
            G.EARBUD -> {
                drawCircle(tint, w * .17f, Offset(w * .3f, h * .3f))
                drawLine(tint, Offset(w * .3f, h * .3f), Offset(w * .3f, h * .85f), sw * 1.4f, StrokeCap.Round)
                drawCircle(tint, w * .17f, Offset(w * .7f, h * .45f))
                drawLine(tint, Offset(w * .7f, h * .45f), Offset(w * .7f, h * .95f), sw * 1.4f, StrokeCap.Round)
            }
            G.PAUSE -> {
                drawLine(tint, Offset(w * .35f, h * .18f), Offset(w * .35f, h * .82f), sw * 1.8f, StrokeCap.Round)
                drawLine(tint, Offset(w * .65f, h * .18f), Offset(w * .65f, h * .82f), sw * 1.8f, StrokeCap.Round)
            }
        }
    }
}
