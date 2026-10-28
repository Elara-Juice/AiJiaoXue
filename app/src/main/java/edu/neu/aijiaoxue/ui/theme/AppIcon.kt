package edu.neu.aijiaoxue.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class AppGlyph {
    SCHOOL, PERSON, CHEVRON_RIGHT, SEARCH, TASK, HISTORY, COMPARE, CALENDAR,
    REPORT, FEEDBACK, CHECKLIST, IDEA, TREND, BOOK, BACK, INFO, ERROR,
    ARCHIVE, HOME, SAVE, SEND, PLAY, PHOTO, EDIT, ANALYTICS, LOGIN,
}

@Composable
fun AppBackAction(onClick: () -> Unit, label: String = "返回") {
    TextButton(onClick = onClick) {
        AppIcon(AppGlyph.BACK, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}

@Composable
fun AppIcon(
    glyph: AppGlyph,
    contentDescription: String? = null,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val described = if (contentDescription == null) modifier else modifier.semantics {
        this.contentDescription = contentDescription
    }
    Canvas(described.size(24.dp)) {
        val unit = size.minDimension / 24f
        val stroke = 1.9f * unit
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = stroke) {
            drawLine(tint, point(x1, y1), point(x2, y2), strokeWidth = width, cap = StrokeCap.Round)
        }
        fun poly(vararg xy: Float, close: Boolean = false) {
            val path = Path().apply {
                moveTo(xy[0] * unit, xy[1] * unit)
                var i = 2
                while (i < xy.size) {
                    lineTo(xy[i] * unit, xy[i + 1] * unit)
                    i += 2
                }
                if (close) close()
            }
            drawPath(path, tint, style = if (close) Stroke(stroke, join = StrokeJoin.Round) else Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        fun circle(x: Float, y: Float, r: Float, filled: Boolean = false) {
            drawCircle(tint, r * unit, point(x, y), style = if (filled) androidx.compose.ui.graphics.drawscope.Fill else Stroke(stroke))
        }
        fun box(x: Float, y: Float, w: Float, h: Float, radius: Float = 2f, filled: Boolean = false) {
            drawRoundRect(
                tint, topLeft = point(x, y), size = Size(w * unit, h * unit),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * unit),
                style = if (filled) androidx.compose.ui.graphics.drawscope.Fill else Stroke(stroke),
            )
        }
        fun arc(x: Float, y: Float, w: Float, h: Float, start: Float, sweep: Float) {
            drawArc(tint, start, sweep, false, point(x, y), Size(w * unit, h * unit), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        when (glyph) {
            AppGlyph.SCHOOL -> { poly(2f, 9f, 12f, 4f, 22f, 9f, 12f, 14f, 2f, 9f, close = true); line(6f, 11f, 6f, 16f); poly(6f, 16f, 12f, 19f, 18f, 16f, 18f, 11f); line(22f, 9f, 22f, 16f) }
            AppGlyph.PERSON -> { circle(12f, 8f, 4f); arc(4f, 14f, 16f, 12f, 180f, 180f) }
            AppGlyph.CHEVRON_RIGHT -> poly(9f, 5f, 16f, 12f, 9f, 19f)
            AppGlyph.SEARCH -> { circle(10.5f, 10.5f, 6.5f); line(15.5f, 15.5f, 21f, 21f) }
            AppGlyph.TASK -> { box(5f, 3f, 14f, 18f); line(9f, 8f, 16f, 8f); line(9f, 12f, 16f, 12f); line(9f, 16f, 14f, 16f); line(7f, 8f, 7.1f, 8f, stroke * 1.7f) }
            AppGlyph.HISTORY -> { arc(4f, 4f, 16f, 16f, -55f, 300f); poly(4f, 4f, 4f, 10f, 10f, 9f); line(12f, 8f, 12f, 12f); line(12f, 12f, 16f, 14f) }
            AppGlyph.COMPARE -> { line(4f, 8f, 20f, 8f); poly(16f, 4f, 20f, 8f, 16f, 12f); line(20f, 16f, 4f, 16f); poly(8f, 12f, 4f, 16f, 8f, 20f) }
            AppGlyph.CALENDAR -> { box(3f, 5f, 18f, 16f); line(3f, 10f, 21f, 10f); line(8f, 3f, 8f, 7f); line(16f, 3f, 16f, 7f); poly(8f, 15f, 11f, 18f, 16f, 13f) }
            AppGlyph.REPORT, AppGlyph.ANALYTICS -> { line(4f, 20f, 20f, 20f); line(5f, 20f, 5f, 12f); line(10f, 20f, 10f, 8f); line(15f, 20f, 15f, 14f); line(20f, 20f, 20f, 5f) }
            AppGlyph.FEEDBACK -> { poly(4f, 5f, 20f, 5f, 20f, 17f, 11f, 17f, 6f, 21f, 7f, 17f, 4f, 17f, 4f, 5f, close = true); line(8f, 9f, 16f, 9f); line(8f, 13f, 14f, 13f) }
            AppGlyph.CHECKLIST -> { poly(4f, 7f, 6f, 9f, 9f, 5f); line(12f, 7f, 20f, 7f); poly(4f, 15f, 6f, 17f, 9f, 13f); line(12f, 15f, 20f, 15f); line(4f, 21f, 20f, 21f) }
            AppGlyph.IDEA -> { circle(12f, 10f, 6f); line(9f, 16f, 10f, 19f); line(15f, 16f, 14f, 19f); line(10f, 20f, 14f, 20f); line(12f, 2f, 12f, 1f); line(3f, 10f, 2f, 10f); line(21f, 10f, 22f, 10f) }
            AppGlyph.TREND -> { poly(3f, 18f, 9f, 12f, 13f, 15f, 21f, 7f); poly(16f, 7f, 21f, 7f, 21f, 12f) }
            AppGlyph.BOOK -> { poly(3f, 5f, 10f, 5f, 12f, 7f, 14f, 5f, 21f, 5f, 21f, 19f, 14f, 19f, 12f, 21f, 10f, 19f, 3f, 19f, 3f, 5f, close = true); line(12f, 7f, 12f, 21f); line(5f, 9f, 9f, 9f); line(15f, 9f, 19f, 9f) }
            AppGlyph.BACK -> { poly(14f, 5f, 7f, 12f, 14f, 19f); line(7f, 12f, 21f, 12f) }
            AppGlyph.INFO -> { circle(12f, 12f, 9f); circle(12f, 7f, 0.8f, filled = true); line(12f, 11f, 12f, 17f) }
            AppGlyph.ERROR -> { poly(12f, 3f, 22f, 20f, 2f, 20f, 12f, 3f); line(12f, 9f, 12f, 14f); circle(12f, 17f, 0.8f, filled = true) }
            AppGlyph.ARCHIVE -> { box(4f, 8f, 16f, 12f); box(3f, 4f, 18f, 4f); line(9f, 12f, 15f, 12f); line(9f, 15f, 15f, 15f) }
            AppGlyph.HOME -> { poly(3f, 11f, 12f, 4f, 21f, 11f); poly(6f, 10f, 6f, 20f, 18f, 20f, 18f, 10f); box(10f, 14f, 4f, 6f) }
            AppGlyph.SAVE -> { box(4f, 3f, 16f, 18f); box(8f, 3f, 8f, 6f, 1f); box(8f, 13f, 8f, 6f, 1f); line(7f, 9f, 17f, 9f) }
            AppGlyph.SEND -> { poly(3f, 4f, 22f, 12f, 3f, 20f, 7f, 13f, 15f, 12f, 7f, 11f, 3f, 4f); line(7f, 13f, 7f, 11f) }
            AppGlyph.PLAY -> poly(7f, 4f, 19f, 12f, 7f, 20f, close = true)
            AppGlyph.PHOTO -> { box(3f, 5f, 18f, 14f); circle(9f, 10f, 1.5f, filled = true); poly(5f, 17f, 10f, 12f, 13f, 15f, 16f, 11f, 20f, 17f) }
            AppGlyph.EDIT -> { poly(5f, 17f, 6f, 21f, 10f, 20f, 20f, 10f, 15f, 5f, 5f, 17f); line(13f, 7f, 18f, 12f); line(5f, 17f, 10f, 20f) }
            AppGlyph.LOGIN -> { box(3f, 4f, 10f, 16f); line(9f, 12f, 21f, 12f); poly(17f, 8f, 21f, 12f, 17f, 16f) }
        }
    }
}
