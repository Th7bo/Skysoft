package com.skysoft.gui.tooltip

import com.skysoft.config.SkysoftConfigGui
import com.skysoft.config.TooltipScrollConfig
import com.skysoft.mixin.ClientTextTooltipAccessor
import com.skysoft.utils.MinecraftClient
import com.skysoft.utils.gui.Rect
import com.skysoft.utils.input.InputUtilities
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner
import net.minecraft.util.FormattedCharSequence
import org.joml.Vector2i
import org.joml.Vector2ic
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

interface TooltipScrollExcludedScreen

interface TooltipScrollPriorityScreen {
    val mouseScrollPriorityAreas: List<Rect>
}

object TooltipViewport {
    private val minecraft = Minecraft.getInstance()
    private var session: PanSession? = null
    private var wasResetKeyPressedLastTick = false

    /** Zoom the tooltip currently being drawn is scaled by; only meaningful inside a [useRenderZoom] scope. */
    var renderZoom = 1.0
        private set

    @JvmStatic
    fun decorate(
        font: Font,
        components: List<ClientTooltipComponent>,
        anchorX: Int,
        anchorY: Int,
        original: ClientTooltipPositioner,
    ): ClientTooltipPositioner {
        val settings = config()
        if (
            original is TooltipViewportExcludedPositioner ||
            !settings.enabled ||
            !isEnabledForCurrentScreen(settings) ||
            components.isEmpty()
        ) return original
        return OffsetPositioner(original, tooltipIdentity(font, components), anchorX, anchorY)
    }

    /**
     * Pins the zoom for one tooltip render. Holding it fixed for the whole render is what lets [place] measure the
     * tooltip at exactly the size it ends up on screen, instead of a size the wheel may have changed part-way through.
     */
    @JvmStatic
    fun useRenderZoom(): RenderZoomScope {
        val settings = config()
        renderZoom = if (settings.enabled && isEnabledForCurrentScreen(settings)) currentSession()?.zoom ?: 1.0 else 1.0
        return RenderZoomScope(renderZoom)
    }

    @JvmStatic
    fun didHandleMouseScroll(horizontal: Double, vertical: Double): Boolean =
        didHandleMouseScroll(horizontal, vertical, GLFW.GLFW_KEY_UNKNOWN)

    @JvmStatic
    fun didHandleCompetingMouseScroll(horizontal: Double, vertical: Double): Boolean =
        didHandleMouseScroll(horizontal, vertical, config().settings.interfaceScrollTooltipKey)

    @JvmStatic
    fun isCompetingScrollKeyDown(): Boolean =
        isKeyDown(config().settings.interfaceScrollTooltipKey)

    @JvmStatic
    fun updateKeyboardPan() {
        val settings = config()
        if (!settings.enabled || !isEnabledForCurrentScreen(settings)) {
            clear()
            return
        }
        if (!hasVisibleSession()) {
            wasResetKeyPressedLastTick = false
            if (settings.details.resetPositionWhenNotHovered) session?.reset()
            return
        }

        val activeSession = checkNotNull(session)
        val isResetPressed = isKeyDown(settings.settings.resetTooltipKey)
        if (isResetPressed && !wasResetKeyPressedLastTick) activeSession.reset()
        wasResetKeyPressedLastTick = isResetPressed

        val speed = settings.settings.keyboardScrollingSpeed
        var x = 0.0
        var y = 0.0
        if (settings.settings.enableWASD) {
            if (isKeyDown(GLFW.GLFW_KEY_A)) x -= speed
            if (isKeyDown(GLFW.GLFW_KEY_D)) x += speed
            if (isKeyDown(GLFW.GLFW_KEY_W)) y -= speed
            if (isKeyDown(GLFW.GLFW_KEY_S)) y += speed
        }

        val isHorizontal = isHorizontalModifierDown(settings)
        if (isKeyDown(settings.settings.moveUpKey)) {
            if (isHorizontal) x -= speed else y -= speed
        }
        if (isKeyDown(settings.settings.moveDownKey)) {
            if (isHorizontal) x += speed else y += speed
        }
        if (x != 0.0 || y != 0.0) activeSession.panBy(x, y)
    }

    fun needsKeyboardUpdate(): Boolean = config().enabled || session != null

    @JvmStatic
    fun clear() {
        session = null
        wasResetKeyPressedLastTick = false
        renderZoom = 1.0
    }

