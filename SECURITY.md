# 安全策略

## 报告漏洞

请**不要**开公开 issue。用 GitHub 的 [Private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
或直接邮件联系维护者。请在报告里包含：

- 影响范围（泄露数据 / 越权 / 远程执行 …）
- 复现步骤或 PoC
- 你使用的版本与设备信息

## 密钥管理红线

| 密钥 | 能否进客户端 | 能否进仓库 | 说明 |
| --- | --- | --- | --- |
| `SUPABASE_ANON_KEY` | ✅ 可以 | ❌ 不提交 | 会编译进 APK，视为半公开；默认无读权限 |
| `SUPABASE_SERVICE_KEY` | ❌ **绝对不行** | ❌ **绝对不行** | 等同数据库管理员，泄露即全量数据暴露 |
| `SUPABASE_PASSWORD`（自动登录用） | ⚠️ 会被编进 APK | ❌ 不提交 | 若在意，改用 anon 直传 + 服务端网页读取 |
| 签名 `keystore` 与口令 | — | ❌ 不提交 | 丢失则无法升级同一个应用 |

.env 与 local.properties 都已在 `.gitignore` 中。**分享压缩包时请手动排除它们**——
`.gitignore` 只对 git 生效，打包不会自动跳过。

## 若密钥已经泄露

1. **Supabase → Project Settings → API → Rotate** 立即轮换 `service_role` 与 `anon`；
2. 检查 `public.images` 是否有异常读取（Supabase 的 Logs / Reports）；
3. 重新编译并分发新 APK（anon key 变了）；
4. 若 `service_role` 泄露过，视为数据已泄露，评估是否需要通知受影响人员
   （《个人信息保护法》第 57 条要求发生泄露时告知并报告）。

## 部署加固清单

- [ ] `web/` 只监听 `127.0.0.1`，不直接对公网开放（如需远程访问，用 SSH 隧道或反代 + 鉴权）
- [ ] Supabase 开启 2FA，关闭不需要的登录方式
- [ ] 定期轮换密钥；不要把密钥写进截图、日志、issue
- [ ] 手机开启锁屏密码与磁盘加密（本地数据库是明文的）
- [ ] 关注依赖漏洞：`./gradlew dependencyUpdates` 或 Dependabot

## 支持的版本

仅对**最新提交**提供安全修复。这是个人项目，不承诺 SLA。
