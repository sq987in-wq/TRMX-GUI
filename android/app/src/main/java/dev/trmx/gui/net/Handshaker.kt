package dev.trmx.gui.net

/*
 * Handshake poller: after sending a START intent we cannot observe intent
 * completion (deliberately — CONTROL-PLANE.md §1), so we poll the data
 * plane until /v1/system/info answers or the deadline passes.
 *
 * The delay is injectable so tests are deterministic.
 */

import dev.trmx.gui.model.SystemInfo
import kotlinx.coroutines.delay

class Handshaker(
    private val probe: suspend () -> BridgeResult<SystemInfo>,
    private val pollIntervalMs: Long = 500,
    private val maxAttempts: Int = 60,   // 30 s at the default interval
) {

    suspend fun awaitHandshake(
        delayFn: suspend (Long) -> Unit = ::delay,
    ): BridgeResult<SystemInfo> {
        var last: BridgeResult<SystemInfo> =
            BridgeResult.NetworkError(IllegalStateException("handshake deadline passed"))
        repeat(maxAttempts) {
            val r = probe()
            when (r) {
                is BridgeResult.Success -> return r
                // auth problems will not fix themselves by retrying — surface now
                is BridgeResult.HttpError -> if (r.status == 401 || r.status == 403) return r
                else -> {}
            }
            last = r
            delayFn(pollIntervalMs)
        }
        return last
    }
}
