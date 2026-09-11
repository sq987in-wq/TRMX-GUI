# On-Device Test Guide — Phases 4–7

> **STATUS: VERIFIED ✅ — 2026-09-08, physical device, Android 16.**
> Wizard → pair → start → handshake → jobs J-1…J-3 (exit 0). Kept as the
> regression checklist for future rounds; the troubleshooting notes below
> are the distilled lessons from the three fix rounds.

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
- **401 at handshake / "did the PAIR intent even run?"** — check:
  `grep -o '"token_generated": [a-z]*' ~/.trmx/bridge.json`
  `true` = bridge self-generated → the app's PAIR intent never executed →
  check `cat ~/.termux/termux.properties` (needs `allow-external-apps=true`),
  run `termux-reload-settings`, restart Termux, retry. `false` = pair ran;
  a running bridge just needed the STOP→PAIR→START order (wizard does this
  since round 3). Manual escape hatch: the failure card shows a copyable
  token — `~/.trmx/trmx stop` → `~/.trmx/trmx pair <token>` →
  `~/.trmx/trmx start` → Retry.
- The token is stored in `~/.trmx/bridge.json` (key `"token"`), not a
  `token.secret` file — check with `head -c 200 ~/.trmx/bridge.json`.

## Known limits in this build (by design, not bugs)

- No live output *text* yet — detail shows byte counters; streaming arrives
  in Phase 6.
- Manual refresh on the dashboard (no push/auto-refresh yet).
- Plain HTTP on 127.0.0.1 only, bearer-token auth (V1 transport decision).
- Debug APK is signed with the debug key — fine for testing; release signing
  is Phase 10/11.

> Update: the first two limits above (live output text, manual dashboard
> refresh) were resolved and verified on-device in the Phase 6 round
> (2026-09-08): live console with stdout/stderr filter + replay, and the
> dashboard self-updates via `/v1/events`.

---

# Phase 7 round — file manager acceptance (2026-09-08)

> **STATUS: VERIFIED ✅ — 2026-09-08, physical device, Android 16.**
> Bridge upgraded to v0.3.0 in place (wizard Re-run setup) and running
> stably; `~` listing, navigation, folder create/rename/delete, and file
> download verified; §6 wire shape and protocol headers (`X-TRMX-Protocol:
> 1`) confirmed working end-to-end.

**Build:** CI run green on `6cb16d9` (bridge v0.3.0, checksum
`fe41b16d…f38152`, app with the Files browser). Both planes change this
round — update the APK **and** the bridge.

## Update sequence (both planes)

1. **App:** download `trmx-debug-apk` from the latest green Actions run →
   unzip → install. Updates in place over the round-2+ key (no uninstall,
   token survives).
2. **Bridge:** dashboard → **Re-run setup**. The wizard re-runs INSTALL
   (idempotent `install.sh` — this is the upgrade path) with the base URL
   from §0, then STOP → PAIR → START → handshake.
3. **Verify:** dashboard shows **Bridge v0.3.0** (the handshake's
   `/v1/system/info` reports the version). Termux-side cross-check:
   `head -c 400 ~/.trmx/bridge.json` or `~/.trmx/trmx status`.

## Acceptance checklist

| # | Action | Expected |
|---|--------|----------|
| 1 | Dashboard → **Files** | listing of `~` loads (dirs/files, sizes, glyph per type) |
| 2 | Tap a directory (e.g. `downloads`) | listing of that dir; "up" row returns |
| 3 | "New folder" → `trmx-test` | dialog → folder appears in the listing |
| 4 | Long-press `trmx-test` → Rename → `trmx-renamed` | listing shows the new name |
| 5 | Long-press `trmx-renamed` → Delete | confirm dialog warns **recursive + irreversible**; confirm → gone |
| 6 | Long-press a **non-empty** directory → Delete | same interlock: bridge demands `confirm:true`, app sends it after the dialog |
| 7 | Long-press a file → Download | notice with byte count; file lands in `Android/data/dev.trmx.gui/files/Documents/` (check with a file manager — no storage permission needed or granted) |
| 8 | "Upload" → pick any file in the system picker | progress → file appears in the listing with the right size |
| 9 | Dashboard → Files again (or rotate/reopen) | state reloads cleanly; no stale listings after ops (each op refreshes) |