    private fun didHandleMouseScroll(horizontal: Double, vertical: Double, ignoredHorizontalKey: Int): Boolean {
        val settings = config()
        if (!settings.enabled || !isEnabledForCurrentScreen(settings) || !hasVisibleSession()) return false
        if (didHandleMouseZoom(settings, vertical)) return true
        if (!settings.settings.enableScrollWheel) return false

        val pansHorizontally = horizontal != 0.0 || isHorizontalModifierDown(settings, ignoredHorizontalKey)
        var x = horizontal * settings.settings.mouseScrollingSpeed
        var y = 0.0
        if (vertical != 0.0) {
            if (pansHorizontally) x += vertical * settings.settings.mouseScrollingSpeed
            else y = vertical * settings.settings.mouseScrollingSpeed
        }
        if (settings.details.invertHorizontalMovement) x = -x
        if (settings.details.invertVerticalMovement) y = -y
        if (x == 0.0 && y == 0.0) return false

        checkNotNull(session).panBy(x, y)
        return true
    }

    private fun didHandleMouseZoom(settings: TooltipScrollConfig, vertical: Double): Boolean {
        if (vertical == 0.0 || !settings.settings.enableZoom || !isKeyDown(settings.settings.zoomKey)) return false
        val minimum = settings.details.minimumZoom / PERCENT_SCALE
        val maximum = max(minimum, settings.details.maximumZoom / PERCENT_SCALE)
        checkNotNull(session).zoomBy(vertical, settings.settings.zoomSpeed / PERCENT_SCALE, minimum, maximum)
        return true
    }

    private fun place(
        original: ClientTooltipPositioner,
        identity: Int,
        anchorX: Int,
        anchorY: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        x: Int,
        y: Int,
        tooltipWidth: Int,
        tooltipHeight: Int,
    ): Vector2ic {
        val zoom = renderZoom
        val zoomedWidth = zoomed(tooltipWidth, zoom)
        val zoomedHeight = zoomed(tooltipHeight, zoom)
        val base = original.positionTooltip(viewportWidth, viewportHeight, x, y, zoomedWidth, zoomedHeight)
        val frame = TooltipFrame(base.x(), base.y(), zoomedWidth, zoomedHeight, viewportWidth, viewportHeight)
        val now = System.nanoTime()
        val isExpired = !hasVisibleSession(now)
        val activeSession = currentSession()

        if (activeSession == null) {
            session = PanSession(MinecraftClient.screen(minecraft), identity, anchorX, anchorY, frame, now)
        } else {
            val hasChangedTarget = activeSession.isDifferentTarget(identity, anchorX, anchorY)
            activeSession.observe(identity, anchorX, anchorY, frame, now)
            if (config().details.resetPositionWhenNotHovered && (isExpired || hasChangedTarget)) {
                activeSession.reset()
                activeSession.alignTallTooltipToTop()
            }
        }

        val placedSession = checkNotNull(session)
        placedSession.advance(config().details.scrollSmoothness / PERCENT_SCALE)
        return Vector2i(
            unzoomed(base.x() + placedSession.roundedX(), zoom),
            unzoomed(base.y() + placedSession.roundedY(), zoom),
        )
    }

    private fun currentSession(): PanSession? = session?.takeIf { it.screen === MinecraftClient.screen(minecraft) }

    private fun hasVisibleSession(): Boolean = hasVisibleSession(System.nanoTime())

    private fun hasVisibleSession(now: Long): Boolean {
        val activeSession = currentSession() ?: return false
        return now - activeSession.lastObservedNanos <= VISIBILITY_GRACE_NANOS
    }

    private fun isEnabledForCurrentScreen(settings: TooltipScrollConfig): Boolean =
        isTooltipScrollEnabledForScreen(MinecraftClient.screen(minecraft), settings.settings.isEnabledInChat)

