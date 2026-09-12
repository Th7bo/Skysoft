package com.skysoft.gui.scale

import com.skysoft.gui.tooltip.TooltipViewport
import com.skysoft.utils.MinecraftClient
import com.skysoft.utils.SkysoftErrorBoundary
import com.skysoft.utils.renderables.withIsolatedPose
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.function.BiConsumer

internal object TooltipScaleRenderer {
    @JvmStatic
    fun render(context: GuiGraphicsExtractor, x: Int, y: Int, render: BiConsumer<Int, Int>) {
        val position = TooltipPosition(x, y)
        TooltipViewport.useRenderZoom().use { zoomScope ->
            SkysoftErrorBoundary.aroundUnit(
                "Tooltip GUI scale rendering",
                position,
                { point -> render.accept(point.x, point.y) },
            ) { renderTooltip ->
                renderAtScale(context, position, zoomScope.zoom, renderTooltip)
            }
        }
    }

    private fun renderAtScale(
        context: GuiGraphicsExtractor,
        position: TooltipPosition,
        zoom: Double,
        render: (TooltipPosition) -> Unit,
    ) {
        val minecraft = Minecraft.getInstance()
        val screen = MinecraftClient.screen(minecraft)
        val window = minecraft.window
        val usesTooltipScale = GuiScaleController.usesSeparateTooltipScale(screen)
        val activeScale = window.guiScale.coerceAtLeast(1)
        val tooltipScale =
            if (usesTooltipScale) GuiScaleController.resolve(screen, window).tooltip() else activeScale
        if (tooltipScale == activeScale && zoom == 1.0) {
            render(position)
            return
        }
        val scaledPosition = TooltipPosition(
            GuiScaleController.convertCoordinate(position.x, activeScale, tooltipScale),
            GuiScaleController.convertCoordinate(position.y, activeScale, tooltipScale),
        )
        val poseScale = (tooltipScale / activeScale.toDouble() * zoom).toFloat()
        val scaleOverride = if (usesTooltipScale) GuiScaleController.useTooltipScale(screen, window) else null
        context.withIsolatedPose {
            try {
                context.pose().scale(poseScale, poseScale)
                render(scaledPosition)
            } finally {
                scaleOverride?.close()
            }
        }
    }

    private data class TooltipPosition(val x: Int, val y: Int)
}
