# Lumen 增量更新设计（Komga 变更集合）

- 状态：**待规格审阅**（六节设计已于 2026-10-12 由项目负责人确认，无修改）
- 分支：`feat/lumen-incremental-update`（不直接提交 master）
- 仓库：`Landslide3154/Lumen`（`komikku-app/komikku` 的 fork，默认分支 master）
- 本文中的路径均相对仓库根目录。

---

## 1. 目标

把 Lumen 改造成：**整库更新时，只向 Komga 拉取「自上次成功更新后发生变化」的作品的章节列表，其余 Komga 作品跳过**；顺带完成改名（Lumen）与换图标。

### 1.1 验收标准（来自项目负责人）

1. 往 Komga 加 1 本书 → 触发更新 → 日志显示**只请求了那 1 部**；对比改动前后的更新耗时。
2. 关掉「增量」开关后，行为与官方完全一致；非 Komga 源完全不受影响。
3. 应用显示名是 Lumen、图标为新图标（preview 构建也要对）、可与官方 Komikku 并存。

---

## 2. 范围

### 2.1 做

| # | 内容 |
| --- | --- |
| 1 | 增量更新的判定与跳过（只改本仓库，App 自己当 Komga 客户端） |
| 2 | 3 个设置项 + 1 行说明（设置 → 书架 → 全局更新） |
| 3 | 更新结果里报告跳过数量（更新页一行 + 日志明细） |
| 4 | 改名：显示名 Lumen、包名 `app.lumen` |
| 5 | 换图标：启动图标（3 套）、通知大图标（3 份）、启动闪屏（2 份）、通知强调色 |
| 6 | 单元测试 + 本机验证 + GitHub Actions workflow（写好但暂不推送） |

### 2.2 不做

- 不改 `extensions-lib`、不改 Komga 扩展、不重编扩展。
- 不动「哪些条目进书架」的逻辑。
- 不连带跳过「自动刷新作品信息」（只加一行界面说明）。
- 不启用 in-app updater（`-Penable-updater`）——它指向 `komikku-app/komikku` 的 Release，签名不同，装了也更新不了。
- 不改登录回调 scheme `komikku://`（Bangumi / Shikimori 已在第三方注册）、不改扩展仓库信任签名、不改内部包名/命名空间。
- 不做「回看窗口」设置（首次更新直接走全量）。

---

## 3. 已核实的事实（本设计的地基）

### 3.1 更新流程（App 侧）

| 事实 | 出处 |
| --- | --- |
| 整库更新在 `doWork()` 里：`Target.CHAPTERS` 时先写「上次更新时间」，再建更新队列，再逐条拉章节 | `app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJob.kt:165-187` |
| 「上次更新时间」是在**更新开始时**写入的（不是成功后），所以不能直接拿来当增量基准 | 同上 `:167-170` |
| 更新队列由 `addMangaToQueue()` 决定：取整库 → 分类/分组筛选 → 4 条官方跳过规则 → 排序 | 同上 `:224-364` |
| 官方 4 条跳过规则写进 `skippedUpdates`，但**只写日志**（代码里留着 `TODO: surface skipped reasons to user?`） | 同上 `:306-378` |
| 逐条更新按 source 分组并发执行，失败的进 `failedUpdates` 并写库 | 同上 `:389-502` |
| 扩展「少返回章节」会真删本地章节记录 → **绝不能用「返回空列表」来跳过** | `app/src/main/java/eu/kanade/domain/chapter/interactor/SyncChaptersWithSource.kt:77,233-235` |
| 4 条跳过规则的常量与默认值 | `domain/src/main/java/tachiyomi/domain/library/service/LibraryPreferences.kt:64-72,278-281` |
| 设置界面「全局更新」分组 | `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsLibraryScreen.kt:123-239`（跳过规则在 `:217-226`） |
| 偏好对象是单例，UI 侧只需 `Injekt.get<LibraryPreferences>()`；新增 key 不需要迁移 | `app/src/main/java/eu/kanade/tachiyomi/di/PreferenceModule.kt:48-50` |
| 设置页新增条目会被设置搜索自动收录，无需登记 | `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSearchScreen.kt:262-272` |

### 3.2 Komga 扩展侧（外部仓库，已核实）

