# Lumen 增量更新 + 品牌改造 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 subagent-driven-development（推荐）或 executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 把 Lumen（Komikku 的 fork）改造成「整库更新时只向 Komga 拉取有变化的作品」，并完成改名（Lumen）与换图标。

**架构：** 在 `LibraryUpdateJob.addMangaToQueue()` 的官方筛选之后插入一层「Komga 变更集合过滤」：读 Komga 扩展的配置 → 查询 `/api/v1/series/latest` → 用「水位线」（上次成功更新时看到的最大 `lastModified`）判断哪些系列有变化 → 不在集合里的 Komga 条目从更新队列中移除（等于不请求，因此不会触发删章节）。查询失败按策略回退全量。改名与换图标独立于功能，作为第一个交付物先出 APK。

**技术栈：** Kotlin 2.x / Jetpack Compose（设置界面）/ OkHttp 5 + kotlinx.serialization（Komga 客户端）/ Injekt DI / JUnit Jupiter + Kotest（单测）/ Gradle 9.3.1 + JDK 21。

**规格：** `docs/superpowers/specs/2026-10-12-lumen-incremental-design.md`（执行者必须同时读规格）

---

## 全局约束

- 仓库：`D:\code\Komikku`（`Landslide3154/Lumen`），当前分支 `feat/lumen-incremental-update`；**任何情况下不直接提交 master**。
- 对既有文件的每一处改动都用 `// KMK -->` / `// KMK <--` 包裹（XML 文件用 `<!-- KMK -->` / `<!-- KMK <-- -->`）；新增文件不需要标记。
- **新增字符串只能加到** `i18n-kmk/src/commonMain/moko-resources/base/strings.xml`（用 `KMR` 引用），名字全局唯一；中文写到 `app/src/main/res/values-zh-rCN/strings.xml` 覆盖。**绝不**手改 `i18n` / `i18n-kmk` / `i18n-sy` 的非 base 语言文件。
- 每次改完 Kotlin/XML 必须跑：`spotlessApply` → `spotlessCheck` → `testReleaseUnitTest` → `assemblePreview`，四项全绿才算完成。
- 本机跑 Gradle 前必须设 `JAVA_HOME=C:\Users\godis\.jdks\jdk21\jdk-21.0.12.1+1`（本机默认 JDK 25 不匹配）。
- 新增依赖一律不允许（测试也不许加 MockWebServer：用假 fetcher 代替）。
- 日志统一前缀 `Komga incremental:`，用 `logcat(LogPriority.INFO) { ... }`。
- 提交信息用 `type(scope): 中文简述`；每个任务结束提交一次。
- 不改：内部 `namespace`/代码包名、扩展仓库签名、`komikku://` 回调、上游 3 个 workflow 文件。

## 审查重点（Review Focus）

规格隐含、但没有任务测试覆盖到、且最可能伤到使用者的五类输入 —— 每条都已在本计划对应任务里安排了钉住它的测试：

| # | 输入 / 条件 | 正常人的期望 | 钉住它的任务 |
| --- | --- | --- | --- |
| 1 | `manga.url` 带查询串或尾斜杠（`…/series/ABC?page=1`） | 取到干净的 seriesId 或判为不可增量，**绝不**因为解析错而误跳过 | 任务 2 |
| 2 | 同一条 URL 里 seriesId 含 URL 编码字符（`%2F` 等） | 原样保留、不二次解码，匹配不上就是「有变化」 | 任务 2 |
| 3 | 同一个 Komga 地址被多个扩展（多个 sourceId）使用 | 只查一次，两边条目都能正确匹配 | 任务 7 |
| 4 | Komga 换了地址后水位线里残留旧地址 | 旧地址条目按「无水位线」处理（全量），不报错 | 任务 4、7 |
| 5 | `lastModified` 为 null 或缺字段（Komga 改版） | 判为无法比较 → 回退全量，不崩溃 | 任务 5 |

---

## 文件结构

**新增（增量功能，全部在 `app/src/main/java/eu/kanade/tachiyomi/data/library/komga/`）**

| 文件 | 职责 |
| --- | --- |
| `KomgaSeriesUrl.kt` | 纯函数：把 `manga.url` 解析成 `(baseUrl, seriesId)` |
| `KomgaSourceConfig.kt` | 读 `source_<id>` 偏好 → `KomgaSourceConfig`；纯函数 `from(map)` + Android 侧薄壳 |
| `KomgaWatermark.kt` | 水位线的解析 / 比较 / 编码解码（JSON） |
| `KomgaChangeSet.kt` | 分页收集与停止条件（纯逻辑，接受 `fetchPage` 闭包）+ DTO |
| `KomgaChangeSetClient.kt` | OkHttp 请求 `/api/v1/series/latest` 与 JSON 解析 |
| `KomgaSkipDecider.kt` | 纯函数：条目 × 服务器结果 × 策略 → 保留/跳过 |
| `KomgaIncrementalFilter.kt` | 编排：分组、去重、读配置、调用客户端与 decider |

**新增（测试，`app/src/test/java/eu/kanade/tachiyomi/data/library/komga/`）**
`KomgaSeriesUrlTest.kt`、`KomgaSourceConfigTest.kt`、`KomgaWatermarkTest.kt`、`KomgaChangeSetTest.kt`、`KomgaLatestResponseTest.kt`、`KomgaSkipDeciderTest.kt`、`LibraryUpdateModeTest.kt`

**新增（其它）**
`app/src/main/res/values-zh-rCN/strings.xml`、`.github/workflows/build-apk.yml`

