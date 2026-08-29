import express from 'express'
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { fileURLToPath } from 'node:url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const root = path.resolve(__dirname, '..')
const dist = path.join(root, 'dist')
const dataDir = process.env.DATA_DIR || path.join(root, 'data')
const returnsFile = path.join(dataDir, 'returns.json')
const port = Number(process.env.PORT || 3000)
const wooBase = (process.env.WOOCOMMERCE_URL || 'https://rishe.store').replace(/\/$/, '')
const adminToken = process.env.ADMIN_TOKEN || ''

const fallbackProducts = [
  { id: 101, name: 'برنج هاشمی درجه یک', price: '۲۹۸٬۰۰۰', regularPrice: '', image: '', permalink: 'https://rishe.store', category: 'برنج' },
  { id: 102, name: 'لوبیا چیتی زنجان', price: '۱۹۸٬۰۰۰', regularPrice: '', image: '', permalink: 'https://rishe.store', category: 'حبوبات' },
  { id: 103, name: 'عسل طبیعی', price: '۴۹۸٬۰۰۰', regularPrice: '', image: '', permalink: 'https://rishe.store', category: 'عسل' },
  { id: 104, name: 'چای شمال', price: '۲۲۸٬۰۰۰', regularPrice: '', image: '', permalink: 'https://rishe.store', category: 'چای' },
]

let productCache = { expiresAt: 0, products: fallbackProducts }
let writeQueue = Promise.resolve()

async function ensureData() {
  await fs.mkdir(dataDir, { recursive: true })
  try { await fs.access(returnsFile) } catch { await fs.writeFile(returnsFile, '[]', 'utf8') }
}

function stripHtml(value = '') {
  return String(value).replace(/<[^>]+>/g, '').replace(/&amp;/g, '&').replace(/&quot;/g, '"').trim()
}

function formatStorePrice(raw) {
  const minor = Number(raw?.prices?.price || 0)
  const unit = Number(raw?.prices?.currency_minor_unit ?? 0)
  const value = unit ? minor / (10 ** unit) : minor
  return new Intl.NumberFormat('fa-IR').format(value)
}

function normalizeProduct(product) {
  return {
    id: Number(product.id),
    name: stripHtml(product.name),
    price: formatStorePrice(product),
    regularPrice: product?.prices?.regular_price ? formatStorePrice({ prices: { ...product.prices, price: product.prices.regular_price } }) : '',
    image: product?.images?.[0]?.src || '',
    permalink: product.permalink || `${wooBase}/?p=${product.id}`,
    category: stripHtml(product?.categories?.[0]?.name || 'مزرعه پدری'),
  }
}

async function fetchProducts() {
  if (Date.now() < productCache.expiresAt) return productCache.products
  try {
    const response = await fetch(`${wooBase}/wp-json/wc/store/v1/products?per_page=40`, {
      headers: { 'User-Agent': 'RisheApp/1.0' }, signal: AbortSignal.timeout(9000),
    })
    if (!response.ok) throw new Error(`WooCommerce returned ${response.status}`)
    const body = await response.json()
    const products = Array.isArray(body) ? body.map(normalizeProduct).filter(p => p.name && p.price !== '۰') : []
    if (!products.length) throw new Error('No products')
    productCache = { products, expiresAt: Date.now() + 10 * 60 * 1000 }
  } catch (error) {
    console.warn('[products] using fallback:', error.message)
    productCache = { products: fallbackProducts, expiresAt: Date.now() + 60 * 1000 }
  }
  return productCache.products
}

function cleanText(value, max = 500) {
  return String(value || '').trim().slice(0, max)
}

async function appendReturn(record) {
  writeQueue = writeQueue.then(async () => {
    let rows = []
    try { rows = JSON.parse(await fs.readFile(returnsFile, 'utf8')) } catch { rows = [] }
    rows.unshift(record)
    await fs.writeFile(returnsFile, JSON.stringify(rows.slice(0, 5000), null, 2), 'utf8')
  })
  return writeQueue
}

const app = express()
app.disable('x-powered-by')
app.use(express.json({ limit: '200kb' }))
app.use((req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff')
  res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin')
  res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()')
  next()
})

app.get('/api/health', (_req, res) => res.json({ ok: true, service: 'rishe-app', time: new Date().toISOString() }))
app.get('/api/products', async (_req, res) => res.json({ products: await fetchProducts(), source: productCache.products === fallbackProducts ? 'fallback' : 'woocommerce' }))

