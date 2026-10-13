# 任务提示词：为 Komikku（本仓库）实现 SyncYomi 协议 v2 客户端

> 用法：把本文件整份作为提示词交给目标仓库（Komikku 的 fork）的 AI 会话。
> 文中所有路径、行号、版本号都是 2026-10-10 核实过的现状，实施前请按"先核实再动手"的原则复核。
> 本文件是自包含的：执行方不需要本对话的上下文。

## 0. 一句话任务

在本仓库实现 SyncYomi 同步服务的 **协议 v2 客户端**（服务端合并 + 增量上传/下载 + 显式删除墓碑），
**保留现有 v1 路径作为老服务器回退**，并保证：多设备不再丢条目、同步只传变化、不做无意义的全库重写。

## 1. 背景：为什么必须做

**SyncYomi 是什么**：一个自托管的同步服务端（Go 单二进制 + 内嵌 Web UI），给 Tachiyomi/Mihon 系
安卓客户端（TachiyomiSY、Komikku、Suwayomi-Server）做多设备「阅读进度 + 书库」同步。
客户端用 `X-API-Token` 鉴权，**一个 API Key = 一个用户**，同一 Key 下的所有设备共享一个书库。
项目：https://github.com/syncyomi/syncyomi

**v1 的结构性缺陷（必须换 v2 的唯一理由）**：v1 让每个客户端自己判断"对端没有 = 被删了"
（拿条目的修改时间和自己的上次同步时间比）。两台设备时会这样丢数据：

```
A 添加漫画 M (T1)
B 同步 (T2 > T1) —— M 还没上传到服务端
A 上传（含 M）(T3)
B 再同步：M 是"仅远端有"且早于 B 上次同步 → B 判定 M 被删，丢弃
B 上传（不含 M）
A 再同步：M 是"仅本机有"且早于 A 上次同步 → A 也丢弃
→ M 永远同步不过去，直到有人改动它把时间戳顶新
```

真实用户报障就是这个现象（TachiyomiSY #1635）："新加的漫画有时同步不过去，但先读一页再同步就正常了"。

**v2 的收益**（也是本任务的验收口径）：
1. 多设备不再丢条目——服务端合并，**缺失永远不等于删除**，删除只有客户端显式上报的墓碑。
2. 每次同步只传变化（上传只带改动的 manga/章节，下载只回 `seq > cursor` 的项），不再整库搬运。
3. 没有"空转同步"——`X-Sync-Changed: false` 时客户端跳过 restore，服务端也不会把回传的自身数据再写一遍。

**服务端版本要求**：v2 自服务端 **1.3.0**（2026-08-30）起提供；1.5.x/1.6.0 对协议本身没有改动。
客户端探测到老服务器（capabilities 返回 404）时必须回退到 v1。

## 2. 协议 v2 规格（权威来源：服务端 docs/sync-protocol-v2.md）

### 2.1 端点与往返

```
① GET  /api/sync/v2/capabilities          → 200 表示服务器支持 v2；404 表示老服务器，回退 v1
② POST /api/sync/v2/merge                 → 上传本次变化，拿回自己缺的
③ POST /api/sync/event                    → 上报同步结果（v1 已有，沿用）
```

请求 ② 的头：

| 头 | 含义 |
|---|---|
| `X-API-Token` | 鉴权（沿用现有实现） |
| `X-Device-ID` | **必填**，设备稳定标识（一个 UUID，生成一次后持久化） |
| `X-Device-Name` | 可选，Web UI 里显示的名字 |
| `X-Sync-Cursor` | 上次响应里的 cursor；首次同步不带或传 0 |
| `X-Sync-Full` | `true` 表示 body 是完整书库 |
| `X-Sync-Deleted-Categories` | 逗号分隔的分类 uid，表示客户端删了哪些分类 |
| `Content-Encoding: gzip` | 可选；body 允许分块传输（chunked） |

请求 ② 的 body：**Tachiyomi 备份的 protobuf**，和 v1 用的是同一套模型。
增量时只包含：自上次**成功上传**以来变化的 manga（每个只带变化的章节）+ **全部分类**（服务端要靠它
映射分类编号）+ 仅在变化时才带的 settings。**某个 manga 不带章节 ≠ "它没有章节"，而是"章节没变化"。**