**修改（既有文件）**
`domain/.../LibraryPreferences.kt`（偏好 + 枚举）、`app/.../LibraryUpdateJob.kt`（接入）、`app/.../SettingsLibraryScreen.kt`（3 项设置）、`app/.../presentation/updates/UpdatesUiItem.kt` + `UpdatesScreen.kt` + `ui/updates/UpdatesScreenModel.kt`（跳过数量一行）、`i18n-kmk/.../base/strings.xml`（9 条）、`i18n/.../base/strings.xml`（app_name）、`app/build.gradle.kts`（applicationId）、品牌相关的 17 个资源文件（见任务 1）。

---

## 阶段一：品牌改造（先出一个能装的 APK）

### 任务 1：改名 Lumen + 换图标 + 闪屏 + 通知强调色

**文件：**
- 修改：`i18n/src/commonMain/moko-resources/base/strings.xml:3`、`app/build.gradle.kts:29`、`i18n-kmk/src/commonMain/moko-resources/base/strings.xml`（`discord_app_description`）、`i18n-sy/src/commonMain/moko-resources/base/strings.xml`（`redundant_extension_message`）、`app/src/main/java/eu/kanade/tachiyomi/ui/manga/EditMangaDialog.kt:348`
- 修改（图标，17 个）：`app/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml`、`app/src/beta/res/drawable/ic_launcher_{background,foreground}.xml`、`app/src/debug/res/drawable/ic_launcher_{background,foreground}.xml`、`app/src/{main,beta,debug}/res/drawable/komikku.png`、`app/src/main/res/drawable/ic_splash.xml`、`app/src/main/res/drawable-night/ic_splash.xml`、`presentation-core/src/main/res/values{,-night}/colors.xml`、`app/src/{main,beta,debug}/res/values/colors.xml`

- [ ] **步骤 1：改显示名与包名**

`app_name` 的值 `Komikku` → `Lumen`（保留 `translatable="false"`）；`app/build.gradle.kts` 的 `applicationId = "app.komikku"` → `"app.lumen"`（`namespace` 不动）。

- [ ] **步骤 2：改 3 处硬编码旧名文案**

`i18n-kmk` 的 `discord_app_description`、`i18n-sy` 的 `redundant_extension_message` 里的 `Komikku` → `Lumen`；`EditMangaDialog.kt:348` 的 `Pictures/Komikku/Cover.jpg` → `Pictures/Lumen/Cover.jpg`。全部用 KMK 注释标记包裹。

- [ ] **步骤 3：用脚本从 SVG 生成三个矢量 drawable**

写一次性脚本（放素材目录 `lumen-icons/`，不进仓库）把 `lumen-final.svg` 的 4 个 polygon 转成 Android pathData：

- 背景层：432×432 满幅 `<path fillColor="#F7F3EC" pathData="M0,0h432v432h-432z"/>`
- 前景层：`<group android:translateX="84" android:translateY="84" android:scaleX="0.2578125" android:scaleY="0.2578125">`，内含：页 `#0B1622` → `<clip-path>` 到页 + 斜光带 `#FFFFFF` → 折角 `#FFFFFF`
- 单色层：同变换，页 + 折角合成一个「折角剪影」单路径（`#FFFFFF`）

**验证几何**：脚本必须断言「前景所有路径在 432 视口里的包围盒 = (84,84)-(348,348)」，即 264/432 = 0.6111，与素材 `lumen-final-adaptive-fg.png` 实测一致。

- [ ] **步骤 4：beta / debug 覆盖层与通知大图标**

`app/src/beta`、`app/src/debug` 的两层 drawable 用与 main 相同的内容覆盖（三套样式统一）；`app/src/{main,beta,debug}/res/drawable/komikku.png` 换成新标识（200×200，由 `lumen-final-1024.png` 缩放）。**不动** `app/src/main/res/mipmap/komikku.png`（扩展仓库徽标）。

- [ ] **步骤 5：闪屏与通知强调色**

`ic_splash.xml` / `drawable-night/ic_splash.xml` 指向新的 logo 矢量（日间墨色、夜间米白）；`presentation-core` 的 `@color/splash` → `#F7F3EC` / `#0B1622`；三个 `colors.xml` 的 `@color/ic_launcher` → `#0B1622`。

- [ ] **步骤 6：本机四项验证**

运行：`.\gradlew.bat spotlessApply spotlessCheck testReleaseUnitTest assemblePreview`
预期：BUILD SUCCESSFUL；产物在 `app/build/outputs/apk/preview/`。

- [ ] **步骤 7：Commit 并交付出 APK**

```bash
git add -A && git commit -m "feat(brand): 改名 Lumen、换图标与闪屏、通知强调色"
```

把 `app-universal-preview.apk` 路径给项目负责人安装验收（显示名 / 图标 / 与官方 Komikku 并存）。

---

## 阶段二：增量更新核心（TDD，每个任务先红后绿）

### 任务 2：Komga 条目解析

**文件：**
- 创建：`app/src/main/java/eu/kanade/tachiyomi/data/library/komga/KomgaSeriesUrl.kt`
- 测试：`app/src/test/java/eu/kanade/tachiyomi/data/library/komga/KomgaSeriesUrlTest.kt`

**接口：**

```kotlin
data class KomgaSeriesRef(val baseUrl: String, val seriesId: String)

fun parseKomgaSeriesUrl(url: String): KomgaSeriesRef?
```

