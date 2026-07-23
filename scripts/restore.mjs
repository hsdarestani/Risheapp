import fs from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

const gzipEntries = [
  ['generated/App.tsx.gz.b64', 'src/App.tsx'],
  ['generated/styles.css.gz.b64', 'src/styles.css'],
]

const base64Entries = [
  ['generated/icon-192.png.b64', 'public/icon-192.png'],
  ['generated/icon-512.png.b64', 'public/icon-512.png'],
]

for (const [source, target] of gzipEntries) {
  const encoded = fs.readFileSync(path.join(root, source), 'utf8').trim()
  const content = zlib.gunzipSync(Buffer.from(encoded, 'base64'))
  const destination = path.join(root, target)
  fs.mkdirSync(path.dirname(destination), { recursive: true })
  fs.writeFileSync(destination, content)
}

for (const [source, target] of base64Entries) {
  const encoded = fs.readFileSync(path.join(root, source), 'utf8').trim()
  const destination = path.join(root, target)
  fs.mkdirSync(path.dirname(destination), { recursive: true })
  fs.writeFileSync(destination, Buffer.from(encoded, 'base64'))
}

console.log('Restored generated frontend source files and app icons.')
