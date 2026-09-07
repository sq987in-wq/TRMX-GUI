# CI activation (one manual step)

The Android workflow is ready but is **not yet active**: the automation token
used for this branch cannot create `.github/workflows/` files (GitHub requires
`workflows` permission for that path — a sensible safety rule). Until a human
adds it, the Android app has no compile gate (the dev sandbox has no
JDK/Android SDK — ADR-006).

**To activate (2 minutes, any one of):**

1. **GitHub web UI** — on branch `arena/01a07bc5-trmx-gui` (or `main` after
   merge): Add file → Create new file → name it
   `.github/workflows/android.yml` → paste the content of
   [`android-workflow.yml`](android-workflow.yml) → commit.
2. **Locally** —
   ```sh
   git fetch && git checkout arena/01a07bc5-trmx-gui
   mkdir -p .github/workflows && cp docs/ci/android-workflow.yml .github/workflows/android.yml
   git add .github/workflows/android.yml && git commit -m "ci: android build" && git push
   ```

Then every push (including future pushes from this workspace) builds the app:
assembleDebug + JVM unit tests + spec conformance, APK uploaded as an
artifact under the repo's **Actions** tab.