实现要点：正则 `^(?<base>https?://.+?)/api/v1/series/(?<id>[^/?#]+)/?$`；`baseUrl` 去尾斜杠；**不**对 `seriesId` 做 URL 解码。

- [ ] **步骤 1：写失败的测试**

```kotlin
@Test fun `parses plain url`() =
    parseKomgaSeriesUrl("https://komga.example.com/api/v1/series/ABC123") shouldBe
        KomgaSeriesRef("https://komga.example.com", "ABC123")

@Test fun `keeps sub path and port in base`() =
    parseKomgaSeriesUrl("http://192.168.1.9:25600/komga/api/v1/series/S1") shouldBe
        KomgaSeriesRef("http://192.168.1.9:25600/komga", "S1")

@Test fun `strips trailing slash`() =
    parseKomgaSeriesUrl("https://h/api/v1/series/S1/") shouldBe KomgaSeriesRef("https://h", "S1")

@Test fun `ignores query and fragment`() =
    parseKomgaSeriesUrl("https://h/api/v1/series/S1?page=1#x") shouldBe KomgaSeriesRef("https://h", "S1")

@Test fun `keeps percent encoding as is`() =
    parseKomgaSeriesUrl("https://h/api/v1/series/A%2FB") shouldBe KomgaSeriesRef("https://h", "A%2FB")

@Test fun `rejects readlist url`() =
    parseKomgaSeriesUrl("https://h/api/v1/readlists/R1") shouldBe null

@Test fun `rejects other source url`() =
    parseKomgaSeriesUrl("https://mangadex.org/title/abc") shouldBe null

@Test fun `rejects blank malformed and id-less`() {
    parseKomgaSeriesUrl("") shouldBe null
    parseKomgaSeriesUrl("not a url") shouldBe null
    parseKomgaSeriesUrl("https://h/api/v1/series/") shouldBe null
}
```

- [ ] **步骤 2：运行验证失败**

运行：`.\gradlew.bat :app:testReleaseUnitTest --tests "*KomgaSeriesUrlTest"`
预期：FAIL（`parseKomgaSeriesUrl` 未定义 / 断言失败）

- [ ] **步骤 3：实现 `parseKomgaSeriesUrl`**

按上面的正则与规则实现；不匹配一律返回 null。

- [ ] **步骤 4：运行验证通过**（同步骤 2 命令，预期 PASS）

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/eu/kanade/tachiyomi/data/library/komga/KomgaSeriesUrl.kt app/src/test/java/eu/kanade/tachiyomi/data/library/komga/KomgaSeriesUrlTest.kt
git commit -m "feat(komga): 解析 Komga 作品网址"
```

### 任务 3：读取扩展配置

**文件：**
- 创建：`app/src/main/java/eu/kanade/tachiyomi/data/library/komga/KomgaSourceConfig.kt`
- 测试：`app/src/test/java/eu/kanade/tachiyomi/data/library/komga/KomgaSourceConfigTest.kt`

**接口：**

```kotlin
data class KomgaSourceConfig(val address: String, val apiKey: String, val username: String, val password: String) {
    val hasApiKey: Boolean get() = apiKey.isNotBlank()
    val hasBasicAuth: Boolean get() = !hasApiKey && username.isNotBlank()

    companion object {
        const val KEY_ADDRESS = "Address"
        const val KEY_API_KEY = "API key"
        const val KEY_USERNAME = "Username"
        const val KEY_PASSWORD = "Password"
        fun from(prefs: Map<String, String?>): KomgaSourceConfig?
    }
}

fun readKomgaSourceConfig(context: Context, sourceId: Long): KomgaSourceConfig?
```

实现要点：`from` 取 4 个键（缺省空串），`address` 去尾斜杠，为空则返回 null；`readKomgaSourceConfig` 读 `context.getSharedPreferences("source_$sourceId", Context.MODE_PRIVATE)` 并转成 map 交给 `from`（键集合固定为这 4 个）。

- [ ] **步骤 1：写失败的测试**：4 个键正确映射；`Address` 缺失/空白 → null；`Address` 带尾斜杠被去掉；多余键被忽略；只有 API key 时 `hasApiKey=true`、`hasBasicAuth=false`。

- [ ] **步骤 2：运行验证失败**（同任务 2 的运行方式，换成 `*KomgaSourceConfigTest`）

- [ ] **步骤 3：实现 `KomgaSourceConfig` 与 `readKomgaSourceConfig`**

- [ ] **步骤 4：运行验证通过**

- [ ] **步骤 5：Commit**：`feat(komga): 读取 Komga 扩展配置`

### 任务 4：水位线（解析 / 比较 / 存取）

**文件：**
- 创建：`app/src/main/java/eu/kanade/tachiyomi/data/library/komga/KomgaWatermark.kt`
- 测试：`app/src/test/java/eu/kanade/tachiyomi/data/library/komga/KomgaWatermarkTest.kt`

**接口：**

```kotlin
object KomgaWatermarks {
    fun parseLastModified(raw: String?): LocalDateTime?
    fun isChanged(lastModified: String?, watermark: LocalDateTime?): Boolean
    fun isBackwards(maxSeen: String?, watermark: LocalDateTime?): Boolean
    fun decode(json: String?): Map<String, String>   // baseUrl -> lastModified 原样串
    fun encode(map: Map<String, String>): String
}
```

规则：`parseLastModified` 先 `removeSuffix("Z")` 再 `LocalDateTime.parse`（兼容带/不带小数秒），失败返回 null；`isChanged` 在「水位线为 null」或「任一侧解析失败」时返回 **true**（宁可多拉不可漏）；`isBackwards` 只在两侧都能解析且 `maxSeen < watermark` 时为 true。

> **与规格的差异（已确认的细化）**：规格 §6.4-3 写「任一侧解析失败 → 该服务器回退全量」。本计划实现为「**该条目**视为有变化（保留）」。理由：字段格式若真的变了，会导致所有条目都解析失败，效果等价于全量；而按条目处理不会被一条脏数据拖垮整个服务器。此差异记录在此，实现者按本计划执行。

- [ ] **步骤 1：写失败的测试**

```kotlin
@Test fun `parses with and without fractional seconds`() {
    KomgaWatermarks.parseLastModified("2026-10-12T02:11:21Z") shouldBe LocalDateTime.of(2026, 10, 12, 2, 11, 21)
    KomgaWatermarks.parseLastModified("2026-10-12T02:11:21.500Z") shouldBe
        LocalDateTime.of(2026, 10, 12, 2, 11, 21, 500_000_000)
}