响应 ② 的 body：一个备份，装着客户端缺的一切——全部存活分类、cursor 之后被其他设备改过的 manga
（每个带**完整**章节列表，哪怕只改了一章）、变化过的 settings、以及服务端副本胜出的条目。

响应 ② 的头：

| 头 | 含义 |
|---|---|
| `X-Sync-Cursor` | 下次请求原样带回 |
| `X-Sync-Changed` | `false` = body 里没有新东西，客户端**跳过 restore** |
| `X-Sync-Full-Requested` | `true` = 服务端要求下次上传完整书库 |
| `ETag` | `seq=<cursor>`，与 v1 客户端看到的是同一个值 |

### 2.2 客户端流程（照做即可）

1. **探测一次**：`GET /api/sync/v2/capabilities`，200 → 用 v2；404 → 保持 v1。
   每个服务器地址探测一次并缓存结果（`unknown / yes / no`）；**地址变化时重置**。
2. **标识设备**：生成一次 UUID 持久化，每个请求都带 `X-Device-ID`（v1 请求也建议带上，Web UI 就能显示设备）。
3. **构建上传**：
   - 增量：只放自上次成功上传后修改过的 manga（用本地自己的 `last_modified_at`/`version` 判断），
     每个 manga 只带那时之后修改的章节；**分类永远全带**；settings 变了才带。
   - 全量：首次同步、cursor 为 0、上次响应带了 `X-Sync-Full-Requested`、以及**每天一次的兜底全量**。
   - **必须流式编码**：把每个 manga 作为独立的 `backup_manga` 字段记录写出，再写其余部分，
     gzip 后分块发送。把整个书库编码进一个 byte[] 正是安卓端 OOM 的根因。
4. **记录删除**：用户删分类时把它的 uid 存进 `pendingDeletedCategoryUids`，放进
   `X-Sync-Deleted-Categories`；**收到 200 后清空**。
5. **发 ②**，带上 cursor。**收到响应后绝对不要做本地 merge**（v1 那套 `mergeSyncData` 在 v2 路径必须跳过）。
6. **应用响应**：`X-Sync-Changed: false` → 记录成功、结束；
   否则用常规 upsert restore 应用，**删除本地在响应分类列表里不存在的分类**（该列表保证是全量的），
   存下新 cursor。
7. **上报** `POST /api/sync/event`（沿用现有实现）。

### 2.3 服务端合并规则（决定客户端要保证什么）

- **键**：manga 用 `source|url`；章节用它在自己 manga 内的 url；分类用 `uid`（无 uid 时退回 name）。
- **版本**：`version` **只由客户端设置**（由本地的 SQL 触发器在实质性变更时递增），服务端只存胜出副本、
  永不递增。`version` 高者胜；相等时服务端保留自己的、**不回传**（若内容有实质差异则按
  `last_modified_at` 决出——所以客户端不要给"同步写入"乱打时间戳，见第 5 节）。
- **取消收藏**是普通的带版本变更（manga 里 `favorite=false`），不是删除。**章节永远不因同步被删。**
- **分类删除**：只有 `X-Sync-Deleted-Categories` 里的 uid 会变成墓碑；此后若有**更高版本**的编辑，可以被"复活"。
- **settings**（sources、preferences、source preferences、扩展仓库、保存的搜索）没有版本概念，客户端副本胜出。
- **分类成员**在服务端存成对分类的引用，所以各设备分类顺序不同不影响归属。

### 2.4 幂等与并发

- 重试安全：内容相同的重放不产生写入，cursor 也不动。
- 同一 API Key 的 merge 串行执行（服务端保证），客户端不必自己做互斥。
- **cursor 超前**（服务端库被回滚过）会被当作 0 处理并返回 `X-Sync-Full-Requested`，客户端按全量重传即可。

### 2.5 v1 必须继续可用（硬要求）

- 老服务器（capabilities 404）走原 v1 流程，**行为一行都不能变**。
- v1 的 `GET/PUT /api/sync/content` 是字节级回显 + `If-Match`/`If-None-Match`（`uuid=`/`seq=` 两种 ETag），
  这套语义必须保留；v1/v2 设备可以在同一个 Key 下共存（v1 设备之间仍是客户端本地合并说了算）。
