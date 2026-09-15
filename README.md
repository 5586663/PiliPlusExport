# PiliPlus 导出（LSPosed 模块）v2

给 PiliPlus 加两个悬浮按钮：

- **导出评论** —— 单个视频的完整评论区
- **导出UP主** —— 某 UP 的全部投稿视频 + 动态 + 每个视频的全部评论

**不动 PiliPlus 一行代码，不重编译 PiliPlus，不用管签名。**

---

## 为什么用 LSPosed

PiliPlus 界面与逻辑编译进 `lib/arm64-v8a/libapp.so`（Flutter AOT 快照），无法反编译修改。
LSPosed 可在运行时注入 Java 到 PiliPlus 进程，从而：

- 往界面叠加悬浮按钮（不需要悬浮窗权限）
- 直接调用 B站 gRPC / App 签名接口（字段号已核实）
- 复用 PiliPlus 的登录态，拿到未登录看不到的深层评论

---

## 模块结构

```
app/src/main/java/com/piliplus/export/
├── MainHook.java      Xposed 入口，注入两个悬浮按钮
├── MainActivity.java  模块说明界面
├── Exporter.java      单视频评论导出编排（原有）
├── BiliApi.java       gRPC 通道 + 评论接口（原有，grpc() 已公开为 grpcRaw）
├── Proto.java         手工 protobuf 编解码（支持任意字段号）
├── Reply.java         单条评论模型
├── MdWriter.java      Markdown 渲染（4 种排版）
│   ── 以下为 v2 新增 ──
├── Json2.java         轻量 JSON 解析器（无第三方依赖）
├── SpaceApi.java      UP主视频列表 / 用户信息（App 签名 HTTP）
├── OpusApi.java       UP主动态列表（gRPC OpusSpaceFlow）
├── ReplyApi2.java     带 type 参数的评论接口（动态评论 type=17）
├── VideoItem.java     视频列表项模型
├── DynItem.java       动态项模型
├── UpExporter.java    UP主全量导出编排
└── MdWriter2.java     UP主导出专用渲染
```

---

## 核实过的协议字段

### 评论（对照 PiliPlus `lib/grpc/bilibili/main/community/reply/v1.pb.dart`）

| 结构 | 字段 |
|------|------|
| `Mode` | `MAIN_LIST_TIME=2`，`MAIN_LIST_HOT=3`（**没有 1**） |
| `MainListReq` | `oid=1` `type=2` `cursor=3` `pagination=10` |
| `CursorReq` | `next=1` `mode=4` |
| `MainListReply` | `cursor=1` `replies=2` `subjectControl=3` `paginationReply=20` |
| `SubjectControl` | `count=16` |
| `DetailListReq` | `oid=1` `type=2` `root=3` `rpid=4` `cursor=5` `scene=6` `mode=7` `pagination=8` |
| `DetailListReply` | `cursor=1` `root=3` |
| `ReplyInfo` | `replies=1` `id=2` `oid=3` `type=4` `mid=5` `like=9` `ctime=10` `count=11` `content=12` `member=13` |
| `Content` | `message=1` |
| `Member` | `mid=1` `name=2` |

评论区 type：`1`=视频，`12`=专栏，`17`=动态。

### 动态（对照 `lib/grpc/bilibili/app/dynamic/v2.pb.dart`）

| 结构 | 字段 |
|------|------|
| `OpusSpaceFlowReq` | `hostMid=1` `localTime=2` `pagination=3` `filterType=4` |
| `Pagination` | `pageSize=1` `next=2` |
| `OpusSpaceFlowResp` | `itemList=1` `nextPage=2` |
| `PaginationReply` | `next=1` `prev=2` |
| `OpusFlowItem` | `itemType=1` `oid=2` `extend=3` `flowItemOpus=4` |
| `Extend` | `dynIdStr=1` `origDynIdStr=3` `origDynType=8` `dynType=13` `uid=14` `descTextOpus=26` |
| `FlowItemOpus` | `coverPic=1` `textParagraph=5` |
| `Paragraph` | `text=3` |
| `TextParagraph` | `nodes=1` |
| `TextNode` | `nodeType=1` `rawText=2` `emote=4` |
| `EmoteNode` | `previewName=8` |
| `MdlDynDrawItem` | `src=1` |

### UP主视频列表（App 签名 HTTP）

`GET https://app.bilibili.com/x/v2/space/archive/cursor`

参数对齐 PiliPlus `lib/http/member.dart:119 spaceArchive()`。
用户信息：`GET https://app.bilibili.com/x/v2/space`。

---

## 构建

### 方式一：GitHub Actions（推荐，手机上即可触发）

本目录已含 `.github/workflows/build.yml`。

1. 把整个 `PiliPlus_LSPosed` 目录推到 GitHub 仓库
2. 仓库 → Actions → 左侧选 `Build PiliPlusExport`
3. 右侧 `Run workflow`
4. 等 3~8 分钟，在 Artifacts 下载 `PiliPlusExport-apk`

### 方式二：本地构建（需 JDK 17 + Android SDK）

```bash
export ANDROID_HOME=~/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
chmod +x gradlew && gradle assembleRelease
# 产物 app/build/outputs/apk/release/app-release.apk
```

体积约 100 KB。**不需要 Flutter**（不编译 Dart）。

---

## 安装

1. 装 `app-release.apk`
2. LSPosed 管理器 → 模块 → 勾选「PiliPlus 导出」
3. 作用域勾选 PiliPlus（改名版/克隆版同样可用）
4. **强制停止 PiliPlus**，再打开
5. 界面右侧出现两个按钮

---

## 输出目录结构

单视频：`/sdcard/Download/<视频标题>_评论.md`

UP主：`/sdcard/Download/PiliPlus_导出/<UP名>/`

```
<UP名>/
├── 00_总览.md          UP信息 + 汇总 + 视频清单表 + 动态清单表
├── 动态.md             全部动态正文（逐条，含图片链接）
└── 视频/
    ├── <标题1>.md      该视频的完整评论区
    ├── <标题2>.md
    └── ...
```

---

## 已知限制

- **动态发布时间未解析**：`Extend` 消息（v2.pb.dart）中不存在时间字段，已核实。
  动态条目的时间列会留空。如需时间，需另调 `OpusDetail` 逐个获取，成本高，本版未做。
- **动态互动数未解析**：点赞/评论数在 `InteractionItem`（另一层），本版未接入。
- 动态图片：`FlowItemOpus.coverPic` 只取封面一张，正文内嵌图未逐张提取。
- 接口 `stat.reply` 含已删除/审核中评论，抓不到，通常差 3~5 条。
- 评论实时增长，导出是某一时刻的快照。
- 需要 root + LSPosed（或 LSPatch）。
- 耗时：不含评论 1~3 分钟；含评论逐视频拉取，视规模可能数小时。

---

## 关于隐私

- 模块**不读**任何本地文件
- cookie 只从 `CookieManager` 取一次，存在内存变量里
- **不写日志、不写文件、不发往任何第三方**
- 唯一外部请求是 `app.bilibili.com` 和 `api.bilibili.com`
