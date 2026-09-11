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
import dev.trmx.gui.job.JobOutputState
import dev.trmx.gui.model.FileEntry
import dev.trmx.gui.job.OutputReducer
import dev.trmx.gui.job.SubmitValidator
import dev.trmx.gui.model.FileOpRequest
import dev.trmx.gui.model.JobSummary
import dev.trmx.gui.model.ServiceDefRequest
import dev.trmx.gui.model.ServiceStatus
import dev.trmx.gui.model.SubmitRequest
import dev.trmx.gui.model.SystemInfo
import dev.trmx.gui.net.BridgeClient
import dev.trmx.gui.net.BridgeResult
import dev.trmx.gui.net.Handshaker
import dev.trmx.gui.net.SseClient
import dev.trmx.gui.net.SseFrame
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import android.net.Uri
import android.os.Environment
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dev.trmx.gui.files.FileMime
import dev.trmx.gui.model.ToolStatus
import dev.trmx.gui.model.ToolSubmitRequest
import dev.trmx.gui.store.ChainStore
import dev.trmx.gui.store.Recipe
import dev.trmx.gui.store.RecipeStore
import dev.trmx.gui.tools.ChainDef
import dev.trmx.gui.tools.ChainPlanner
import dev.trmx.gui.tools.ChainRunState
import dev.trmx.gui.tools.ChainStep
import dev.trmx.gui.tools.FieldValue
import dev.trmx.gui.tools.Artifact
import dev.trmx.gui.tools.Artifacts
import dev.trmx.gui.tools.FormEngine
import dev.trmx.gui.tools.ToolJobMeta
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.io.File
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

data class FileBrowserState(
    val path: String = "~",
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val opPending: Boolean = false,
    val transfer: Transfer? = null,
    val pendingOpen: PendingOpen? = null,
)

/** One in-flight file transfer (download / upload / open / share). */
data class Transfer(
    val label: String,        // "Downloading" | "Uploading" | "Opening" | "Sharing"
    val name: String,
    val bytes: Long,
    val total: Long?,         // null → indeterminate progress
)

/**
 * A bridge file staged in cache/shared, ready for the UI to fire
 * ACTION_VIEW / ACTION_SEND through the FileProvider (ADR-008).
 */
data class PendingOpen(
    val path: String,         // absolute local path
    val mime: String,
    val share: Boolean,
)

/** Toolbox state (PROTOCOL §7). */
data class ToolsState(
    val tools: List<ToolStatus> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val schemaErrors: List<String> = emptyList(),
)

/** AI Schema Builder state (AI round, ADR-014). */
data class SchemaBuilderState(
    val description: String = "",
    val submitting: Boolean = false,
    val jobId: String? = null,      // running/finished trmx-ai job
    val result: String? = null,     // terminal notice (success or failure)
    val error: String? = null,      // submit-side error (validation/HTTP)
)

/** Services state (protocol 1.1, §14). */
data class ServicesState(
    val services: List<ServiceStatus> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** null = unknown (bridge < 0.5.0); false shows the upgrade card */
    val supported: Boolean? = null,
)

/** One dynamic tool form (a schema + live field values). */
data class ToolFormState(
    val schema: dev.trmx.gui.model.ToolSchema? = null,
    val values: Map<String, FieldValue> = emptyMap(),
    val touched: Set<String> = emptySet(),   // errors surface only after touch (Ph 9.5)
    val submitting: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val pathArg: String? = null,        // arg being picked in the Files browser
    val stepIndex: Int? = null,         // set = editing a chain step, not submitting
)

