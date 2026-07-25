# RikkaHub 2.0 Agent Notes

## AI Knowledge Routing

- Start with `项目地图.md` for architecture and modification anchors.
- Read only the matching page under `功能列表/`, then `项目规则.md`, before opening broad source trees.
- Use `任务进展/` to distinguish merged work, active workspace state, and known follow-ups.
- Treat current source and Gradle configuration as authoritative when documentation drifts.

## Project Shape

- Android/Kotlin project, root project name: `rikkahub`.
- Main app module: `app`.
- Included modules: `highlight`, `ai`, `search`, `speech`, `common`, `document`, `web`, `material3`, `usage-tracker`, `weather`.
- User-facing fork/release repo: `https://github.com/innna327-source/rikkahub-Jude`.

## User Preferences

- Keep changes narrow and grounded in the current repo. Do not rewrite unrelated code.
- Do not commit, build APKs, or upload releases unless the user explicitly asks.
- After code changes, do not build/package APKs by default. Run compile/tests for verification, but only package an APK when the user explicitly asks to build/package after the change.
- When the user explicitly asks to commit, push the current branch to GitHub after a successful commit.
- When the user explicitly asks to build/package/update an APK, default to the universal debug APK workflow unless they ask for another variant:
  `E:/rikkahub2.0/app/build/outputs/apk/debug/app-universal-debug.apk`
- When the user asks to build/package/update an APK, do not run unit tests as a separate pre-step unless the user explicitly asks for tests in that same turn. The packaging build's own compilation checks are enough for a packaging request.
- The universal packaging helper also creates a timestamped copy in the same folder:
  `RikkaHub-universal-debug-YYYYMMDD-HHMMSS.apk`
- Avoid adding other APK outputs or build artifacts to git.

## Common Commands

- Check status:
  `git status --short`
- Compile app Kotlin:
  `.\gradlew :app:compileDebugKotlin`
- Run app unit tests:
  `.\gradlew :app:testDebugUnitTest`
- Build the default universal debug APK and timestamped copy:
  `powershell -ExecutionPolicy Bypass -File scripts\build-universal-debug-apk.ps1`
- Force rebuild the default universal debug APK and timestamped copy:
  `powershell -ExecutionPolicy Bypass -File scripts\build-universal-debug-apk.ps1 -RerunTasks`
- Stop Gradle daemons if files are locked or builds keep running:
  `.\gradlew --stop`

## Important Areas

- Backup and restore:
  `app/src/main/java/me/rerere/rikkahub/data/sync/`
- S3 backup/restore:
  `app/src/main/java/me/rerere/rikkahub/data/sync/S3Sync.kt`
- WebDAV backup/restore:
  `app/src/main/java/me/rerere/rikkahub/data/sync/webdav/WebDavSync.kt`
- Settings JSON migrations:
  `app/src/main/java/me/rerere/rikkahub/data/datastore/migration/`
- Local file folders used by backups:
  `app/src/main/java/me/rerere/rikkahub/data/files/FilesManager.kt`
- Usage reminder service:
  `app/src/main/java/me/rerere/rikkahub/service/UsageReminderService.kt`
- Usage tracker UI/models:
  `usage-tracker/src/main/java/me/rerere/usagetracker/`
- Request logging:
  `app/src/main/java/me/rerere/rikkahub/network/RequestLoggingInterceptor.kt`
  `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/LogPage.kt`
  `app/src/main/java/me/rerere/rikkahub/utils/Logging.kt`

## Recent Project Context

- App update checks should prefer the `app-universal-debug.apk` asset and only fall back to `app-arm64-v8a-debug.apk` if the universal asset is absent.
- Backup restore has compatibility work for newer upstream RikkaHub exports:
  - database version 24 / upstream workspace tables
  - nullable top-level settings JSON fields
  - restored local image/background/avatar paths
- Backups should include local image files as well as existing uploaded files, fonts, and skills.
- Usage reminder messages are stored in settings config and can be imported from JSON; the old bundled asset JSON was removed.

## Build Output Caution

Normal `.\gradlew :app:assembleDebug` builds all configured debug split APK outputs, including universal and x86_64 variants. Use `scripts\build-universal-debug-apk.ps1` for the default package/update request because it refreshes the stable universal APK and creates a timestamped copy:

`E:/rikkahub2.0/app/build/outputs/apk/debug/app-universal-debug.apk`

`E:/rikkahub2.0/app/build/outputs/apk/debug/RikkaHub-universal-debug-YYYYMMDD-HHMMSS.apk`

Use `scripts\build-arm64-debug-apk.ps1` only when the user explicitly asks for the arm64-only variant:

`E:/rikkahub2.0/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`

## Git Caution

- Inspect `git status --short` before editing and before committing.
- Stage only files relevant to the user's request.
- If unrelated files are dirty, leave them alone and mention them if needed.