Uploads are atomic on the bridge side (temp + fsync + rename, optional
SHA-256 the app always sends) — a killed mid-upload never leaves a partial
file; re-listing after an interrupted upload is a valid extra check.

## Known limits in this round (by design, not bugs)

- No open/share yet: downloading a file is the only way to get it out of
  Termux storage via the app (FileProvider open/share is Phase 8).
- TOCTOU window between the path-policy check and the operation (ADR-007) —
  single-user local threat model, accepted for v1.
- Copy follows symlinks (shutil semantics) — documented in ADR-007.

## If something breaks

Same as §4, plus: file ops surface typed errors from the bridge
(`CONFIRM_REQUIRED`, `PATH_NOT_EMPTY`, `PATH_EXISTS`, `NOT_A_FILE`,
`CHECKSUM_MISMATCH`) — screenshot the exact error text and include the tail
of `~/.trmx/bridge.log`.

---

# Phase 8 round — polish, UX & FileProvider acceptance (2026-09-09)

> **STATUS: VERIFIED ✅ — 2026-09-09, physical device, Android 16.**
> Launcher icon, back navigation (up-the-tree first), and open/share via
> FileProvider all confirmed working.

**Build:** CI green on `c85262f` (run 34368335630). **App-only round — no
bridge update needed** (bridge stays v0.3.0, checksums unchanged).

## Update sequence

1. Download `trmx-debug-apk` from the green run → unzip → install in place
   (token survives; no wizard re-run needed).

## Acceptance checklist

| # | Action | Expected |
|---|--------|----------|
| 1 | Look at the app drawer | TRMX now has a real icon: dark slate plate, green `>` chevron + blue underscore |
| 2 | Files → long-press any **file** | action sheet: Open / Share / Save to app documents / Rename / Delete |
| 3 | Long-press a **folder** | action sheet: Rename / Delete only |
| 4 | Open a video (`.mp4`/`.mkv` in `~/downloads`) | progress bar fills (determinate, byte counts) → player app opens and plays it |
| 5 | Open a file with no viewer (e.g. `.log`) | honest error: "no app can open … (text/plain) — try Share instead" |
| 6 | Share a file | system share sheet opens → share somewhere → content arrives intact |
| 7 | Save a **large** file to app documents | determinate progress bar the whole way → "saved N bytes → path" notice |
| 8 | Upload a **large** file via the picker | determinate progress bar → listing shows it with the right size |
| 9 | Navigate into a subfolder → system **Back** | goes **up one level**; only at `~` does Back exit Files to the dashboard |
| 10 | Dashboard | dot is green "● connected"; after Stop bridge + Refresh it turns red "● connection error" with the error banner |
| 11 | Files with the bridge stopped | error card with a **retry** button (start the bridge again → retry works) |
| 12 | Open an empty folder | centered "This folder is empty." |

Notes: the staged open/share copy lives in app cache, **single-slot**
(replaced on every open) — Android may reclaim it under storage pressure,
which is fine; the Termux original is the source of truth. No mid-transfer
cancel in v1 (documented in ADR-008).

## If something breaks

Screenshot the exact error (open/share failures name the file, the MIME
type, and the exception), plus the tail of `~/.trmx/bridge.log` if a
transfer itself failed.

---

# Phase 9 round — Tool Registry, dynamic forms, recipes & chains (2026-09-09)

**Build:** CI green on the Phase 9 commits (bridge **v0.4.1** — universal
runtime broadening, app with Toolbox/forms/recipes/chains). **Both planes
change** — update the APK *and* the bridge (same sequence as Phase 7):

1. **App:** latest green run → `trmx-debug-apk` → install in place.
2. **Bridge:** dashboard → **Re-run setup** (idempotent installer, base URL
   on the branch) → verify the dashboard shows **Bridge v0.4.0**.
3. **Termux prep (optional but recommended):** `pkg install -y yt-dlp ffmpeg`
   so the first toolbox scan finds real binaries — or leave them out and use
   the app's own install buttons (step 4).

## Acceptance checklist

