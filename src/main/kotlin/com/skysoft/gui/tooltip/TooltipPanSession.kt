package com.skysoft.gui.tooltip

import net.minecraft.client.gui.screens.Screen
import org.joml.Vector2i
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal class TooltipPanSession(
    val screen: Screen?,
    identity: Int,
    anchorX: Int,
    anchorY: Int,
    frame: TooltipPanFrame,
    observedAt: Long,
    startOnTop: Boolean,
) {
    private var identity = identity
    private var anchorX = anchorX
    private var anchorY = anchorY
    private var frame = frame
    var lastObservedNanos = observedAt
        private set
    var zoom = 1.0
        private set
    private var targetX = 0.0
    private var targetY = 0.0
    private var displayedX = 0.0
    private var displayedY = 0.0
    private var pinnedCorner: Vector2i? = null

    init {
        clampMotion()
        alignTallTooltipToTop(startOnTop)
    }

    fun isDifferentTarget(nextIdentity: Int, nextAnchorX: Int, nextAnchorY: Int): Boolean =
        identity != nextIdentity || abs(anchorX - nextAnchorX) > ANCHOR_TOLERANCE ||
            abs(anchorY - nextAnchorY) > ANCHOR_TOLERANCE

    fun observe(
        nextIdentity: Int,
        nextAnchorX: Int,
        nextAnchorY: Int,
        nextFrame: TooltipPanFrame,
        observedAt: Long,
    ) {
        identity = nextIdentity
        anchorX = nextAnchorX
        anchorY = nextAnchorY
        frame = nextFrame
        lastObservedNanos = observedAt
        restorePinnedCorner()
        clampMotion()
    }

    fun panBy(x: Double, y: Double) {
        targetX += x
        targetY += y
        clampMotion()
    }

    fun center() {
        targetX = 0.0
        targetY = 0.0
        displayedX = 0.0
        displayedY = 0.0
    }

    /**
     * Scales the tooltip by [steps] wheel notches, keeping its top-left corner where it already sits so the
     * lines being read stay under the cursor instead of jumping when the layout is measured again.
     */
    fun zoomBy(steps: Double, step: Double, minimum: Double, maximum: Double) {
        val next = (zoom * (1.0 + step).pow(steps)).coerceIn(minimum, maximum)
        if (next == zoom) return
        pinnedCorner = Vector2i(
            Math.round(frame.x + displayedX).toInt(),
            Math.round(frame.y + displayedY).toInt(),
        )
        zoom = next
    }

    fun reset() {
        center()
        zoom = 1.0
        pinnedCorner = null
    }

    fun alignTallTooltipToTop(startOnTop: Boolean) {
        if (
            !startOnTop ||
            frame.height <= frame.viewportHeight - EDGE_GAP * 2 ||
            frame.y >= EDGE_GAP
        ) return
        targetY = EDGE_GAP - frame.y.toDouble()
        displayedY = targetY
        clampMotion()
    }

    fun advance(amount: Double) {
        if (amount >= 1.0) {
            displayedX = targetX
            displayedY = targetY
            return
        }
        displayedX = settle(displayedX + (targetX - displayedX) * amount, targetX)
        displayedY = settle(displayedY + (targetY - displayedY) * amount, targetY)
    }

    private fun restorePinnedCorner() {
        val corner = pinnedCorner ?: return
        pinnedCorner = null
        targetX = (corner.x - frame.x).toDouble()
        targetY = (corner.y - frame.y).toDouble()
        displayedX = targetX
        displayedY = targetY
    }

    private fun clampMotion() {
        targetX = frame.clampX(targetX)
        targetY = frame.clampY(targetY)
        displayedX = frame.clampX(displayedX)
        displayedY = frame.clampY(displayedY)
    }

    fun roundedX(): Int = Math.round(displayedX).toInt()

    fun roundedY(): Int = Math.round(displayedY).toInt()

    private fun settle(value: Double, target: Double): Double =
        if (abs(target - value) < SETTLE_TOLERANCE) target else value
}

/**
 * [margin] is how much of the tooltip has to stay inside the viewport. A negative value lets the tooltip be pushed
 * entirely past an edge, which is what makes the far side of a tooltip taller than the screen reachable.
 */
internal data class TooltipPanFrame(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val margin: Int = EDGE_GAP,
) {
    private val bounds = PanBounds(
        margin - width - x,
        viewportWidth - margin - x,
        margin - height - y,
        viewportHeight - margin - y,
    )

    fun clampX(value: Double): Double = bounds.clampX(value)

    fun clampY(value: Double): Double = bounds.clampY(value)
}

private data class PanBounds(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int) {
    fun clampX(value: Double): Double = clamp(value, minX, maxX)

    fun clampY(value: Double): Double = clamp(value, minY, maxY)

    private fun clamp(value: Double, minimum: Int, maximum: Int): Double {
        if (minimum > maximum) return 0.0
        return max(minimum.toDouble(), min(value, maximum.toDouble()))
    }
}

internal fun tooltipPanMargin(allowsOffScreen: Boolean): Int = if (allowsOffScreen) -OFF_SCREEN_SLACK else EDGE_GAP

private const val EDGE_GAP = 4

private const val OFF_SCREEN_SLACK = 32

private const val ANCHOR_TOLERANCE = 12
private const val SETTLE_TOLERANCE = 0.05
