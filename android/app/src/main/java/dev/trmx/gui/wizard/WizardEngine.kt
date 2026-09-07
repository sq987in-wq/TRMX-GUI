package dev.trmx.gui.wizard

/*
 * First-run wizard state machine — pure reducer, no Android/coroutine
 * dependencies, exhaustively unit-tested. The ViewModel maps async
 * outcomes (intent sends, polls, timeouts) onto WizardEvents.
 *
 * Sequence (CONTROL-PLANE.md §4): the three consents happen in the UI
 * before Begin; then INSTALL_PY → INSTALL → PAIR → START → HANDSHAKE.
 */

import dev.trmx.gui.control.ControlOps

enum class WizardStep {
    WELCOME,          // consents + base URL
    INSTALL_PY,       // pkg install -y python
    INSTALL,          // install.sh (SHA256-verified)
    PAIR,             // trmx pair <token>
    START,            // trmx start
    HANDSHAKE,        // poll /v1/system/info
    DONE,
}

data class WizardState(
    val step: WizardStep = WizardStep.WELCOME,
    val baseUrl: String = ControlOps.DEFAULT_BASE,
    val token: String = "",
    val consentTermux: Boolean = false,
    val consentRunCommand: Boolean = false,
    val consentAllowExternal: Boolean = false,
    val busy: Boolean = false,
    val detail: String = "",
    val failure: String? = null,
    /** True while a fixed wait can be skipped by the user (INSTALL_PY). */
    val skippable: Boolean = false,
) {
    val allConsentsGiven: Boolean
        get() = consentTermux && consentRunCommand && consentAllowExternal
}

sealed interface WizardEvent {
    data class ConsentTermux(val given: Boolean) : WizardEvent
    data class ConsentRunCommand(val given: Boolean) : WizardEvent
    data class ConsentAllowExternal(val given: Boolean) : WizardEvent
    data class BaseUrlChanged(val url: String) : WizardEvent
    data class Begin(val token: String) : WizardEvent

    data class StepStarted(val detail: String = "") : WizardEvent
    data class StepSucceeded(val next: WizardStep) : WizardEvent
    data class StepFailed(val reason: String) : WizardEvent
    object SkipWait : WizardEvent
    object Retry : WizardEvent
    object Reset : WizardEvent
}

class WizardEngine {

    fun reduce(state: WizardState, event: WizardEvent): WizardState = when (event) {
        is WizardEvent.ConsentTermux ->
            state.copy(consentTermux = event.given, failure = null)
        is WizardEvent.ConsentRunCommand ->
            state.copy(consentRunCommand = event.given, failure = null)
        is WizardEvent.ConsentAllowExternal ->
            state.copy(consentAllowExternal = event.given, failure = null)
        is WizardEvent.BaseUrlChanged ->
            state.copy(baseUrl = event.url.trim())
        is WizardEvent.Begin -> {
            if (!state.allConsentsGiven) {
                state.copy(failure = "All three consents are required before continuing.")
            } else if (event.token.length < 20) {
                state.copy(failure = "Internal error: pairing token too short.")
            } else {
                WizardState(
                    step = WizardStep.INSTALL_PY,
                    baseUrl = state.baseUrl,
                    token = event.token,
                    busy = true,
                    skippable = true,
                    detail = "asking Termux to install Python…")
            }
        }

        is WizardEvent.StepStarted ->
            state.copy(busy = true, failure = null, detail = event.detail)
        is WizardEvent.StepSucceeded -> {
            val next = event.next
            val detail = when (next) {
                WizardStep.WELCOME -> ""
                WizardStep.INSTALL_PY -> "asking Termux to install Python…"
                WizardStep.INSTALL -> "downloading + verifying the bridge…"
                WizardStep.PAIR -> "pairing with the bridge…"
                WizardStep.START -> "starting the bridge…"
                WizardStep.HANDSHAKE -> "waiting for the bridge to answer…"
                WizardStep.DONE -> "connected"
            }
            state.copy(step = next, busy = next != WizardStep.DONE,
                       skippable = false,   // only the initial INSTALL_PY wait is skippable
                       detail = detail, failure = null)
        }
        is WizardEvent.StepFailed ->
            state.copy(busy = false, skippable = false, failure = event.reason)
        WizardEvent.SkipWait ->
            state.copy(busy = false, skippable = false, detail = "skipped (already installed)")
        WizardEvent.Retry ->
            state.copy(busy = true, skippable = false, failure = null,
                       detail = "retrying ${state.step}…")
        WizardEvent.Reset ->
            WizardState(baseUrl = state.baseUrl)
    }
}
