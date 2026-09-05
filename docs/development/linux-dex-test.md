# Linux DexKit 解析器测试

本仓库提供了一个桌面端兼容性测试工具，用于运行与 Android 缓存流程相同的 DexKit
解析器代码。它不会安装微信或执行 Hook，只会检查当前源码中的匹配器能否在各 APK 的
DEX 文件中解析出目标。

```bash
./x dex-test
./x dex-test --apk ~/coding/wechat_8069.apk --apk ~/coding/wechat_8069_3020_play.apk
./x dex-test --output-dir /absolute/report/root --verbose
```

未指定 `--apk` 时，工具会对符合 `~/coding/wechat_*.apk` 的普通文件进行自然排序并逐一测试。
每个 APK 都会在独立的 JVM 工作进程中运行。默认情况下，报告会写入
`dex-test-results/<run-id>/`（也可使用指定的输出目录），每个 APK 对应一个 JSON 文件，
并生成汇总文件 `summary.json`。DexKit 源码和原生构建缓存保存在 `.wekit/dex-test/` 下。

委托项的状态如下：

- `SUCCESS`：成功解析出真实描述符；
- `EXPECTED_FAILURE`：`allowFailure = true` 设置了对应的占位值；
- `UNEXPECTED_FAILURE`：查找或解析器抛出异常，或使用了未分类的占位值；
- `BLOCKED`：前面的委托项失败后，后续委托项未运行；
- `INCOMPLETE`：解析器返回时，该委托项仍未完成解析。

仅出现预期失败时，退出码为 0。若发生任何非预期失败、阻塞、未完成、功能初始化失败、
工作进程失败、APK 失败、原生构建失败或报告失败，工具会在尝试其余 APK 后返回非零退出码。
源码解析报告通过，并不能证明 Hook 在实体设备上运行时的行为正确。

首次运行需要 JDK 21 环境、Android SDK `apkanalyzer`、CMake、Ninja、Git，以及用于拉取
固定版本 DexKit 源码的网络连接。固定版本的 Linux 原生库根据
`gradle/libs.versions.toml` 中的 DexKit 版本构建；复用缓存前，工具会验证缓存检出内容的
版本。

## 离线比较版本报告

现有报告的 `descriptor` 已包含方法／构造函数的完整参数和返回类型，不需要扩展报告格式、
重新解析 APK 或启动 JVM。传入两个或更多**单 APK 报告**，按指定顺序比较相邻项：

```bash
./x dex-report-diff /path/wechat_8076.json /path/wechat_8077.json /path/wechat_8078.json
./x dex-report-diff /path/old.json /path/new.json --output /path/version-diff.json
```

输入兼容 schema 1 和 2，不接受 `summary.json`。工具不按 `versionCode` 自动排序：不同
版本可能使用相同的版本码，国内版和 Google Play 版也应由调用者明确安排比较顺序。
输出保留每份报告的版本名、版本码、buildTag、渠道和 APK SHA-256。

比较使用功能的 `className` 和委托的 `key` 对齐，兼容旧报告的 `Feature:key` 格式：

- 方法／构造函数：显示前后的参数数量、按顺序排列的完整类型和返回类型，包括基本类型和多维数组。
- 解析状态／占位符变化：单独列出，不将占位用的 `LauncherUI.getInstance()` 当成真实解析结果。
- 功能／委托增减：只表示报告覆盖或声明发生变化，不能视为微信删除了对应结构。
- 解析器 `methodHash` 变化：明确提示，避免将源码调整产生的差异都归因于微信升级。

仅所属类名或方法名变化、而参数和返回类型相同的项，默认只计数；加 `--include-renames`
可展开。类／字段委托的 descriptor 变化也作为信息项保留，不属于方法参数扫描。
**参数及返回类型按原名精确比较**：混淆类型改名仍会列为类型变化，必须结合源码判断，
不会将所有宿主类型统一成一个占位类型而掩盖真正的变化。

