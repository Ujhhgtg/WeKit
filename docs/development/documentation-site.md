# 文档站维护

文档位于代码仓库的 `docs/`，使用 VitePress 2 alpha 和 Bun。生产站点为 [docs.wekit.ujhhgtg.dev](https://docs.wekit.ujhhgtg.dev)，跟随 `Ujhhgtg/WeKit` 的 `master`，内容对应开发版。

## 本地命令

使用 `package.json` 指定的 Bun 版本，以及 `.node-version` 指定的 Node 版本：

```bash
cd docs
bun install --frozen-lockfile
bun run dev
```

提交前运行：

```bash
bun run build
bun run deploy:check
git diff --check
```

`bun run preview` 预览构建结果；`bunx wrangler dev --local` 检查 Workers 静态资源路由。依赖固定精确版本，更新时提交 `bun.lock`，不要提交其他包管理器的锁文件、缓存或站点产物。

## 新增和更新页面

在 `features/<分类>/<英文文件名>.md` 中编写页面，使用 `# 中文标题`。功能目录递归生成导航，中文标题排序；新增文件、分类或修改标题不需要编辑目录清单。分类名称来自该目录的 `README.md` H1，没有时使用目录名。

分类 README 仅保存标题和简述，不发布为独立页面。`SUMMARY.md` 不参与构建，也不再维护。站点、开发和翻译目录的首页使用 `index.md`，无需 rewrite。

一个功能只维护一篇主文档。核对当前功能对象的设置分类、实际入口、配置项、默认值和实现限制；复杂功能可以增加子页面。不要从功能名称或简介猜测行为。明确标为「没写完」或 TODO 的功能不写文档；功能被移除后，删除失效内容并清理引用，不写历史状态或旧入口迁移说明。

文档应随功能代码一起更新。普通开关可以用短文说明；有配置或运行时依赖的功能应写出完整首次使用步骤。不要把构建成功、源码存在或 DEX 解析通过当作真机验证。

## 链接与搜索

站内链接使用对应 Markdown 文件的相对路径，如 `[配置](../configuration.md)`，目录首页链接到 `index.md`。站点外源码使用明确的 GitHub 链接，不要通过 `../` 越出 docs。构建保留死链检测；修复原链接，不开启 `ignoreDeadLinks`。

代码围栏使用正确语言，纯文本使用 `text`。本地搜索通过中文分词支持短词检索，不需要配置外部搜索账户。

## Cloudflare 自动构建

部署使用纯 Workers Static Assets，不需要 Worker JS 或 `main`。资源目录为 `.vitepress/dist`；未知路径返回真实 404，不回退到首页。

| Workers Builds 设置 | 值 |
| --- | --- |
| Repository | `Ujhhgtg/WeKit` |
| Production branch | `master` |
| Root directory | `docs` |
| Build command | `bun install --frozen-lockfile && bun run build` |
| Deploy command | `bun run deploy` |
| Build variables | `BUN_VERSION=1.4.0`、`NODE_VERSION=24.15.0`、`SKIP_DEPENDENCY_INSTALL=1` |
| Build watch includes | `docs/*` |
| Build watch excludes | 空 |
| Non-production branch builds | 关闭 |

GitHub App 必须有仓库访问权限，构建使用独立的部署凭据。不要把本地 OAuth token 写入配置。Watch paths 属于 Cloudflare 控制面设置，不写进 wrangler.jsonc。正常 docs 变更触发构建；空 push、极大的文件/提交批次存在 Cloudflare 的路径匹配例外。

必要时可在 docs 中执行 `bun run deploy` 手工发布。回退内容时撤销有问题的文档提交，再经相同构建链部署。自动构建失败时先查看 Git 访问、Bun 安装和 build 日志，不跳过锁文件或死链检查。

## 人工检查

在桌面和窄屏浏览器检查首页、目录、中文短词搜索、编辑链接、上一页/下一页及直接刷新深层链接。开发服务器运行时新增、改名或删除一篇功能页，确认目录同步；删除测试文件后再提交。线上同时检查 HTTPS、图片加载和不存在页面的 404。

参考：[Workers Builds](https://developers.cloudflare.com/workers/ci-cd/builds/)、[构建路径规则](https://developers.cloudflare.com/workers/ci-cd/builds/build-watch-paths/)、[VitePress](https://vitepress.dev/)。