@Test fun `returns null for null blank or garbage`() {
    KomgaWatermarks.parseLastModified(null) shouldBe null
    KomgaWatermarks.parseLastModified("") shouldBe null
    KomgaWatermarks.parseLastModified("yesterday") shouldBe null
}

@Test fun `changed when equal greater or watermark missing`() {
    val wm = LocalDateTime.of(2026, 10, 12, 2, 0, 0)
    KomgaWatermarks.isChanged("2026-10-12T02:00:00Z", wm) shouldBe true   // 等于也算
    KomgaWatermarks.isChanged("2026-10-12T03:00:00Z", wm) shouldBe true
    KomgaWatermarks.isChanged("2026-10-12T01:00:00Z", wm) shouldBe false
    KomgaWatermarks.isChanged("2026-10-12T01:00:00Z", null) shouldBe true
    KomgaWatermarks.isChanged(null, wm) shouldBe true
}

@Test fun `backwards only when strictly older`() {
    val wm = LocalDateTime.of(2026, 10, 12, 2, 0, 0)
    KomgaWatermarks.isBackwards("2026-10-12T01:00:00Z", wm) shouldBe true
    KomgaWatermarks.isBackwards("2026-10-12T02:00:00Z", wm) shouldBe false
    KomgaWatermarks.isBackwards(null, wm) shouldBe false
}

@Test fun `encode decode round trip and garbage input`() {
    val m = mapOf("https://a" to "2026-10-12T02:11:21Z")
    KomgaWatermarks.decode(KomgaWatermarks.encode(m)) shouldBe m
    KomgaWatermarks.decode(null) shouldBe emptyMap()
    KomgaWatermarks.decode("{ not json") shouldBe emptyMap()
}
```

- [ ] **步骤 2：运行验证失败**（`--tests "*KomgaWatermarkTest"`）
- [ ] **步骤 3：实现 `KomgaWatermarks`**（JSON 编解码用注入的全局 `Json`，或 `Json { ignoreUnknownKeys = true }` 默认实例）
- [ ] **步骤 4：运行验证通过**
- [ ] **步骤 5：Commit**：`feat(komga): 水位线比较与存取`

### 任务 5：分页收集与停止条件

**文件：**
- 创建：`app/src/main/java/eu/kanade/tachiyomi/data/library/komga/KomgaChangeSet.kt`
- 测试：`app/src/test/java/eu/kanade/tachiyomi/data/library/komga/KomgaChangeSetTest.kt`

**接口：**

```kotlin
data class KomgaSeriesItem(val id: String, val lastModified: String?)
data class KomgaPage(val items: List<KomgaSeriesItem>, val isLast: Boolean)
sealed interface KomgaPageResult {
    data class Ok(val page: KomgaPage) : KomgaPageResult
    data class Failed(val reason: String) : KomgaPageResult
}
data class KomgaChangeSet(
    val changedIds: Set<String>,
    val maxSeen: String?,
    val complete: Boolean,   // false = 命中上限/超时/失败 → 交给策略回退
    val empty: Boolean,      // 首页就是空的（Komga 上没有任何系列）→ 视为全部有变化
)

suspend fun collectChangedSeries(
    watermark: LocalDateTime?,
    fetchPage: suspend (page: Int) -> KomgaPageResult,
    pageSize: Int = 500,
    maxPages: Int = 10,
    timeoutMillis: Long = 5_000,
): KomgaChangeSet
```

规则：逐页 `fetchPage(0), fetchPage(1)…`；`Failed` → `complete=false` 立即返回；每项用 `isChanged` 决定是否加入 `changedIds`，并记录可解析的最大 `lastModified`；遇到「可解析且 `< 水位线`」的项即停止翻页（倒序，后面只会更旧）；`isLast` 或 `maxPages` 用尽即结束；整段用 `withTimeout(timeoutMillis)` 包裹，超时按 `complete=false` 处理。

- [ ] **步骤 1：写失败的测试**（用假 `fetchPage` 闭包 + `runTest` 虚拟时间）

```kotlin
@Test fun `collects items at or after watermark and stops at older one`() = runTest {
    val pages = listOf(
        KomgaPage(listOf(item("A", "03:00"), item("B", "02:00")), isLast = false),
        KomgaPage(listOf(item("C", "01:00"), item("D", "00:00")), isLast = false),
    )
    var requested = mutableListOf<Int>()
    val r = collectChangedSeries(wm("02:00")) { p -> requested += p; KomgaPageResult.Ok(pages[p]) }
    r.changedIds shouldBe setOf("A", "B")     // 等于水位线的 B 也算
    requested shouldBe listOf(0, 1)           // 遇到 C 之后不再翻页
    r.complete shouldBe true
    r.maxSeen shouldBe "2026-10-12T03:00:00Z"
}

