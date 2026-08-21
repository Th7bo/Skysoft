package com.skysoft.features.inventory

import com.skysoft.config.InventoryButtonConfig
import com.skysoft.config.NO_INVENTORY_BUTTON_GROUP

internal object InventoryButtonGroups {
    private val expandedGroups = mutableSetOf<Int>()

    fun isExpanded(group: Int): Boolean = group != NO_INVENTORY_BUTTON_GROUP && group in expandedGroups

    fun isVisible(button: InventoryButtonConfig): Boolean = !button.isGrouped() || isExpanded(button.group)

    fun toggle(button: InventoryButtonConfig, buttons: List<InventoryButtonConfig>) {
        val group = button.toggleGroup
        if (group == NO_INVENTORY_BUTTON_GROUP) return
        if (expandedGroups.add(group)) return
        collapse(group, buttons)
    }

    fun collapseAll() {
        expandedGroups.clear()
    }

    fun toggleDescription(button: InventoryButtonConfig): String {
        val action = if (isExpanded(button.toggleGroup)) "Hide" else "Show"
        return "$action group ${button.toggleGroup}"
    }

    fun groupLabel(group: Int): String =
        if (group == NO_INVENTORY_BUTTON_GROUP) "None" else group.toString()

    private fun collapse(group: Int, buttons: List<InventoryButtonConfig>) {
        expandedGroups.remove(group)
        buttons.asSequence()
            .filter { it.group == group && isExpanded(it.toggleGroup) }
            .map { it.toggleGroup }
            .toList()
            .forEach { collapse(it, buttons) }
    }
}
