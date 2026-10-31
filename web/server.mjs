// 小狗 · 照片墙服务（零依赖 Node，只用内置模块）
// 读取同目录 .env → 代理 Supabase → 对外提供列表 / 图片 / 下载

import { createServer } from 'node:http'
import { readFileSync, existsSync } from 'node:fs'
import { join, extname, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = dirname(fileURLToPath(import.meta.url))
const PUBLIC = join(ROOT, 'public')
const PORT = Number(process.env.PORT || 5173)

// ---------------- 读取 .env ----------------
function loadEnv() {
  const file = join(ROOT, '.env')
  const out = {}
  if (!existsSync(file)) return out
  for (const raw of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const eq = line.indexOf('=')
    if (eq < 0) continue
    const k = line.slice(0, eq).trim()
    let v = line.slice(eq + 1).trim()
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) {
      v = v.slice(1, -1)
    }
    out[k] = v
  }
  return out
}

const env = loadEnv()
const BASE = (env.SUPABASE_URL || '').replace(/\/+$/, '')
const ANON = env.SUPABASE_ANON_KEY || ''
const SERVICE = env.SUPABASE_SERVICE_KEY || ''
const EMAIL = env.SUPABASE_EMAIL || ''
const PASSWORD = env.SUPABASE_PASSWORD || ''

if (!BASE || !ANON) {
  console.error('[!] web/.env 缺少 SUPABASE_URL 或 SUPABASE_ANON_KEY')
}

// ---------------- 鉴权 / 请求 ----------------
let token = null

async function signIn() {
  const res = await fetch(`${BASE}/auth/v1/token?grant_type=password`, {
    method: 'POST',
    headers: { apikey: ANON, 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: EMAIL, password: PASSWORD }),
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`登录失败(${res.status}): ${text.slice(0, 200)}`)
  token = JSON.parse(text).access_token
  return token
}

function authHeaders() {
  if (SERVICE) return { apikey: SERVICE, Authorization: `Bearer ${SERVICE}` }
  const bearer = token || ANON
  return { apikey: ANON, Authorization: `Bearer ${bearer}` }
}

async function rest(path, { retry = true } = {}) {
  if (!SERVICE && EMAIL && PASSWORD && !token) await signIn().catch(() => {})
  const res = await fetch(`${BASE}/rest/v1/${path}`, { headers: authHeaders() })
  if (res.status === 401 && retry && EMAIL && PASSWORD) {
    token = null
    await signIn().catch(() => {})
    return rest(path, { retry: false })
  }
  return res
}

function rlsHint(status, body) {
  if (status !== 401 && status !== 403 && status !== 400) return null
  if (!/row-level security|permission denied/i.test(body)) return null
  return {
    hint: 'anon key 没有读取权限，二选一：',
    options: [
      '① 在 web/.env 里填 SUPABASE_SERVICE_KEY（Project Settings → API → service_role secret），重启服务；',
      '② 或在 Supabase SQL Editor 执行 supabase_public_read.sql（会让拿到 anon key 的人也能读照片）。',
    ],
  }
}

// ---------------- 接口 ----------------
const FIELDS = 'id,source,mime,w,h,size_bytes,created_at'

async function handlePhotos(url, res) {
  const limit = Math.min(Number(url.searchParams.get('limit') || 60), 120)
  const offset = Math.max(Number(url.searchParams.get('offset') || 0), 0)
  const q = `images?select=${FIELDS}&order=created_at.desc&limit=${limit + 1}&offset=${offset}`
  const r = await rest(q)
  const text = await r.text()
  if (!r.ok) {
    res.writeHead(r.status, { 'Content-Type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify({ error: `读取失败(${r.status})`, detail: text.slice(0, 300), ...(rlsHint(r.status, text) || {}) }))
    return
  }
  const rows = JSON.parse(text)
  const hasMore = rows.length > limit
  const mode = SERVICE ? 'service' : EMAIL ? 'account' : 'anon'
  const payload = { items: rows.slice(0, limit), hasMore, offset, limit, mode }
  // anon key 没有 select 策略：请求成功但永远返回 0 行，这里直接给出人话提示
  if (mode === 'anon') {
    payload.hint =
      'anon key 没有读取权限（RLS 把行全部过滤掉了，所以看起来是空的）。二选一：\n' +
      '① 在 web/.env 填 SUPABASE_SERVICE_KEY 后重启服务（推荐，密钥只留本机）；\n' +
      '② 或在 Supabase SQL Editor 执行 supabase_public_read.sql（会让拿到 anon key 的人也能读照片）。'
  }
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
  res.end(JSON.stringify(payload))
}

async function handleImage(id, res, download) {
  const r = await rest(`images?id=eq.${encodeURIComponent(id)}&select=base64,mime,created_at,source`)
  const text = await r.text()
  if (!r.ok || !text.startsWith('[')) {
    res.writeHead(r.status || 500).end('读取失败')
    return
  }
  const rows = JSON.parse(text)
  if (!rows.length || !rows[0].base64) {
    res.writeHead(404).end('不存在')
    return
  }
  const row = rows[0]
  const buf = Buffer.from(row.base64, 'base64')
  const mime = row.mime || 'image/jpeg'
  const ext = mime.includes('png') ? 'png' : mime.includes('webp') ? 'webp' : 'jpg'
  const headers = {
    'Content-Type': mime,
    'Content-Length': buf.length,
    'Cache-Control': 'public, max-age=31536000, immutable',
  }
  if (download) {
    const stamp = String(row.created_at || '').replace(/[:T]/g, '-').slice(0, 19)
    const name = `dog_${stamp}_${String(id).slice(0, 8)}.${ext}`
    headers['Content-Disposition'] = `attachment; filename="${name}"`
  }
  res.writeHead(200, headers).end(buf)
}

// ---------------- 静态文件 ----------------
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
}

function serveStatic(pathname, res) {
  const rel = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '')
  const file = join(PUBLIC, rel)
  if (!file.startsWith(PUBLIC)) {
    res.writeHead(403).end('forbidden')
    return
  }
  if (!existsSync(file)) {
    res.writeHead(404).end('not found')
    return
  }
  res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' })
  res.end(readFileSync(file))
}

// ---------------- 路由 ----------------
createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`)
  try {
    if (url.pathname === '/api/photos') return await handlePhotos(url, res)
    if (url.pathname.startsWith('/api/photo/')) {
      return await handleImage(decodeURIComponent(url.pathname.slice('/api/photo/'.length)), res, false)
    }
    if (url.pathname.startsWith('/api/download/')) {
      return await handleImage(decodeURIComponent(url.pathname.slice('/api/download/'.length)), res, true)
    }
    if (url.pathname === '/api/config') {
      res.writeHead(200, { 'Content-Type': 'application/json' })
      return res.end(JSON.stringify({
        mode: SERVICE ? 'service' : EMAIL ? 'account' : 'anon',
        url: BASE.replace(/^(https:\/\/)([^.]+).*/, '$1$2…'),
      }))
    }
    return serveStatic(url.pathname, res)
  } catch (e) {
    console.error(e)
    res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' }).end('服务异常：' + e.message)
  }
}).listen(PORT, '127.0.0.1', () => {
  console.log(`小狗照片墙  →  http://localhost:${PORT}`)
  console.log(`Supabase   →  ${BASE || '(未配置)'}`)
  console.log(`鉴权模式    →  ${SERVICE ? 'service_role' : EMAIL ? '账号' : 'anon key'}`)
})
