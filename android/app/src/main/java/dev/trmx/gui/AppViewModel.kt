package dev.trmx.gui

/*
 * AppViewModel — orchestrates the control plane (intents) and the data
 * plane (BridgeClient) and exposes UI state. Kept deliberately thin: all
 * decision logic lives in WizardEngine (pure), Handshaker (pure) and
 * SubmitValidator (pure).
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.trmx.gui.control.ControlOps
import dev.trmx.gui.control.ControlPlaneException
import dev.trmx.gui.control.IntentControlPlane
import dev.trmx.gui.job.SubmitValidator
import dev.trmx.gui.model.JobSummary
import dev.trmx.gui.model.SubmitRequest
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.UUID

data class DashboardState(
    val info: SystemInfo? = null,
    val jobs: List<JobSummary> = emptyList(),
    val refreshing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

data class SubmitFormState(
    val name: String = "",
    val argvText: String = "",
    val cwd: String = "",
    val timeoutText: String = "",
    val submitting: Boolean = false,
    val errors: List<SubmitValidator.FieldError> = emptyList(),
)

data class JobDetailState(
    val jobId: String,
    val job: JobSummary? = null,
    val error: String? = null,
    val cancelling: Boolean = false,
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

    private val _submitForm = MutableStateFlow(SubmitFormState())
    val submitForm = _submitForm.asStateFlow()

    private val _detail = MutableStateFlow<JobDetailState?>(null)
    val detail = _detail.asStateFlow()

    fun isTermuxInstalled(): Boolean = controlPlane.isTermuxInstalled()

    private var sequenceJob: Job? = null
    private var pollJob: Job? = null
    private val skipSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        if (store.isPaired) warmStart()
    }

    private fun client() = BridgeClient(store.bridgeBaseUrl, store.token, http)

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
            // STOP first, always: a bridge left running (e.g. started manually
            // in Termux) holds its OLD token in memory and would 401 the app
            // even after a successful pair. Stopping is idempotent — a no-op
            // when nothing is running (on-device round 3 lesson).
            if (!sendOp(ControlOps.spec("STOP")!!)) return
            delay(1_000L)
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

    private suspend fun awaitHandshakeOrFail(allowRecovery: Boolean = true) {
        when (val r = handshaker().awaitHandshake()) {
            is BridgeResult.Success -> {
                applyEvent(WizardEvent.StepSucceeded(WizardStep.DONE))
                refresh()
            }
            is BridgeResult.HttpError -> {
                // 401 with a reachable bridge: our token is not the bridge's
                // token. Self-heal once: re-pair, restart, retry. If intents
                // cannot reach Termux, the failure card offers manual pairing.
                if (r.status == 401 && allowRecovery) {
                    applyEvent(WizardEvent.StepStarted(
                        "token rejected — re-pairing and restarting the bridge…"))
                    if (!sendOp(ControlOps.spec("PAIR")!!)) return
                    delay(2_000L)
                    if (!sendOp(ControlOps.spec("STOP")!!)) return
                    delay(2_000L)
                    if (!sendOp(ControlOps.spec("START")!!)) return
                    awaitHandshakeOrFail(allowRecovery = false)
                } else {
                    failHandshake(r)
                }
            }
            is BridgeResult.NetworkError ->
                applyEvent(
                    WizardEvent.StepFailed(
                        "The bridge did not answer. Most likely causes:\n" +
                            "• allow-external-apps is not set in ~/.termux/termux.properties\n" +
                            "• the install failed — open Termux and run ~/.trmx/trmx status\n" +
                            "• Termux was killed by Android (phantom process limits)\n" +
                            "Last network error: ${r.cause.message}"))
        }
    }

    private fun failHandshake(r: BridgeResult.HttpError) {
        val hint = if (r.status == 401) {
            "The bridge rejected our token (HTTP 401) — pairing did not take effect.\n" +
                "Checklist:\n" +
                "• TRMX holds the Termux:Run Command permission (App info → Permissions)\n" +
                                "• ~/.termux/termux.properties contains allow-external-apps=true\n" +
                                "  (then run termux-reload-settings and restart Termux)\n" +
                "• no other bridge instance is running with an old token\n" +
                "If Termux is not running our commands, use manual pairing below."
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

    // ---- submit form ----------------------------------------------------

    fun editName(v: String) = _submitForm.update { it.copy(name = v) }
    fun editArgvText(v: String) = _submitForm.update { it.copy(argvText = v) }
    fun editCwd(v: String) = _submitForm.update { it.copy(cwd = v) }
    fun editTimeout(v: String) = _submitForm.update { it.copy(timeoutText = v) }

    fun clearSubmitErrors() = _submitForm.update { it.copy(errors = emptyList()) }

    fun submitJob() {
        val form = _submitForm.value
        val argv = SubmitValidator.parseArgvText(form.argvText)
        val timeout = form.timeoutText.trim().toLongOrNull()
        if (timeout == null && form.timeoutText.isNotBlank()) {
            _submitForm.update {
                it.copy(errors = listOf(SubmitValidator.FieldError("timeout_s", "Timeout must be a number of seconds (or empty).")))
            }
            return
        }
        val errors = SubmitValidator.validate(form.name.trim(), argv, timeout, form.cwd.trim())
        if (errors.isNotEmpty()) {
            _submitForm.update { it.copy(errors = errors) }
            return
        }
        _submitForm.update { it.copy(submitting = true, errors = emptyList()) }
        viewModelScope.launch {
            val request = SubmitRequest(
                name = form.name.trim().ifEmpty { argv.firstOrNull()?.substringAfterLast('/') ?: "job" },
                type = "argv",
                argv = argv,
                cwd = form.cwd.trim().ifEmpty { null },
                env = null,
                timeout_s = timeout,
                idempotency_key = UUID.randomUUID().toString(),
            )
            when (val r = client().submitJob(request)) {
                is BridgeResult.Success -> {
                    _submitForm.value = SubmitFormState()
                    _dashboard.update {
                        it.copy(notice = "job ${r.data.response.job_id} submitted" +
                            if (r.data.replayed) " (idempotent replay)" else "")
                    }
                    refresh()
                }
                is BridgeResult.HttpError -> _submitForm.update {
                    it.copy(submitting = false,
                            errors = listOf(SubmitValidator.FieldError(r.code, r.message)))
                }
                is BridgeResult.NetworkError -> _submitForm.update {
                    it.copy(submitting = false,
                            errors = listOf(SubmitValidator.FieldError(
                                "network", "bridge unreachable: ${r.cause.message}")))
                }
            }
        }
    }

    // ---- job detail + cancel --------------------------------------------

    fun selectJob(jobId: String?) {
        pollJob?.cancel()
        if (jobId == null) {
            _detail.value = null
        } else {
            _detail.value = JobDetailState(jobId)
            pollJob = viewModelScope.launch {
                while (isActive) {
                    when (val r = client().getJob(jobId)) {
                        is BridgeResult.Success -> {
                            _detail.update { it?.copy(job = r.data, error = null) }
                            if (r.data.status in TERMINAL) break
                        }
                        is BridgeResult.HttpError -> {
                            _detail.update { it?.copy(error = "HTTP ${r.status}: ${r.code}") }
                            break
                        }
                        is BridgeResult.NetworkError -> {
                            _detail.update { it?.copy(error = "bridge unreachable: ${r.cause.message}") }
                        }
                    }
                    delay(DETAIL_POLL_MS)
                }
            }
        }
    }

    fun cancelJob(jobId: String) {
        _detail.update { it?.copy(cancelling = true) }
        viewModelScope.launch {
            when (val r = client().cancelJob(jobId)) {
                is BridgeResult.Success -> _detail.update { it?.copy(job = r.data, cancelling = false) }
                is BridgeResult.HttpError ->
                    _detail.update { it?.copy(cancelling = false, error = "HTTP ${r.status}: ${r.code} — ${r.message}") }
                is BridgeResult.NetworkError ->
                    _detail.update { it?.copy(cancelling = false, error = "bridge unreachable: ${r.cause.message}") }
            }
        }
    }

    // ---- dashboard ------------------------------------------------------

    fun refresh() {
        _dashboard.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            val client = client()
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

    private suspend fun probe(): BridgeResult<SystemInfo> = client().systemInfo()

    private fun handshaker() =
        Handshaker(probe = { probe() }, pollIntervalMs = 500, maxAttempts = 60)

    companion object {
        private const val INSTALL_PY_WAIT_MS = 40_000L
        private const val INSTALL_WAIT_MS = 20_000L
        private const val PAIR_WAIT_MS = 3_000L
        private const val DETAIL_POLL_MS = 2_000L
        private val TERMINAL = setOf("COMPLETED", "FAILED", "CANCELLED", "LOST")
    }
}
