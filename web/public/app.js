const grid = document.getElementById('grid')
const empty = document.getElementById('empty')
const loader = document.getElementById('loader')
const sentinel = document.getElementById('sentinel')
const notice = document.getElementById('notice')
const countEl = document.getElementById('count')
const lightbox = document.getElementById('lightbox')
const bigimg = document.getElementById('bigimg')
const download = document.getElementById('download')
const info = document.getElementById('info')

let offset = 0
let hasMore = true
let loading = false
let total = 0

const SOURCE_LABEL = { front: '前置', back: '后置', album: '相册' }

function fmtDate(s) {
  if (!s) return ''
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return s
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function showNotice(html) {
  notice.innerHTML = html
  notice.hidden = false
}

function cellOf(item) {
  const cell = document.createElement('div')
  cell.className = 'cell'

  const img = document.createElement('img')
  img.loading = 'lazy'
  img.decoding = 'async'
  img.alt = ''
  img.src = `/api/photo/${item.id}`
  img.addEventListener('load', () => img.classList.add('loaded'))
  img.addEventListener('error', () => {
    cell.style.background = 'var(--tile)'
  })

  const tag = document.createElement('span')
  tag.className = 'tag'
  tag.textContent = SOURCE_LABEL[item.source] || item.source

  cell.append(img, tag)
  cell.addEventListener('click', () => open(item))
  return cell
}

function open(item) {
  bigimg.src = `/api/photo/${item.id}`
  download.href = `/api/download/${item.id}`
  download.setAttribute('download', '')
  info.textContent = `${item.w}×${item.h} · ${fmtDate(item.created_at)}`
  lightbox.hidden = false
  document.body.style.overflow = 'hidden'
}

function close() {
  lightbox.hidden = true
  bigimg.src = ''
  document.body.style.overflow = ''
}

document.getElementById('close').addEventListener('click', close)
lightbox.addEventListener('click', (e) => {
  if (e.target === lightbox) close()
})
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && !lightbox.hidden) close()
})

async function load() {
  if (loading || !hasMore) return
  loading = true
  loader.hidden = false
  try {
    const res = await fetch(`/api/photos?offset=${offset}&limit=60`)
    const data = await res.json()

    if (data.error) {
      showNotice(
        `<b>${data.error}</b>\n` +
          (data.hint ? `${data.hint}\n` : '') +
          (data.options ? data.options.join('\n') : '') +
          (data.detail ? `\n\n${data.detail}` : '')
      )
      hasMore = false
      return
    }

    if (data.hint) {
      countEl.textContent = '只读受限'
      empty.hidden = true
      showNotice(`<b>读不到照片</b>\n${data.hint}`)
      hasMore = false
      return
    }

    for (const item of data.items) grid.append(cellOf(item))
    total += data.items.length
    countEl.textContent = `${total} 张`
    empty.hidden = total !== 0

    offset += data.items.length
    hasMore = data.hasMore && data.items.length > 0
  } catch (e) {
    showNotice(`<b>连接失败</b>\n${e.message}`)
    hasMore = false
  } finally {
    loading = false
    loader.hidden = true
  }
}

new IntersectionObserver(
  (entries) => {
    if (entries.some((e) => e.isIntersecting)) load()
  },
  { rootMargin: '600px' }
).observe(sentinel)

document.getElementById('refresh').addEventListener('click', () => {
  grid.innerHTML = ''
  offset = 0
  total = 0
  hasMore = true
  notice.hidden = true
  load()
})

load()
