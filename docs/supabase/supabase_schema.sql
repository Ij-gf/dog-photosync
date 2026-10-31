-- ============================================================
-- 自动拍摄同步 · Supabase 初始化脚本
-- 用法：Supabase 控制台 → SQL Editor → New query → 全文粘贴 → Run
-- 特性：可重复执行（幂等），重复跑不会报错、不会丢数据
-- ============================================================

-- ---------- 1. 建表 ----------
create table if not exists public.images (
    id          uuid        primary key,
    user_id     uuid        references auth.users (id) on delete cascade,
    source      text        not null,
    mime        text        not null default 'image/jpeg',
    w           integer     not null default 0,
    h           integer     not null default 0,
    size_bytes  bigint      not null default 0,
    sha1        text,
    base64      text        not null,
    created_at  timestamptz not null default now()
);

-- 已有旧表时补齐新增列（幂等）
alter table public.images add column if not exists size_bytes bigint not null default 0;
alter table public.images add column if not exists sha1       text;
alter table public.images add column if not exists w          integer not null default 0;
alter table public.images add column if not exists h          integer not null default 0;

-- 取值约束：只允许三种来源、只允许图片 MIME
alter table public.images drop constraint if exists images_source_check;
alter table public.images
    add constraint images_source_check check (source in ('front', 'back', 'album'));

alter table public.images drop constraint if exists images_mime_check;
alter table public.images
    add constraint images_mime_check check (mime like 'image/%');

-- ---------- 2. 索引 ----------
create index if not exists images_user_created_idx
    on public.images (user_id, created_at desc);

create index if not exists images_source_created_idx
    on public.images (source, created_at desc);

-- sha1 只建普通索引用于查重（不建唯一索引：批量插入时任何一条重复都会让整批失败）
create index if not exists images_sha1_idx
    on public.images (sha1)
    where sha1 is not null;

-- ---------- 3. 行级安全 ----------
alter table public.images enable row level security;

-- 3.1 已登录账号：只能碰自己的行
drop policy if exists "images_insert_own" on public.images;
create policy "images_insert_own"
    on public.images
    for insert
    to authenticated
    with check (auth.uid() = user_id);

drop policy if exists "images_select_own" on public.images;
create policy "images_select_own"
    on public.images
    for select
    to authenticated
    using (auth.uid() = user_id);

drop policy if exists "images_update_own" on public.images;
create policy "images_update_own"
    on public.images
    for update
    to authenticated
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

drop policy if exists "images_delete_own" on public.images;
create policy "images_delete_own"
    on public.images
    for delete
    to authenticated
    using (auth.uid() = user_id);

-- 3.2 未登录（anon key 直传）：只允许插入 user_id 为空的行，且不能读、不能改、不能删
drop policy if exists "images_insert_anon" on public.images;
create policy "images_insert_anon"
    on public.images
    for insert
    to anon
    with check (user_id is null);

-- ---------- 4. 授权（PostgREST 需要显式 GRANT） ----------
grant usage on schema public to anon, authenticated;

grant insert on public.images to anon;
grant select, insert, update, delete on public.images to authenticated;
grant all on public.images to service_role;

-- ---------- 5. 自检（可单独执行） ----------
-- 表结构：
--   select column_name, data_type, is_nullable, column_default
--   from information_schema.columns where table_schema = 'public' and table_name = 'images'
--   order by ordinal_position;
-- 策略：
--   select policyname, cmd, roles, qual, with_check from pg_policies where tablename = 'images';
-- 数据量：
--   select source, count(*), min(created_at), max(created_at) from public.images group by source;