const eventRelayRoutes = [
  { method: 'POST', pattern: /^\/device-login$/ },
  { method: 'GET', pattern: /^\/bootstrap$/ },
  { method: 'GET', pattern: /^\/events\/\d+\/catalog$/ },
  { method: 'POST', pattern: /^\/sync$/ },
]

function isAllowedEventRelay(method, suffix) {
  return eventRelayRoutes.some(route => route.method === method && route.pattern.test(suffix))
}

app.all('/api/event-rishe/*', async (req, res) => {
  const suffix = `/${String(req.params[0] || '').replace(/^\/+/, '')}`
  const method = req.method.toUpperCase()
  if (!isAllowedEventRelay(method, suffix)) return res.status(404).json({ message: 'مسیر درخواست معتبر نیست.' })

  const headers = {
    Accept: 'application/json',
    'Content-Type': 'application/json; charset=utf-8',
    'User-Agent': 'Event-Rishe-Relay/1.1',
  }
  const bearer = cleanText(req.get('authorization') || '', 260)
  const tokenFromBearer = bearer.toLowerCase().startsWith('bearer ') ? cleanText(bearer.slice(7), 200) : ''
  const deviceToken = cleanText(req.get('x-rishe-event-token') || tokenFromBearer, 200)
  if (deviceToken) {
    headers['X-Rishe-Event-Token'] = deviceToken
    headers.Authorization = `Bearer ${deviceToken}`
  }

  try {
    const upstream = await fetch(`${wooBase}/wp-json/rishe/v1/event-sales${suffix}`, {
      method,
      headers,
      body: method === 'GET' || method === 'HEAD' ? undefined : JSON.stringify(req.body || {}),
      signal: AbortSignal.timeout(18000),
      redirect: 'follow',
    })
    const text = await upstream.text()
    res.status(upstream.status)
    res.type('application/json')
    return res.send(text || '{}')
  } catch (error) {
    console.error('[event-rishe-relay]', method, suffix, error?.message || error)
    const timeout = String(error?.name || '').toLowerCase().includes('timeout') || String(error?.message || '').toLowerCase().includes('timeout')
    return res.status(502).json({
      message: timeout
        ? 'سرور فروشگاه به‌موقع پاسخ نداد. دوباره تلاش کنید.'
        : 'ارتباط واسط با سرور فروشگاه برقرار نشد.',
    })
  }
})

app.post('/api/returns', async (req, res) => {
  const body = req.body || {}
  const required = ['name', 'mobile', 'order', 'product', 'details']
  if (required.some(key => !cleanText(body[key]))) return res.status(400).json({ error: 'اطلاعات کامل نیست.' })
  const ticket = `RSH-${new Date().toISOString().slice(2, 10).replaceAll('-', '')}-${crypto.randomInt(1000, 9999)}`
  const record = {
    ticket,
    name: cleanText(body.name, 100),
    mobile: cleanText(body.mobile, 30),
    order: cleanText(body.order, 60),
    product: cleanText(body.product, 120),
    reason: cleanText(body.reason, 200),
    details: cleanText(body.details, 1500),
    status: 'new',
    createdAt: new Date().toISOString(),
    ipHash: crypto.createHash('sha256').update(String(req.ip || '')).digest('hex').slice(0, 16),
  }
  await appendReturn(record)
  res.status(201).json({ ok: true, ticket })
})

app.get('/api/returns/:ticket', async (req, res) => {
  const rows = JSON.parse(await fs.readFile(returnsFile, 'utf8'))
  const found = rows.find(row => row.ticket === req.params.ticket)
  if (!found) return res.status(404).json({ error: 'کد پیدا نشد.' })
  res.json({ ticket: found.ticket, status: found.status, createdAt: found.createdAt })
})

app.get('/api/admin/returns', async (req, res) => {
  if (!adminToken || req.get('authorization') !== `Bearer ${adminToken}`) return res.status(404).end()
  const rows = JSON.parse(await fs.readFile(returnsFile, 'utf8'))
  res.json({ returns: rows })
})

app.use(express.static(dist, { maxAge: '1h', etag: true }))
app.get('*', (_req, res) => res.sendFile(path.join(dist, 'index.html')))

ensureData().then(() => {
  app.listen(port, '0.0.0.0', () => console.log(`Rishe app listening on :${port}`))
}).catch(error => {
  console.error(error)
  process.exit(1)
})
