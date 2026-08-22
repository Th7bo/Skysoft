package com.skysoft.gui.tooltip

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner
import net.minecraft.resources.Identifier
import net.minecraft.util.FormattedCharSequence
import org.joml.Vector2i
import org.joml.Vector2ic

internal interface TooltipViewportExcludedPositioner

object AdjacentTooltipRenderer {
    private const val TOOLTIP_MARGIN = 12
    private const val TOOLTIP_GAP = 2
    private const val SCREEN_EDGE = 4

    private var pendingTooltip: PendingTooltip? = null

    fun clear() {
        pendingTooltip = null
    }

    fun prepare(
        context: GuiGraphicsExtractor,
        lines: List<FormattedCharSequence>,
        style: Identifier? = null,
    ) {
        if (lines.isEmpty()) return
        pendingTooltip = PendingTooltip(
            context = context,
            components = lines.map(ClientTooltipComponent::create),
            style = style,
        )
    }

    fun captureMainFrame(
        context: GuiGraphicsExtractor,
        position: Vector2ic,
        width: Int,
    ) {
        val pending = pendingTooltip ?: return
        if (pending.context !== context || pending.mainFrame != null) return
        pending.mainFrame = TooltipFrame(position.x(), position.y(), width)
    }

    fun renderPending(context: GuiGraphicsExtractor, font: Font) {
        val pending = pendingTooltip ?: return
        pendingTooltip = null
        if (pending.context !== context) return
        val mainFrame = pending.mainFrame ?: return
        context.nextStratum()
        context.tooltip(
            font,
            pending.components,
            0,
            0,
            AdjacentPositioner(mainFrame),
            pending.style,
        )
    }

    private data class PendingTooltip(
        val context: GuiGraphicsExtractor,
        val components: List<ClientTooltipComponent>,
        val style: Identifier?,
        var mainFrame: TooltipFrame? = null,
    )

    private data class TooltipFrame(
        val x: Int,
        val y: Int,
        val width: Int,
    )

    private class AdjacentPositioner(
        private val mainFrame: TooltipFrame,
    ) : ClientTooltipPositioner, TooltipViewportExcludedPositioner {
        override fun positionTooltip(
            screenWidth: Int,
            screenHeight: Int,
            x: Int,
            y: Int,
            tooltipWidth: Int,
            tooltipHeight: Int,
        ): Vector2ic {
            // The main frame is measured in the zoomed tooltip's own coordinates, so the viewport has to be read in
            // those same coordinates rather than in the screen units the caller hands over.
            val zoom = TooltipViewport.renderZoom
            val viewportWidth = (screenWidth / zoom).toInt()
            val viewportHeight = (screenHeight / zoom).toInt()
            val framedGap = TOOLTIP_MARGIN * 2 + TOOLTIP_GAP
            val right = mainFrame.x + mainFrame.width + framedGap
            val left = mainFrame.x - tooltipWidth - framedGap
            val minimumX = TOOLTIP_MARGIN + SCREEN_EDGE
            val maximumX = (viewportWidth - tooltipWidth - TOOLTIP_MARGIN - SCREEN_EDGE).coerceAtLeast(minimumX)
            val tooltipX = when {
                right + tooltipWidth + TOOLTIP_MARGIN <= viewportWidth - SCREEN_EDGE -> right
                left - TOOLTIP_MARGIN >= SCREEN_EDGE -> left
                viewportWidth - mainFrame.x - mainFrame.width >= mainFrame.x -> right.coerceIn(minimumX, maximumX)
                else -> left.coerceIn(minimumX, maximumX)
            }
            val maximumY = (viewportHeight - tooltipHeight - SCREEN_EDGE).coerceAtLeast(SCREEN_EDGE)
            return Vector2i(tooltipX, mainFrame.y.coerceIn(SCREEN_EDGE, maximumY))
        }
    }
}
