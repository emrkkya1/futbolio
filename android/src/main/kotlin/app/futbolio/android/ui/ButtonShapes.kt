package app.futbolio.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

internal val relayDiamond: Shape = SoftPolygon(5.dp) { size ->
    listOf(Offset(size.width / 2, 0f), Offset(size.width, size.height / 2),
        Offset(size.width / 2, size.height), Offset(0f, size.height / 2))
}

/** A horizontal button with half a diamond at each end, rather than rounded corners. */
internal val pointedButton: Shape = SoftPolygon(4.dp) { size ->
    val tip = minOf(size.height / 2, size.width / 4)
    listOf(Offset(tip, 0f), Offset(size.width - tip, 0f), Offset(size.width, size.height / 2),
        Offset(size.width - tip, size.height), Offset(tip, size.height), Offset(0f, size.height / 2))
}

internal val pointedOnSegment: Shape = SoftPolygon(3.dp) { size ->
    val tip = minOf(size.height / 2, size.width / 4)
    val middle = size.width / 2
    listOf(Offset(tip, 0f), Offset(middle - tip / 2, 0f), Offset(middle + tip / 2, size.height / 2),
        Offset(middle - tip / 2, size.height), Offset(tip, size.height), Offset(0f, size.height / 2))
}

internal val pointedOffSegment: Shape = SoftPolygon(3.dp) { size ->
    val tip = minOf(size.height / 2, size.width / 4)
    val middle = size.width / 2
    listOf(Offset(middle - tip / 2, 0f), Offset(size.width - tip, 0f), Offset(size.width, size.height / 2),
        Offset(size.width - tip, size.height), Offset(middle - tip / 2, size.height), Offset(middle + tip / 2, size.height / 2))
}

/** Trim a few density-independent pixels off each vertex and join with a soft curve. */
private class SoftPolygon(
    private val cornerTrim: Dp,
    private val vertices: (Size) -> List<Offset>,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val points = vertices(size)
        val trim = with(density) { cornerTrim.toPx() }
        val path = Path()
        points.forEachIndexed { index, corner ->
            val previous = points[(index + points.size - 1) % points.size] - corner
            val next = points[(index + 1) % points.size] - corner
            val distance = minOf(trim, previous.getDistance() / 2, next.getDistance() / 2)
            val entry = corner + previous * (distance / previous.getDistance().coerceAtLeast(.001f))
            val exit = corner + next * (distance / next.getDistance().coerceAtLeast(.001f))
            if (index == 0) path.moveTo(entry.x, entry.y) else path.lineTo(entry.x, entry.y)
            path.quadraticTo(corner.x, corner.y, exit.x, exit.y)
        }
        path.close()
        return Outline.Generic(path)
    }
}
