package dev.trmx.gui

/*
 * AppViewModel — orchestrates the control plane (intents) and the data
 * plane (BridgeClient) and exposes UI state. Kept deliberately thin: all
 * decision logic lives in WizardEngine (pure) and Handshaker (pure).
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.trmx.gui.control.ControlOps
import dev.trmx.gui.control.ControlPlaneException
import dev.trmx.gui.control.IntentControlPlane
import dev.trmx.gui.model.JobSummary
import dev.trmx.gui.model.SystemInfo
import dev.trmx.gui.net.BridgeClient
import dev.trmx.gui.net.BridgeResult
import dev.trmx.gui.net.Handshaker
import dev.trmx.gui.store.TokenStore
import dev.trmx.gui.wizard.WizardEngine
import dev.trmx.gui.wizard.WizardEvent
import dev.trmx.gui.wizard.WizardState
import dev.trmx.gui.wizard.WizardStep
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

data class DashboardState(
    val info: SystemInfo? = null,
    val jobs: List<JobSummary> = emptyList(),
    val refreshing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = TokenStore(app)
    private val controlPlane = IntentControlPlane(app)
    private val wizardEngine = WizardEngine()
    private val http = OkHttpClient()

    private val _wizard = MutableStateFlow(WizardState())
    val wizard = _wizard.asStateFlow()

    private val _dashboard = MutableStateFlow(DashboardState())
    val dashboard = _dashboard.asStateFlow()

    /** True when a previous successful wizard run is stored. */
    val everPaired: Boolean get() = store.isPaired

    fun isTermuxInstalled(): Boolean = controlPlane.isTermuxInstalled()

    private var sequenceJob: Job? = null
    private val skipSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        if (store.isPaired) warmStart()
    }

    // ---- wizard ---------------------------------------------------------

    fun consentTermux(given: Boolean) = applyEvent(WizardEvent.ConsentTermux(given))
    fun consentRunCommand(given: Boolean) = applyEvent(WizardEvent.ConsentRunCommand(given))
    fun consentAllowExternal(given: Boolean) =
        applyEvent(WizardEvent.ConsentAllowExternal(given))
    fun baseUrlChanged(url: String) = applyEvent(WizardEvent.BaseUrlChanged(url))

    fun newToken(): String =
        store.generateToken().also { store.token = it }

    fun beginWizard() {
        val token = if (store.isPaired) store.token else newToken()
        store.installBase = _wizard.value.baseUrl
        applyEvent(WizardEvent.Begin(token))
        sequenceJob?.cancel()
        skipSignal.tryReceive()
        sequenceJob = viewModelScope.launch { firstRunSequence() }
    }

    fun skipWait() {
        skipSignal.trySend(Unit)
    }

    fun retry() {
        applyEvent(WizardEvent.Retry)
        sequenceJob?.cancel()
        skipSignal.tryReceive()
        sequenceJob = viewModelScope.launch { firstRunSequence(fromStep = _wizard.value.step) }
    }

    fun resetWizard() {
        sequenceJob?.cancel()
        skipSignal.tryReceive()
        applyEvent(WizardEvent.Reset)
        _dashboard.value = DashboardState()
    }

    /**
     * CONTROL-PLANE.md §4 first-run sequence. Each op's acceptance is
     * observed on the data plane, never assumed from intent delivery.
     */
    private suspend fun firstRunSequence(fromStep: WizardStep = WizardStep.INSTALL_PY) {
        val s = _wizard.value
        var step = fromStep
        if (step == WizardStep.WELCOME) step = WizardStep.INSTALL_PY
        if (step.ordinal >= WizardStep.INSTALL_PY.ordinal &&
            step.ordinal < WizardStep.INSTALL.ordinal
        ) {
            if (!sendOp(ControlOps.spec("INSTALL_PY")!!)) return
            withTimeoutOrNull(INSTALL_PY_WAIT_MS) { skipSignal.receive() }
            applyEvent(WizardEvent.StepSucceeded(WizardStep.INSTALL))
        }
        if (step.ordinal < WizardStep.PAIR.ordinal) {
            if (!sendOp(ControlOps.spec("INSTALL")!!)) return
            delay(INSTALL_WAIT_MS)
            applyEvent(WizardEvent.StepSucceeded(WizardStep.PAIR))
        }
        if (step.ordinal < WizardStep.START.ordinal) {
            if (!sendOp(ControlOps.spec("PAIR")!!)) return
            delay(PAIR_WAIT_MS)
            applyEvent(WizardEvent.StepSucceeded(WizardStep.START))
        }
        if (step.ordinal < WizardStep.HANDSHAKE.ordinal) {
            if (!sendOp(ControlOps.spec("START")!!)) return
            applyEvent(WizardEvent.StepSucceeded(WizardStep.HANDSHAKE))
        }
        awaitHandshakeOrFail()
    }

    /** CONTROL-PLANE.md §4 warm start: probe, START if down, probe again. */
    private fun warmStart() {
        applyEvent(WizardEvent.StepStarted("reconnecting to the bridge…"))
        _wizard.update { it.copy(step = WizardStep.HANDSHAKE) }
        sequenceJob = viewModelScope.launch {
            when (val r = probe()) {
                is BridgeResult.Success ->
                    applyEvent(WizardEvent.StepSucceeded(WizardStep.DONE))
                is BridgeResult.HttpError -> failHandshake(r)
                is BridgeResult.NetworkError -> {
                    if (!sendOp(ControlOps.spec("START")!!)) return@launch
                    awaitHandshakeOrFail()
                }
            }
        }
    }

    private suspend fun awaitHandshakeOrFail() {
        when (val r = handshaker().awaitHandshake()) {
            is BridgeResult.Success -> {
                applyEvent(WizardEvent.StepSucceeded(WizardStep.DONE))
                refresh()
            }
            is BridgeResult.HttpError -> failHandshake(r)
            is BridgeResult.NetworkError ->
                applyEvent(
                    WizardEvent.StepFailed(
                        "The bridge did not answer. Most likely causes:\n" +
                            "• allow-external-apps is not set in ~/.termux/termux.properties\n" +
                            "• the install failed — open Termux and run ~/.trmx/trmx status\n" +
                            "• Termux was killed by Android (phantom process limits)"))
        }
    }

    private fun failHandshake(r: BridgeResult.HttpError) {
        val hint = if (r.status == 401) {
            "The bridge rejected our token (HTTP 401). Re-run pairing from the wizard."
        } else {
            "The bridge answered with HTTP ${r.status} (${r.code})."
        }
        applyEvent(WizardEvent.StepFailed(hint))
    }

    /** Sends one op; on send failure applies StepFailed and returns false. */
    private fun sendOp(spec: ControlOps.OpSpec): Boolean {
        val s = _wizard.value
        val res = controlPlane.send(
            spec,
            baseUrl = if (spec.id == "INSTALL") s.baseUrl else null,
            token = if (spec.id == "PAIR") s.token else null)
        return res.fold(
            onSuccess = { true },
            onFailure = { e ->
                val reason = if (e is ControlPlaneException) {
                    when (e.error) {
                        ControlPlaneException.SendError.PERMISSION_REQUIRED ->
                            "RUN_COMMAND permission is not granted to TRMX. " +
                                "Open App info → Permissions and grant it."
                        ControlPlaneException.SendError.TERMUX_NOT_RUNNING ->
                            "Termux did not accept the command. Open Termux once, then retry."
                    }
                } else {
                    "Could not send ${spec.id}: ${e.message}"
                }
                applyEvent(WizardEvent.StepFailed(reason))
                false
            })
    }

    private fun applyEvent(e: WizardEvent) = _wizard.update { wizardEngine.reduce(it, e) }

    // ---- dashboard ------------------------------------------------------

    fun refresh() {
        _dashboard.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            val client = BridgeClient(store.bridgeBaseUrl, store.token, http)
            when (val info = client.systemInfo()) {
                is BridgeResult.Success -> _dashboard.update { it.copy(info = info.data) }
                is BridgeResult.HttpError -> {
                    _dashboard.update { it.copy(error = "HTTP ${info.status}: ${info.code}") }
                    return@launch
                }
                is BridgeResult.NetworkError -> {
                    _dashboard.update { it.copy(error = "bridge unreachable: ${info.cause.message}") }
                    return@launch
                }
            }
            when (val jobs = client.listJobs()) {
                is BridgeResult.Success ->
                    _dashboard.update { it.copy(jobs = jobs.data.jobs, refreshing = false) }
                is BridgeResult.HttpError ->
                    _dashboard.update { it.copy(error = "HTTP ${jobs.status}: ${jobs.code}", refreshing = false) }
                is BridgeResult.NetworkError ->
                    _dashboard.update { it.copy(error = "jobs unreachable: ${jobs.cause.message}", refreshing = false) }
            }
        }
    }

    fun stopBridge() {
        controlPlane.send(ControlOps.spec("STOP")!!)
        _dashboard.update { it.copy(notice = "stop requested — the bridge is shutting down") }
        viewModelScope.launch {
            delay(2_000)
            refresh()
        }
    }

    private suspend fun probe(): BridgeResult<SystemInfo> =
        BridgeClient(store.bridgeBaseUrl, store.token, http).systemInfo()

    private fun handshaker() =
        Handshaker(probe = { probe() }, pollIntervalMs = 500, maxAttempts = 60)

    companion object {
        private const val INSTALL_PY_WAIT_MS = 40_000L
        private const val INSTALL_WAIT_MS = 20_000L
        private const val PAIR_WAIT_MS = 3_000L
    }
}
