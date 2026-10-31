# 小狗 · 照片墙（web）

浏览 Supabase `images` 表里的照片，点开可下载原图。

## 运行

```bash
cd D:/C/PhotoSync/web
node server.mjs
# 打开 http://localhost:5173
# Windows 也可直接双击 start.bat
```

Node 18+，零依赖，不需要 npm install。

## 配置（web/.env）

```properties
SUPABASE_URL=...
SUPABASE_ANON_KEY=...
SUPABASE_SERVICE_KEY=      # 读照片必须有这个（推荐）
```

anon key 只有插入权限（App 上传用），**没有读权限**，所以网页要读数据得二选一：

1. **推荐**：把 `service_role` secret 填进 `SUPABASE_SERVICE_KEY`
   （Supabase → Project Settings → API → service_role）。密钥只留在这台机器，服务端使用，不会进浏览器。
2. 或者在 SQL Editor 执行项目根目录的 `../docs/supabase/supabase_public_read.sql`，给 anon 加只读策略。
   代价是：拿到 anon key 的人（APK 里就有）也能读你所有照片。

## 接口

| 路径 | 作用 |
| --- | --- |
| `GET /api/photos?offset=0&limit=60` | 照片列表（不含 base64） |
| `GET /api/photo/:id` | 原图字节流（浏览器缓存一年） |
| `GET /api/download/:id` | 以附件形式下载 |
| `GET /api/config` | 当前鉴权模式 |
