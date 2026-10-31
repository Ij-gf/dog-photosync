-- ============================================================
-- 可选脚本：让「只用 URL + anon key」的网页端能读到照片
-- 用法：Supabase 控制台 → SQL Editor → 全文粘贴 → Run（可重复执行）
-- 前置：已执行过 supabase_schema.sql
--
-- 背景（实测）：
--   anon 角色对 public.images 只有 INSERT 权限，没有任何 SELECT 策略，
--   因此 GET /rest/v1/images 返回 200 但结果恒为空数组（RLS 静默过滤，不报错）。
--   这就是「网页端只显示空白」的根因。
--
-- ⚠️ 安全影响（必须知情后再执行）：
--   执行后，任何拿到本项目 anon key 的人都能读取 user_id 为空的行——
--   也就是 App 未配置 SUPABASE_EMAIL/PASSWORD 时上传的全部照片。
--   如果这些行里有不想外泄的内容，请改用另一种方案：
--   在 web/.env 填 SUPABASE_EMAIL / SUPABASE_PASSWORD，以 authenticated 身份读取，
--   这样不需要执行本脚本，且只能看到该账号自己的行。
-- ============================================================

-- 1) 匿名只读策略：仅放行 user_id 为空的行（账号登录上传的行依然对 anon 不可见）
drop policy if exists "images_select_anon" on public.images;
create policy "images_select_anon"
    on public.images
    for select
    to anon
    using (user_id is null);

-- 2) 显式授权（Supabase 新库默认已授予 anon SELECT，这里补一次保证幂等可跑）
grant usage on schema public to anon;
grant select on public.images to anon;

-- 3) 自检：应看到 images_select_anon 这一条，cmd = SELECT，roles 含 anon
-- select policyname, cmd, roles::text, qual from pg_policies where tablename = 'images';