@Test fun `null watermark collects everything until last page`() = runTest { /* … */ }
@Test fun `hitting max pages marks incomplete`() = runTest { /* maxPages=2，三页数据 */ }
@Test fun `fetch failure marks incomplete and returns nothing`() = runTest { /* Failed("timeout") */ }
@Test fun `timeout marks incomplete`() = runTest { /* fetchPage 内 delay(10_000) */ }
@Test fun `empty first page marks empty`() = runTest { /* 首页 items 为空 */ }
@Test fun `unparsable lastModified is kept as changed`() = runTest { /* item("Z", null) */ }
```

- [ ] **步骤 2：运行验证失败**
- [ ] **步骤 3：实现 `collectChangedSeries`**（签名与规则如上；只有算法本身，不写注释以外的额外抽象）
- [ ] **步骤 4：运行验证通过**
- [ ] **步骤 5：Commit**：`feat(komga): 分页收集变更集合`

### 任务 6：HTTP 客户端与响应解析

**文件：**
- 创建：`app/src/main/java/eu/kanade/tachiyomi/data/library/komga/KomgaChangeSetClient.kt`
- 测试：`app/src/test/java/eu/kanade/tachiyomi/data/library/komga/KomgaLatestResponseTest.kt`

**接口：**

```kotlin
class KomgaChangeSetClient(private val client: OkHttpClient, private val json: Json) {
    fun buildRequest(baseUrl: String, config: KomgaSourceConfig, page: Int, pageSize: Int): Request
    fun parsePage(body: String): KomgaPageResult
    suspend fun fetchPage(baseUrl: String, config: KomgaSourceConfig, page: Int, pageSize: Int): KomgaPageResult
}
```

要点：URL `{baseUrl}/api/v1/series/latest?size={pageSize}&page={page}`；请求头 `User-Agent: Lumen v{BuildConfig.VERSION_NAME} ({BuildConfig.APPLICATION_ID})`、`Accept: application/json`，有 API key 用 `X-API-Key`，否则有用户名用 `Credentials.basic`；DTO 只声明 `content[].id`、`content[].lastModified`、`last`，其余字段靠全局 `ignoreUnknownKeys` 忽略；`fetchPage` 捕获所有异常返回 `Failed(原因)`。

- [ ] **步骤 1：写失败的测试**（不发真实请求）

```kotlin
@Test fun `builds url with paging params`() {
    val req = client.buildRequest("https://h", cfg(apiKey = "k"), page = 2, pageSize = 500)
    req.url.toString() shouldBe "https://h/api/v1/series/latest?size=500&page=2"
}

@Test fun `uses api key header when present`() {
    client.buildRequest("https://h", cfg(apiKey = "k", user = "u", pass = "p"), 0, 500)
        .header("X-API-Key") shouldBe "k"
    // 且不带 Authorization
}

@Test fun `falls back to basic auth`() {
    val req = client.buildRequest("https://h", cfg(apiKey = "", user = "u", pass = "p"), 0, 500)
    req.header("X-API-Key") shouldBe null
    req.header("Authorization") shouldBe Credentials.basic("u", "p")
}

@Test fun `parses spring page with unknown fields`() {
    val r = client.parsePage(FIXTURE_FULL_PAGE)
    (r as KomgaPageResult.Ok).page.items shouldBe listOf(KomgaSeriesItem("S1", "2026-10-12T02:11:21Z"))
    r.page.isLast shouldBe false
}

@Test fun `parses empty content and last flag`() { /* content: [] , last: true */ }

@Test fun `malformed body yields failure`() {
    client.parsePage("<html>502</html>") shouldBe KomgaPageResult.Failed(/* 任意非空原因 */)
}
```

`FIXTURE_FULL_PAGE` 用真实形状的字符串常量：`{"content":[{"id":"S1","libraryId":"L1","name":"x","lastModified":"2026-10-12T02:11:21Z","booksCount":3,"metadata":{...}}],"last":false,"totalPages":2}`（含若干未知字段，验证忽略行为）。

- [ ] **步骤 2：运行验证失败**（`--tests "*KomgaLatestResponseTest"`）
- [ ] **步骤 3：实现 `KomgaChangeSetClient` 与两个私有 DTO**
- [ ] **步骤 4：运行验证通过**
- [ ] **步骤 5：Commit**：`feat(komga): Komga 客户端与分页响应解析`

### 任务 7：跳过决策（策略矩阵）

**文件：**
- 创建：`app/src/main/java/eu/kanade/tachiyomi/data/library/komga/KomgaSkipDecider.kt`
- 测试：`app/src/test/java/eu/kanade/tachiyomi/data/library/komga/KomgaSkipDeciderTest.kt`

**接口：**

```kotlin
enum class KomgaUnavailablePolicy { FALLBACK_FULL, SKIP }

data class KomgaEntryRef(val mangaId: Long, val sourceId: Long, val baseUrl: String?, val seriesId: String?)

sealed interface KomgaServerOutcome {
    data class Ok(val changedIds: Set<String>, val maxSeen: String?, val empty: Boolean) : KomgaServerOutcome
    data class Failed(val reason: String) : KomgaServerOutcome
}