/** Artifacts of the opened (completed) job — Phase 9.5. */
data class ArtifactsState(
    val jobId: String? = null,
    val artifacts: List<Artifact> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Chains: saved defs + the open builder + the live run. */
data class ChainsState(
    val defs: List<ChainDef> = emptyList(),
    val editing: ChainDef? = null,
    val run: ChainRunState? = null,
    val error: String? = null,
    val notice: String? = null,
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

    private val _output = MutableStateFlow<JobOutputState?>(null)
    val output = _output.asStateFlow()

    private val _files = MutableStateFlow(FileBrowserState())

    private val recipeStore = RecipeStore(getApplication<Application>().filesDir)
    private val chainStore = ChainStore(getApplication<Application>().filesDir)

    private val _tools = MutableStateFlow(ToolsState())
    val tools = _tools.asStateFlow()

    private val _toolForm = MutableStateFlow(ToolFormState())
    val toolForm = _toolForm.asStateFlow()

    private val _schemaBuilder = MutableStateFlow(SchemaBuilderState())
    val schemaBuilder = _schemaBuilder.asStateFlow()

    private val _services = MutableStateFlow(ServicesState())
    val services = _services.asStateFlow()

    private val _chains = MutableStateFlow(ChainsState())
    val chains = _chains.asStateFlow()

    private val _recipes = MutableStateFlow<List<Recipe>>(emptyList())
    val recipes = _recipes.asStateFlow()

    init {
        _recipes.value = recipeStore.list()
        _chains.update { it.copy(defs = chainStore.list()) }
        refreshShortcuts()
    }

    private val _artifacts = MutableStateFlow(ArtifactsState())
    val artifacts = _artifacts.asStateFlow()

    /** tool jobs submitted this session: jobId -> outputs/outdir (Ph 9.5) */
    private val toolJobMeta = HashMap<String, ToolJobMeta>()

    private var chainRunner: Job? = null
    private var installWatch: String? = null   // job_id of a running pkg install
    private var aiWatch: String? = null        // job_id of a running trmx-ai job
    val files = _files.asStateFlow()

    fun isTermuxInstalled(): Boolean = controlPlane.isTermuxInstalled()

    private var sequenceJob: Job? = null
    private var pollJob: Job? = null
    private var outputJob: Job? = null
    private var eventsJob: Job? = null
    private val skipSignal = Channel<Unit>(Channel.CONFLATED)

    private fun sseClient() = SseClient(store.bridgeBaseUrl, store.token, http)

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
        pollJob?.cancel()
        outputJob?.cancel()
        eventsJob?.cancel()
        skipSignal.tryReceive()
        applyEvent(WizardEvent.Reset)
        _dashboard.value = DashboardState()
        _output.value = null
        _files.value = FileBrowserState()
        _toolForm.value = ToolFormState()
        _chains.value = ChainsState()
        _recipes.value = recipeStore.list()
        _artifacts.value = ArtifactsState()
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
        outputJob?.cancel()
        if (jobId == null) {
            _detail.value = null
            _output.value = null
        } else {
            _detail.value = JobDetailState(jobId)
            _output.value = OutputReducer.initial()
            _artifacts.value = ArtifactsState(jobId = jobId)
            maybeLoadArtifacts(jobId)
            startOutput(jobId)
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

    // ---- file browser (Phase 7) ------------------------------------------

    fun openPath(path: String) {
        _files.update { it.copy(path = path, loading = true, error = null, notice = null) }
        viewModelScope.launch {
            when (val r = client().listFiles(path)) {
                is BridgeResult.Success ->
                    _files.update { it.copy(entries = r.data.entries, loading = false) }
                is BridgeResult.HttpError ->
                    _files.update { it.copy(loading = false, error = "HTTP ${r.status}: ${r.code} — ${r.message}") }
                is BridgeResult.NetworkError ->
                    _files.update { it.copy(loading = false, error = "bridge unreachable: ${r.cause.message}") }
            }
        }
    }

    fun refreshFiles() = openPath(_files.value.path)

    /** @return true when we moved up, false at the root (caller may pop). */
    fun filesUp(): Boolean {
        val cur = _files.value.path
        if (cur == "~") return false
        val parent = cur.removeSuffix("/").substringBeforeLast('/')
        openPath(if (parent.isEmpty() || parent == "~") "~" else parent)
        return true
    }

    fun makeDir(name: String) {
        val base = _files.value.path.trimEnd('/')
        runOp(FileOpRequest(op = "mkdir", path = "$base/$name"))
    }

    fun renameEntry(entry: FileEntry, newName: String) {
        val base = _files.value.path.trimEnd('/')
        runOp(FileOpRequest(op = "rename", path = "$base/${entry.name}", new_name = newName))
    }

    fun deleteEntry(entry: FileEntry) {
        val base = _files.value.path.trimEnd('/')
        val req = if (entry.type == "dir") {
            FileOpRequest(op = "delete", path = "$base/${entry.name}",
                          recursive = true, confirm = true)
        } else {
            FileOpRequest(op = "delete", path = "$base/${entry.name}")
        }
        runOp(req)
    }

    private fun runOp(req: FileOpRequest) {
        _files.update { it.copy(opPending = true, error = null, notice = null) }
        viewModelScope.launch {
            when (val r = client().fileOp(req)) {
                is BridgeResult.Success -> {
                    _files.update { it.copy(opPending = false, notice = "${req.op}: ok") }
                    refreshFiles()
                }
                is BridgeResult.HttpError ->
                    _files.update { it.copy(opPending = false, error = "${req.op} failed: HTTP ${r.status} ${r.code} — ${r.message}") }
                is BridgeResult.NetworkError ->
                    _files.update { it.copy(opPending = false, error = "bridge unreachable: ${r.cause.message}") }
            }
        }
    }

    /** Download into the app's external files dir (no permissions needed). */
    fun downloadEntry(entry: FileEntry) {
        val base = _files.value.path.trimEnd('/')
        val remote = "$base/${entry.name}"
        val progress = TransferPoster("Downloading", entry.name)
        _files.update {
            it.copy(opPending = true, notice = null, error = null,
                    transfer = Transfer("Downloading", entry.name, 0, null))
        }
        viewModelScope.launch {
            val destDir = getApplication<Application>()
                .getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: getApplication<Application>().filesDir
            val dest = File(destDir, entry.name)
            when (val r = client().downloadFile(remote, dest, progress::onBytes)) {
                is BridgeResult.Success ->
                    _files.update { it.copy(opPending = false, transfer = null,
                                            notice = "saved ${r.data} bytes → ${dest.absolutePath}") }
                is BridgeResult.HttpError ->
                    _files.update { it.copy(opPending = false, transfer = null,
                                            error = "download failed: HTTP ${r.status} ${r.code}") }
                is BridgeResult.NetworkError ->
                    _files.update { it.copy(opPending = false, transfer = null,
                                            error = "download failed: ${r.cause.message}") }
            }
        }
    }

    /**
     * Phase 8 download-then-open (ADR-008): stream the entry into the
     * single-slot cache/shared dir (cleared first, so the cache stays
     * bounded), then the UI fires ACTION_VIEW / ACTION_SEND through the
     * FileProvider.
     */
    fun openEntry(entry: FileEntry, share: Boolean) {
        if (entry.type == "dir") return
        val base = _files.value.path.trimEnd('/')
        openRemote("$base/${entry.name}", entry.name, share)
    }

    /** Path-based open/share (files browser AND artifact cards use this). */
    fun openRemote(remote: String, name: String, share: Boolean) {
        val label = if (share) "Sharing" else "Opening"
        val progress = TransferPoster(label, name)
        _files.update {
            it.copy(opPending = true, notice = null, error = null,
                    transfer = Transfer(label, name, 0, null))
        }
        viewModelScope.launch {
            val sharedDir = File(getApplication<Application>().cacheDir, "shared")
            sharedDir.listFiles()?.forEach { it.delete() }   // single slot
            val dest = File(sharedDir, remote.substringAfterLast('/'))
            when (val r = client().downloadFile(remote, dest, progress::onBytes)) {
                is BridgeResult.Success ->
                    _files.update {
                        it.copy(opPending = false, transfer = null,
                                pendingOpen = PendingOpen(
                                    dest.absolutePath, FileMime.of(name), share))
                    }
                is BridgeResult.HttpError ->
                    _files.update { it.copy(opPending = false, transfer = null,
                                            error = "${label.lowercase()} failed: " +
                                                "HTTP ${r.status} ${r.code} — ${r.message}") }
                is BridgeResult.NetworkError ->
                    _files.update { it.copy(opPending = false, transfer = null,
                                            error = "${label.lowercase()} failed: ${r.cause.message}") }
            }
        }
    }

    /** UI fired the open/share intent (or it failed) — clear the slot. */
    fun afterOpen(error: String?) {
        _files.update { it.copy(pendingOpen = null, error = error) }
    }

    /** Upload a picked document into the current directory. */
    fun uploadFromUri(uri: Uri, displayName: String) {
        val base = _files.value.path.trimEnd('/')
        val dest = "$base/$displayName"
        val progress = TransferPoster("Uploading", displayName)
        _files.update {
            it.copy(opPending = true, notice = null, error = null,
                    transfer = Transfer("Uploading", displayName, 0, null))
        }
        viewModelScope.launch {
            val tmp = File(getApplication<Application>().cacheDir, "upload-$${System.currentTimeMillis()}")
            try {
                val resolver = getApplication<Application>().contentResolver
                resolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                } ?: throw java.io.IOException("cannot open picked document")
                val total = tmp.length()
                when (val r = client().uploadFile(dest, tmp) { n -> progress.onBytes(n, total) }) {
                    is BridgeResult.Success -> {
                        _files.update { it.copy(opPending = false, transfer = null,
                                                notice = "uploaded $displayName") }
                        refreshFiles()
                    }
                    is BridgeResult.HttpError ->
                        _files.update { it.copy(opPending = false, transfer = null,
                                                error = "upload failed: HTTP ${r.status} ${r.code} — ${r.message}") }
                    is BridgeResult.NetworkError ->
                        _files.update { it.copy(opPending = false, transfer = null,
                                                error = "upload failed: ${r.cause.message}") }
                }
            } catch (e: Exception) {
                _files.update { it.copy(opPending = false, transfer = null,
                                        error = "upload failed: ${e.message}") }
            } finally {
                tmp.delete()
            }
        }
    }

    fun clearFilesNotice() = _files.update { it.copy(notice = null, error = null) }

    /** Throttles progress callbacks to ~10 Hz (plus the final value). */
    private inner class TransferPoster(private val label: String, private val name: String) {
        private var last = 0L

        fun onBytes(bytes: Long, total: Long?) {
            val now = android.os.SystemClock.elapsedRealtime()
            val done = total != null && bytes >= total
            if (done || now - last >= 100) {
                last = now
                _files.update { it.copy(transfer = Transfer(label, name, bytes, total)) }
            }
        }
    }


    // ---- tools, recipes & chains (Phase 9) ------------------------------

    fun loadTools() {
        if (_tools.value.loading) return
        _tools.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = client().listTools()) {
                is BridgeResult.Success -> {
                    val schemaErrors = _dashboard.value.info?.features?.tool_schema_errors
                        ?: emptyList()
                    _tools.update {
                        it.copy(loading = false, tools = r.data.tools,
                                schemaErrors = schemaErrors)
                    }
                }
                is BridgeResult.HttpError ->
                    _tools.update { it.copy(loading = false,
                                            error = "HTTP ${r.status} ${r.code} — ${r.message}") }
                is BridgeResult.NetworkError ->
                    _tools.update { it.copy(loading = false,
                                            error = "bridge unreachable: ${r.cause.message}") }
            }
        }
    }

    fun refreshToolsNow() {
        _tools.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = client().refreshTools()) {
                is BridgeResult.Success -> _tools.update {
                    it.copy(loading = false, tools = r.data.tools,
                            notice = "rescan complete — ${r.data.tools.size} tools")
                }
                is BridgeResult.HttpError ->
                    _tools.update { it.copy(loading = false,
                                            error = "HTTP ${r.status} ${r.code}") }
                is BridgeResult.NetworkError ->
                    _tools.update { it.copy(loading = false,
                                            error = "bridge unreachable: ${r.cause.message}") }
            }
        }
    }

    /** Self-healing toolbox: run `pkg install -y <pkg>` as a normal job and
     *  rescan when it finishes (watched via /v1/events, see onEvent). */
    fun installTool(t: ToolStatus) {
        val pkg = t.schema.pkg ?: return
        viewModelScope.launch {
            when (val r = client().submitJob(dev.trmx.gui.model.SubmitRequest(
                name = "install ${pkg}", type = "argv",
                argv = listOf("pkg", "install", "-y", pkg), cwd = "~"))) {
                is BridgeResult.Success -> {
                    installWatch = r.data.response.job_id
                    _tools.update {
                        it.copy(notice = "installing ${pkg} (job ${r.data.response.job_id}) — "
                                + "the toolbox rescans automatically when it finishes")
                    }
                }
                is BridgeResult.HttpError ->
                    _tools.update { it.copy(error = "install failed: HTTP ${r.status} ${r.code}") }
                is BridgeResult.NetworkError ->
                    _tools.update { it.copy(error = "install failed: ${r.cause.message}") }
            }
        }
    }

    // ---- dynamic form ------------------------------------------------------

    fun openToolForm(toolId: String, stepIndex: Int? = null,
                     preset: Map<String, JsonElement>? = null) {
        val schema = _tools.value.tools.firstOrNull { it.schema.id == toolId }?.schema
        if (schema == null) {
            _tools.update { it.copy(error = "tool not found: $toolId") }
            return
        }
        var values = FormEngine.initialValues(schema)
        preset?.forEach { (k, v) ->
            val spec = schema.args.firstOrNull { it.name == k } ?: return@forEach
            val text = runCatching { v.jsonPrimitive.content }.getOrNull()
            values = values + (k to if (spec.type == "bool")
                FieldValue(bool = text == "true" || text == "1") else FieldValue(text = text ?: ""))
        }
        _toolForm.value = ToolFormState(schema = schema, values = values,
                                      stepIndex = stepIndex, touched = preset?.keys?.toSet() ?: emptySet())
    }

    fun closeToolForm() { _toolForm.value = ToolFormState() }

    /**
     * Home intent cards (P2): open a tool form even when the registry is
     * cold (Home renders before the Toolbox ever loads). Cached schema →
     * straight to the form; otherwise fetch, merge into the registry, open.
     */
    fun openToolById(toolId: String) {
        if (_tools.value.tools.any { it.schema.id == toolId }) {
            openToolForm(toolId); return
        }
        viewModelScope.launch {
            when (val res = client().getTool(toolId)) {
                is BridgeResult.Success -> {
                    _tools.update { t ->
                        if (t.tools.none { it.schema.id == toolId })
                            t.copy(tools = t.tools + res.data) else t
                    }
                    openToolForm(toolId)
                }
                else -> _dashboard.update {
                    it.copy(notice = "tool unavailable: $toolId — try Tools → Scan")
                }
            }
        }
    }

    fun editFieldValue(arg: String, value: FieldValue) {
        _toolForm.update { it.copy(values = it.values + (arg to value),
                                   touched = it.touched + arg) }
    }

    /** Run attempted: surface every field's validation state (Ph 9.5). */
    fun touchAllFields() {
        _toolForm.update { f -> f.copy(touched = f.values.keys) }
    }

    /** Path args are picked in the Files browser (Phase 7) — never typed. */
    fun pickPathFor(arg: String) { _toolForm.update { it.copy(pathArg = arg) } }
    fun pathPicked(path: String) {
        val arg = _toolForm.value.pathArg ?: return
        _toolForm.update { it.copy(values = it.values + (arg to FieldValue(text = path)),
                                   pathArg = null) }
    }
    fun cancelPathPick() { _toolForm.update { it.copy(pathArg = null) } }

    fun submitToolForm() {
        val f = _toolForm.value
        val schema = f.schema ?: return
        if (!FormEngine.isSubmittable(schema, f.values)) {
            _toolForm.update { it.copy(error = "some fields need attention first",
                                       touched = f.values.keys) }
            return
        }
        val args = FormEngine.argsPayload(schema, f.values)
        if (f.stepIndex != null) {          // chain-step edit mode
            applyStepArgs(f.stepIndex, args)
            return
        }
        _toolForm.update { it.copy(submitting = true, error = null, notice = null) }
        viewModelScope.launch {
            val req = ToolSubmitRequest(
                name = schema.name.ifBlank { schema.id },
                tool = schema.id, args = args, cwd = "~")
            when (val r = client().submitToolJob(req)) {
                is BridgeResult.Success -> {
                    toolJobMeta[r.data.response.job_id] = Artifacts.metaFor(schema, args)
                    _toolForm.update { it.copy(submitting = false,
                                               notice = "job ${r.data.response.job_id} submitted") }
                    _dashboard.update {
                        it.copy(notice = "job ${r.data.response.job_id} submitted (${schema.id})")
                    }
                }
                is BridgeResult.HttpError ->
                    _toolForm.update { it.copy(submitting = false,
                                               error = "HTTP ${r.status} ${r.code} — ${r.message}") }
                is BridgeResult.NetworkError ->
                    _toolForm.update { it.copy(submitting = false,
                                               error = "bridge unreachable: ${r.cause.message}") }
            }
        }
    }

    // ---- AI Schema Builder (AI round, ADR-014) ------------------------------

    /** Runs the bundled `ai-schema-builder` tool (trmx-ai on the phone);
     *  completion is watched via /v1/events — see onEvent. */
    fun editSchemaDescription(v: String) =
        _schemaBuilder.update { it.copy(description = v, error = null) }

    fun clearSchemaBuilderResult() =
        _schemaBuilder.update { it.copy(result = null, error = null, jobId = null) }

    fun submitSchemaBuilder() {
        val s = _schemaBuilder.value
        if (s.submitting) return
        val desc = s.description.trim()
        if (desc.length < 8) {
            _schemaBuilder.update {
                it.copy(error = "describe the tool in a few more words (name the binary)")
            }
            return
        }
        _schemaBuilder.update { it.copy(submitting = true, error = null, result = null) }
        viewModelScope.launch {
            val req = ToolSubmitRequest(
                name = "AI schema build",
                tool = "ai-schema-builder",
                args = mapOf("description" to JsonPrimitive(desc)),
                cwd = "~")
            when (val r = client().submitToolJob(req)) {
                is BridgeResult.Success -> {
                    val id = r.data.response.job_id
                    aiWatch = id
                    _schemaBuilder.update { it.copy(submitting = false, jobId = id) }
                    _dashboard.update {
                        it.copy(notice = "job $id submitted (ai-schema-builder)")
                    }
                }
                is BridgeResult.HttpError ->
                    _schemaBuilder.update {
                        it.copy(submitting = false,
                                error = "HTTP ${r.status} ${r.code} — ${r.message}")
                    }
                is BridgeResult.NetworkError ->
                    _schemaBuilder.update {
                        it.copy(submitting = false,
                                error = "bridge unreachable: ${r.cause.message}")
                    }
            }
        }
    }

    // ---- services (protocol 1.1, §14) ----------------------------------------

    /** Capability flag from the last system/info (null = not fetched yet). */
    fun noteServiceSupport(supported: Boolean?) {
        _services.update { it.copy(supported = supported) }
    }

    fun loadServices() {
        if (_services.value.loading) return
        _services.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = client().listServices()) {
                is BridgeResult.Success -> {
                    _services.update {
                        it.copy(loading = false, services = r.data.services,
                                supported = true)
                    }
                }
                is BridgeResult.HttpError ->
                    _services.update { it.copy(loading = false,
                                               error = "HTTP ${r.status} ${r.code} — ${r.message}") }
                is BridgeResult.NetworkError ->
                    _services.update { it.copy(loading = false,
                                               error = "bridge unreachable: ${r.cause.message}") }
            }
        }
    }

    fun clearServicesNotice() = _services.update { it.copy(notice = null, error = null) }

    private fun applyServiceResult(r: BridgeResult<ServiceStatus>, what: String) {
        when (r) {
            is BridgeResult.Success -> {
                val st = r.data
                _services.update { s ->
                    s.copy(notice = "${st.name}: $what",
                           services = s.services.map {
                               if (it.id == st.id) st else it
                           })
                }
            }
            is BridgeResult.HttpError ->
                _services.update { it.copy(error = "HTTP ${r.status} ${r.code} — ${r.message}") }
            is BridgeResult.NetworkError ->
                _services.update { it.copy(error = "bridge unreachable: ${r.cause.message}") }
        }
    }

    fun startService(id: String) {
        viewModelScope.launch { applyServiceResult(client().startService(id), "started") }
    }

    fun stopService(id: String) {
        viewModelScope.launch { applyServiceResult(client().stopService(id), "stopping…") }
    }

    fun restartService(id: String) {
        viewModelScope.launch { applyServiceResult(client().restartService(id), "restarted") }
    }

    fun setServiceAutostart(id: String, enabled: Boolean) {
        viewModelScope.launch {
            applyServiceResult(client().setServiceAutostart(id, enabled),
                               if (enabled) "autostart on" else "autostart off")
        }
    }

    fun deleteService(id: String) {
        viewModelScope.launch {
            when (val r = client().deleteService(id)) {
                is BridgeResult.Success -> {
                    _services.update { s ->
                        s.copy(notice = "service deleted",
                               services = s.services.filterNot { it.id == id })
                    }
                }
                is BridgeResult.HttpError ->
                    _services.update { it.copy(error = "HTTP ${r.status} ${r.code} — ${r.message}") }
                is BridgeResult.NetworkError ->
                    _services.update { it.copy(error = "bridge unreachable: ${r.cause.message}") }
            }
        }
    }

    /** Save the CURRENT tool form as a service definition (§14): the form's
     *  validated args become the service's fixed args. Long-running tools
     *  (http-server & friends) are the natural fit, but any tool works. */
    fun saveServiceFromForm() {
        val f = _toolForm.value
        val schema = f.schema ?: return
        if (f.stepIndex != null) return
        if (!FormEngine.isSubmittable(schema, f.values)) {
            _toolForm.update { it.copy(error = "fill the required fields first",
                                       touched = f.values.keys) }
            return
        }
        val args = FormEngine.argsPayload(schema, f.values)
        val sid = schema.id.replace(Regex("[^A-Za-z0-9._-]"), "-")
        viewModelScope.launch {
            when (val r = client().createService(
                    ServiceDefRequest(id = sid, name = schema.name.ifBlank { sid },
                                      tool = schema.id, args = args))) {
                is BridgeResult.Success -> {
                    _toolForm.update {
                        it.copy(notice = "saved as service \"${r.data.id}\" — manage it in Services")
                    }
                    _services.update { it.copy(notice = "service \"${r.data.name}\" created") }
                }
                is BridgeResult.HttpError ->
                    _toolForm.update { it.copy(error = "HTTP ${r.status} ${r.code} — ${r.message}") }
                is BridgeResult.NetworkError ->
                    _toolForm.update { it.copy(error = "bridge unreachable: ${r.cause.message}") }
            }
        }
    }

    // ---- artifacts (Phase 9.5) ---------------------------------------------

    /**
     * Derive artifacts for a COMPLETED tool job: exact outputs from the
     * submit-time meta (or schema defaults after an app restart) plus files
     * newly detected in the outdir (mtime >= started_at, labeled "detected").
     */
    private fun maybeLoadArtifacts(jobId: String) {
        viewModelScope.launch {
            val job = when (val r = client().getJob(jobId)) {
                is BridgeResult.Success -> r.data
                else -> return@launch
            }
            if (job.type != "tool" || job.tool == null || job.status != "COMPLETED") return@launch
            val schema = _tools.value.tools.firstOrNull { it.schema.id == job.tool }?.schema
            val meta = toolJobMeta[jobId]
                ?: (schema?.let { Artifacts.metaFor(it, emptyMap()) })   // post-restart fallback
                ?: return@launch
            if (meta.outputs.isEmpty() && meta.outdir == null) return@launch
            _artifacts.update { it.copy(jobId = jobId, loading = true, error = null) }
            var listing: List<FileEntry>? = null
            if (meta.outdir != null) {
                when (val r = client().listFiles(meta.outdir!!)) {
                    is BridgeResult.Success -> listing = r.data.entries
                    is BridgeResult.HttpError ->
                        _artifacts.update { it.copy(loading = false,
                            error = "could not list ${meta.outdir}: HTTP ${r.status} ${r.code}") }
                    is BridgeResult.NetworkError -> Unit   // exact outputs still shown
                }
            }
            val arts = Artifacts.collect(meta, job.started_at, listing, meta.outdir)
            _artifacts.update { it.copy(loading = false, artifacts = arts) }
        }
    }

    /** Open/share an artifact card (download-then-open, ADR-008 path). */
    fun openArtifact(a: Artifact, share: Boolean) = openRemote(a.path, a.name, share)

    // ---- recipes -------------------------------------------------------------

    fun saveRecipeFromForm(title: String) {
        val f = _toolForm.value
        val schema = f.schema ?: return
        if (!FormEngine.isSubmittable(schema, f.values)) {
            _toolForm.update { it.copy(error = "fix the fields before saving a recipe") }
            return
        }
        val recipe = Recipe(RecipeStore.newId(), title.ifBlank { schema.name },
                            schema.id, FormEngine.argsPayload(schema, f.values),
                            System.currentTimeMillis())
        recipeStore.add(recipe)
        _recipes.value = recipeStore.list()
        refreshShortcuts()
        _toolForm.update { it.copy(notice = "recipe saved: ${recipe.title}") }
    }

    fun deleteRecipe(id: String) {
        recipeStore.remove(id)
        _recipes.value = recipeStore.list()
        refreshShortcuts()
    }

    /** Cold-start entry (home-screen shortcut): fetch the schema, then open. */
    fun openRecipe(id: String) {
        val r = recipeStore.byId(id) ?: return
        val cached = _tools.value.tools.firstOrNull { it.schema.id == r.toolId }?.schema
        if (cached != null) { openToolForm(r.toolId, preset = r.args); return }
        viewModelScope.launch {
            when (val res = client().getTool(r.toolId)) {
                is BridgeResult.Success -> {
                    _tools.update { t ->
                        if (t.tools.none { it.schema.id == r.toolId })
                            t.copy(tools = t.tools + res.data) else t
                    }
                    openToolForm(r.toolId, preset = r.args)
                }
                else -> _tools.update { it.copy(error = "recipe's tool is unavailable: ${r.toolId}") }
            }
        }
    }

    /** Share a recipe as JSON via the Phase 8 staging mechanism. */
    fun shareRecipe(r: Recipe) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val sharedDir = File(getApplication<Application>().cacheDir, "shared")
                sharedDir.mkdirs()
                sharedDir.listFiles()?.forEach { it.delete() }   // single slot
                val safe = r.title.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val f = File(sharedDir, "trmx-recipe-$safe.json")
                f.writeText(RecipeStore.export(r))
                _files.update { it.copy(pendingOpen =
                    PendingOpen(f.absolutePath, "application/json", true)) }
            }.onFailure { e ->
                _tools.update { it.copy(error = "share failed: ${e.message}") }
            }
        }
    }

    fun importRecipe(uri: Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val text = getApplication<Application>().contentResolver
                    .openInputStream(uri)?.bufferedReader()?.readText()
                    ?: throw java.io.IOException("cannot open picked file")
                val parsed = RecipeStore.import(text)
                    ?: throw IllegalArgumentException("not a valid TRMX recipe")
                val r = parsed.copy(id = RecipeStore.newId())   // never hijack ids
                recipeStore.add(r)
                _recipes.value = recipeStore.list()
                refreshShortcuts()
                _tools.update { it.copy(error = null,
                                         notice = "imported recipe: ${r.title}") }
            }.onFailure { e ->
                _tools.update { it.copy(error = "import failed: ${e.message}") }
            }
        }
    }

    /** Long-press TRMX → one-tap recipes (dynamic shortcuts, newest 4). */
    fun refreshShortcuts() {
        val context = getApplication<Application>()
        runCatching {
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            val infos = recipeStore.list()
                .sortedByDescending { it.createdAt }
                .take(4)
                .map { r ->
                    ShortcutInfoCompat.Builder(context, r.id)
                        .setShortLabel(r.title.take(12))
                        .setLongLabel(r.title.take(30))
                        .setIntent(Intent(context, MainActivity::class.java)
                            .setAction(Intent.ACTION_VIEW)
                            .putExtra("recipe_id", r.id))
                        .build()
                }
            if (infos.isNotEmpty()) ShortcutManagerCompat.addDynamicShortcuts(context, infos)
        }
    }

    // ---- chains ---------------------------------------------------------------

    fun newChainBuilder() {
        _chains.update {
            it.copy(editing = ChainDef(RecipeStore.newId(), "", emptyList(),
                                       System.currentTimeMillis()),
                    error = null, notice = null)
        }
    }

    fun editChainDef(id: String) {
        _chains.update { it.copy(editing = it.defs.firstOrNull { d -> d.id == id }) }
    }

    fun setChainTitle(t: String) {
        _chains.update { s -> s.editing?.let { s.copy(editing = it.copy(title = t.take(80))) } ?: s }
    }

    fun addChainStep(toolId: String) {
        _chains.update { s ->
            val def = s.editing ?: return@update s
            s.copy(editing = def.copy(steps = def.steps + ChainStep(toolId = toolId)))
        }
    }

    fun removeChainStep(i: Int) {
        _chains.update { s ->
            val def = s.editing ?: return@update s
            if (i !in def.steps.indices) return@update s
            s.copy(editing = def.copy(
                steps = def.steps.filterIndexed { idx, _ -> idx != i }))
        }
    }

    fun editChainStep(i: Int) {
        val def = _chains.value.editing ?: return
        val step = def.steps.getOrNull(i) ?: return
        openToolForm(step.toolId, stepIndex = i, preset = step.args)
    }

    private fun applyStepArgs(i: Int, args: Map<String, JsonElement>) {
        _toolForm.value = ToolFormState()
        _chains.update { s ->
            val def = s.editing ?: return@update s
            if (i !in def.steps.indices) return@update s
            s.copy(editing = def.copy(
                steps = def.steps.toMutableList().also { st -> st[i] = st[i].copy(args = args) }))
        }
    }

    fun saveChainDef() {
        val def = _chains.value.editing ?: return
        if (def.title.isBlank()) {
            _chains.update { it.copy(error = "give the chain a title first") }; return
        }
        val schemas = _tools.value.tools.associate { it.schema.id to it.schema }
        val errs = ChainPlanner.validate(def, schemas)
        if (errs.isNotEmpty()) {
            _chains.update { it.copy(error = errs.joinToString("; ")) }; return
        }
        chainStore.save(def)
        _chains.update { it.copy(defs = chainStore.list(), editing = null,
                                  error = null, notice = "chain saved: ${def.title}") }
    }

    fun deleteChainDef(id: String) {
        chainStore.delete(id)
        _chains.update { it.copy(defs = chainStore.list()) }
    }

    fun runChain(def: ChainDef) {
        val schemas = _tools.value.tools.associate { it.schema.id to it.schema }
        val errs = ChainPlanner.validate(def, schemas)
        if (errs.isNotEmpty()) {
            _chains.update { it.copy(error = errs.joinToString("; ")) }; return
        }
        chainRunner?.cancel()
        _chains.update { it.copy(run = ChainRunState(def, 0), error = null, notice = null) }
        chainRunner = viewModelScope.launch { driveChain(def, 0, emptyMap()) }
    }

    fun resumeChain() {
        val run = _chains.value.run ?: return
        if (run.status != "PAUSED") return
        chainRunner?.cancel()
        _chains.update { s -> s.copy(run = run.copy(status = "RUNNING", error = null)) }
        chainRunner = viewModelScope.launch { driveChain(run.def, run.currentStep, run.stepJobIds) }
    }

    /** Stops orchestrating; a job already on the phone keeps running there. */
    fun stopChainRun() {
        chainRunner?.cancel()
        _chains.update { s ->
            s.run?.let { s.copy(run = it.copy(status = "PAUSED",
                error = "orchestration stopped — the current job (if any) keeps running")) } ?: s
        }
    }

    private suspend fun driveChain(def: ChainDef, fromStep: Int, priorJobs: Map<Int, String>) {
        val schemas = _tools.value.tools.associate { it.schema.id to it.schema }
        var stepJobIds = priorJobs
        var prevOutput: String? = null
        if (fromStep > 0) {
            prevOutput = ChainPlanner.outputsUpTo(def, schemas, fromStep)[fromStep - 1]
        }
        var i = fromStep
        while (i < def.steps.size) {
            val step = def.steps[i]
            val schema = schemas[step.toolId]
            if (schema == null) {
                _chains.update { s -> s.copy(run = s.run?.copy(status = "FAILED",
                    error = "step ${i + 1}: tool '${step.toolId}' disappeared from the registry")) }
                return
            }
            // Legacy defs saved before the native-number fix carry int/float
            // args as strings; re-type them per the schema (UX-audit P0).
            val args = FormEngine.coerceLegacyArgs(
                schema, ChainPlanner.resolveArgs(step, prevOutput))
            val req = ToolSubmitRequest(
                name = "${def.title} — ${ChainPlanner.stepTitle(step, schema)}",
                tool = step.toolId, args = args, cwd = "~")
            val jid = when (val r = client().submitToolJob(req)) {
                is BridgeResult.Success -> r.data.response.job_id
                is BridgeResult.HttpError ->
                    { _chains.update { s -> s.copy(run = s.run?.copy(status = "FAILED",
                        error = "step ${i + 1} rejected: HTTP ${r.status} ${r.code} — ${r.message}")) }
                      return }
                is BridgeResult.NetworkError ->
                    { _chains.update { s -> s.copy(run = s.run?.copy(
                        status = "PAUSED", currentStep = i, stepJobIds = stepJobIds,
                        error = "bridge unreachable — resume when it is back")) }
                      return }
            }
            toolJobMeta[jid] = Artifacts.metaFor(schema, args)
            stepJobIds = stepJobIds + (i to jid)
            _chains.update { s -> s.copy(run = s.run?.copy(
                currentStep = i, stepJobIds = stepJobIds, status = "RUNNING")) }
            // watch the step to its terminal status (2 s poll; local + cheap)
            var misses = 0
            var done: dev.trmx.gui.model.JobSummary? = null
            while (done == null) {
                delay(2_000L)
                when (val r = client().getJob(jid)) {
                    is BridgeResult.Success ->
                        if (r.data.status in TERMINAL) done = r.data
                    is BridgeResult.HttpError -> {
                        _chains.update { s -> s.copy(run = s.run?.copy(status = "PAUSED",
                            error = "step ${i + 1}: job vanished (HTTP ${r.status}) — resume?")) }
                        return
                    }
                    is BridgeResult.NetworkError -> {
                        if (++misses > 5) {
                            _chains.update { s -> s.copy(run = s.run?.copy(
                                status = "PAUSED",
                                error = "bridge unreachable — resume when it is back")) }
                            return
                        }
                    }
                }
            }
            if (done.status != "COMPLETED") {
                _chains.update { s -> s.copy(run = s.run?.copy(status = "FAILED",
                    error = "step ${i + 1} (${ChainPlanner.stepTitle(step, schema)}) " +
                            "ended ${done.status}")) }
                return
            }
            prevOutput = args["output"]?.let { a ->
                runCatching { a.jsonPrimitive.content }.getOrNull()
            }
            i++
        }
        _chains.update { s -> s.copy(run = s.run?.copy(status = "COMPLETED")) }
    }

    // ---- live output stream (Phase 6) -----------------------------------

    /**
     * Open /v1/jobs/{id}/output with full replay (from_seq=1) + live follow.
     * On connection loss the stream resumes from the last delivered seq
     * (the bridge's from_seq replay makes resume seamless); a normal close
     * after a terminal status frame ends collection.
     */
    private fun startOutput(jobId: String) {
        outputJob?.cancel()
        outputJob = viewModelScope.launch {
            var fromSeq = 1L
            var attempts = 0
            while (isActive) {
                try {
                    sseClient()
                        .stream("/v1/jobs/$jobId/output?from_seq=$fromSeq&follow=1&stream=both")
                        .collect { frame ->
                            _output.value = OutputReducer.apply(
                                _output.value ?: OutputReducer.initial(), frame)
                            if (frame.event == "status") mergeStatusIntoDetail(frame.data)
                            fromSeq = (_output.value?.lastSeq ?: (fromSeq - 1)) + 1
                        }
                    // stream ended normally — done if a terminal status came through
                    if (_output.value?.ended == true) return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // connection lost — retry below
                }
                attempts++
                if (attempts >= 30) {
                    _output.update { it?.copy(error = "stream lost after $attempts reconnect attempts") }
                    return@launch
                }
                delay(1_000L)
            }
        }
    }

    /** Re-open the output stream from seq 1 (fresh replay). */
    fun replayOutput() {
        val id = _detail.value?.jobId ?: return
        _output.value = OutputReducer.initial()
        startOutput(id)
    }

    /** Live status frames also refresh the job detail header. */
    private fun mergeStatusIntoDetail(data: String) {
        val el = runCatching {
            BridgeClient.jsonFormat.parseToJsonElement(data).jsonObject
        }.getOrNull() ?: return
        fun str(k: String) = el[k]?.jsonPrimitive?.contentOrNull
        _detail.update { d ->
            d?.copy(job = d.job?.copy(
                status = str("status") ?: d.job.status,
                exit_code = el["exit_code"]?.jsonPrimitive?.longOrNull ?: d.job.exit_code,
                ended_at = str("ended_at") ?: d.job.ended_at,
                progress_pct = el["progress_pct"]?.jsonPrimitive?.doubleOrNull
                    ?: d.job.progress_pct,
                progress_detail = str("progress_detail") ?: d.job.progress_detail,
            ))
        }
    }

    // ---- global event stream (Phase 6) ----------------------------------

    /** Subscribe to /v1/events while the dashboard is visible. */
    fun startEvents() {
        if (eventsJob?.isActive == true) return
        eventsJob = viewModelScope.launch {
            while (isActive) {
                try {
                    sseClient().stream("/v1/events").collect { frame -> onEvent(frame) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // bridge stopped or connection lost — retry below
                }
                delay(2_000L)
            }
        }
    }

    fun stopEvents() {
        eventsJob?.cancel()
        eventsJob = null
    }

    private fun onEvent(frame: SseFrame) {
        when (frame.event) {
            "job.updated" -> {
                val job = runCatching {
                    BridgeClient.jsonFormat.decodeFromString(JobSummary.serializer(), frame.data)
                }.getOrNull() ?: return
                _dashboard.update { d ->
                    val idx = d.jobs.indexOfFirst { it.job_id == job.job_id }
                    val jobs = if (idx >= 0) {
                        d.jobs.toMutableList().also { it[idx] = job }
                    } else {
                        listOf(job) + d.jobs
                    }
                    d.copy(jobs = jobs)
                }
                // self-healing toolbox: a finished pkg install triggers a rescan
                if (installWatch == job.job_id && job.status in TERMINAL) {
                    installWatch = null
                    _tools.update { it.copy(notice =
                        "install ${if (job.status == "COMPLETED") "finished" else job.status.lowercase()} — rescanning") }
                    refreshToolsNow()
                }
                // protocol 1.1: a bound service job changed state — refresh
                // that service's derived status without a round trip
                val bound = _services.value.services.firstOrNull { it.job_id == job.job_id }
                if (bound != null) {
                    _services.update { s ->
                        s.copy(services = s.services.map {
                            if (it.id == bound.id) it.copy(
                                state = if (job.status in TERMINAL) "stopped" else "running",
                                last_status = job.status,
                                last_exit_code = job.exit_code,
                                job_id = if (job.status in TERMINAL) null else job.job_id)
                            else it
                        })
                    }
                }
                // AI round: a finished trmx-ai job installed a schema —
                // rescan the registry so the new tool appears immediately
                if (aiWatch == job.job_id && job.status in TERMINAL) {
                    aiWatch = null
                    if (job.status == "COMPLETED") {
                        _schemaBuilder.update {
                            it.copy(result = "schema installed — toolbox rescanned")
                        }
                        refreshToolsNow()
                    } else {
                        _schemaBuilder.update {
                            it.copy(result = "job ${job.status.lowercase()} — open it for details")
                        }
                    }
                }
            }
            "service.updated" -> {
                // protocol 1.1: replace/delete the entry in place
                val st = runCatching {
                    BridgeClient.jsonFormat.decodeFromString(ServiceStatus.serializer(), frame.data)
                }.getOrNull()
                if (st != null) {
                    _services.update { s ->
                        when {
                            st.deleted -> s.copy(services = s.services.filterNot { it.id == st.id })
                            s.services.any { it.id == st.id } ->
                                s.copy(services = s.services.map { if (it.id == st.id) st else it })
                            else -> s.copy(services = s.services + st)
                        }
                    }
                }
            }
            "bridge.stopping" -> {
                val reason = runCatching {
                    BridgeClient.jsonFormat.parseToJsonElement(frame.data)
                        .jsonObject["reason"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                _dashboard.update {
                    it.copy(notice = "bridge stopping (${reason ?: "unknown"}) — will reconnect")
                }
            }
        }
    }

    // ---- dashboard ------------------------------------------------------

    fun refresh() {
        _dashboard.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            val client = client()
            when (val info = client.systemInfo()) {
                is BridgeResult.Success -> {
                    _dashboard.update { it.copy(info = info.data) }
                    // protocol 1.1 capability flag (§14) — drives the Services tab
                    noteServiceSupport(info.data.features?.service_registry)
                }
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

    override fun onCleared() {
        sequenceJob?.cancel()
        pollJob?.cancel()
        outputJob?.cancel()
        eventsJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val INSTALL_PY_WAIT_MS = 40_000L
        private const val INSTALL_WAIT_MS = 20_000L
        private const val PAIR_WAIT_MS = 3_000L
        private const val DETAIL_POLL_MS = 2_000L
        private val TERMINAL = setOf("COMPLETED", "FAILED", "CANCELLED", "LOST")
    }
}