    private fun isHorizontalModifierDown(
        settings: TooltipScrollConfig,
        ignoredKey: Int = GLFW.GLFW_KEY_UNKNOWN,
    ): Boolean {
        val usesLeftShift = settings.details.useLeftShift && ignoredKey != GLFW.GLFW_KEY_LEFT_SHIFT &&
            isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT)
        val usesConfiguredKey = settings.settings.horizontalMovementKey != ignoredKey &&
            isKeyDown(settings.settings.horizontalMovementKey)
        return usesLeftShift || usesConfiguredKey
    }

    private fun isKeyDown(key: Int): Boolean = InputUtilities.isBindingDown(key)

    /**
     * Identifies the hovered tooltip well enough to notice that a different one took its place. Text styles are left
     * out on purpose: chroma and other animated colors change every frame, and treating that as a new tooltip would
     * reset the panned position before the wheel could ever move it.
     */
    private fun tooltipIdentity(font: Font, components: List<ClientTooltipComponent>): Int {
        var result = 1
        for (component in components) {
            result = HASH_MULTIPLIER * result + component.javaClass.hashCode()
            result = HASH_MULTIPLIER * result + component.getWidth(font)
            result = HASH_MULTIPLIER * result + component.getHeight(font)
            if (component is ClientTextTooltipAccessor) {
                result = HASH_MULTIPLIER * result + textIdentity(component.skysoftGetText())
            }
        }
        return result
    }

    private fun textIdentity(text: FormattedCharSequence): Int {
        var result = 1
        text.accept { _, _, codePoint ->
            result = HASH_MULTIPLIER * result + codePoint
            true
        }
        return result
    }

    private fun config(): TooltipScrollConfig = SkysoftConfigGui.config().inventory.tooltipScroll

    private class PanSession(
        val screen: Screen?,
        identity: Int,
        anchorX: Int,
        anchorY: Int,
        frame: TooltipFrame,
        observedAt: Long,
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
            alignTallTooltipToTop()
        }

        fun isDifferentTarget(nextIdentity: Int, nextAnchorX: Int, nextAnchorY: Int): Boolean =
            identity != nextIdentity || abs(anchorX - nextAnchorX) > ANCHOR_TOLERANCE ||
                abs(anchorY - nextAnchorY) > ANCHOR_TOLERANCE

        fun observe(
            nextIdentity: Int,
            nextAnchorX: Int,
            nextAnchorY: Int,
            nextFrame: TooltipFrame,
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
            targetX = 0.0
            targetY = 0.0
            displayedX = 0.0
            displayedY = 0.0
            zoom = 1.0
            pinnedCorner = null
        }

        fun alignTallTooltipToTop() {
            if (
                !config().details.startOnTop ||
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
            val bounds = frame.bounds(edgeMargin(config().details.allowOffScreen))
            targetX = bounds.clampX(targetX)
            targetY = bounds.clampY(targetY)
            displayedX = bounds.clampX(displayedX)
            displayedY = bounds.clampY(displayedY)
        }

        fun roundedX(): Int = Math.round(displayedX).toInt()

        fun roundedY(): Int = Math.round(displayedY).toInt()

        private fun settle(value: Double, target: Double): Double =
            if (abs(target - value) < SETTLE_TOLERANCE) target else value
    }

    private data class TooltipFrame(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val viewportWidth: Int,
        val viewportHeight: Int,
    ) {
        /**
         * Movement limits expressed as how much of the tooltip has to stay inside the viewport. A negative [margin]
         * lets the tooltip be pushed entirely past an edge, which is what makes the far side of a tooltip that is
         * taller than the screen reachable.
         */
        fun bounds(margin: Int) = PanBounds(
            margin - width - x,
            viewportWidth - margin - x,
            margin - height - y,
            viewportHeight - margin - y,
        )
    }

    private data class PanBounds(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int) {
        fun clampX(value: Double): Double = clamp(value, minX, maxX)

        fun clampY(value: Double): Double = clamp(value, minY, maxY)

        private fun clamp(value: Double, minimum: Int, maximum: Int): Double {
            if (minimum > maximum) return 0.0
            return max(minimum.toDouble(), min(value, maximum.toDouble()))
        }
    }

    private data class OffsetPositioner(
        val original: ClientTooltipPositioner,
        val identity: Int,
        val anchorX: Int,
        val anchorY: Int,
    ) : ClientTooltipPositioner {
        override fun positionTooltip(
            screenWidth: Int,
            screenHeight: Int,
            x: Int,
            y: Int,
            tooltipWidth: Int,
            tooltipHeight: Int,
        ): Vector2ic = place(
            original,
            identity,
            anchorX,
            anchorY,
            screenWidth,
            screenHeight,
            x,
            y,
            tooltipWidth,
            tooltipHeight,
        )
    }

    class RenderZoomScope internal constructor(val zoom: Double) : AutoCloseable {
        override fun close() {
            renderZoom = 1.0
        }
    }

    private const val VISIBILITY_GRACE_NANOS = 250_000_000L
    private const val ANCHOR_TOLERANCE = 12
    private const val HASH_MULTIPLIER = 31
    private const val PERCENT_SCALE = 100.0
    private const val SETTLE_TOLERANCE = 0.05
}

private fun isTooltipScrollEnabledForScreen(screen: Screen?, isEnabledInChat: Boolean): Boolean =
    screen !is TooltipScrollExcludedScreen && (screen !is ChatScreen || isEnabledInChat)

private fun zoomed(length: Int, zoom: Double): Int =
    if (zoom == 1.0) length else ceil(length * zoom).toInt()

private fun unzoomed(coordinate: Int, zoom: Double): Int =
    if (zoom == 1.0) coordinate else Math.round(coordinate / zoom).toInt()

private fun edgeMargin(allowsOffScreen: Boolean): Int = if (allowsOffScreen) -OFF_SCREEN_SLACK else EDGE_GAP

private const val EDGE_GAP = 4

private const val OFF_SCREEN_SLACK = 32