| # | Action | Expected |
|---|--------|----------|
| 1 | Dashboard → **Tools** | Toolbox loads: cards across categories — git-clone, python-run, http-server, tar-backup, yt-dlp, ffmpeg, aria2c — with ✓/✗, version lines, tier badges |
| 2 | **scan ⟳** | rescan notice; tools you installed in Termux show ✓ |
| 3 | Tap a ✗ card → **install** → confirm | runs `pkg install -y …` as a job (live console in job detail); when it finishes the toolbox rescans and the card flips ✓ |
| 4 | yt-dlp card → fill **Video URL** (any small public video) → 📁 pick `~/downloads` → **RUN ▶** | job submitted; dashboard row shows a **live % bar**; detail console shows `[download] …%` lines; file lands in `~/downloads` (check in Files) |
| 5 | Reopen the form → **Try ▸ MP4, best quality** | example prefills the fields |
| 6 | Form: type a bad URL / rate `500X` | inline ⚠ validation before any submit |
| 7 | ffmpeg card (tier CONFIRM) → pick input+output → RUN | confirmation dialog shows the exact command before it runs |
| 8 | Form → **☆ save recipe** → name it | appears under Recipes; **long-press the TRMX app icon** → the recipe is a one-tap shortcut |
| 9 | Tap the shortcut (app closed) | app opens straight to the prefilled form |
| 10 | Recipes → **share** | system share sheet offers the `.json` file |
| 11 | **Chains** → + new chain → add ffmpeg step (output `~/a.mp4`) → add ffmpeg step with input `$PREV_FILE` → save → **▶ run** | step 1 runs; when it completes step 2 auto-runs with the previous output; run card shows per-step status |
| 12 | While a chain runs: Termux → `~/.trmx/trmx stop`, wait, restart | chain **PAUSES** with "bridge unreachable — resume when it is back"; after restarting the bridge, **resume from step N** continues |
| 13 | (advanced) In Termux: `mkdir -p ~/.trmx/tools`, drop a JSON schema there, **scan ⟳** | new card + full form from your schema — zero app changes |

Notes & honest limits: yt-dlp cannot be chained FROM (server-named output
files — the builder marks it "no known output"); if the app is killed
mid-chain, finished steps remain real jobs in the history and the chain is
re-run from the start; progress parsing reads stdout lines only.

## If something breaks

Screenshot the toolbox card / form error / chain state, and include the tail
of `~/.trmx/bridge.log`. Tool submits surface typed errors
(`TOOL_UNKNOWN`, `ARG_INVALID` with the field name, `PATH_DENIED`,
`PATH_NOT_FOUND`).

---

# Phase 9.5 round — executive polish & artifacts (2026-09-10)

**Build:** CI green on the Phase 9.5 commit. **App-only** — bridge stays
v0.4.1 (if you skipped the v0.4.1 update, do the bridge Re-run setup first,
see the Phase 9 section).

## Acceptance checklist

| # | Action | Expected |
|---|--------|----------|
| 1 | Open the app | terminal-luxe look: deep-slate surfaces, green accents, **bottom navigation** (Home / Files / Tools / Chains) — no more "← Dashboard" buttons |
| 2 | Any tool form, before typing | **no red errors anywhere**; help text sits under each field |
| 3 | Tool form: type an invalid URL | error appears under the field only after you edit it |
| 4 | Path field | the 📁 picker sits **inside the field** (trailing icon), aligned |
| 5 | yt-dlp form: Quality | **segmented buttons** (mp4 / mkv / best), not a text field |
| 6 | ffmpeg form: CRF (0–51) | **slider** with value readout |
| 7 | Tap **RUN** with an empty required field | fields light up with their errors (button never dead) |
| 8 | Run yt-dlp on a small video → open the job | job detail shows an **Artifacts** card; the video is listed with open/share; "detected" entries are labeled as such |
| 9 | Artifact → **open** | progress → viewer opens it |
| 10 | Navigate Files → system Back | up the tree first, then Home tab |
| 11 | Tools / Chains tabs via bottom bar | instant switching, state kept |

## If something breaks

Screenshot the screen (theming regressions are visual by nature), plus the
usual `~/.trmx/bridge.log` tail if a transfer/listing failed.

# UX-audit round (P0 + P1) — correctness & Command Center (2026-09-10)

**Build:** CI green on the UX-audit commits. **App-only** — bridge stays
v0.4.1, install the APK in place. No wizard re-run needed.

## Acceptance checklist

