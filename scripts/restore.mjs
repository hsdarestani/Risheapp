import fs from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const entries = [
  ['generated/App.tsx.gz.b64', 'src/App.tsx'],
  ['generated/styles.css.gz.b64', 'src/styles.css'],
]

for (const [source, target] of entries) {
  const encoded = fs.readFileSync(path.join(root, source), 'utf8').trim()
  const content = zlib.gunzipSync(Buffer.from(encoded, 'base64'))
  const destination = path.join(root, target)
  fs.mkdirSync(path.dirname(destination), { recursive: true })
  fs.writeFileSync(destination, content)
}

console.log('Restored generated frontend source files.')
