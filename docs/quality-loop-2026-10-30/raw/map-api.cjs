const fs = require('fs')
const path = require('path')

const root = 'frontend/src'
const calls = []

function walk(dir) {
  for (const name of fs.readdirSync(dir)) {
    const p = path.join(dir, name)
    const st = fs.statSync(p)
    if (st.isDirectory()) {
      walk(p)
    } else if (/\.(ts|vue)$/.test(name) && !name.endsWith('.test.ts')) {
      const s = fs.readFileSync(p, 'utf8')
      const re = /["'`](\/api\/[^"'`$\s]*)["'`]/g
      let m
      while ((m = re.exec(s))) {
        calls.push({ file: p.split(path.sep).join('/').replace('frontend/src/', ''), path: m[1] })
      }
    }
  }
}

walk(root)

const eps = JSON.parse(fs.readFileSync('docs/quality-loop-2026-10-30/raw/backend-endpoints.json', 'utf8'))
const norm = (p) => p.replace(/\$\{[^}]+\}/g, '{x}').replace(/\{[^}]+\}/g, '{x}').replace(/\?.*$/, '').replace(/\/+$/, '')
const eset = new Map()
for (const e of eps) eset.set(e.method + ' ' + norm(e.path), e)

const used = new Set()
for (const c of calls) {
  const n = norm(c.path)
  for (const k of eset.keys()) {
    const [m, p] = k.split(' ')
    if (n === p || n.startsWith(p + '/') || (p.startsWith(n) && n.includes('{'))) used.add(k)
  }
}

const frontendPaths = [...new Set(calls.map((c) => c.path))].sort()
console.log('FRONTEND DISTINCT PATHS', frontendPaths.length)
const missing = [...eset.keys()].filter((k) => !used.has(k))
console.log('\n=== ENDPOINTS WITH NO FRONTEND REFERENCE (' + missing.length + ') ===')
for (const k of missing.sort()) console.log(k, '<-', eset.get(k).file)
console.log('\n=== FRONTEND PATHS ===')
for (const p of frontendPaths) console.log(p)
fs.writeFileSync('docs/quality-loop-2026-10-30/raw/frontend-api-calls.json', JSON.stringify(frontendPaths, null, 2))