| 事实 | 值 |
| --- | --- |
| 作品网址形状 | `{Address}/api/v1/series/{seriesId}`，Address 结尾不带斜杠（扩展侧强制校验） |
| 扩展配置所在文件 | 宿主 App 的 `source_<sourceId>`（`ConfigurableSource.preferenceKey()`） |
| 配置键名 | `Address`、`API key`、`Username`、`Password`（逐字，区分大小写与空格） |
| 认证 | 有 API key → 请求头 `X-API-Key`；否则 用户名+密码 的 HTTP Basic（扩展额外用 `X-Auth-Token` 复用会话，我们不需要） |
| 也支持「书单」条目 | 网址形如 `/api/v1/readlists/{id}`，本功能不处理（回退全量） |

### 3.3 Komga 服务端侧（已核实）

| 事实 | 影响 |
| --- | --- |
| `GET /api/v1/series/latest` 返回 Spring 分页对象（`content[]` / `last` / `totalPages` …），按 `lastModified` 倒序，默认 `size=20`，框架上限 2000（超限截断而非报错） | 我们显式传 `size=500`，按页翻 |
| `SeriesDto.lastModified` 序列化格式 `yyyy-MM-dd'T'HH:mm:ss'Z'`，其中 `Z` 是**被引号转义的字面字符**，不代表 UTC；DB 侧写入用的是 `LocalDateTime.now(ZoneId.of("Z"))` | **不做时间比较**，只做「同字段前后值比较」（见 6.4），彻底绕开时区/时钟问题 |
| 阅读进度（标记已读/未读）**不会**刷新 `Series.lastModified` | 增量不会因为用户看书而失效 |
| 系列/书籍**元数据**编辑**不会**刷新 `Series.lastModified` | 改元数据不会触发拉取（章节本就没变） |
| 新增书籍只有在「扫描发现系列目录 mtime 变化」时才刷新 `Series.lastModified`（库设置 `scanForceModifiedTime` 可改用书文件时间） | 正常「往系列文件夹加书」会被识别；NFS/SMB 缓存场景可能识别不到 → 写进设置说明（6.7） |
| 官方 WebUI 的「Recently Updated」用的是 `/api/v1/series/updated`（多排除 `created == lastModified` 的系列） | 不适用；我们要的正是「新建的系列也算变化」 |

### 3.4 现有可复用/不可复用的代码

| 结论 | 依据 |
| --- | --- |
| App 里已有 Komga tracker（`data/track/komga/`），但它的请求**不带任何认证**（只设 User-Agent），且凭证来自 tracker 配置而非扩展配置 | `app/src/main/java/eu/kanade/tachiyomi/data/track/komga/KomgaApi.kt:28-32`、`Komga.kt:26-29` |
| 因此**不复用 tracker 的客户端**；但可以复用它的 User-Agent 写法与 `SeriesDto` 思路 | 同上 |
| 全局 Json 已开 `ignoreUnknownKeys`、`explicitNulls=false` | `app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt:145-150` |
| 网络客户端：`Injekt.get<NetworkHelper>().client`；Komga 场景必须 `dns(Dns.SYSTEM)`（DoH 会破坏 IP 直连） | `AppModule.kt:171`、`data/track/komga/Komga.kt:26-29` |
| 读扩展配置的既有先例 | `app/src/main/java/eu/kanade/tachiyomi/source/online/all/MangaDex.kt:76-78` |
| 测试栈：JUnit Jupiter + Kotest + MockK，无 Robolectric / Compose UI 测试 | `gradle/libs.versions.toml:126`、`buildSrc/.../ProjectExtensions.kt:92-99` |
| CI 用的单测任务名 | `testReleaseUnitTest` |

### 3.5 构建与改名相关

| 事实 | 影响 |
| --- | --- |
| `namespace = eu.kanade.tachiyomi`、`applicationId = app.komikku`；无 productFlavor，只有 buildType | 只改 `applicationId` |
| 6 个完整 applicationId：`app.komikku`、`.dev`、`.rt`、`.foss`、`.beta`、`.benchmark` | 占位 google-services.json 需要覆盖我们要构建的变体 |
| google-services / crashlytics 是**有条件** apply（仅 `-Pinclude-telemetry` 时） | 不带该参数的构建**不需要** `google-services.json`；仍写占位以防以后打开 |
| Gradle 9.3.1，JDK 21（`.github/.java-version`），未配置 toolchain 自动下载 | 本机需要自备 JDK 21 |
| `app_name` 唯一定义处，`translatable="false"` | 改一处即可全局生效（含通知标题、相册目录名） |
| 相册目录名、备份文件名来自 `app_name` / `APPLICATION_ID` | 改名后：图片进 `Pictures/Lumen`（旧图不迁移）；旧备份不再被自动清理规则匹配（不影响恢复） |
| `TelemetryConfig` 白名单硬编码 `app.komikku` | 我们不构建遥测版本，不影响；若以后打开遥测需同步改 |
| 图标：`mipmap/ic_launcher.xml` 引用 background/foreground/monochrome 三层；beta/debug 各自覆盖 background+foreground；`R.drawable.komikku`（3 份 200×200）是通知大图标；`R.mipmap.komikku` 是扩展仓库徽标；闪屏走 `ic_splash` → `ic_komikku{,_dark}` | 见 6.11 的文件清单 |
| `@color/ic_launcher` 被约 20 处通知代码引用（通知强调色），不是死资源 | 改成深墨色而不是米白 |

