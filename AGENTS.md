# Cadence — agent rules

## Git workflow
- Before EVERY `git commit` or `git push`, run `/ponytail-review` and fix (or explicitly waive) its findings. The PreToolUse hook (`.zcode/config.json` → `.zcode/hooks/ponytail-gate.sh`) blocks commit/push until the review marker `.git/ponytail_ok` is fresh (< 30 min).
- Commit often — one concern per commit, conventional lowercase style (`feat:`, `fix:`, `ui:`, `data:`, `chore:`), matching existing history.

## Project
- Android app `com.cadence.music` — Jetpack Compose, single `:app` module, Room, Media3, Coil, raw HttpURLConnection. Build with `./gradlew assembleDebug`.
