package dev.trmx.gui.control

/*
 * Sends RUN_COMMAND intents to Termux (the control plane). Used only when
 * the data plane is down: install, pair, start/stop, lifecycle setup.
 * Everything else goes over TRMX-P/1 HTTP via BridgeClient.
 */

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

class ControlPlaneException(
    val error: SendError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class SendError { PERMISSION_REQUIRED, TERMUX_NOT_RUNNING }
}

class IntentControlPlane(private val context: Context) {

    fun isTermuxInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(ControlOps.TERMUX_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun isRunCommandPermissionGranted(): Boolean =
        context.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Build and send the intent for [spec]. Substitutes <BASE>/<TOKEN> at
     * call time — the token never lives in the static op table.
     * Acceptance is observed through the data plane, not intent callbacks
     * (CONTROL-PLANE.md §1).
     */
    fun send(
        spec: ControlOps.OpSpec,
        baseUrl: String? = null,
        token: String? = null,
    ): Result<Unit> {
        require(ControlOps.pathIsInTermuxSandbox(spec.path)) {
            "refusing to execute outside the Termux sandbox: ${spec.path}"
        }
        val args = ControlOps.resolveArgs(spec, baseUrl, token)
        val intent = Intent(ControlOps.ACTION_RUN_COMMAND).apply {
            component = ComponentName(ControlOps.TERMUX_PACKAGE, ControlOps.RUN_COMMAND_SERVICE)
            putExtra(ControlOps.EXTRA_PATH, spec.path)
            putExtra(ControlOps.EXTRA_ARGUMENTS, args.toTypedArray())
            putExtra(ControlOps.EXTRA_WORKDIR, ControlOps.TERMUX_HOME)
            putExtra(ControlOps.EXTRA_BACKGROUND, true)
            putExtra(ControlOps.EXTRA_LABEL, "TRMX: ${spec.id}")
            putExtra(ControlOps.EXTRA_DESCRIPTION, spec.description)
        }
        return try {
            context.startService(intent)
            Result.success(Unit)
        } catch (e: SecurityException) {
            Result.failure(
                ControlPlaneException(
                    ControlPlaneException.SendError.PERMISSION_REQUIRED,
                    "RUN_COMMAND permission not granted to TRMX", e))
        } catch (e: IllegalStateException) {
            Result.failure(
                ControlPlaneException(
                    ControlPlaneException.SendError.TERMUX_NOT_RUNNING,
                    "cannot start Termux service (is Termux running?)", e))
        }
    }
}