---

## 4. 名词

| 名词 | 含义 |
| --- | --- |
| **Komga 条目** | 书架里 `manga.url` 匹配 `^(.+)/api/v1/series/([^/?#]+)/?$` 的条目。与装的是哪个 Komga 扩展无关 |
| **非增量条目** | 其余所有条目（其它源、Komga 书单条目、合并源条目、网址解析失败者）。永远走全量 |
| **水位线（watermark）** | 每个 Komga 服务器地址各存一个「上次成功更新时在 Komga 上看到的最大 `lastModified`」字符串 |
| **变更集合** | 本次查询 Komga 得到的、`lastModified >= 水位线` 的 seriesId 集合（按服务器分开） |
| **整库更新** | `categoryId == -1` 且更新范围是整库（`group == BY_DEFAULT`，或分组模式为 GLOBAL，或 `ALL_BUT_UNGROUPED` 下的 UNGROUPED 组）且未指定单本 ID 的 `Target.CHAPTERS` 更新 |
| **局部更新** | 单本/多本定向更新、指定分类更新、指定分组更新 |

---

## 5. 总体设计

```
LibraryUpdateJob.addMangaToQueue()
  ├─ 官方流程：整库 → 分类/分组筛选 → 4 条跳过规则
  └─ KMK 增量分支（仅整库更新 + 增量模式）
        └─ KomgaIncrementalFilter
              ├─ KomgaSeriesUrl      : 纯函数，解析 manga.url
              ├─ KomgaSourcePrefs    : 读 source_<id> 的 Address / API key / Username / Password
              ├─ KomgaChangeSetClient: OkHttp 查询 /api/v1/series/latest（分页 + 超时）
              └─ KomgaWatermarks     : 读写水位线（偏好存储）
        ↓ 返回：保留的条目、跳过的条目（含原因）、新水位线、是否回退
LibraryUpdateJob.updateChapterList()   ← 只处理保留下来的条目（不请求 = 不会删章节）
        ↓ 成功后
  提交新水位线 + 记录跳过数量 → 更新页显示
```

**关键性质**：跳过 = 从更新队列里移除，**根本不调用扩展**。因此不会触发 `SyncChaptersWithSource` 的删章节逻辑。

**不做时间比较**：水位线与 `lastModified` 都是 Komga 自己返回的同一个字段，只做「字符串/本地时间的前后比较」，不使用手机时钟、不使用手机时区。

---

## 6. 详细设计

### 6.1 判定 Komga 条目（纯函数）

```kotlin
data class KomgaSeriesRef(val baseUrl: String, val seriesId: String)

fun parseKomgaSeriesUrl(url: String): KomgaSeriesRef?
```

1. 正则：`^(?<base>https?://.+?)/api/v1/series/(?<id>[^/?#]+)/?$`。
2. `baseUrl` 去掉结尾 `/`；`seriesId` 原样保留。
3. 不匹配（含 `/api/v1/readlists/…`）→ 返回 null → 该条目按非增量处理。
4. 该函数不依赖 `sourceId`、不依赖扩展是否安装。

### 6.2 读取扩展配置

1. 文件：`context.getSharedPreferences("source_$sourceId", Context.MODE_PRIVATE)`。
2. 键：`Address`、`API key`、`Username`、`Password`（逐字）。
3. `Address` 为空 → 该 sourceId 整体视为非增量（走全量）。
4. `Address`（去尾斜杠后）必须与条目网址解析出的 `baseUrl` 一致；不一致 → 该条目按非增量处理（避免用 A 服务器的凭证去问 B 服务器）。
5. 为便于单测，解析逻辑做成纯函数 `KomgaSourceConfig.from(map: Map<String, String?>): KomgaSourceConfig?`，Android 侧只负责取 map。

