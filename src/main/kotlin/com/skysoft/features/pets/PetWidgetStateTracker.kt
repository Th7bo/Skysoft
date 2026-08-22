package com.skysoft.features.pets

import com.skysoft.config.SkysoftConfigGui
import com.skysoft.config.features.pets.display.text.PetTextConfig
import com.skysoft.data.hypixel.HypixelLocationState
import com.skysoft.data.hypixel.TabListApi
import kotlin.time.Duration.Companion.seconds

object PetWidgetStateTracker {
    private val widgetLoadGrace = 3.seconds
    private var state = State.LOADING
    private var tabSessionId = Long.MIN_VALUE

    val isReadyForDisplay: Boolean
        get() = isCurrentWidgetState && (state == State.READY || (state == State.MAXED_WITHOUT_OVERFLOW_XP && !isOverflowXpTextEnabled))

    internal val displayDataSource: PetDisplayDataSource
        get() = petDisplayDataSource(isReadyForDisplay, HypixelLocationState.currentIsland)

    val displayMessage: List<String>?
        get() = when {
            isCurrentWidgetState &&
                state == State.NOT_READY &&
                displayDataSource.shouldWarnAboutMissingWidget &&
                ActivePetTracker.currentPet != null &&
                TabListApi.hasWaitedForSkyBlockData(widgetLoadGrace) -> listOf(
                "§cPet Tab Widget Missing",
                "§cDo /widget and enable the pet widget",
            )

            isCurrentWidgetState && state == State.MAXED_WITHOUT_OVERFLOW_XP && isOverflowXpTextEnabled -> listOf(
                "§cPet Widget Overflow XP Missing",
                "§cEnable overflow XP in the pet widget",
            )

            else -> null
        }

    private val isCurrentWidgetState: Boolean
        get() = TabListApi.isSkyBlockDataLoaded && tabSessionId == TabListApi.sessionId

    /**
     * Whether the equipped pet's overflow XP is actually put on screen. The widget reads MAX instead of a number for a
     * maxed pet with overflow XP turned off, which costs an exact XP read but nothing else, so nagging about it - and
     * replacing the whole display with that nag - is only worth it when the missing number was going to be shown.
     * Exp-share pets are not considered: their overflow XP comes from stored pet data, not this widget line.
     */
    private val isOverflowXpTextEnabled: Boolean
        get() = PetTextConfig.TextElement.OVERFLOW_XP in
            SkysoftConfigGui.config().pets.display.text.equippedPet.enabledTexts.get()

    fun syncLoadingState() {
        if (!isCurrentWidgetState && state != State.LOADING) {
            state = State.LOADING
        }
    }

    fun reset() {
        state = State.LOADING
        tabSessionId = Long.MIN_VALUE
    }

    fun setReady() {
        update(State.READY)
    }

    fun setNotReady() {
        update(State.NOT_READY)
    }

    fun setMaxedWithoutOverflowXp() {
        update(State.MAXED_WITHOUT_OVERFLOW_XP)
    }

    private fun update(newState: State) {
        state = newState
        tabSessionId = TabListApi.sessionId
    }

    private enum class State {
        LOADING,
        NOT_READY,
        READY,
        MAXED_WITHOUT_OVERFLOW_XP,
    }
}