data class KomgaDecision(
    val keptIds: Set<Long>,
    val skippedUnchangedIds: Set<Long>,
    val skippedUnavailableIds: Set<Long>,
    val newWatermarks: Map<String, String>,   // 仅成功且 maxSeen 非空的服务器
)

fun decide(
    entries: List<KomgaEntryRef>,
    outcomes: Map<String, KomgaServerOutcome>,
    policy: KomgaUnavailablePolicy,
): KomgaDecision
```

判定顺序（先命中先返回）：`baseUrl == null || seriesId == null` → kept；`baseUrl !in outcomes`（配置缺失）→ kept；`Failed` → 按 policy（FALLBACK_FULL → kept，SKIP → skippedUnavailable）；`Ok(empty=true)` → kept；`seriesId in changedIds` → kept；否则 → skippedUnchanged。

- [ ] **步骤 1：写失败的测试**（逐行覆盖规格 §6.6 的矩阵，外加两条审查重点）

```kotlin
@Test fun `non komga entry is always kept`()
@Test fun `missing config keeps entry`()
@Test fun `failure falls back to full by default`()
@Test fun `failure skips when policy is skip`()
@Test fun `empty server keeps everything`()
@Test fun `unchanged entry is skipped and changed entry is kept`()
@Test fun `same address used by two sources is decided once`()   // 审查重点 3
@Test fun `stale watermark for a removed address does not break others`()  // 审查重点 4
@Test fun `new watermarks only from successful servers with maxSeen`()
```

- [ ] **步骤 2：运行验证失败**
- [ ] **步骤 3：实现 `decide`**（纯函数，无 Android 依赖）
- [ ] **步骤 4：运行验证通过**
- [ ] **步骤 5：Commit**：`feat(komga): 跳过决策与策略矩阵`

### 任务 8：偏好与枚举

**文件：**
- 修改：`domain/src/main/java/tachiyomi/domain/library/service/LibraryPreferences.kt`
- 测试：`app/src/test/java/eu/kanade/tachiyomi/data/library/komga/LibraryUpdateModeTest.kt`（内含内存版 `FakePreferenceStore`）

**接口（加在 `LibraryPreferences` 内，KMK 标记包裹）：**

```kotlin
enum class LibraryUpdateMode { INCREMENTAL, FULL }
enum class KomgaUnavailablePolicy { FALLBACK_FULL, SKIP }

fun incrementalUpdateMode() = preferenceStore.getEnum("pref_library_update_mode", LibraryUpdateMode.INCREMENTAL)
fun komgaUnavailablePolicy() =
    preferenceStore.getEnum("pref_komga_unavailable_policy", KomgaUnavailablePolicy.FALLBACK_FULL)
fun komgaWatermarks() =
    preferenceStore.getString(Preference.appStateKey("library_update_komga_watermarks"), "")
fun komgaSkippedCount() =
    preferenceStore.getInt(Preference.appStateKey("library_update_komga_skipped_count"), 0)
