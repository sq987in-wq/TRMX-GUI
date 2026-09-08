# On-Device Test Guide — Phases 4–5 (first real-world run)

**Applies to:** the debug APK built by CI (Actions → latest green `android` run →
Artifacts → `trmx-debug-apk`). Phone requirements: Android 8+ (user targets 14).

## 0. The one gotcha: install source URL

The app's default install source points at the **`main`** branch, but the
backend code currently lives on `arena/01a07bc5-trmx-gui`. Until the branch is
merged, **edit the base URL in the wizard** (step 4 below), replacing `main`:

```
https://raw.githubusercontent.com/sq987in-wq/TRMX-GUI/arena/01a07bc5-trmx-gui/termux
```

(If you already merged to main, keep the default.)

## 1. Prepare the phone (5 min, once)

1. **Termux** installed from GitHub Releases (termux.dev) — same source for
   everything Termux; never mix GitHub/F-Droid builds. Open it once.
2. In Termux:
   ```sh
   mkdir -p ~/.termux
   echo "allow-external-apps=true" >> ~/.termux/termux.properties
   termux-reload-settings
   ```
3. Install the TRMX app: download `trmx-debug-apk` artifact from the latest
   green Actions run (GitHub → Actions → run → Artifacts), unzip, install the
   APK (allow "install unknown apps" for your browser/file manager).
   > One-time note: artifacts built before 2026-09-08 round 2 were signed
   > with per-run random keys, so updating over them fails with a signature
   > mismatch — uninstall the old TRMX once, then install. From the round-2
   > build on, all artifacts share a stable test key and update in place.
   > After a reinstall the app has no token: stop the bridge first
   > (`~/.trmx/trmx stop` in Termux) so the wizard's PAIR + START re-pair
   > cleanly, then re-run the wizard.
4. Grant the permission: Android Settings → Apps → TRMX → Permissions →
   Additional permissions → **Termux: Run Command** → allow. (The wizard has
   a button that deep-links to this screen.)

## 1b. Manual backend setup (alternative — Termux side, no app needed)

```sh
pkg install -y python curl git
git clone -b arena/01a07bc5-trmx-gui https://github.com/sq987in-wq/TRMX-GUI
cd TRMX-GUI
sh termux/install.sh --source termux     # → "checksums verified", "installed"
~/.trmx/trmx start                       # bridge on 127.0.0.1:27342
~/.trmx/trmx status                      # → data-plane: UP
~/.trmx/trmx stop                        # ⚠️ STOP before pairing with the app
```

**Why the stop:** the app pairs its *own* token into `~/.trmx/bridge.json`,
but a *running* bridge keeps the old token in memory until restarted — the
app's handshake would get 401. Stopping first lets the app's START launch
the bridge with the app's token. (Safe either way: nothing is lost.)

## 2. Run the wizard

Open TRMX → confirm the three consent checkboxes (they confirm you did §1) →
**fix the base URL (§0)** → "Install backend & connect".

Expected: step 1 "installing Python" (can be skipped if `pkg install python`
was already done) → step 2 downloads + SHA256-verifies the bridge → step 3
pairs → step 4 starts it → step 5 connects → dashboard shows Bridge v0.2.0
with system info (memory, uptime, limits).

If step 1 hangs: Termux's package update may be slow the first time — wait or
skip if Python is already installed. If step 5 fails, the failure card lists
the three likely causes (allow-external-apps / install failed / phantom
killer).

## 3. Test sequence (the actual Phase 4–5 acceptance)

| # | Action | Expected |
|---|--------|----------|
| 1 | "+ New job" → name `count`, argv lines: `python3` / `-c` / `for i in range(5): print(i, flush=True)` (each on its own line) → Run | notice appears ("job J-XX submitted"), job shows RUNNING then COMPLETED (exit 0) after ~1 s |
| 2 | Tap the `count` job | detail shows argv on three lines, pid, exit code 0, stdout_bytes > 0 |
| 3 | "+ New job" → argv: `false` → Run | FAILED, exit code 1 |
| 4 | "+ New job" → argv: `sleep` / `600` → Run, open it, "Cancel job" → confirm | CANCELLING → CANCELLED within ~5 s |
| 5 | Force-stop TRMX (recents → swipe), reopen | warm-start handshake → dashboard without the wizard |
| 6 | "Stop bridge" | notice; Refresh shows "bridge unreachable" (it's stopped). Reopen Termux and `~/.trmx/trmx start`, or "Re-run setup" |

Note on job 1: the `-c` program is a single argv element — the line-based
editor passes it verbatim with no shell quoting. That is the argv-first
design working.

## 4. If something breaks

- The wizard/job failure cards name the state — screenshot them.
- Termux side: `~/.trmx/trmx status`, `~/.trmx/bridge.log` (tail).
- If the bridge dies randomly later: Android's phantom-process killer —
  enable Developer options → "Disable child process restrictions" (Android
  14+; details in the Phase 0 report §B.9).
- Report: which step, what the card said, and the tail of `~/.trmx/bridge.log`
  if the bridge was involved. Failure cards include a "Last network error:"
  line — always include it (it named the cleartext bug instantly once added).
- The token is stored in `~/.trmx/bridge.json` (key `"token"`), not a
  `token.secret` file — check with `head -c 200 ~/.trmx/bridge.json`.

## Known limits in this build (by design, not bugs)

- No live output *text* yet — detail shows byte counters; streaming arrives
  in Phase 6.
- Manual refresh on the dashboard (no push/auto-refresh yet).
- Plain HTTP on 127.0.0.1 only, bearer-token auth (V1 transport decision).
- Debug APK is signed with the debug key — fine for testing; release signing
  is Phase 10/11.