| # | Action | Expected |
|---|--------|----------|
| 1 | ffmpeg form, **change nothing**, tap RUN | the job **submits** — no "arg 'crf': must be an integer" anywhere (the P0 wire bug) |
| 2 | Same for http-server (port) and aria2c (connections) | submits with defaults |
| 3 | Home | **Command Center**: status pill top-right, Quick run (if recipes exist), Tasks — **no bridge metrics dump** |
| 4 | Tap the status pill | **Diagnostics sheet** slides up: version/protocol/uptime/memory + Refresh, Re-run setup, **Stop bridge** (wraps cleanly, never "Sto/p/brid/ge") |
| 5 | Tasks list | human labels ("Video Downloader (yt-dlp) · done · 3 min ago"), tiny `J-… ⧉` line; tap the id → copied |
| 6 | Files | **no green vertical bar**; toolbar buttons wrap to a second row if narrow; **dotfiles hidden** by default |
| 7 | Files → toggle "◌ dotfiles" | `.trmx`, `.bashrc` … appear; toggle state survives navigation |
| 8 | A folder with only dotfiles | "N hidden entries" + **show dotfiles** button (not a dead empty screen) |
| 9 | Toolbox | **⚡ Custom command** card at top → the argv dialog (moved from Home) |
| 10 | Tool form → RUN while submitting | spinner sits **inside** the RUN button, button row never overflows |
| 11 | Job detail | title is the **task name**, `J-…` copyable underneath; card leads with "tool · time" |

## If something breaks

Screenshots for anything visual; `~/.trmx/bridge.log` tail for submit or
listing failures. For a rejected submit, note the exact error text shown
under the form — the wire-type fix should make ARG_INVALID on numeric
args impossible.

# UX-audit round 2 (P2) — productization (2026-09-10)

**Build:** CI green on the P2 commit. **App-only** — bridge stays v0.4.1,
install in place.

## Acceptance checklist

| # | Action | Expected |
|---|--------|----------|
| 1 | Cold-start the app (fresh from launcher) | **no white flash** at launch; status bar/nav dark slate |
| 2 | Open any tool form and focus a text field | the **keyboard is dark**, fields are dark slate containers with green focus border — no harsh white anywhere |
| 3 | Tool form / job detail top | **native top app bar** with a back arrow; no "← Toolbox" pill |
| 4 | Home with no tasks | **hero workspace** ("Your Linux runtime, on this phone.") — not a dead task list |
| 5 | Home intent cards | four cards: **Download media · Convert / transcode · Run a command · Start a service** — each opens its form (or the argv dialog) with one tap |
| 6 | "Run a command" card | the argv dialog opens right from Home |
| 7 | Files | rows read **"Aug 28 · 3.5 KB"** — no ISO timestamps, no `drwx------`; folders listed first |
| 8 | Tap a file in Files | **File Details sheet**: full path, type, exact size, modified (relative + ISO), permissions, symlink target; open/share/save at the bottom |
| 9 | Long-press a file | menu now leads with **Details** |
| 10 | Toolbox | cards read **"● Ready"** / **"● Setup required"** — no SAFE/CONFIRM badges |
| 11 | Chains tab (no saved chains) | educational **Fetch → Process → Archive** card with "Create your first pipeline" — not a black void |
| 12 | Tool form RUN | unchanged: confirm per tier, spinner inside the button |

## If something breaks

Screenshots for anything visual (this round is visual by nature). If a
field still renders white anywhere, note WHICH screen and whether the
keyboard or the field itself is white — the XML theme is the root fix,
TrmxFieldColors the belt-and-braces.

# UX-audit round 3 (P3) — OLED design system (2026-09-11)

**Build:** CI green on the P3 commit. **App-only** — bridge stays v0.4.1,
install in place. Presentation-tier only; all behavior unchanged.

## Acceptance checklist