```

两个枚举定义在 domain 侧（`KomgaUnavailablePolicy` 被 app 侧的 `KomgaSkipDecider` 引用：app 依赖 domain，方向正确）。

- [ ] **步骤 1：写失败的测试**：默认值分别是 `INCREMENTAL` / `FALLBACK_FULL`；写入后能读回；`komgaSkippedCount` 默认 0；`komgaWatermarks` 默认空串。（测试里的 `FakePreferenceStore` 用 `MutableMap<String, Any?>` 实现 `PreferenceStore` 接口的 6 个方法。）
- [ ] **步骤 2：运行验证失败**
- [ ] **步骤 3：实现 4 个偏好方法与 2 个枚举**
- [ ] **步骤 4：运行验证通过**
- [ ] **步骤 5：Commit**：`feat(library): 增量更新相关偏好`

### 任务 9：接入更新流程

**文件：**
- 创建：`app/src/main/java/eu/kanade/tachiyomi/data/library/komga/KomgaIncrementalFilter.kt`
- 修改：`app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJob.kt`（`:306-364` 的过滤链、`:181-187` 的 `when`）

**接口（编排层）：**

```kotlin
class KomgaIncrementalFilter(
    private val context: Context,
    private val client: OkHttpClient,       // 已 dns(Dns.SYSTEM) + callTimeout(5s)
    private val json: Json,
    private val libraryPreferences: LibraryPreferences,
) {
    data class Outcome(
        val kept: List<LibraryManga>,
        val skipped: List<Pair<Manga, String>>,   // 原因已解析成字符串
        val newWatermarks: Map<String, String>,
        val ranIncremental: Boolean,
    )

    suspend fun apply(
        candidates: List<LibraryManga>,
        watermarksRaw: String,
        policy: KomgaUnavailablePolicy,
    ): Outcome
}
```

`apply` 流程：`entries = candidates.map { KomgaEntryRef(manga.id, manga.source, ref?.baseUrl, ref?.seriesId) }` → 按 `sourceId` 读配置（`readKomgaSourceConfig`）→ 按 `baseUrl` 去重（同址取 `sourceId` 升序第一个非空配置）→ 对每个服务器取水位线并 `collectChangedSeries` → 汇总成 `Map<String, KomgaServerOutcome>` → `decide(...)` → 用 mangaId 还原成 `kept` / `skipped`（跳过原因文案：不变 → `KMR.strings.skipped_reason_komga_unchanged`，不可用 → `KMR.strings.skipped_reason_komga_unavailable`）→ 写 `logcat(LogPriority.INFO) { "Komga incremental: servers=… pages=… changed=… skipped=… reason=…" }`。

**接入 `LibraryUpdateJob` 的三处改动（全部 KMK 标记）：**

1. 字段：`private var pendingKomgaWatermarks: Map<String, String> = emptyMap()`、`private var pendingKomgaSkippedCount = 0`、`private var komgaRanIncremental = false`、`private val komgaFilter by lazy { KomgaIncrementalFilter(context, Injekt.get<NetworkHelper>().client.newBuilder().dns(Dns.SYSTEM).build(), Injekt.get(), libraryPreferences) }`。
2. `addMangaToQueue()`：把现有的 `mangaToUpdate = listToUpdate.distinctBy{}.filter{ 官方 4 条规则 }` 拆成局部 `val filtered = …`，在它与 `.sortedWith(…)` 之间插入：
   `if (isFullLibraryUpdate(categoryId, group) && libraryPreferences.incrementalUpdateMode().get() == LibraryUpdateMode.INCREMENTAL) { val o = komgaFilter.apply(filtered, …); filtered = o.kept; skippedUpdates += o.skipped; pendingKomgaWatermarks = o.newWatermarks; pendingKomgaSkippedCount = o.skipped.size; komgaRanIncremental = o.ranIncremental }`
3. 新增私有函数 `isFullLibraryUpdate(categoryId: Long, group: Int): Boolean`：`categoryId == -1L && inputData.getLongArray(KEY_MANGA_IDS) == null && (group == LibraryGroup.BY_DEFAULT || type == GLOBAL || (type == ALL_BUT_UNGROUPED && group == LibraryGroup.UNGROUPED))`。
4. `doWork()` 的 `when`：`Target.CHAPTERS -> { updateChapterList(); commitKomgaIncrementalState() }`；`commitKomgaIncrementalState()` 只在 `komgaRanIncremental` 为真时写水位线（`KomgaWatermarks.encode(pendingKomgaWatermarks)`，仅当非空）与跳过数量（`pendingKomgaSkippedCount`）。

- [ ] **步骤 1：实现 `KomgaIncrementalFilter`**（依赖任务 2-7 的接口，不新增测试）
- [ ] **步骤 2：按上面 4 点接入 `LibraryUpdateJob`**
- [ ] **步骤 3：编译 + 单测**

运行：`.\gradlew.bat spotlessApply spotlessCheck testReleaseUnitTest`
预期：BUILD SUCCESSFUL；既有 69 个用例仍全绿。

- [ ] **步骤 4：代码自检（人工）**：确认跳过的条目**没有**进入 `updateChapterList()`；确认官方 4 条规则在前、我们的规则在后；确认水位线只在成功路径提交（`catch (CancellationException)` 分支不会走到）。
- [ ] **步骤 5：Commit**：`feat(library): 更新流程接入 Komga 增量过滤`

---

## 阶段三：设置界面与结果呈现

### 任务 10：设置项与字符串

**文件：**
- 修改：`i18n-kmk/src/commonMain/moko-resources/base/strings.xml`（10 条）
- 创建：`app/src/main/res/values-zh-rCN/strings.xml`（10 条同名中文）
- 修改：`app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsLibraryScreen.kt`（在 `:217-226` 的「智能更新」之后插入）

- [ ] **步骤 1：加 10 条字符串**

key 与中英文案见规格 §6.8 的表（`pref_library_update_mode`、`pref_library_update_mode_incremental`、`pref_library_update_mode_full`、`pref_komga_unavailable_policy`、`pref_komga_unavailable_fallback`、`pref_komga_unavailable_skip`、`pref_library_update_incremental_note`、`skipped_reason_komga_unchanged`、`skipped_reason_komga_unavailable`、`updates_skipped_komga_info`）。

- [ ] **步骤 2：插入 3 个设置项**（KMK 标记包裹）

```kotlin
Preference.PreferenceItem.ListPreference(
    preference = libraryPreferences.incrementalUpdateMode(),
    entries = persistentMapOf(
        LibraryUpdateMode.INCREMENTAL to stringResource(KMR.strings.pref_library_update_mode_incremental),
        LibraryUpdateMode.FULL to stringResource(KMR.strings.pref_library_update_mode_full),
    ),
    title = stringResource(KMR.strings.pref_library_update_mode),
),
Preference.PreferenceItem.ListPreference(
    preference = libraryPreferences.komgaUnavailablePolicy(),
    entries = persistentMapOf(
        KomgaUnavailablePolicy.FALLBACK_FULL to stringResource(KMR.strings.pref_komga_unavailable_fallback),
        KomgaUnavailablePolicy.SKIP to stringResource(KMR.strings.pref_komga_unavailable_skip),
    ),
    title = stringResource(KMR.strings.pref_komga_unavailable_policy),
),
Preference.PreferenceItem.InfoPreference(
    title = stringResource(KMR.strings.pref_library_update_incremental_note),
),
```

- [ ] **步骤 3：编译并确认中文覆盖生效**

运行：`.\gradlew.bat spotlessApply spotlessCheck assemblePreview`
预期：BUILD SUCCESSFUL。中文是否真的覆盖成功**必须装机确认**（系统语言为中文时，设置页这三项显示中文）——若未生效，退回「只写英文」并把 `values-zh-rCN/strings.xml` 删除，同时告知项目负责人。

- [ ] **步骤 4：Commit**：`feat(settings): 增量更新设置项与中英文案`

### 任务 11：更新页显示跳过数量

**文件：**
- 修改：`app/src/main/java/eu/kanade/tachiyomi/ui/updates/UpdatesScreenModel.kt`（`:81` 附近）、`app/src/main/java/eu/kanade/presentation/updates/UpdatesScreen.kt`（`:139` 调用处）、`app/src/main/java/eu/kanade/presentation/updates/UpdatesUiItem.kt`（`:75-90`）

- [ ] **步骤 1：ScreenModel 暴露跳过数**

`val komgaSkippedCount by libraryPreferences.komgaSkippedCount().asState(screenModelScope)`

- [ ] **步骤 2：UI 加一行**

`updatesLastUpdatedItem(lastUpdated, komgaSkippedCount)`；在 `UpdatesUiItem.kt` 里，当 `komgaSkippedCount > 0` 时再追加一个 `item(key = "updates-komga-skipped")`，文案 `stringResource(KMR.strings.updates_skipped_komga_info, komgaSkippedCount)`。

- [ ] **步骤 3：编译验证**

运行：`.\gradlew.bat spotlessApply spotlessCheck testReleaseUnitTest`
预期：BUILD SUCCESSFUL。

- [ ] **步骤 4：Commit**：`feat(updates): 更新页显示 Komga 跳过数量`

---

## 阶段四：验证与交付

### 任务 12：本机全量验证 + 出 APK + 真机验收

- [ ] **步骤 1：本机四项全跑**

运行：`.\gradlew.bat spotlessApply spotlessCheck testReleaseUnitTest assemblePreview`
预期：BUILD SUCCESSFUL；测试数 ≥ 69 + 新增用例数，0 失败。

- [ ] **步骤 2：产出并交付 APK**

把 `app/build/outputs/apk/preview/app-universal-preview.apk` 交给项目负责人，按规格 §7.3 的五步验收（显示名/图标/并存 → 加 1 本书 → 只请求那 1 部 → 切全量行为一致 → 非 Komga 源不受影响）。

- [ ] **步骤 3：按验收结果修正**

若「加书后只请求那 1 部」不成立，先查日志里的 `Komga incremental:` 行判断是「没识别出变化」还是「水位线没推进」；前者属 Komga 侧 mtime 行为（见规格 §8-1），后者是代码问题。

- [ ] **步骤 4：Commit 收尾**（如有修正）：`fix(komga): 真机验收修正`

### 任务 13：GitHub Actions workflow（写好，等指示再推）

**文件：** 创建 `.github/workflows/build-apk.yml`（**不改**上游 3 个 workflow）

- [ ] **步骤 1：写 workflow**

```yaml
name: Build APK (Lumen)
on:
  workflow_dispatch:
  push:
    branches: ['**']
    tags: ['lumen-v*']
