# 小狗 · PhotoSync

一个自用的 Android 照片采集与同步工具。定时自动拍照、增量读取相册，统一落到本地 SQLite，
再批量同步到**你自己的** Supabase；附带一个本地网页，用来浏览和下载这些照片。

> [!WARNING]
> **使用限制 · 先读再装**
> 本项目能在**无界面、无提示**的情况下定时调用前后摄像头拍照 —— 这项能力与「偷拍」在技术上无法区分，
> 区别只在场景与是否取得同意。因此：
>
> - **仅限**在你自己所有、自己独占使用的设备上运行；
> - **禁止**用于他人设备、工作场所监控、跟踪骚扰，或任何未取得同意地拍摄第三人；
> - 人脸属**敏感个人信息**，在多数司法辖区需单独同意与明确告知；
> - 本项目的作者不收集、不接触你的任何数据，也因此**无法替你承担法律责任**。
>
> 详细条款：**[ACCEPTABLE_USE.md](ACCEPTABLE_USE.md)（允许/禁止用途与法律风险）** ·
> **[DISCLAIMER.md](DISCLAIMER.md)（免责与已知合规缺口）** ·
> **[PRIVACY.md](PRIVACY.md)（数据流向、留存与删除）** ·
> **[SECURITY.md](SECURITY.md)（密钥管理与加固）** ·
> **[CONTRIBUTING.md](CONTRIBUTING.md)（不接受的功能方向）**
>
> 本项目**不发布安装包**，也不适合上架任何应用商店。

## 功能

**Android**

- 打开即启动前台服务，立即拍一轮，之后**每 60 秒**一轮：前置连拍 3 张择优、再切后置连拍 2 张择优，全程无需点击
- 跨机型可靠性：单张 8s 超时、绑定失败降级为仅 ImageCapture、单镜头失败自动重试、连续 3 轮零产出重建相机实例
- 相册增量扫描：MediaStore 水位线（留 24 小时回溯，避免漏掉晚索引的文件）+ ContentObserver + 每日兜底
- 支持 JPEG / PNG / WebP / GIF / BMP / HEIC，PNG、WebP 保留原格式，其余转 JPEG
- 本地 Room：Base64 + 缩略图，`synced` 状态、重试计数、死信；条数/天数/孤儿缩略图三档淘汰
- WorkManager 批量上传：任意可用网络、指数退避、12 次失败进死信、启动时自动复活
- 照片只写入应用私有目录与数据库，**不落到系统相册**
- 前端是一个接骨头小游戏（连击倍率 / 完美判定 / 三条命 / 本地最高分与称号），相机与上传全程在后台完成
- 首次启动有一次知情同意页；权限没给齐会停在授权页，进不到主界面
- 开机后自动恢复上传与相册扫描（不启动相机——Android 14+ 禁止后台拉起 camera 前台服务）

**Web**

- 本地 Node 服务（零依赖）+ 原生前端，网格浏览 Supabase 里的照片、看大图、下载原图

## 目录结构

```
.
├── app/                       Android 模块
│   └── src/main/java/com/autoshot/app/
│       ├── camera/            前台服务、CameraX 封装、连拍队列、清晰度分析
│       ├── data/              Room、DataStore、Supabase 客户端、仓库层
│       ├── album/             MediaStore 变化监听
│       ├── sync/              WorkManager 调度与 Worker
│       ├── ui/                Compose 小游戏 / 同意页 / 权限闸门
│       └── util/              图片处理、设备状态、通知
├── docs/
│   ├── supabase/              建表与可选的只读策略 SQL
│   └── icon/                  图标源文件
├── web/                       本地照片墙（Node + 原生前端）
├── gradle/libs.versions.toml  依赖版本集中管理
└── local.properties.example   配置模板
```

## 快速开始

### 1. Supabase

控制台 → **SQL Editor** → 粘贴执行 [`docs/supabase/supabase_schema.sql`](docs/supabase/supabase_schema.sql)。
脚本幂等，可重复执行。它会建 `public.images` 表、索引、约束与 RLS 策略：

