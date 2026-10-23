# Lumen（Komikku 的 fork）— 开发约定

> 跨机器通用规则见全局 `C:\Users\godis\.dsh\AGENTS.md`，本文件不重复。
> 本机专属（JDK 21 切换、SDK、remote、`AGENTS.md` 合并保护）见同目录 `AGENTS.local.md`，不入库。
> 本文件只写「必须怎么做」；查阅型内容见 §7 指针。

## 1. 这个项目是什么

Lumen 是基于 **Mihon** + **TachiyomiSY** 的安卓漫画阅读器（Komikku fork）：Kotlin + Jetpack Compose，`applicationId` 为 `app.lumen`（debug 后缀 `.dev`），构建需 JDK 21。
版本值（minSdk / targetSdk / JVM 目标等）以 `buildSrc/src/main/kotlin/mihon/buildlogic/AndroidConfig.kt` 为准，本文件不写死。

## 2. 常用命令（可直接复制执行）

| 目的 | 命令 |
| --- | --- |
| 修格式 | `./gradlew spotlessApply` |
| 格式检查（CI 门禁） | `./gradlew spotlessCheck` |
| 仅编译检查 | `./gradlew :app:compileDebugKotlin` |
| 打 debug 包 | `./gradlew assembleDebug` |
| 改过 `.sq`/`.sqm` 后 | `./gradlew :data:generateSqlDelightInterface` |
| OOM 时停 daemon | `./gradlew --stop` |
| 开特性分支 | `git checkout -b <type>/<short-description>` |

## 3. 硬约束（必须 / 禁止）

- **Git**：每个任务开特性分支；用户没明确要求，**绝不**直接提交默认分支 `lumen`（上游叫 `master`/`main`，本 fork 已改名，别照上游文档找）。可本地提交；推送口径见全局 §3。
- **KMK 标记**：对既有代码的每处 Komikku 新增或改动都必须用 `// KMK -->` … `// KMK <--` 包住；新代码一律 `// KMK`，不要新增 `// SY` / `// EXH` 块。
- 上游 `// SY` / `// EXH` 块保持原样；要在里面改就嵌一层 `// KMK`。`.sq` 查询与 mapper 同样适用。
- **i18n**：新字符串一律进 `i18n-kmk/`（`import tachiyomi.i18n.kmk.KMR`）；`i18n/`（`MR`）与 `i18n-sy/`（`SYMR`）属上游，只在同步上游时增改。
- 新条目只写 `base/` 目录；三个 i18n 模块的非 `base` 语言文件一律不动（Weblate 管翻译）。`// KMK` 块内或 Komikku 独有行为，即使邻近代码 import 了 `MR`，也用 `KMR`。
- 完工自检：`git diff` 不得在非 `base` 语言下新增 `<string>` / `<plurals>` 条目。
- **唯一例外**：`i18n/src/…/base/strings.xml` 的 `app_name` 直接改在上游 base 里；其余上游字符串一律不改——需要不同措辞时新增 `i18n-kmk` 条目并在调用点改用 `KMR`。
- **格式化与构建验证**按顺序跑，缺一不可：`spotlessApply` → `spotlessCheck`（CI 门禁）→ `assembleDebug`。只有用户要求快速检查时才换 `:app:compileDebugKotlin`，Spotless 照跑。
- Spotless + ktlint 配置在 `buildSrc/src/main/kotlin/mihon.code.lint.gradle.kts`。

## 4. 架构边界与因果（从代码看不出为什么）

| 上游 | 代码标记 | 字符串 |
| --- | --- | --- |
| Mihon | 无（基础代码） | `MR`（`i18n/`） |
| TachiyomiSY | `// SY -->` … `// SY <--`（旧 `// EXH` 同源） | `SYMR`（`i18n-sy/`） |
| **Lumen**（本仓库） | `// KMK -->` … `// KMK <--` | `KMR`（`i18n-kmk/`） |

- **为什么要标记**：fork 要持续 cherry-pick 上游，标记让归属与冲突一眼可辨；所以新增只加 `KMK`，上游块不混入。
- **为什么 `app_name` 例外**：应用显示名没有其它落点（`MR.strings.app_name` 与 Android 资源同名，改别处会 merge 冲突）。
- **为什么版本值指向 `AndroidConfig.kt`**：多模块共用同一份构建逻辑，文档里硬编码必漂移。
- **为什么 i18n 只动 `base/`**：非 `base` 语言由 Weblate 管理，本地改动会被下游覆盖。

## 5. 版本与发布

- 版本号唯一来源：`app/build.gradle.kts` 的 `lumenRevision` / `lumenVersionName`（`versionCode = 82 * 100 + lumenRevision`；debug 另加 `-<commitCount>` 后缀）——改版本只改这里。
- 发版：CI `.github/workflows/build_release.yml` 由 `v*` tag 触发；本地产物名为 `Lumen[-<ABI>]-<versionName>.apk`，由 `assemble*` 后复制，**不移动** AGP 原产物。

## 6. 已知坑（症状 → 原因 → 对策）

- Gradle OOM / 构建卡死 → daemon 默认堆不够 → `gradle.properties` 用 `-Xmx4g`；先 `./gradlew --stop` 再试。
- Spotless 或 SQLDelight 报错 → 格式/生成代码没更新 → 先 `spotlessApply` 再 `spotlessCheck`；改过 `.sq`/`.sqm` 后跑 `:data:generateSqlDelightInterface`。
- 缺 `google-services.json` / `client_secrets.json` → 属 CI 机密，本地没有 → 不加 `-Pinclude-telemetry` 的构建不需要它们。
- 提交到 `lumen` 分支 → 上游文档写 `master`/`main`，容易跟错 → 动手前 `git branch --show-current`。
- 构建出的包只有 1.5 MB、装不上 → 把 AGP 原产物移走后再构建，增量打包只剩变化的 dex（实测 68 MB → 1.5 MB）→ 只复制、不移动 AGP 产物。

## 7. 指针

- 全局规则：`C:\Users\godis\.dsh\AGENTS.md`；本机专属：`./AGENTS.local.md`（JDK/SDK、remote、合并保护）。
- 上游全文参考（技术栈、模块布局、架构、构建与 CI、测试、关键文件、新增功能五步法、常见坑、cherry-pick）：`docs/agents-md-full-reference.md`；中文全文 `AGENTS.zh.md`（只读）。
- 相关技能：上下文治理 `dsh-context-hygiene`；装/排查技能见 `dsh-tooling-setup`。
