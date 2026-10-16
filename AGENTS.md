# Lumen (Komikku fork) — AI agent guide

Lumen is an Android manga reader (Komikku fork) forked from **Mihon** + **TachiyomiSY**: minSdk 26, targetSdk 36, JVM target 17 / Kotlin, `applicationId` `app.lumen` (debug `app.lumen.dev`), built with JDK 21.
Only what an agent must get right is here; lookup material is in the reference doc below.

| Upstream | Code marker | Strings |
|----------|-------------|---------|
| Mihon | none (base code) | `MR` (`i18n/`) |
| TachiyomiSY | `// SY -->` … `// SY <--` (legacy `// EXH` blocks also come from SY) | `SYMR` (`i18n-sy/`) |
| **Lumen** (this repo) | `// KMK -->` … `// KMK <--` | `KMR` (`i18n-kmk/`) |

- Machine-specific setup (JDK switch, Android SDK, git remotes): [`AGENTS.local.md`](./AGENTS.local.md) — private, not committed.
- Full upstream reference (tech stack, module layout, architecture, build flags & CI, tests, key files): [`docs/agents-md-full-reference.md`](docs/agents-md-full-reference.md); `AGENTS.zh.md` is its Chinese translation (read-only).

## Mandatory rules for AI agents

**Read this section before every change.** These rules override shortcuts (e.g. copying nearby `MR` imports or only running `compileDebugKotlin`).

### Git

- Work on a **feature branch** (`git checkout -b <type>/<short-description>`); never commit directly to `master` / `main` unless the user explicitly asks.
- Commit locally when the work is ready. **Never push unless the user explicitly asks** — check `git branch --show-current` first, then report "committed, not pushed".

### Fork markers

Every Komikku addition or modification to existing code **must** be wrapped:

```kotlin
// KMK -->
// your code here
// KMK <--
```

- Use `// KMK` for all new code; never add new `// SY` / `// EXH` blocks.
- Keep upstream `// SY` / `// EXH` blocks intact — when changing code inside one, nest a `// KMK` block.
- Markers apply to `.sq` queries and mappers too.

### Internationalization

- **All new strings go to `i18n-kmk/`** (`import tachiyomi.i18n.kmk.KMR`). `i18n/` (`MR`) and `i18n-sy/` (`SYMR`) are upstream-owned: reuse existing entries, only add or change them when syncing upstream.
- New entries belong **only** in the `base/` folder (`i18n-kmk/src/commonMain/moko-resources/base/`). **Never** edit non-`base` locale files in any of the three i18n modules — [Weblate](https://hosted.weblate.org/engage/komikku-app/) owns translations.
- Inside a `// KMK` block or for Komikku-only behavior, default to `KMR` even if nearby code imports `MR`.
- **Self-check before finishing:** `git diff` must not add `<string name="…">` / `<plurals name="…">` entries under non-`base` locales.

<!-- KMK -->
**Lumen fork 的唯一例外：`i18n/src/…/base/strings.xml` 的 `app_name`。** 应用显示名没有其它落点（`MR.strings.app_name` 与 Android 资源同名，改别处会 merge 冲突），所以这一条直接改在上游 base 里；其余上游字符串一律不改——需要不同措辞时新增 `i18n-kmk` 的条目并在调用点改用 `KMR`（例：`ExtensionDetailsScreen` 的冗余扩展提示）。
<!-- /KMK -->

### Formatting & build verification

**“Build passes” is not enough.** After Kotlin/XML edits, run **in this order**:

```bash
./gradlew spotlessApply    # fix formatting
./gradlew spotlessCheck    # must pass (CI gate) — never skip
./gradlew assembleDebug    # or :app:compileDebugKotlin for a faster compile-only check
```

Spotless + ktlint config: `buildSrc/src/main/kotlin/mihon.code.lint.gradle.kts` (trim trailing whitespace, end with newline; non-base i18n locales and `**/build/**` excluded). Use `compileDebugKotlin` only when the user asked for a quick check — Spotless still applies. `./gradlew` is the Git Bash spelling; PowerShell 5.1 needs `.\gradlew.bat` and has no `&&` (see `AGENTS.local.md`).

## Adding a Komikku feature

1. **Interactor:** `domain/src/main/java/tachiyomi/domain/<area>/interactor/MyFeature.kt`
2. **DI:** `addFactory { MyFeature(get()) }` in `KMKDomainModule.kt` (or a `// KMK` block in `DomainModule.kt`)
3. **UI:** ScreenModel + Screen under `app/src/main/java/eu/kanade/tachiyomi/ui/`
4. **Strings:** `i18n-kmk/…/base/strings.xml`, referenced via `KMR`
5. **Markers:** wrap edits to existing files in `// KMK -->` / `// KMK <--`

Reference: `domain/src/main/java/tachiyomi/domain/category/interactor/HideCategory.kt` + its registration in `DomainModule.kt`. DI / navigation / coroutine / database / logging conventions → [`docs/agents-md-full-reference.md`](docs/agents-md-full-reference.md).

## Common issues

- **Gradle OOM** – the daemon uses `-Xmx4g` (`gradle.properties`); run `./gradlew --stop` and retry.
- **Spotless / SQLDelight failures** – `spotlessApply` then `spotlessCheck`; after `.sq` / `.sqm` changes run `./gradlew :data:generateSqlDelightInterface`.
- **Missing `google-services.json` / `client_secrets.json`** – CI secrets; builds without `-Pinclude-telemetry` don't need them.

## Upstream sync & cherry-pick log

`cherrypick_log.md` (repo root) lists upstream commits since 2024-01-01 with their cherry-pick status (`mihon/main` + `tachiyomiSY/master`; Renovate excluded). Generated and marked by `.claude/skills/cherrypick-log/update_cherrypick_log.py`; rules and porting guide in `.claude/skills/cherrypick-log/README.md`. Never hand-edit columns other than Status/Notes — use the script's `mark` command. Upstream remotes are **not configured on this machine** (see `AGENTS.local.md`).

## Pointers

- `AGENTS.local.md` — machine-specific setup, JDK / SDK paths, git remotes, credentials.
- `docs/agents-md-full-reference.md` — full upstream reference (tech stack, modules, architecture, build & CI, tests, key files).
- `C:\Users\godis\.dsh\AGENTS.md` — global rules for this machine.
