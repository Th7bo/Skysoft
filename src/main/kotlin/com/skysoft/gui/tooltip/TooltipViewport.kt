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
import kotlin.math.ceil
import kotlin.math.max

interface TooltipScrollExcludedScreen

interface TooltipScrollPriorityScreen {
    val mouseScrollPriorityAreas: List<Rect>
}

object TooltipViewport {
    private val minecraft = Minecraft.getInstance()
    private var session: TooltipPanSession? = null
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
        val frame = TooltipPanFrame(
            x = base.x(),
            y = base.y(),
            width = zoomedWidth,
            height = zoomedHeight,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            margin = tooltipPanMargin(config().details.allowOffScreen),
        )
        val now = System.nanoTime()
        val isExpired = !hasVisibleSession(now)
        val activeSession = currentSession()

        if (activeSession == null) {
            session = TooltipPanSession(
                screen = MinecraftClient.screen(minecraft),
                identity = identity,
                anchorX = anchorX,
                anchorY = anchorY,
                frame = frame,
                observedAt = now,
                startOnTop = config().details.startOnTop,
            )
        } else {
            val hasChangedTarget = activeSession.isDifferentTarget(identity, anchorX, anchorY)
            activeSession.observe(identity, anchorX, anchorY, frame, now)
            if (config().details.resetPositionWhenNotHovered && (isExpired || hasChangedTarget)) {
                activeSession.reset()
                activeSession.alignTallTooltipToTop(config().details.startOnTop)
            }
        }

        val placedSession = checkNotNull(session)
        placedSession.advance(config().details.scrollSmoothness / PERCENT_SCALE)
        return Vector2i(
            unzoomed(base.x() + placedSession.roundedX(), zoom),
            unzoomed(base.y() + placedSession.roundedY(), zoom),
        )
    }

    private fun currentSession(): TooltipPanSession? = session?.takeIf { it.screen === MinecraftClient.screen(minecraft) }

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
    private const val HASH_MULTIPLIER = 31
    private const val PERCENT_SCALE = 100.0
}

private fun isTooltipScrollEnabledForScreen(screen: Screen?, isEnabledInChat: Boolean): Boolean =
    screen !is TooltipScrollExcludedScreen && (screen !is ChatScreen || isEnabledInChat)

private fun zoomed(length: Int, zoom: Double): Int =
    if (zoom == 1.0) length else ceil(length * zoom).toInt()

private fun unzoomed(coordinate: Int, zoom: Double): Int =
    if (zoom == 1.0) coordinate else Math.round(coordinate / zoom).toInt()
