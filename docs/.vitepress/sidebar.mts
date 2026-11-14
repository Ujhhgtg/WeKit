import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { basename, join, relative, sep } from 'node:path'
import type { DefaultTheme } from 'vitepress'

const categoryOrder = [
  'chat', 'contacts', 'payment', 'moments', 'system', 'voip', 'notifications',
  'beautify', 'official_accounts', 'miniapps', 'shortvideos', 'profile', 'debug',
  'scripting_java', 'scripting_python', 'entertain', 'batch',
  'home_screen_menu', 'contact_details',
]
const collator = new Intl.Collator('zh-CN')

export function pageTitle(file: string): string {
  const source = readFileSync(file, 'utf8').replace(/^\uFEFF/, '')
    .replace(/^---\s*\r?\n[\s\S]*?\r?\n---\s*(?:\r?\n|$)/, '')
  let fence: string | undefined
  for (const line of source.split(/\r?\n/)) {
    const marker = line.match(/^\s{0,3}(`{3,}|~{3,})/)
    if (marker) {
      if (!fence) fence = marker[1]
      else if (marker[1][0] === fence[0] && marker[1].length >= fence.length) fence = undefined
      continue
    }
    if (fence) continue
    const heading = line.match(/^#\s+(.+?)\s*#*\s*$/)
    if (heading) return heading[1].replace(/\[([^\]]+)\]\([^)]*\)/g, '$1').replace(/[*`]/g, '')
  }
  return basename(file, '.md')
}

function pageLink(root: string, file: string): string {
  return '/' + relative(root, file).split(sep).join('/').replace(/(^|\/)index\.md$/, '$1').replace(/\.md$/, '')
}

function scan(root: string, directory: string): DefaultTheme.SidebarItem[] {
  if (!existsSync(directory)) return []
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const file = join(directory, entry.name)
    if (entry.name.startsWith('.') || entry.name === 'README.md') return []
    if (entry.isDirectory()) {
      const items = scan(root, file)
      if (!items.length) return []
      const index = join(file, 'index.md')
      return [{ text: existsSync(index) ? pageTitle(index) : entry.name, collapsed: true, items }]
    }
    if (!entry.isFile() || !entry.name.endsWith('.md')) return []
    return [{ text: pageTitle(file), link: pageLink(root, file) }]
  }).sort((a, b) => {
    const aIndex = a.link?.endsWith('/') ? 0 : 1
    const bIndex = b.link?.endsWith('/') ? 0 : 1
    return aIndex - bIndex || collator.compare(a.text!, b.text!) || (a.link ?? a.text!).localeCompare(b.link ?? b.text!, 'en')
  })
}

export function createSidebar(root: string): DefaultTheme.SidebarItem[] {
  const guides = [
    'index', 'getting-started', 'installation', 'zygisk', 'configuration',
    'module-settings', 'faq', 'bug-report-guide', 'feature-request-guide', 'cosmic-level-disclaimer',
  ].map(name => ({ text: pageTitle(join(root, name + '.md')), link: name === 'index' ? '/' : '/' + name }))
  const featuresRoot = join(root, 'features')
  const categories = readdirSync(featuresRoot, { withFileTypes: true })
    .filter(entry => entry.isDirectory() && !entry.name.startsWith('.'))
    .sort((a, b) => {
      const rank = (name: string) => categoryOrder.includes(name) ? categoryOrder.indexOf(name) : categoryOrder.length
      return rank(a.name) - rank(b.name) || a.name.localeCompare(b.name, 'en')
    }).flatMap(entry => {
      const directory = join(featuresRoot, entry.name)
      const items = scan(root, directory)
      if (!items.length) return []
      const readme = join(directory, 'README.md')
      const text = existsSync(readme) ? pageTitle(readme).replace(/功能$/, '') : entry.name
      return [{ text, collapsed: true, items }]
    })
  return [
    { text: '使用指南', items: guides },
    { text: '功能文档', items: categories },
    { text: '开发与贡献', collapsed: true, items: [
      { text: '开发指南', items: scan(root, join(root, 'development')) },
      { text: '翻译贡献', items: scan(root, join(root, 'translations')) },
    ] },
  ]
}