- v1 的每个响应都带 `Deprecation: true`，可以在设置界面据此给个"该服务器/客户端协议已过时"的提示（可选）。

## 3. 现成参考实现（**先读这个，不要从零设计**）

### 参考 A（首选，同生态同语言）：TachiyomiSY 的 v2 PR

- PR：https://github.com/jobobby04/TachiyomiSY/pull/1648（`state=open`，未合并，但 CI `Build app` 已通过）
- 分支：`syncyomi/TachiyomiSY` 的 `feat/syncyomi-v2`（比 master 领先 25 个提交，改动 42 个文件）
- 同仓库里的 Komikku 与 TachiyomiSY 同源（都源自 Mihon），**类名、包名、备份模型几乎一一对应**，
  是最省力的移植对象。用 `gh` 把它的 diff 拉下来逐文件对照：

```bash
gh api repos/jobobby04/TachiyomiSY/pulls/1648/files --paginate > sy-v2-files.json
gh pr diff 1648 --repo jobobby04/TachiyomiSY > sy-v2.patch   # 需要 git 远端可达
```

它改动的 42 个文件按作用分类（**这就是本任务的改动清单蓝本**）：

| 作用 | 文件（TachiyomiSY 侧） |
|---|---|
| 协议客户端核心 | `data/sync/service/SyncYomiSyncService.kt`（+166/−44）、`data/sync/service/SyncService.kt`、`data/sync/SyncManager.kt`（+127/−57） |
| 增量构建 | 新增 `data/sync/SyncDelta.kt`、新增 `data/sync/service/BackupRequestBody.kt`（流式上传） |
| 偏好项 | `domain/sync/SyncPreferences.kt`（+30） |
| 备份模型（proto3 零值） | `data/backup/models/*.kt` 共 13 个文件（Manga/Chapter/Category/Source/Preference/Tracking/History/ExtensionStore/MergedMangaReference/SavedSearch/FlatMetadata/metadata 三个） |
| restore 语义 | `data/backup/restore/BackupRestoreJob.kt`（+41）、`BackupRestorer.kt`、`restorers/CategoriesRestorer.kt`、`restorers/MangaRestorer.kt`（+37/−15） |
| 分类删除墓碑 | `domain/category/interactor/DeleteCategory.kt`（+10）、`domain/DomainModule.kt` |
| 数据库/SQL | `sqldelight/tachiyomi/data/mangas.sq`、`chapters.sq`、`mangas_categories.sq`、新增迁移 `migrations/41.sqm`、`data/chapter/ChapterRepositoryImpl.kt`、`data/manga/MangaRepositoryImpl.kt` |
| 测试 | 新增 `SyncDeltaTest.kt`、`service/BackupRequestBodyTest.kt`、`models/BackupProto3DecodeTest.kt` |
| 调试用（可跳过） | `app/src/debug/**`（SyncTriggerReceiver、debug manifest）、`di/AppModule.kt` |

### 参考 B（已合并、已发版）：Suwayomi-Server 的 v2 实现（Kotlin）

- PR：https://github.com/Suwayomi/Suwayomi-Server/pull/2325（已合并，随 v2.4.2366 发布）
- 改动 7 个文件，最值得抄的是 `global/impl/sync/SyncDelta.kt`（16 行，增量数据结构的极简写法）、
  `SyncYomiSyncService.kt`（+200/−40）、以及分类删除在 `CategoryMutation.kt` / `Category.kt` 里怎么接线。
- 它的 release notes 还列出了**同一批必须一起修的客户端侧 bug**（章节阅读顶 manga 版本、restore 给每行
  重打 `last_modified_at`、分类顺序约定等），见第 5 节。

### 参考 C（协议文档与测试口径）：服务端仓库

- 协议：`docs/sync-protocol-v2.md`；客户端实现清单：`docs/clients.md`；API：`docs/api.md` + `OpenAPI.yaml`
- 端到端场景（服务端 e2e 目录，带编号，可直接当验收参照）：
  - `S14` Suwayomi 发送侧分类线上顺序
  - `S17` 无用户改动的设备不产生任何服务端写入，且 restore 不打行时间戳
  - `S18` 设备 A 的一次阅读 = 服务端恰好一次写入；B 应用后 A 再同步 = 零写入、不跑 restore
  - `S19` 同一契约在 Suwayomi 与安卓之间双向成立
  - `S8` soak：空闲轮次 `seq` 保持不动

