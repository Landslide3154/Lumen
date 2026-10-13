<div align="center">

<img width=160px height=160px src="./.github/readme-images/app-icon.png"/>

# Lumen

安卓漫画阅读器，**Komikku 的 fork**（上游链路：Tachiyomi → Mihon → TachiyomiSY → Komikku → Lumen）。

[下载最新版](https://github.com/Landslide3154/Lumen/releases/latest) ·
[报告问题](https://github.com/Landslide3154/Lumen/issues) ·
[更新日志](https://github.com/Landslide3154/Lumen/releases)

*Lumen is a fork of [Komikku](https://github.com/komikku-app/komikku) with a SyncYomi protocol v2 client, incremental Komga library updates and its own branding. Chinese-first README below.*

</div>

## 下载与安装

- 安装包在 [Releases](https://github.com/Landslide3154/Lumen/releases/latest)：文件名形如 `Lumen-arm64-v8a-1.14.1.17.apk`，只出 **arm64-v8a** 一个 ABI
- 要求 **Android 8.0** 及以上；包名 `app.lumen.beta`，与上游 Komikku 共存互不影响
- 版本号 = 上游版本 + 交付序号，例如 `1.14.1.17` 表示「基于上游 1.14.1 的第 17 次交付」

## 这个 fork 做了什么（相对上游 Komikku）

### 一、SyncYomi 协议 v2 客户端（多设备同步）

- **服务端合并取代客户端合并**：缺失不再等于删除。不会再出现「新加的漫画同步不过去，先读一页再同步就正常了」这类丢条目问题
- **增量同步**：上传只带自上次成功同步后变化的内容，下载只取 cursor 之后变化的项，不再整库搬运
- **流式上传**：逐条写 protobuf 记录再 gzip 分块发送，大书库不再内存溢出
- **分类删除走显式墓碑**：删掉的分类 uid 随请求头 `X-Sync-Deleted-Categories` 上报，另一台设备下次同步即删除，不会复活
- **分类新增稳定 uid 与合并版本**：改名、改序、隐藏都能跨设备收敛，不再多出重复分类
- **老服务端自动回退 v1**：capabilities 返回 404 时完全按改动前行为工作
- 要求：服务端 **SyncYomi ≥ 1.3.0**；客户端的「设置 → 数据 → 同步」里会显示当前用的是 **v2** 还是 **v1**

### 二、Komga 增量书库更新

- 「整库更新」先查 Komga 的 `/api/v1/series/latest`，用本地水位线判断哪些作品有变化，没变化的条目直接不请求（因此也不会误删章节）；查询失败/超时自动回退全量
- 「更新」页显示本次增量诊断：服务器数 / 查询页数 / 有变化数量 / 跳过数量 / 回退原因
- 设置项：「更新方式（增量 / 全量）」「Komga 不可用时（回退全量 / 跳过）」

### 三、品牌与细节

- 应用名改为 **Lumen**，新图标与闪屏（超椭圆底板 + 两层投影，见上方图标）
- 通知不再放大图标（只保留左侧小图标），通知强调色统一为深墨色 `#0B1622`
- Lumen 新增文案统一放 `i18n-kmk`（`KMR`），中文覆盖放在 app 模块的 `values-zh-rCN`，不碰 Weblate 拥有的上游语言文件
- 本地构建的 APK 自动带软件名、ABI 与版本号，例如 `Lumen-arm64-v8a-1.14.1.17.apk`

## 上游功能

上游 Komikku / TachiyomiSY / Mihon 的功能全部保留并持续跟进，包括：在线源阅读、本地下载阅读、可配置阅读器、追踪（MyAnimeList / AniList / Kitsu / MangaUpdates / Bangumi / Shikimori 等）、书库分类、定时更新、备份与恢复、Feed、合并漫画、自动 Webtoon、E-Hentai/ExHentai 等。详细清单见上游 [Komikku README](https://github.com/komikku-app/komikku)。

上游代码的改动一律用注释标记，便于跟进上游：

| 标记 | 含义 |
|---|---|
| `// KMK -->` … `// KMK <--` | 本 fork（Lumen）的改动 |
| `// SY -->` … `// SY <--` | 来自 TachiyomiSY 上游 |
| 无标记 | Mihon / Tachiyomi 上游代码 |

## 从源码构建

```bash
./gradlew assemblePreview       # 交付版 APK（包名 app.lumen.beta）
./gradlew assembleDebug         # 调试版（包名 app.lumen.dev）
./gradlew spotlessCheck         # 格式门禁（CI 会跑）
./gradlew testReleaseUnitTest   # 单元测试
./gradlew verifySqlDelightMigration  # 校验数据库迁移
```

- 需要 **JDK 21**（版本见 `.github/.java-version`）；首次构建会下载约 1 GB 依赖
- 开发约定、模块结构、上游同步规则见 [AGENTS.md](./AGENTS.md)
- 推送分支或 `lumen-v*` 标签会触发 [build-apk.yml](./.github/workflows/build-apk.yml) 自动构建；标签会额外建 Release 并附 APK

## 反馈问题

请附上：版本号（更多 → 关于 → 版本）、复现步骤、截图。
同步相关问题还请附上「设置 → 数据 → 同步」里显示的**协议版本（v1 / v2）**与**服务端版本**——v1 与 v2 的同步语义差别很大（v1 不会同步删除，也不会传增量）。

## 鸣谢

- [Komikku](https://github.com/komikku-app/komikku)（本 fork 的直接上游）
- [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY)
- [Mihon](https://github.com/mihonapp/mihon) 与最初的 Tachiyomi
- 以及所有为上述项目贡献过的开发者

## 免责声明

本应用的开发者与任何内容提供方均无关联，本应用自身不托管任何内容。

## License

    Copyright 2015 Javier Tomás

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