### 6.3 查询变更集合

1. 分组：按 `sourceId` 收集条目 → 解析出 `(baseUrl, seriesId)`；**同一个 `baseUrl` 只查询一次**。若同一地址出现在多个扩展里，按 `sourceId` 升序取第一份非空配置（顺序确定，避免每次跑用不同凭证）。
2. 请求：`GET {baseUrl}/api/v1/series/latest?size=500&page=N`（`N` 从 0 开始）。
3. 请求头：`User-Agent: Lumen v{VERSION_NAME} ({APPLICATION_ID})`；有 API key → `X-API-Key: {apiKey}`；否则若有用户名 → `Authorization: Basic base64(user:pass)`。
4. 客户端：`Injekt.get<NetworkHelper>().client.newBuilder().dns(Dns.SYSTEM).callTimeout(5, SECONDS).build()`（DoH 会破坏 IP 直连，必须用系统 DNS）。
5. 解析：只取 `content[].id` 与 `content[].lastModified`；`ignoreUnknownKeys` 已全局开启。
6. 停止条件（满足任一即停）：
   - 某页出现 `lastModified < 水位线` 的条目（因为按时间倒序，后面只会更旧）；
   - 已翻 10 页；
   - **整次查询**（含全部分页请求）超过 5 秒；
   - 响应 `last` 为 true 或 `content` 为空。
7. 超时实现：整次查询用协程 `withTimeout(5_000)` 包住；OkHttp 的 `callTimeout(5s)` 作为单请求兜底。
8. 命中「10 页」或「5 秒」上限 → 视为**无法确定完整变更集合** → 按兜底策略处理（默认回退全量）。
9. 空列表（Komga 上没有任何系列）→ 视为「全部有变化」→ 全量。
10. 该次查询看到的**最大 `lastModified`** 记录下来，作为成功后的新水位线候选值。

### 6.4 水位线

1. **存储**：应用状态偏好 `library_update_komga_watermarks`，值是一个 JSON 对象 `{"<baseUrl>": "<lastModified 原样字符串>"}`（复用全局 Json）。
2. **比较**：`有变化 = lastModified >= 水位线`（用「大于等于」，避免同一秒内的边界漏书；代价是最新的那一两个系列每次都会被重新拉一次，可忽略）。
3. **比较方式**：两侧都去掉结尾的 `Z` 后用 `LocalDateTime.parse` 解析成 `LocalDateTime` 再比较（`ISO_LOCAL_DATE_TIME`，兼容带/不带小数秒）；任一侧解析失败 → 该服务器按「无法判断」处理（回退全量）。**全程不使用手机时钟与手机时区。**
4. **推进条件**（需同时满足）：整库更新 + 增量模式 + 目标为章节 + `updateChapterList()` 正常返回（未抛异常、未被取消）+ 本次查询成功拿到最大 `lastModified`。
5. **推进值**：本次查询看到的**最大 `lastModified`**，不是「现在几点」——这样更新过程中在 Komga 上新增的书，下次一定还会被算作有变化。
6. **不推进**：局部更新（单本/分类/分组）、更新失败或被取消、查询整体失败（没有候选值）。
7. **异常自愈**：若查询返回的最大 `lastModified` **严格小于**当前水位线（Komga 从备份恢复、时间倒流等）→ 判定异常，本次按「全部有变化」处理，成功后把水位线重置为新的最大值，并写一条警告日志。
8. **首次没有水位线** → 本次所有 Komga 条目都按「有变化」处理（等价全量），查询成功后建立水位线。
9. **从「全量」切回「增量」**：全量模式下不查询、水位线保持旧值 → 切回后会把「自上次增量成功以来」变化过的系列都拉一次，属预期（宁可多拉，不可漏）。

### 6.5 过滤与跳过

1. **时机**：官方 4 条跳过规则跑完之后、排序之前（官方规则优先，原因文案也优先）。
2. **逐条判定**：

