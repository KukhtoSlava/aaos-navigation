package com.autobox.navigation.carapp.cluster

import androidx.car.app.navigation.model.Maneuver
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autobox.navigation.R
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Saira = FontFamily(
    Font(R.font.saira_medium, FontWeight.Medium),
    Font(R.font.saira_semibold, FontWeight.SemiBold),
)

/**
 * Next maneuver on the cluster: an arrow, the distance to it and the road after it.
 * Styled after the cluster's own floating panels (colors from the AutoBox cluster palette).
 */
@Composable
internal fun ManeuverCard(maneuver: NextManeuver, modifier: Modifier = Modifier) {
    val accent = colorResource(R.color.cluster_accent)
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .padding(top = 16.dp)
            .widthIn(max = 420.dp)
            .background(colorResource(R.color.cluster_panel), shape)
            .border(1.dp, colorResource(R.color.cluster_panel_border), shape)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ManeuverArrow(type = maneuver.type, color = accent, modifier = Modifier.size(52.dp))
        Spacer(Modifier.width(18.dp))
        Column {
            Text(
                text = formatDistance(maneuver.distanceMeters),
                color = colorResource(R.color.cluster_text),
                fontFamily = Saira,
                fontWeight = FontWeight.SemiBold,
                fontSize = 30.sp,
                lineHeight = 32.sp,
            )
            if (maneuver.road.isNotEmpty()) {
                Text(
                    text = maneuver.road,
                    color = colorResource(R.color.cluster_text_secondary),
                    fontFamily = Saira,
                    fontWeight = FontWeight.Medium,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatDistance(meters: Double): String = when {
    meters >= 10_000 -> "${(meters / 1000).roundToInt()} km"
    meters >= 1_000 -> "%.1f km".format(meters / 1000)
    meters >= 100 -> "${(meters / 50).roundToInt() * 50} m"
    else -> "${(meters / 10).roundToInt() * 10} m"
}

/** Arrow shapes; the angle is the turn in degrees, negative to the left. */
private sealed interface ArrowShape {
    data class Turn(val degrees: Float) : ArrowShape
    data class UTurn(val left: Boolean) : ArrowShape
    data class Roundabout(val clockwise: Boolean) : ArrowShape
    data object Destination : ArrowShape
}

private fun arrowShapeOf(type: Int): ArrowShape = when (type) {
    Maneuver.TYPE_TURN_SLIGHT_LEFT, Maneuver.TYPE_KEEP_LEFT, Maneuver.TYPE_FORK_LEFT,
    Maneuver.TYPE_MERGE_LEFT, Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT, Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT,
    Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT, Maneuver.TYPE_ON_RAMP_NORMAL_LEFT,
    -> ArrowShape.Turn(-45f)
    Maneuver.TYPE_TURN_SLIGHT_RIGHT, Maneuver.TYPE_KEEP_RIGHT, Maneuver.TYPE_FORK_RIGHT,
    Maneuver.TYPE_MERGE_RIGHT, Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT, Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT,
    Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT, Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT,
    -> ArrowShape.Turn(45f)
    Maneuver.TYPE_TURN_NORMAL_LEFT -> ArrowShape.Turn(-90f)
    Maneuver.TYPE_TURN_NORMAL_RIGHT -> ArrowShape.Turn(90f)
    Maneuver.TYPE_TURN_SHARP_LEFT, Maneuver.TYPE_ON_RAMP_SHARP_LEFT -> ArrowShape.Turn(-135f)
    Maneuver.TYPE_TURN_SHARP_RIGHT, Maneuver.TYPE_ON_RAMP_SHARP_RIGHT -> ArrowShape.Turn(135f)
    Maneuver.TYPE_U_TURN_LEFT, Maneuver.TYPE_ON_RAMP_U_TURN_LEFT -> ArrowShape.UTurn(left = true)
    Maneuver.TYPE_U_TURN_RIGHT, Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT -> ArrowShape.UTurn(left = false)
    Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
    Maneuver.TYPE_ROUNDABOUT_ENTER_CW, Maneuver.TYPE_ROUNDABOUT_EXIT_CW,
    -> ArrowShape.Roundabout(clockwise = true)
    Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE,
    Maneuver.TYPE_ROUNDABOUT_ENTER_CCW, Maneuver.TYPE_ROUNDABOUT_EXIT_CCW,
    -> ArrowShape.Roundabout(clockwise = false)
    Maneuver.TYPE_DESTINATION, Maneuver.TYPE_DESTINATION_LEFT, Maneuver.TYPE_DESTINATION_RIGHT,
    Maneuver.TYPE_DESTINATION_STRAIGHT,
    -> ArrowShape.Destination
    else -> ArrowShape.Turn(0f)
}

@Composable
private fun ManeuverArrow(type: Int, color: Color, modifier: Modifier = Modifier) {
    val shape = arrowShapeOf(type)
    Canvas(modifier) {
        val stroke = Stroke(
            width = size.minDimension * 0.13f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        when (shape) {
            is ArrowShape.Turn -> drawTurn(shape.degrees, color, stroke)
            is ArrowShape.UTurn -> drawUTurn(shape.left, color, stroke)
            is ArrowShape.Roundabout -> drawRoundabout(shape.clockwise, color, stroke)
            ArrowShape.Destination -> drawDestination(color, stroke)
        }
    }
}

/** A stem from the bottom up to the middle, then a leg bent by [degrees] ending in a head. */
private fun DrawScope.drawTurn(degrees: Float, color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val bend = Offset(w / 2 + degrees / 180f * w * 0.12f, h * 0.55f)
    val radians = Math.toRadians(degrees.toDouble())
    val leg = h * 0.38f
    val tip = Offset(
        bend.x + (sin(radians) * leg).toFloat(),
        bend.y - (cos(radians) * leg).toFloat(),
    )
    val path = Path().apply {
        moveTo(bend.x, h * 0.95f)
        lineTo(bend.x, bend.y)
        lineTo(tip.x, tip.y)
    }
    drawPath(path, color, style = stroke)
    drawHead(tip, degrees, color, stroke)
}

private fun DrawScope.drawUTurn(left: Boolean, color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val side = if (left) -1f else 1f
    val start = Offset(w / 2 - side * w * 0.18f, h * 0.95f)
    val end = Offset(w / 2 + side * w * 0.18f, h * 0.6f)
    val path = Path().apply {
        moveTo(start.x, start.y)
        lineTo(start.x, h * 0.35f)
        cubicTo(start.x, h * 0.05f, end.x, h * 0.05f, end.x, h * 0.35f)
        lineTo(end.x, end.y)
    }
    drawPath(path, color, style = stroke)
    drawHead(end, 180f, color, stroke)
}

private fun DrawScope.drawRoundabout(clockwise: Boolean, color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val center = Offset(w / 2, h * 0.45f)
    val radius = w * 0.2f
    drawCircle(color, radius, center, style = stroke)
    val side = if (clockwise) -1f else 1f
    drawLine(color, Offset(w / 2, h * 0.95f), Offset(w / 2, center.y + radius), stroke.width, StrokeCap.Round)
    val exit = Offset(center.x + side * radius * 0.7f, center.y - radius * 0.7f)
    val tip = Offset(exit.x + side * w * 0.18f, exit.y - h * 0.18f)
    drawLine(color, exit, tip, stroke.width, StrokeCap.Round)
    drawHead(tip, side * 45f, color, stroke)
}

private fun DrawScope.drawDestination(color: Color, stroke: Stroke) {
    val w = size.width
    val h = size.height
    val head = Offset(w / 2, h * 0.38f)
    drawCircle(color, w * 0.24f, head, style = stroke)
    drawCircle(color, w * 0.07f, head)
    val path = Path().apply {
        moveTo(w / 2 - w * 0.17f, h * 0.55f)
        lineTo(w / 2, h * 0.92f)
        lineTo(w / 2 + w * 0.17f, h * 0.55f)
    }
    drawPath(path, color, style = stroke)
}

/** Arrow head at [tip], pointing along [degrees] (0 = up, positive = clockwise). */
private fun DrawScope.drawHead(tip: Offset, degrees: Float, color: Color, stroke: Stroke) {
    val arm = size.minDimension * 0.2f
    rotate(degrees, pivot = tip) {
        val path = Path().apply {
            moveTo(tip.x - arm, tip.y + arm)
            lineTo(tip.x, tip.y)
            lineTo(tip.x + arm, tip.y + arm)
        }
        drawPath(path, color, style = stroke)
    }
}