`--output` 写出独立的差异 JSON（包含默认隐藏的名称／descriptor 信息项），不改写输入报告。
`--fail-on-change` 在存在参数、返回类型、状态、覆盖范围、解析器源码等实质差异，或输入报告
未通过时返回非零退出码；差异文件会先保存。默认模式下发现差异不导致命令失败，但无效报告、
重复输入、重复功能／委托键和不支持的 schema 会报错。

例如 8.0.77 → 8.0.78 可从报告直接发现：

```text
ctorNetSceneSendMsg: 5 → 6 parameters
  (String, String, int, int, Object)
  (String, String, int, int, Object, String)
methodSetVoice: 4 → 5 parameters
  (String, int, int, MsgInfo)
  (String, int, int, MsgInfo, String)
```

这里为方便阅读省略了类型包名，工具实际输出完整类型名。报告中没有声明的动态反射目标无法
检查；descriptor 也不包含 static 标志、泛型、参数名或参数语义，工具不推断这些信息。
签名相同不能证明 Hook 或消息发送在真机上正常。

## CI 与云端报告

`dex-test` CI 作业独立于 Android 构建运行，当前工作流监听 `master` 的推送/拉取请求和手动运行，各作业另有仓库条件；
以 `.github/workflows/ci.yml` 的实际规则为准。其 APK 矩阵来自
[`docs/getting-started.md`](../getting-started.md) 中的下载链接：国内版使用其中列出的微信官方
URL，Google Play 版使用其中列出的 APKMirror 发布页面。请确保每个版本和渠道的链接唯一。

CI 使用以下命令将该文档转换为清单：

```bash
./x dex-test-ci sources \
  --doc docs/getting-started.md \
  --output /tmp/wekit-dex-test-sources.json
```

下载的 APK 会被验证为包含 `AndroidManifest.xml` 和至少一个 DEX 的 ZIP 文件，随后使用由
文档、下载器和清单实现共同派生的键进行缓存。匹配的缓存 APK 及其 SHA-256 辅助文件会被
复用。测试前，APKMirror 拆分包会使用固定版本的 APKEditor 进行合并。

CI 仅缓存 `.wekit/dex-test/source` 下经过验证的 DexKit 源码检出内容；原生库会在每次运行时
重新构建，避免 CMake 复用旧运行器镜像中已经失效的 JDK 绝对包含路径。

每次 CI 事件都会将完整的运行目录上传为 `wekit-dex-test-reports` Actions 构件，其中包括
失败的各 APK 报告和汇总文件 `summary.json`。即使已经保留所有可用报告，任何失败、阻塞、
未完成、工作进程、APK 或基础设施异常仍会使 `dex-test` 作业失败。

即使前面的来源下载失败，下载器也会继续尝试所有来源。CI 会传入 `--failures-out`，将各来源
的失败记录为警告，同时继续解析并报告成功下载的 APK；部分下载失败不会使本次运行失败，
已成功下载宿主的 APK 缓存也会被保存，因此重试时只需重新获取缺失的文件。未使用
`--failures-out` 时，脚本在独立运行场景中仍保持原有的快速失败行为。只有在所有 APK 均
下载失败时，才会跳过解析步骤并使作业失败。

在 `master` 和 `dev` 上，另一个作业会更新标签为 `Dex-Test`、名称为 `Dex Test` 的预发布版本。
解析成功的各宿主报告使用以下规范资源名称：

```text
wechat-<versionName>-<versionCode>-domestic.json
wechat-<versionName>-<versionCode>-google-play.json
```

发布程序只会替换当前报告为 `PASS` 的宿主资源。如果其他宿主失败，其最近一次成功报告对应的
规范资源仍会保留。如果没有任何宿主通过，Release 将完全不作修改。当至少一个宿主通过时，
当前汇总文件 `summary.json` 和 Release 说明也会更新，因此它们可能描述的是一次失败的尝试，
而失败宿主的规范资源仍来自更早的成功运行。

Android 云端解析客户端只会使用与当前宿主匹配的规范 PASS 报告。它不会使用 `summary.json`，
并且会在写入任何本地缓存前，重新验证宿主标识、生成的解析器哈希、委托项键、状态和描述符。