| 条目情况 | 处理 |
| --- | --- |
| 非 Komga 条目 | 保留，走全量 |
| Komga 条目，服务器查询成功且 `seriesId` 在变更集合里 | 保留 |
| Komga 条目，服务器查询成功但 `seriesId` 不在变更集合里 | **跳过**，原因「Komga 上无变化」 |
| Komga 条目，服务器查询失败 + 策略=回退全量 | 保留，走全量 |
| Komga 条目，服务器查询失败 + 策略=跳过本次 | **跳过**，原因「Komga 暂时不可用」 |
| Komga 条目，配置读不到 / 网址与配置地址不符 | 保留，走全量（不计入跳过） |

3. 跳过的条目写进既有的 `skippedUpdates` 列表（复用官方机制，扩展它的日志输出），同时累计计数。
4. 更新队列大小、进度通知的分子分母自动反映减少后的集合，不需要额外改动。
5. **已确认的安全性**：跳过 = 不进入 `updateChapterList()`，因此不会调用扩展、不会触发 `SyncChaptersWithSource` 的删章节路径。

### 6.6 兜底矩阵（默认列即「Komga 不可用时 = 回退全量」）

| 情况 | 默认（回退全量） | 选「跳过本次」 |
| --- | --- | --- |
| 扩展配置读不到 / Address 为空 | 全量（不算失败，不报错） | 全量（同左，因为「不可用策略」只针对查询失败） |
| 网址与 Address 不一致 | 该条目全量 | 该条目全量 |
| 连接失败 / 超时 / 401 / 403 / 5xx | 该服务器的 Komga 条目全部全量 | 该服务器的 Komga 条目全部跳过 |
| 返回内容解析失败 | 同上 | 同上 |
| 翻页超过 10 页或超过 5 秒 | 同上 | 同上 |
| Komga 返回空列表 | 全部按有变化 → 全量 | 全部按有变化 → 全量 |
| 水位线无法解析 | 同上 | 同上 |
| 增量模式关闭 | 完全不查询，行为与官方一致 | 同左 |

**总原则**：任何异常都不会导致「少更新」；失败一定写日志说明原因。

### 6.7 设置项

位置：`SettingsLibraryScreen.getGlobalUpdateGroup()` 内，紧接「智能更新」之后，整块用 `// KMK -->` / `// KMK <--` 包裹。

| # | 设置 | 键 / 默认 | 形态 |
| --- | --- | --- | --- |
| 1 | 更新方式：增量 / 全量 | `pref_library_update_mode`，枚举 `LibraryUpdateMode`，默认 `INCREMENTAL` | `ListPreference`（2 项） |
| 2 | Komga 不可用时：回退全量 / 跳过本次 | `pref_komga_unavailable_policy`，枚举 `KomgaUnavailablePolicy`，默认 `FALLBACK_FULL` | `ListPreference`（2 项） |
| 3 | 说明行（不可点） | 无偏好 | `Preference.PreferenceItem.InfoPreference`（`Preference.kt:177`） |

说明行文案（中文口径）：

> 增量更新只拉取 Komga 上有变化的作品。开启「自动刷新作品信息」时，作品信息的请求不受本功能影响；若 Komga 库放在网络盘上、加书后未被识别为变化，可临时切到「全量」更新一次。

枚举定义放在 `LibraryPreferences.kt` 内（与既有 `ChapterSwipeAction` 同风格）；`getEnum` 是 `inline reified`，不需要自定义 Serializer。全量模式下**一个请求都不发**。

### 6.8 字符串与 i18n 归属

1. **英文（规范来源）**：加到 `i18n-kmk/src/commonMain/moko-resources/base/strings.xml`，用 `KMR` 引用。名字必须全局唯一（当前 i18n / i18n-kmk / i18n-sy 三个 base 文件 0 重名；跨模块重名会导致资源被静默覆盖，属未定义行为）。
2. **中文覆盖**：`app/src/main/res/values-zh-rCN/strings.xml`（新建）。依据：moko 生成的资源名就是 key 本身、不带前缀，AGP 资源合并中 app 模块优先级高于库模块，所以同名的中文值会覆盖库里的英文值。
3. **理由**：仓库硬规则禁止手改 `i18n*` 的非 base 语言文件（Weblate 拥有），所以中文不写进 i18n-kmk 的 zh-rCN。
4. 新增字符串清单（9 条）：

