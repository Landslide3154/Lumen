# Lumen（Komikku 的 fork）— AI 代理指南

Lumen 是基于 **Mihon** + **TachiyomiSY** 的安卓漫画阅读器（Komikku fork）：Kotlin，`applicationId` = `app.lumen`（debug `app.lumen.dev`），构建用 JDK 21；minSdk / targetSdk / JVM 目标等版本值以 `buildSrc/src/main/kotlin/mihon/buildlogic/AndroidConfig.kt` 为准（别在这儿写死）。
本文件只写「必须怎么做」；查阅型内容见文末指针。

| 上游 | 代码标记 | 字符串 |
| --- | --- | --- |
| Mihon | 无（基础代码） | `MR`（`i18n/`） |
| TachiyomiSY | `// SY -->` … `// SY <--`（旧 `// EXH` 同源） | `SYMR`（`i18n-sy/`） |
| **Lumen**（本仓库） | `// KMK -->` … `// KMK <--` | `KMR`（`i18n-kmk/`） |

## 强制规则（每次改动前必读）

优先于任何「照抄旁边代码」的捷径（如照抄邻近的 `MR` import、只跑 `compileDebugKotlin`）。

**Git**

- 每个任务开特性分支（`git checkout -b <type>/<short-description>`）；用户没明确要求，**绝不**直接提交默认分支 **`lumen`**（上游叫 `master` / `main`，本 fork 已改名，别照着上游文档找 `master`）。
- 可本地提交；**不要主动 push**——真要推之前先 `git branch --show-current`（口径见全局 `AGENTS.md` 的「Git 与 GitHub」节）。

**KMK 标记**：对既有代码的每处 Komikku 新增或改动都必须包住：

```kotlin
// KMK -->
// your code here
// KMK <--
```

- 新代码一律用 `// KMK`；不要新增 `// SY` / `// EXH` 块。
- 上游 `// SY` / `// EXH` 块保持原样；要在里面改就嵌一层 `// KMK`。
- `.sq` 查询与 mapper 同样适用。

**国际化（字符串）**

- 新字符串一律进 `i18n-kmk/`（`import tachiyomi.i18n.kmk.KMR`）；`i18n/`（`MR`）与 `i18n-sy/`（`SYMR`）属上游，只在同步上游时增改。
- 新条目只写 `base/` 目录；三个 i18n 模块的非 `base` 语言文件一律不动（[Weblate](https://hosted.weblate.org/engage/komikku-app/) 管翻译）。
- `// KMK` 块内或 Komikku 独有行为，即使邻近代码 import 了 `MR` 也用 `KMR`。
- 完工自检：`git diff` 不得在非 `base` 语言下新增 `<string>` / `<plurals>` 条目。

<!-- KMK -->
**Lumen fork 的唯一例外：`i18n/src/…/base/strings.xml` 的 `app_name`。** 应用显示名没有其它落点（`MR.strings.app_name` 与 Android 资源同名，改别处会 merge 冲突），故直接改在上游 base 里；其余上游字符串一律不改——需要不同措辞时新增 `i18n-kmk` 条目并在调用点改用 `KMR`。
<!-- /KMK -->

**格式化与构建验证**：改完 Kotlin / XML 按顺序跑，缺一不可：

```bash
./gradlew spotlessApply    # 修格式
./gradlew spotlessCheck    # 必须过（CI 门禁）
./gradlew assembleDebug    # 或 :app:compileDebugKotlin 仅编译检查
```

Spotless + ktlint 配置在 `buildSrc/src/main/kotlin/mihon.code.lint.gradle.kts`。只有用户要求快速检查时才换 `compileDebugKotlin`；Spotless 照跑。

## 常见坑

- **Gradle OOM**：daemon 用 `-Xmx4g`（`gradle.properties`）；跑 `./gradlew --stop` 再试。
- **Spotless / SQLDelight 报错**：先 `spotlessApply` 再 `spotlessCheck`；改过 `.sq` / `.sqm` 后跑 `./gradlew :data:generateSqlDelightInterface`。
- **缺 `google-services.json` / `client_secrets.json`**：属 CI 机密；不加 `-Pinclude-telemetry` 的构建不需要它们。

## 指针

- 本机专属（JDK / SDK、git remote、凭据、**AGENTS.md 合并保护**）→ [`AGENTS.local.md`](./AGENTS.local.md)，不入库。
- 上游全文参考（技术栈、模块布局、架构、构建与 CI、测试、关键文件、新增功能五步法、常见坑、cherry-pick 说明）→ [`docs/agents-md-full-reference.md`](docs/agents-md-full-reference.md)；`AGENTS.zh.md` 是它的中文全文（只读）。
- 本机全局规则 → `C:\Users\godis\.dsh\AGENTS.md`。