## 4. 本仓库的改动清单（路径已按本仓库核实）

> 本仓库是 Komikku 的 fork（包名 `eu.kanade.tachiyomi`），下列路径均已确认存在；
> **动手前请先 `git log`/`grep` 复核，再照参考 A 的 diff 逐文件对照**。
> Komikku 上游**没有**实现 v2（issue #1884 至今无人回应），所以没有可直接 cherry-pick 的上游补丁。

| # | 文件（本仓库路径） | 要做什么 |
|---|---|---|
| 1 | `app/src/main/java/eu/kanade/tachiyomi/data/sync/service/SyncYomiSyncService.kt` | 加 capabilities 探测（每 host 一次，结果缓存）；加 v2 分支：POST `/api/sync/v2/merge`，带 `X-Sync-Cursor`/`X-Sync-Full`/`X-Sync-Deleted-Categories`/`X-Device-ID`/`X-Device-Name`；**v2 路径不跑本地 merge**；保留 v1 全部分支 |
| 2 | `app/src/main/java/eu/kanade/tachiyomi/data/sync/SyncManager.kt` | 在构建备份**之前**决定全量/增量；增量只带改动项；`X-Sync-Changed: false` 时跳过 restore；v2 下跳过 v1 那套"首次不 restore""远端无数据"判断；保留 `filterFavoritesAndNonFavorites`、`updateNonFavorites`、按缺失删分类 |
| 3 | 新增 `app/src/main/java/eu/kanade/tachiyomi/data/sync/SyncDelta.kt` | 增量模型（改动的 manga id 集合 + 各自改动的章节 + 上次成功上传时间戳），参考 A 的同名文件（32 行）与参考 B 的 16 行版本 |
| 4 | 新增 `app/src/main/java/eu/kanade/tachiyomi/data/sync/service/BackupRequestBody.kt` | **流式请求体**：逐条写 `backup_manga` 字段记录 → 写其余部分 → gzip → chunked 发送，避免大书库 OOM |
| 5 | `app/src/main/java/eu/kanade/domain/sync/SyncPreferences.kt` | 新增：`syncCursor`(Long)、`serverSupportsV2`(三态，host 变化时重置)、`pendingDeletedCategoryUids`(String set)、`lastPushedAt`(Long)、`lastFullSyncAt`(Long) |
| 6 | `app/src/main/java/eu/kanade/tachiyomi/data/sync/SyncDataJob.kt`、`SyncService.kt` | 接线：把新参数（cursor、full、删除集合）传进服务实现；保持 GoogleDrive/WebDAV 分支行为不变 |
| 7 | `domain/src/main/java/tachiyomi/domain/category/interactor/DeleteCategory.kt` | 删分类时把 uid 记进 `pendingDeletedCategoryUids`（仅在同步开启时）；注意本仓库的依赖注入分 `DomainModule.kt` / `KMKDomainModule.kt` / `SYDomainModule.kt`，按实际提供方接线，domain 层拿不到 SyncPreferences 时要通过接口回调 |
| 8 | `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/*.kt`（13+ 个，含本仓库多出的 `BackupFeed.kt`） | **每个标量字段给零值默认**（`0`/`""`/`false`）。proto3 不写零值，其他客户端（如 Suwayomi）也不会发，缺默认值会导致解码整个库失败——这是 v2 的硬前提，不是可选优化 |
| 9 | `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/**`（`BackupRestoreJob`/`BackupRestorer`/`restorers/CategoriesRestorer`/`restorers/MangaRestorer`） | 让 restore **不给每行重打 `last_modified_at`**、不给"同步写入"打本地修改标记；分类先于 manga 恢复；分类顺序按下一节的约定处理 |
| 10 | `data/src/main/sqldelight/tachiyomi/data/{mangas,chapters,mangas_categories}.sq` + `data/src/main/sqldelight/tachiyomi/migrations/<下一个编号>.sqm` | SQL 触发器修正：章节阅读**不得**顶 manga 版本；分类顺序按约定转换；迁移编号取本仓库当前最大值 +1（参考 A 用的是 41.sqm，本仓库编号已不同，**不要照抄**） |
| 11 | `data/src/main/java/tachiyomi/data/{manga/MangaRepositoryImpl,chapter/ChapterRepositoryImpl}.kt` | 配合上面的 SQL 变更调整仓储层（参考 A 各 +4/−1） |
| 12 | 测试：`app/src/test/.../SyncDeltaTest.kt`、`service/BackupRequestBodyTest.kt`、`models/BackupProto3DecodeTest.kt` | 三个都要有：增量选点正确、流式 body 字节与一次性编码等价、proto3 零值库能解码 |