| key | 英文（base） | 中文（zh-rCN 覆盖） |
| --- | --- | --- |
| `pref_library_update_mode` | Update mode | 更新方式 |
| `pref_library_update_mode_incremental` | Incremental | 增量 |
| `pref_library_update_mode_full` | Full | 全量 |
| `pref_komga_unavailable_policy` | When Komga is unavailable | Komga 不可用时 |
| `pref_komga_unavailable_fallback` | Fall back to full update | 回退全量更新 |
| `pref_komga_unavailable_skip` | Skip Komga entries | 跳过 Komga 条目 |
| `pref_library_update_incremental_note` | 见 6.7 说明行 | 见 6.7 说明行 |
| `skipped_reason_komga_unchanged` | No changes on Komga | Komga 上无变化 |
| `skipped_reason_komga_unavailable` | Komga unavailable | Komga 暂时不可用 |
| `updates_skipped_komga_info` | Skipped %1$d entries (no changes on Komga) | 本次跳过 %1$d 部（Komga 上无变化） |

### 6.9 结果呈现

1. **新增应用状态偏好** `library_update_komga_skipped_count`（int，默认 0）：**只在「整库 + 增量」的更新成功结束时**写入本次跳过数；本次没走增量（全量模式/局部更新）或更新失败时不写（保留上一次的值）。
2. **更新页**：`UpdatesUiItem.updatesLastUpdatedItem` 那一行下面增加一行，**仅当数量 > 0 时显示**，文案见上表 `updates_skipped_komga_info`。
3. **日志**：统一前缀 `Komga incremental:`，用 `logcat(LogPriority.INFO) { ... }`（`core/common/.../LogcatExtensions.kt` 支持 `tag` 参数）。至少包含：本次涉及几个服务器、各请求了几页、变更系列数、跳过数、回退原因（若有）。验收时用 `adb logcat | grep "Komga incremental"` 过滤。
4. **不弹通知**（跳过是常态，每次弹太吵）。
5. 全量模式下不产生这一行（因为不跳过）。

### 6.10 改名（Lumen）

| # | 动作 | 位置 |
| --- | --- | --- |
| 1 | 显示名 `Komikku` → `Lumen`（保持 `translatable="false"`，所有语言都显示 Lumen） | `i18n/src/commonMain/moko-resources/base/strings.xml:3` |
| 2 | `applicationId = "app.lumen"`（`namespace` 与代码包名不动） | `app/build.gradle.kts:29` |
| 3 | 3 处硬编码旧名文案 | `i18n-kmk/.../base/strings.xml`（Discord 描述）、`i18n-sy/.../base/strings.xml`（扩展提示）、`EditMangaDialog.kt:348`（封面路径提示） |

**必须知道的副作用**（写进交付说明，不改代码）：

- 保存的图片目录变成 `Pictures/Lumen`，旧图不会自动搬（`ImageSaver`）。
- 自动备份文件名变成 `app.lumen_<日期>.tachibk`；旧的 `app.komikku_*` 不再被「自动清理旧备份」的正则匹配（不影响恢复）。
- 崩溃上报白名单硬编码 `app.komikku`；本项目不构建遥测版本（不带 `-Pinclude-telemetry`），因此无影响。

**明确不改**：内部 `namespace`/代码包名、扩展仓库信任签名、登录回调 scheme `komikku://`、更新检查用的上游仓库名、依赖坐标、WebDAV 默认目录名 `komikku`。

### 6.11 图标与闪屏

设计素材：`lumen-final.svg`（4 个 polygon，无渐变无描边）+ 各尺寸 PNG（在 `lumen-icons/`）。已实测自适应前景素材是 **432×432 画布、内容占中央 264×264**（= 61.1%，正好是自适应图标安全区），因此矢量实现就是：

```
viewport 432×432（=108dp）
background : 满幅 #F7F3EC
foreground : <group translateX=84 translateY=84 scaleX=0.2578125 scaleY=0.2578125>
             页 #0B1622  →  斜光带 #FFFFFF（clip 到页）  →  折角 #FFFFFF
monochrome : 同上变换，折角剪影（单色，系统着色）
```

要改的文件（共 **17 个**；若闪屏选择直接改被引用的 logo 矢量，再加 2 个）：