permissions:
  contents: write
jobs:
  build:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@<与上游同版本的固定 SHA>
        with: { fetch-depth: 0 }
      - uses: actions/setup-java@<同上>
        with: { java-version-file: .github/.java-version, distribution: temurin }
      - uses: gradle/actions/setup-gradle@<同上>
      - name: Write placeholder secrets
        run: |
          cat > app/google-services.json <<'JSON'
          { "project_info": {...}, "client": [ app.lumen / app.lumen.beta / app.lumen.dev 三条 ] }
          JSON
          mkdir -p app/src/main/assets && echo '{}' > app/src/main/assets/client_secrets.json
      - run: ./gradlew spotlessCheck testReleaseUnitTest assemblePreview
      - uses: actions/upload-artifact@<同上>
        with: { name: lumen-preview-apk, path: app/build/outputs/apk/preview/*.apk }
      - if: startsWith(github.ref, 'refs/tags/lumen-v')
        uses: softprops/action-gh-release@<固定 SHA>
        with: { files: app/build/outputs/apk/preview/*.apk }
```

固定 SHA 从上游 `build_push.yml` 抄（checkout / setup-java / setup-gradle / upload-artifact），保持仓库一致风格。

- [ ] **步骤 2：本地校验 YAML 语法**

运行：`python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/build-apk.yml',encoding='utf-8'))"`（用捆绑 Python）
预期：无输出、退出码 0。

- [ ] **步骤 3：Commit（不推）**：`ci: 新增 Lumen APK 构建 workflow`

推送、启用 Actions、打 tag 发 Release 一律等项目负责人明确指示。

---

## 自检记录

- **规格覆盖度**：规格 §6.1-6.3 → 任务 2/3/6；§6.4 → 任务 4；§6.3 停止条件 → 任务 5；§6.5-6.6 → 任务 7/9；§6.7 → 任务 8/10；§6.8 → 任务 10；§6.9 → 任务 11；§6.10-6.11 → 任务 1；§6.12 → 任务 13；§7 → 每个任务的测试步骤 + 任务 12。无遗漏。
- **步骤扫描**：所有实现步骤都给了确切签名与文件路径；没有「处理边界情况」这类空步骤；函数体只在签名与测试决定不了的地方给出（`decide` 的判定顺序、`collectChangedSeries` 的停止规则）。
- **类型一致性**：`KomgaSeriesRef` / `KomgaSourceConfig` / `KomgaSeriesItem` / `KomgaPage` / `KomgaPageResult` / `KomgaChangeSet` / `KomgaEntryRef` / `KomgaServerOutcome` / `KomgaDecision` 在任务 2-7 定义，任务 9 只用不重定义。
- **审查重点**：5 条分别由任务 2（两条）、任务 7（两条）、任务 5（一条）的测试钉住。
- **比例**：本计划 ≈ 规格长度量级，代码块只出现在签名、测试断言与 workflow 配置处。

---