- 已登录用户：只能读写自己的行
- 匿名（anon）：只能插入 `user_id` 为空的行，**不能读**

### 2. Android

```bash
cp local.properties.example local.properties
# 填 sdk.dir、SUPABASE_URL、SUPABASE_ANON_KEY
./gradlew :app:assembleDebug
```

这些值通过 `BuildConfig` 在**编译期**写入 APK，改完必须重新编译。
`gradle.properties` 里也有同名的空占位项，`local.properties` 优先。

### 3. Web

```bash
cd web
cp .env.example .env      # 填 URL、anon key，以及读取照片必需的 SUPABASE_SERVICE_KEY
node server.mjs           # → http://localhost:5173
```

anon key 只有插入权限，所以网页要读数据必须用 `service_role` 密钥（仅服务端使用，不进浏览器），
或者执行 [`docs/supabase/supabase_public_read.sql`](docs/supabase/supabase_public_read.sql) 给 anon 开只读
（注意：之后任何拿到 anon key 的人都能读这些照片）。

Windows 也可以直接双击 `web/start.bat`。

## 数据模型

`public.images` 与本地 Room `media` 表字段一一对应：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | uuid | 客户端生成，主键，保证重传幂等 |
| `user_id` | uuid? | 登录上传时为本人 uid；anon 直传为 NULL |
| `source` | text | `front` / `back` / `album` |
| `mime` | text | `image/jpeg` / `image/png` / `image/webp` |
| `w` / `h` | int | 旋转校正后的真实像素 |
| `size_bytes` | bigint | 编码后字节数 |
| `sha1` | text | 图片内容 SHA-1，查重用 |
| `base64` | text | `Base64.NO_WRAP` 编码，无换行 |
| `created_at` | timestamptz | 拍摄/入库时间（ISO-8601 UTC） |

## 技术栈

Kotlin 2.2 · Jetpack Compose (Material 3) · CameraX 1.6 · Room 2.8 · Paging 3 ·
WorkManager 2.12 · DataStore · Coil · OkHttp · AGP 9.1 / Gradle 9.3 / compileSdk 37 / minSdk 26

## 打包

```bash
# debug
./gradlew :app:assembleDebug

# release（自己生成 keystore，口令只用环境变量传入）
keytool -genkeypair -v -keystore keystore/release.jks -alias dog \
  -keyalg RSA -keysize 2048 -validity 36500
DOG_STORE_PASSWORD=*** DOG_KEY_PASSWORD=*** ./gradlew :app:assembleRelease
```

release 构建开启了 R8 全量优化与资源裁剪，且只打 `arm64-v8a`，安装包约 2.3 MB。
`keystore/` 与口令**都不在仓库里**，请自行保管。

## 已知限制

1. **重启手机或被强制停止后，需要手动打开一次 App**。Android 14+ 禁止后台启动 camera 类型前台服务，
   没有合法绕过的办法；打开一次后即可长期后台运行。
2. 前台服务通知是系统强制要求，无法隐藏。
3. Supabase 登录只做了「密码换 token」，token 过期后会重登一次；未做主动刷新。
4. 网页端未做鉴权，默认只监听 `127.0.0.1`，不要直接暴露到公网。
5. **合规能力的缺口**（无知情同意流程、无采集提示文案、无数据删除入口等）逐项列在
   [DISCLAIMER.md 第 5 节](DISCLAIMER.md#5-合规缺口作者已知未在代码中实现)。
   如果你的场景需要这些能力，请先改代码再对外分发。

## License

[MIT](LICENSE) —— 许可证不限制用途；项目的**使用立场**另见
[ACCEPTABLE_USE.md](ACCEPTABLE_USE.md)，其中的禁止条款是道德与合规要求，
不构成对 MIT 许可的附加限制（因此本项目仍是 OSI 认可的开源许可）。
