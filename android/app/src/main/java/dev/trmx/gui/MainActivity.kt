package dev.trmx.gui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.trmx.gui.ui.DashboardScreen
import dev.trmx.gui.ui.TRMXTheme
import dev.trmx.gui.ui.WizardScreen
import dev.trmx.gui.wizard.WizardStep

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
    val termuxInstalled = remember { vm.isTermuxInstalled() }

    if (wizard.step == WizardStep.DONE) {
        LaunchedEffect(Unit) { if (dashboard.info == null) vm.refresh() }
        DashboardScreen(
            state = dashboard,
            onRefresh = vm::refresh,
            onStopBridge = vm::stopBridge,
            onRerunWizard = vm::resetWizard,
        )
    } else {
        WizardScreen(
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
    }
}