## 5. 硬性约束与已知坑（**违反任何一条都会真丢数据，不是风格问题**）

1. **proto3 零值默认**：备份模型每个标量字段都要有零值默认。缺一个字段就可能让"别的客户端产生的书库"
   整个解码失败。参考 A 甚至为此专门加了 `BackupProto3DecodeTest`。
2. **必须流式上传**：整个书库编码进单个 byte[] 会让安卓端 OOM（Komikku/Suwayomi 都为此单独打过补丁）。
   正确做法：每条 manga 单独写成一个 `backup_manga` 字段记录，protobuf 会自动合并记录。
3. **章节阅读不得顶 manga 版本**：否则"读得多的那台设备"会在同步时静默回滚另一台设备的分类/追踪修改。
4. **restore 不得给每行重打 `last_modified_at`、不得把同步插入标成"本地修改"**：这是 v1 时代
   "回显污染"的根因——服务端刚下发的数据会以更新后的时间戳回传、赢下平局、把 `seq` 顶高，
   逼所有其他设备白拉一次。**这条不改，v2 也会退化成 v1。**
5. **分类顺序**：线上使用 **0-based** 顺序约定（Mihon/TachiyomiSY 的约定），本地存 1-based 名次、
   收到时 +1、发出时 −1。这样 Suwayomi 与各 Tachiyomi 分支之间分类位置才能收敛。
6. **分类删除必须显式上报**：v1 的分类删除只在 blob 内容里传递、**不会进服务端的条目库（没有墓碑）**，
   所以过了 v2 设备这一手就会"复活"。删了就记 uid，200 之后清空。
7. **`X-Sync-Changed: false` 时必须跳过 restore**：这是"不空转"的一半收益所在。
8. **v2 路径不得做本地合并**：服务端已经合过了；本地再合会与 v1 的语义打架。
9. **全量同步的时机**：首次、cursor=0、上次响应 `X-Sync-Full-Requested: true`、以及每天一次的兜底。
   除此之外都走增量。
10. **重试安全**：内容相同的重放不能产生额外写入（服务端会保证 cursor 不动，但客户端也不该因此乱打标记）。
11. **v1 路径一行不改**：老服务器（capabilities 404）必须完全按原样工作；v1/v2 设备要能在同一个 Key 下共存。
12. **不要在 UI 上暴露未完成的开关**：功能没测通前，v2 不要做成用户可见的选项（内部按能力探测自动切换即可）。

## 6. 验收标准（逐条可验证）

**功能**
- [ ] 面对支持 v2 的服务器：日志/调试信息能看到 capabilities 探测成功，同步走 `/api/sync/v2/merge`。
- [ ] 面对老服务器（或把探测地址指到一个返回 404 的端点）：完整回退 v1，行为与改动前一致。
- [ ] 两台设备交替同步：A 新增的漫画必定出现在 B（构造 v1 会丢数据的时序：A 添加 → B 先同步 → A 再同步）。
- [ ] 在设备上删除一个分类并同步：另一台设备的该分类被删除，**不会复活**。
- [ ] 增量同步的请求体明显小于全量（大书库下用抓包或日志字节数对比）。
- [ ] 无变化的同步：服务端 `seq` 不变，客户端不跑 restore（`X-Sync-Changed: false` 生效）。

**正确性/回归**
- [ ] 章节阅读只影响阅读状态，不改变 manga 版本，不覆盖对端的分类/追踪修改。
- [ ] 迁移（1→2 台设备、1→N 个分类）后本地库不丢条目、不重复。
- [ ] 大书库（数千 manga）同步不 OOM——这是流式上传的验收点。
- [ ] 三个单元测试（增量选点、流式 body 等价性、proto3 零值解码）全绿。
- [ ] Google Drive / WebDAV 同步路径不受影响。

