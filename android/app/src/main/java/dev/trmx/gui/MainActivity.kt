package dev.trmx.gui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.trmx.gui.ui.DashboardScreen
import dev.trmx.gui.ui.FilesScreen
import dev.trmx.gui.ui.JobDetailScreen
import dev.trmx.gui.ui.TRMXTheme
import dev.trmx.gui.ui.WizardScreen
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.trmx.gui.wizard.WizardStep

private enum class Screen { DASHBOARD, FILES }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TRMXTheme {
                AppRoot()
            }
        }
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
    val termuxInstalled = remember { vm.isTermuxInstalled() }
    var screen by remember { mutableStateOf(Screen.DASHBOARD) }

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
            if (detailState != null) {
                JobDetailScreen(
                    state = detailState,
                    output = output,
                    onBack = { vm.selectJob(null) },
                    onCancelJob = vm::cancelJob,
                    onReplayOutput = vm::replayOutput,
                )
            } else if (screen == Screen.FILES) {
                // Back walks up the directory tree first, then exits to the
                // dashboard (Phase 8 back-stack polish).
                BackHandler { if (!vm.filesUp()) screen = Screen.DASHBOARD }
                LaunchedEffect(Unit) { if (filesState.entries.isEmpty()) vm.openPath(filesState.path) }
                FilesScreen(
                    state = filesState,
                    onBack = { screen = Screen.DASHBOARD },
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
                    onAfterOpen = vm::afterOpen,
                )
            } else {
                LaunchedEffect(Unit) { if (dashboard.info == null) vm.refresh() }
                // live job events while the dashboard is visible
                DisposableEffect(Unit) {
                    vm.startEvents()
                    onDispose { vm.stopEvents() }
                }
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
                )
            }
        }
    }
}