| # | 文件 | 动作 |
| --- | --- | --- |
| 1-3 | `app/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml` | 换成新矢量（保持 432×432 / 108dp） |
| 4-5 | `app/src/beta/res/drawable/ic_launcher_{background,foreground}.xml` | 同上（preview 构建走 beta 源集，漏了等于没换） |
| 6-7 | `app/src/debug/res/drawable/ic_launcher_{background,foreground}.xml` | 同上（debug 构建） |
| 8-10 | `app/src/{main,beta,debug}/res/drawable/komikku.png` | 通知大图标换成新标识（200×200） |
| 11-12 | `app/src/main/res/drawable/ic_splash.xml`、`drawable-night/ic_splash.xml` | 指向新矢量（日间用墨色标、夜间用米白标） |
| 13 | `presentation-core/src/main/res/values{,-night}/colors.xml` 的 `@color/splash` | 日间 `#F7F3EC`、夜间 `#0B1622` |
| 14 | `app/src/{main,beta,debug}/res/values/colors.xml` 的 `@color/ic_launcher` | 统一改为 `#0B1622`（这是**通知强调色**，被约 20 处通知引用；米白在通知上看不见） |

**三套启动图标做成同一个样式**（不再用紫/黄区分 preview/debug），与「全换」的决定一致。

**不动**：`app/src/main/res/mipmap/komikku.png`（那是 Komikku **扩展仓库的签名徽标**，代表上游仓库身份，不是本应用 logo）。

### 6.12 GitHub Actions（写好但暂不推送）

新增 `.github/workflows/build-apk.yml`，**不改上游的 3 个 workflow**。

1. 触发：`workflow_dispatch`（手动按钮）+ push 到分支 + tag `lumen-v*`。
2. 步骤（对齐上游 `build_push.yml` 的写法与固定版本号）：
   - `actions/checkout`，`fetch-depth: 0`（`versionName` 用 `git rev-list --count HEAD`，浅克隆会得到错误版本号）
   - `actions/setup-java` + `java-version-file: .github/.java-version` + `temurin`
   - `gradle/actions/setup-gradle`（开缓存）
   - 写两个占位文件：`app/google-services.json`（client 需含 `app.lumen`、`app.lumen.beta`、`app.lumen.dev`）、`app/src/main/assets/client_secrets.json` = `{}`
   - `./gradlew spotlessCheck testReleaseUnitTest assemblePreview`
   - `actions/upload-artifact` 上传 `app/build/outputs/apk/preview/*.apk`
   - 仅当 ref 是 `lumen-v*` tag：`softprops/action-gh-release` 附 APK
3. 说明：
   - 不带 `-Pinclude-telemetry` 时其实**不需要**占位文件（插件是有条件 apply），写上是为了以后打开遥测不至于突然编译失败。
   - tag 前缀用 `lumen-v*` 而不是 `v*`，避免触发上游 `build_release.yml`。
   - 创建 Release 会触发上游 `update_website.yml`（缺密钥会失败，属噪音）；若嫌吵可删掉该文件。
   - 首次使用需在 Actions 页面手动点一次 Enable。

---

## 7. 测试计划

### 7.1 单元测试（新增，放 `app/src/test/java/eu/kanade/tachiyomi/data/library/komga/`）

| 测试类 | 覆盖点 |
| --- | --- |
| `KomgaSeriesUrlTest` | 带端口 / 带子路径 / 带尾斜杠的合法网址；readlist 网址 → null；其它源网址 → null；空串与畸形 → null |
| `KomgaSourceConfigTest` | 4 个键逐字映射；Address 为空 → null；Address 尾斜杠归一化 |
| `KomgaWatermarkTest` | 无水位线 → 全部有变化；**等于**水位线算有变化；严格小于 → 停止收集；时间倒流 → 异常标记；任一侧解析失败 → 回退标记 |
| `KomgaPagingTest` | 多页数据下的收集与停止页；超过 10 页 → 回退标记；空 `content` → 全部有变化 |
| `KomgaLatestResponseTest` | 解析 Spring 分页响应（含未知字段）取到 id / lastModified |
| `KomgaFilterOutcomeTest` | 6.6 兜底矩阵逐行各一条用例；非 Komga 条目永不被跳过 |
| `LibraryUpdateModeTest` | 两个枚举的默认值与序列化键名 |

测试栈：JUnit Jupiter + Kotest assertions（仓库既有），纯函数优先，不引入 Robolectric。

### 7.2 本机验证（每次改完必跑，全部通过才算完成）

```powershell
$env:JAVA_HOME='C:\Users\godis\.jdks\jdk21\jdk-21.0.12.1+1'
cd D:\code\Komikku
.\gradlew.bat spotlessApply spotlessCheck testReleaseUnitTest assemblePreview
```

基线（未改动代码）已通过：spotlessCheck ✅、69 个单测 0 失败 ✅、assemblePreview ✅（6 分 36 秒）。

### 7.3 真机验收（项目负责人执行）