## 7. 怎么联调（要有一个真服务器才能验收）

**起一个服务端**（任意一台能跑 Docker 的机器，或本地）：

```bash
docker run -d --name syncyomi -p 8282:8282 -v $PWD/syncyomi-data:/config ghcr.io/syncyomi/syncyomi:latest
```

打开 `http://<地址>:8282` → `设置 → API Keys` 建一个 Key → **所有参与测试的设备都用同一个 Key**
（一个 Key = 一个用户，不同 Key 视为不同用户）。然后：

- `设置 → API Keys → Details` 能看到设备列表、上次同步时间、上报的状态；v1 设备会被标成 **legacy**
  （可用它确认自己现在跑的是哪条协议）。
- 同一台服务器上再跑一个 **Suwayomi-Server ≥ v2.4.2366**（它已内置 v2），
  就构成了服务端 e2e 用的"安卓 ↔ 服务端 ↔ Suwayomi"对拍环境——两个 v2 设备互相验证最有效。
- 服务端 `docs/clients.md` 里的实现清单可以当 checklist 逐条打勾。

**调试开关**：v2 请求的响应头 `X-Sync-Changed` / `X-Sync-Cursor` / `X-Sync-Full-Requested`
是最直接的观测点，建议在前两个阶段把它们打进日志。

## 8. 工作方式与交付要求

**先读本仓库的 `AGENTS.md` 并遵守**（尤其是：不要主动 `git push`/打 tag/发 Release，
本地 commit 照常做，做完停下汇报"已提交、未推送"；commit message 用 `type(scope): 中文简述`）。

**建议分 5 个阶段，每阶段可独立验证、独立提交**：

1. **协议层**：capabilities 探测 + v2 全量上传/下载 + `X-Sync-Changed` 跳过 restore。
   先用全量把协议跑通（正确性优先），此阶段不做增量。
2. **增量**：`SyncDelta` + 流式 `BackupRequestBody` + cursor 往返；补两个单元测试。
3. **语义修正**：restore 不重打时间戳、SQL 触发器（章节阅读不顶版本）、分类顺序约定、迁移脚本；
   补 proto3 零值解码测试。
4. **删除墓碑 + 设置项**：`DeleteCategory` 接线、`pendingDeletedCategoryUids` 生命周期。
5. **联调验收**：按第 6 节逐条验证，并把验证方式（命令/步骤/观测点）写进汇报。

**每阶段汇报固定三段**：改了什么文件（含关键行）、怎么验证的（命令/日志/数据）、
第 6 节哪几条验收项已通过、哪几条还没。

**遇到不确定的细节**（尤其第 5 节第 4、5 条的 SQL/触发器改法）：**以参考 A 的 diff 为准**，
不要自己发明语义；参考 A 与本仓库有差异时，先说明差异再决定。

## 附录：速查表

**请求头**：`X-API-Token`、`X-Device-ID`、`X-Device-Name`、`X-Sync-Cursor`、`X-Sync-Full`、
`X-Sync-Deleted-Categories`、`Content-Encoding: gzip`

**响应头**：`X-Sync-Cursor`、`X-Sync-Changed`、`X-Sync-Full-Requested`、`ETag: seq=<cursor>`

**端点**：`GET /api/sync/v2/capabilities`、`POST /api/sync/v2/merge`、
`POST /api/sync/event`（v1 沿用：`GET|PUT /api/sync/content`）

**同步事件名**（`POST /api/sync/event`）：`SYNC_STARTED`、`SYNC_SUCCESS`、`SYNC_FAILED`、
`SYNC_ERROR`、`SYNC_CANCELLED`

**关键链接**
- 协议文档：https://github.com/syncyomi/syncyomi/blob/develop/docs/sync-protocol-v2.md
- 客户端实现清单：https://github.com/syncyomi/syncyomi/blob/develop/docs/clients.md
- 参考实现（Android/Kotlin）：https://github.com/jobobby04/TachiyomiSY/pull/1648
- 参考实现（已合并）：https://github.com/Suwayomi/Suwayomi-Server/pull/2325
- Komikku 上游的 v2 请求（未实现，可作背景）：https://github.com/komikku-app/komikku/issues/1884

