package dev.trmx.gui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.trmx.gui.model.ToolExample
import dev.trmx.gui.tools.FieldValue
import dev.trmx.gui.ui.ChainsScreen
import dev.trmx.gui.ui.DashboardScreen
import dev.trmx.gui.ui.FilesScreen
import dev.trmx.gui.ui.JobDetailScreen
import dev.trmx.gui.ui.ToolFormScreen
import dev.trmx.gui.ui.ToolboxScreen
import dev.trmx.gui.ui.TRMXTheme
import dev.trmx.gui.ui.WizardScreen
import dev.trmx.gui.wizard.WizardStep
import java.io.File

private enum class Screen { DASHBOARD, FILES, TOOLBOX, TOOLFORM, CHAINS }

private const val FILE_PROVIDER = "dev.trmx.gui.fileprovider"

/** Recipe shortcut id waiting for the wizard-safe moment (set by the activity). */
private var pendingRecipeId: String? = null

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleRecipeIntent(intent)
        setContent {
            TRMXTheme {
                AppRoot()
            }
        }
    }

    /** Home-screen recipe shortcuts (launchMode=singleTask -> onNewIntent). */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleRecipeIntent(intent)
    }

    private fun handleRecipeIntent(intent: Intent?) {
        val id = intent?.getStringExtra("recipe_id") ?: return
        pendingRecipeId = id
    }
}

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val wizard by vm.wizard.collectAsStateWithLifecycle()
    val dashboard by vm.dashboard.collectAsStateWithLifecycle()
    val submitForm by vm.submitForm.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val output by vm.output.collectAsStateWithLifecycle()
    val filesState by vm.files.collectAsStateWithLifecycle()
    val toolsState by vm.tools.collectAsStateWithLifecycle()
    val toolFormState by vm.toolForm.collectAsStateWithLifecycle()
    val chainsState by vm.chains.collectAsStateWithLifecycle()
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val termuxInstalled = remember { vm.isTermuxInstalled() }
    var screen by remember { mutableStateOf(Screen.DASHBOARD) }

    // Cold-start recipe shortcut (set by MainActivity before composition).
    LaunchedEffect(Unit) {
        pendingRecipeId?.let { id ->
            pendingRecipeId = null
            vm.loadTools()
            vm.openRecipe(id)
        }
    }

    when {
        wizard.step != WizardStep.DONE -> WizardScreen(
            state = wizard,
            termuxInstalled = termuxInstalled,
            onConsentTermux = vm::consentTermux,
            onConsentRunCommand = vm::consentRunCommand,
            onConsentAllowExternal = vm::consentAllowExternal,
            onBaseUrlChanged = vm::baseUrlChanged,
            onBegin = vm::beginWizard,
            onSkipWait = vm::skipWait,
            onRetry = vm::retry,
            onReset = vm::resetWizard,
        )

        else -> {
            // local capture: `detail` is a delegated property and cannot be smart-cast
            val detailState = detail
            val toolForm = toolFormState

            // Staged open/share (ADR-008): fired at root so it works from
            // every screen — Files, Toolbox (recipe share), anywhere.
            val pending = filesState.pendingOpen
            LaunchedEffect(pending) {
                if (pending == null) return@LaunchedEffect
                try {
                    val uri = FileProvider.getUriForFile(
                        vm.getApplication(), FILE_PROVIDER, File(pending.path))
                    val intent = if (pending.share) {
                        Intent(Intent.ACTION_SEND).apply {
                            type = pending.mime
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri(File(pending.path).name, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    } else {
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, pending.mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                    vm.getApplication().startActivity(
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    vm.afterOpen(null)
                } catch (e: ActivityNotFoundException) {
                    vm.afterOpen("no app can open \u201c${File(pending.path).name}\u201d " +
                        "(${pending.mime}) \u2014 try Share instead")
                } catch (e: SecurityException) {
                    vm.afterOpen("cannot open: ${e.message}")
                }
            }

            // Live events while the app is open (dashboard updates everywhere;
            // chains and the self-healing toolbox install ride on this).
            LaunchedEffect(Unit) { vm.startEvents() }

            if (detailState != null) {
                JobDetailScreen(
                    state = detailState,
                    output = output,
                    onBack = { vm.selectJob(null) },
                    onCancelJob = vm::cancelJob,
                    onReplayOutput = vm::replayOutput,
                )
            } else if (toolForm.schema != null) {
                BackHandler { vm.closeToolForm(); screen = Screen.TOOLBOX }
                ToolFormScreen(
                    state = toolForm,
                    onBack = { vm.closeToolForm(); screen = Screen.TOOLBOX },
                    onEdit = vm::editFieldValue,
                    onBrowsePath = { arg ->
                        vm.pickPathFor(arg)
                        screen = Screen.FILES
                    },
                    onExample = { ex: ToolExample ->
                        vm.openToolForm(toolForm.schema.id,
                                        stepIndex = toolForm.stepIndex,
                                        preset = ex.args)
                    },
                    onSubmit = vm::submitToolForm,
                    onSaveRecipe = vm::saveRecipeFromForm,
                )
            } else if (screen == Screen.FILES) {
                // Back walks up the directory tree first, then exits to the
                // screen that opened the browser (form picker or dashboard).
                val picking = toolFormState.pathArg != null
                BackHandler {
                    if (picking) { vm.cancelPathPick(); screen = Screen.TOOLFORM }
                    else if (!vm.filesUp()) screen = Screen.DASHBOARD
                }
                LaunchedEffect(Unit) { if (filesState.entries.isEmpty()) vm.openPath(filesState.path) }
                val pickArg = toolFormState.pathArg
                FilesScreen(
                    state = filesState,
                    onBack = { if (picking) { vm.cancelPathPick(); screen = Screen.TOOLFORM } else screen = Screen.DASHBOARD },
                    onOpenPath = vm::openPath,
                    onUp = { vm.filesUp() },
                    onRefresh = vm::refreshFiles,
                    onMakeDir = vm::makeDir,
                    onRename = vm::renameEntry,
                    onDelete = vm::deleteEntry,
                    onDownload = vm::downloadEntry,
                    onUpload = vm::uploadFromUri,
                    onOpenFile = { vm.openEntry(it, false) },
                    onShareFile = { vm.openEntry(it, true) },
                    pickFile = pickArg?.let { arg ->
                        toolFormState.schema?.args
                            ?.firstOrNull { it.name == arg }
                            ?.let { it.path_kind != "dir" }
                    },
                    onPicked = { path ->
                        vm.pathPicked(path)
                        screen = Screen.TOOLFORM
                    },
                    onCancelPick = { vm.cancelPathPick(); screen = Screen.TOOLFORM },
                )
            } else if (screen == Screen.TOOLBOX) {
                BackHandler { screen = Screen.DASHBOARD }
                LaunchedEffect(Unit) { vm.loadTools() }
                ToolboxScreen(
                    state = toolsState,
                    recipes = recipes,
                    onBack = { screen = Screen.DASHBOARD },
                    onRefresh = vm::refreshToolsNow,
                    onOpenTool = { toolId -> vm.openToolForm(toolId); screen = Screen.TOOLFORM },
                    onInstall = vm::installTool,
                    onOpenRecipe = { id -> vm.openRecipe(id); screen = Screen.TOOLFORM },
                    onDeleteRecipe = vm::deleteRecipe,
                    onShareRecipe = vm::shareRecipe,
                    onImportRecipe = vm::importRecipe,
                    onOpenChains = { screen = Screen.CHAINS },
                )
            } else if (screen == Screen.CHAINS) {
                BackHandler { screen = Screen.TOOLBOX }
                ChainsScreen(
                    state = chainsState,
                    tools = toolsState.tools,
                    jobs = dashboard.jobs,
                    onBack = { screen = Screen.TOOLBOX },
                    onNew = vm::newChainBuilder,
                    onEditDef = vm::editChainDef,
                    onDeleteDef = vm::deleteChainDef,
                    onRun = vm::runChain,
                    onResume = vm::resumeChain,
                    onStopRun = vm::stopChainRun,
                    onSetTitle = vm::setChainTitle,
                    onAddStep = vm::addChainStep,
                    onRemoveStep = vm::removeChainStep,
                    onEditStep = { i -> vm.editChainStep(i); screen = Screen.TOOLFORM },
                    onSaveDef = vm::saveChainDef,
                )
            } else {
                LaunchedEffect(Unit) { if (dashboard.info == null) vm.refresh() }
                DashboardScreen(
                    state = dashboard,
                    submitForm = submitForm,
                    onRefresh = vm::refresh,
                    onStopBridge = vm::stopBridge,
                    onRerunWizard = vm::resetWizard,
                    onJobClick = vm::selectJob,
                    onOpenSubmit = vm::clearSubmitErrors,
                    onDismissSubmit = vm::clearSubmitErrors,
                    onSubmitName = vm::editName,
                    onSubmitArgv = vm::editArgvText,
                    onSubmitCwd = vm::editCwd,
                    onSubmitTimeout = vm::editTimeout,
                    onSubmitJob = vm::submitJob,
                    onOpenFiles = {
                        vm.clearFilesNotice()
                        screen = Screen.FILES
                    },
                    onOpenTools = {
                        screen = Screen.TOOLBOX
                    },
                )
            }
        }
    }

}
