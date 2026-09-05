import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
import { defineConfig } from 'vitepress'
import { createSidebar } from './sidebar.mts'

const root = fileURLToPath(new URL('..', import.meta.url))

export default defineConfig(() => {
  const sidebar = createSidebar(root)
  return {
    lang: 'zh-CN',
    title: 'WeKit',
    description: 'WeKit 开发版文档：安装、配置、微信增强功能与开发贡献指南。',
    cleanUrls: true,
    srcExclude: ['SUMMARY.md', 'features/**/README.md'],
    themeConfig: {
      nav: [
        { text: '使用指南', link: '/getting-started' },
        { text: '功能', link: '/module-settings#功能设置' },
        { text: '开发与贡献', link: '/development/' },
      ],
      sidebar,
      socialLinks: [{ icon: 'github', link: 'https://github.com/Ujhhgtg/WeKit-Dev' }],
      editLink: { pattern: 'https://github.com/Ujhhgtg/WeKit-Dev/edit/master/docs/:path', text: '在 GitHub 上编辑此页' },
      outline: { label: '本页目录', level: [2, 3] },
      docFooter: { prev: '上一页', next: '下一页' },
      sidebarMenuLabel: '目录',
      returnToTopLabel: '返回顶部',
      darkModeSwitchLabel: '主题',
      lightModeSwitchTitle: '切换到浅色模式',
      darkModeSwitchTitle: '切换到深色模式',
      notFound: { code: '404', title: '页面不存在', quote: '请通过目录或搜索查找当前文档。', linkLabel: '返回首页', linkText: '返回首页' },
      search: {
        provider: 'local',
        options: {
          translations: {
            button: { buttonText: '搜索文档', buttonAriaLabel: '搜索文档' },
            modal: {
              displayDetails: '显示详细内容', resetButtonTitle: '清除搜索', backButtonTitle: '关闭搜索',
              noResultsText: '没有找到相关内容',
              footer: { selectText: '选择', navigateText: '切换', closeText: '关闭' },
            },
          },
          miniSearch: { options: {
            // This function is serialized for the browser: keep it self-contained.
            tokenize(text: string) {
              if (typeof Intl.Segmenter === 'function') {
                return Array.from(new Intl.Segmenter('zh-CN', { granularity: 'word' }).segment(text))
                  .filter(part => part.isWordLike).map(part => part.segment)
              }
              // Old browsers: preserve Latin words and index Chinese character pairs.
              return (text.match(/[\p{L}\p{N}_]+/gu) ?? []).flatMap(word => {
                if (!/\p{Script=Han}/u.test(word)) return [word]
                const characters = Array.from(word)
                return [word, ...characters, ...characters.slice(1).map((char, i) => characters[i] + char)]
              })
            },
          } },
        },
      },
    },
    vite: { plugins: [{
      name: 'wekit-document-navigation',
      configureServer(server) {
        let previous = JSON.stringify(sidebar)
        let timer: ReturnType<typeof setTimeout> | undefined
        const update = (file: string) => {
          if (!file.startsWith(root) || !file.endsWith('.md') || file.includes('/node_modules/')) return
          clearTimeout(timer)
          timer = setTimeout(() => {
            const next = JSON.stringify(createSidebar(root))
            if (next !== previous) {
              previous = next
              // Vite's restart reuses VitePress site data. Its config watcher
              // reloads the site configuration and recreates that data too.
              server.watcher.emit('change', join(root, '.vitepress/config.mts'))
            }
          }, 150)
        }
        server.watcher.on('add', update).on('unlink', update).on('change', update)
        server.httpServer?.once('close', () => {
          clearTimeout(timer)
          server.watcher.off('add', update).off('unlink', update).off('change', update)
        })
      },
    }] },
  }
})