| # | Action | Expected |
|---|--------|----------|
| 1 | Cold-start | **pure black** background everywhere (OLED) — no slate, no grey |
| 2 | Home | status pill, hero/intent cards are **dark surfaces with thin outlines**, not shadowed grey cards |
| 3 | Accents | **ice-cyan** for focus/primary/RUN/running, **steel blue** for completed — **no green, no amber anywhere** |
| 4 | Buttons | Primary RUN is the one loud cyan button per screen; secondary actions are quiet outlines; **no ragged multi-line wraps** (Files toolbar is one compact line) |
| 5 | Tool form | dark fields (no white boxes), dark keyboard, label INSIDE the field, back arrow top bar |
| 6 | Files | rows: name + "Aug 28 · 3.5 KB"; folders first; tap = details sheet |
| 7 | Toolbox | "● Ready" (cyan) / "● Setup required" (dim steel) on dark cards |
| 8 | Chains (empty) | Fetch → Process → Archive educational card, cyan CTA |
| 9 | Job detail | task title top bar, cancel = red outline Danger button |
| 10 | Tasks on Home | each task is its own outcome card (not one big list) |
| 11 | Regression: run ffmpeg with defaults | submits (P0 wire fix intact) |
| 12 | Regression: yt-dlp progress | live cyan progress bar + detail line |

## If something breaks

Screenshots + which screen. If any green/amber sneaks back or a field
renders light, name the screen — every constant lives in Tokens.kt, so
fixes are surgical.

---

## Round 4 — UX-audit P4 commercial-grade rebuild (2026-09-11)

APK from this round's CI run (check the run page for the artifact).

### Zero emojis / vector icons
- [ ] Bottom navigation shows vector icons (Home, Folder, Tools-wrench,
      Chains-tree); the active tab is FILLED with an ice-cyan tint on a
      pill indicator, inactive tabs are outlined. No text glyphs.
- [ ] Files: every row has a vector folder/file/link icon; toolbar uses
      vector Up/Refresh icons; New folder / Upload buttons carry vector
      leading icons; row actions menu is a ⋮ vector icon.
- [ ] Tool form: path fields show a vector folder button, secrets a
      vector eye/eye-off. No 📁👁 anywhere.
- [ ] Copy buttons (job ids) show a vector copy icon.

### NavigationBar (M3)
- [ ] Bar is pure black, no elevation tint, thin top border.
- [ ] Labels are small (11 sp), icon 24 dp.
- [ ] Switching tabs animates the pill indicator between icons.

### Tool form
- [ ] Field labels sit ON the field outline and float up when typing.
- [ ] Field fill is near-black; focus border turns ice-cyan.
- [ ] Enum args render as chips; the selected chip is cyan-tinted.
- [ ] Helper/error text sits directly under its field (4 dp).
- [ ] A full-width ice-cyan RUN button stays pinned at the bottom —
      visible WITHOUT scrolling even on long forms; label is bold
      black. Save-recipe star is in the top bar.

### Chains empty state
- [ ] With no pipelines: headline "Automate Multi-Step Pipelines",
      subtext "Chain Termux tools into reusable workflows with zero
      shell scripting.", three vector node tiles (Ingest → Transform →
      Output) joined by connector lines, CTA "Create First Pipeline".

### Craft
- [ ] Cards have uniform 16 dp inner padding everywhere (no tight
      cards, no double padding).
- [ ] Metadata lines are muted slate; titles are 14–16 sp semibold.
- [ ] Status pills (Home) use a small colored dot + text, not glyphs.

### Regression invariants (unchanged behavior)
- [ ] Int/float args send native numbers (check preview strip).
- [ ] Errors appear only after touching a field.
- [ ] Unsafe tools still show the RUN confirmation dialog.
- [ ] Dotfiles hidden by default; Files details sheet works.
- [ ] Recipes/chains persist across app restart.

---

## Round 5 — engine fix + AI round (2026-09-11)

### Auto-created output dirs (bridge v0.4.2)
- [ ] Update the backend first (Diagnostics → reinstall / installer) so
      the phone runs bridge 0.4.2 (`Diagnostics → Bridge` shows the
      version) and `~/.trmx/bin/trmx-ai` exists.
- [ ] Delete `~/downloads` (Files → long-press → delete), then run
      yt-dlp with the default output folder: the job must start and the
      folder must exist afterwards (check in Files). No 404 toast.
