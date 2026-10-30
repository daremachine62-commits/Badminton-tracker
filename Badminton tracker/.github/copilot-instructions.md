# Coding instructions

- Before making changes, check the current Git branch and use `improvising`.
  If the worktree has changes that prevent switching, or the branch is already
  checked out in another worktree, do not force a switch; report the situation
  instead.
- This is a native Android app written in Kotlin with Jetpack Compose. Keep
  changes consistent with the existing Compose and AndroidX patterns.
- Read the affected code and preserve existing camera, recording, history, and
  video-analysis behavior. Prefer focused changes over broad rewrites.
- Keep expensive video/frame processing off the main thread and respect
  CameraX lifecycle ownership.
- Do not describe experimental motion or court-line estimates as validated
  shuttle, player, or hit detection. Preserve the limitations documented in
  `README.md`.
- Update `README.md` when user-visible app behavior or documented limitations
  change. Add a dated entry to `WORK_LOG.md` for substantial user-requested work.
- Validate Android changes from the project directory with
  `.\gradlew.bat assembleDebug`. Do not add dependencies unless the change
  requires them.
- Do not commit local SDK paths, credentials, generated build output, or video
  files.