| # | 步骤 | 期望 |
| --- | --- | --- |
| 1 | 安装 preview APK | 桌面名称 Lumen、图标为新标识、可与官方 Komikku 并存 |
| 2 | 往 Komga 加 1 本书并让 Komga 扫描到 | —— |
| 3 | 在 Lumen 触发整库更新 | `adb logcat \| grep "Komga incremental"` 显示只请求了那 1 部；对比耗时 |
| 4 | 切到「全量」再更新一次 | 行为与官方一致，日志中无 Komga 查询 |
| 5 | 观察非 Komga 源 | 完全不受影响 |

---

## 8. 风险与已知边界

| # | 风险 | 处理 |
| --- | --- | --- |
| 1 | Komga 是否把「加书」算作系列变化，取决于扫描时系列目录 mtime（NFS/SMB 缓存场景可能识别不到） | 写进设置说明；必要时切「全量」一次；Komga 侧可开 `scanForceModifiedTime` |
| 2 | 某本 Komga 书本次更新失败后，下次不会自动重试（系列没变化） | 手动单本更新永远走全量；Komikku 自带更新错误列表可重试 |
| 3 | 在 Komga 改元数据不触发拉取 | 设计如此（章节没变），写入说明 |
| 4 | 中文覆盖依赖 AGP 资源合并优先级 | 实现后用 preview APK 实测中文界面；若失效则退回「只写英文」并告知 |
| 5 | 矢量图标与 PNG 素材可能有细微差异 | 用 Pillow 量测素材比例（已测得 264/432）与渲染几何比对 |
| 6 | 本机环境依赖三处本机改动（JDK 21、Gradle 镜像、build-tools 标注） | 均为本机改动，不进入仓库；CI 不受影响 |

---

## 9. 实施顺序

1. 骨架：新增 CI workflow（写好不推）。
2. 改名 + 图标 → 先出一个 APK，确认显示名与图标（含 preview）。
3. **TDD**：先写 7.1 的单测（红），再实现 `KomgaSeriesUrl` / `KomgaSourceConfig` / 水位线与分页逻辑（绿）。
4. 实现 `KomgaChangeSetClient`（网络层）。
5. 接入 `LibraryUpdateJob` + `LibraryPreferences` + 设置界面 + 字符串（含 zh-rCN 覆盖）。
6. 更新页一行 + 日志。
7. 本机跑 7.2 三项 + 出 APK。
8. 真机验收（7.3）→ 等指示再打 tag 发 Release。

---

## 10. 变更文件清单

**新增**

- `app/src/main/java/eu/kanade/tachiyomi/data/library/komga/`：`KomgaSeriesUrl.kt`、`KomgaSourceConfig.kt`、`KomgaChangeSetClient.kt`、`KomgaIncrementalFilter.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/library/komga/`：7.1 的 7 个测试类
- `app/src/main/res/values-zh-rCN/strings.xml`
- `.github/workflows/build-apk.yml`
- 本规格文档

**修改**（对既有文件的改动一律用 `// KMK -->` / `// KMK <--` 包裹）

- `domain/src/main/java/tachiyomi/domain/library/service/LibraryPreferences.kt`（新偏好 + 枚举）
- `app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJob.kt`（插入点 + 水位线提交 + 跳过计数）
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsLibraryScreen.kt`（3 项）
- `app/src/main/java/eu/kanade/presentation/updates/UpdatesUiItem.kt`、`UpdatesScreen.kt`、`app/src/main/java/eu/kanade/tachiyomi/ui/updates/UpdatesScreenModel.kt`（跳过数量一行）
- `i18n-kmk/src/commonMain/moko-resources/base/strings.xml`（9 条新字符串）
- `i18n/src/commonMain/moko-resources/base/strings.xml`（`app_name`）
- `app/build.gradle.kts`（`applicationId`）
- `i18n-kmk` / `i18n-sy` base 各 1 条文案、`EditMangaDialog.kt` 1 处路径提示
- 图标/闪屏/颜色共 14 个资源文件（见 6.11）

---

## 11. 实现时才定的细节（不影响设计）

1. 折角剪影（monochrome）的具体 pathData —— 由 SVG 几何推导，实现时给出。
2. 更新页那一行的具体位置与排版 —— 若现有列表结构不方便，退化为「只写日志」并说明。
3. 日志文案的确切措辞 —— 只要保留 `Komga incremental:` 前缀便于过滤。