- [ ] Same for aria2c (Fast Downloader).
- [ ] http-server still REFUSES a missing folder (that's an input dir):
      point it at `~/nope` → 404 PATH_NOT_FOUND with field `dir`.
- [ ] Tool form shows "(folder is created if missing)" under the
      yt-dlp outdir field.

### AI Schema Builder
- [ ] Set up a backend once: `pkg install ollama` (+ pull a model), or
      write `~/.trmx/ai.json` pointing `command` at another CLI.
- [ ] Toolbox → "AI Schema Builder" card → screen with description
      field, example chips, sticky Generate button.
- [ ] Without trmx-ai installed the screen shows the red hint card
      (install it to see both states).
- [ ] Type e.g. "convert webp images to png with cwebp" → Generate →
      job runs (live output under Jobs) → on completion the screen
      shows "schema installed — toolbox rescanned" and the new tool
      appears in the Toolbox with the confirm tier (running it asks
      for confirmation).
- [ ] Trashing the description (e.g. "make it do everything") either
      produces a rejected-output error (exit 4, visible in job output)
      or a valid schema — never a crash, never a file written on
      rejection.
- [ ] Generating the same tool twice → "refusing to overwrite" in the
      job output.

### Regression invariants (unchanged behavior)
- [ ] Bridge still loopback-only; token auth unchanged.
- [ ] Existing tools submit exactly as before (argv preview identical).
- [ ] JVM + python suites green in CI (run for this commit).

---

## Round 6 — trmx-ai dual backend (2026-09-11)

Update the backend first so the phone runs trmx-ai 0.2.0
(Diagnostics → reinstall / installer; `~/.trmx/bin/trmx-ai --version`).

### Config template + offline mode (regression)
- [ ] Delete `~/.trmx/ai.json`, run any AI build → the file is recreated
      as a documented template (mode cli) with 0600 permissions; the
      notice points at docs/AI-CONFIG.md.
- [ ] cli mode (ollama) still works end-to-end as in round 5.

### Cloud mode (http_api)
- [ ] Edit `~/.trmx/ai.json`: set `"mode": "http_api"` and fill provider
      + model (+ `api_key` or export the provider's key variable).
      Cheapest try: a Groq free-tier key, or a Gemini key from AI Studio.
- [ ] Toolbox → AI Schema Builder → describe a tool → Generate → job
      completes, "schema installed — toolbox rescanned", the new tool
      appears (confirm tier).
- [ ] Turn off Wi-Fi/data → Generate → loud, clear network error in the
      job output (exit 3), no file written.
- [ ] Remove the key (and unset the env var) → Generate → loud "no API
      key" error naming the config field; the key never appears anywhere
      in job output or errors.
- [ ] Bad provider typo ("gemni") → error lists the valid providers.
- [ ] Same protections as round 5: garbage description → exit 4 rejected
      output; duplicate generation → refused overwrite (exit 5).

### Switching
- [ ] Flip `mode` back to `cli` → offline builds work again without any
      other change.

---

## Round 7 — Services milestone: protocol 1.1 (2026-09-11)

Update the backend first (Diagnostics → reinstall / installer) so the
phone runs bridge v0.5.0 (`Diagnostics → Bridge` version).

### Create a service
- [ ] Toolbox → Local HTTP Server → fill the form (default port 8000,
      folder ~) → tap the SERVER (Dns) icon in the form header →
      "saved as service" notice.
- [ ] Services tab (5th nav item): the service card appears, state
      "stopped — never started", autostart chip off.

### Lifecycle
- [ ] Start → state running (cyan dot), a "view job" link appears;
      opening it shows the live server output; the server actually
      serves (open http://127.0.0.1:8000 in a browser on the phone).
- [ ] Start again while running → clean "already running" error, no
      duplicate job.
- [ ] Stop → state settles on stopped · last run cancelled.
- [ ] Stop again while stopped → still fine (idempotent), no error.
- [ ] Restart while running → new job id, still running.
- [ ] Long-press/back navigation: leaving the tab and returning keeps
      live state (job.updated correlation).

### Autostart
- [ ] Toggle the autostart chip on → restart the BRIDGE (Diagnostics →
      Restart) → after reconnect the service is running again without
      manual start.
- [ ] Toggle off → bridge restart → stays stopped.
- [ ] `~/.trmx/services/<id>.json` on the phone reflects the flag.

### Delete
- [ ] Delete while running → refused with a clear message.
- [ ] Stop, then delete → confirm dialog → card gone; the tool itself
      still works from the Toolbox.

### Older bridge (honesty)
- [ ] If pointed at a pre-0.5.0 bridge (or before updating): the
      Services tab shows the "Bridge update needed" card, no crash.

### Regression invariants (unchanged behavior)
- [ ] Normal tool jobs, chains, AI builder, files all work as before.
- [ ] Both python + JVM suites green in CI for this commit.
