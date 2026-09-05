# 已读追踪

> 为自己发送的特殊文本消息记录追踪像素请求，并显示按来源 IP 去重的「已读 x 人」。

## 工作方式与限制

使用所选发送模式发送带追踪的文本时，WeKit 会先向所选服务器注册
`wxId`、明文消息内容和创建时间，再发送包含透明追踪像素的消息。收到消息的客户端加载
像素后，服务器记录请求的 TCP 对端 IP；发送方每隔一段时间查询去重计数。

这不是可靠的用户身份或送达证明。同一 NAT、代理或出口下的多个设备可能只计为一人，
切换网络或 VPN 可能把一人计为多人；客户端不加载远程图片时也不会产生记录。

协议限制为：`wxId` 最多 128 UTF-8 字节、消息内容最多 16 KiB、注册请求体最多
20 KiB、原始 query string 最多 1 KiB，消息 ID 为 64 位小写十六进制 SHA-256。
第三方端点最多 2048 字符，不得包含用户名、密码、query 或 fragment。

## 发送方式

默认使用主动菜单模式：在输入框填写文本，长按发送按钮或加号按钮，选择「发送已读回执消息」。也可在设置中选择被动模式（处理发出的文本）或主动前缀模式（只处理以配置前缀开头的文本，发送时去掉前缀）。默认前缀是 `#rr`，不是所有模式都需要前缀。

计数按设置的查询间隔刷新，初始间隔为 5 秒；失败会退避重试。内置模式使用 cloudflared 扩展包，使用前确认扩展包可用。

## 服务器模式

### 第三方服务器

填写一个 HTTPS 基础地址。它必须实现以下协议：

- `POST /register`：接收 `{wxId, content, createTime}`，返回消息 ID；
- `GET /pixel?wxId=<wxId>&id=<id>`：返回追踪像素并记录读取；
- `GET /count?wxId=<wxId>&id=<id>`：返回 `{"count": <number>}`。

仓库中的 [`services/read-receipts`](https://github.com/Ujhhgtg/WeKit/blob/master/services/read-receipts/README.md) 是保留独立
dashboard、REPL、管理 API、本地 SQLite 和远程 Turso 的参考实现，不是官方托管服务，
也不是生产级多租户服务。可直接部署它，也可以按协议使用其他兼容实现。

### 内置服务器

内置 origin 只监听 `127.0.0.1`，路由仅包含 `/register`、`/pixel`、`/count` 和空的
`204 /health`；不会暴露参考服务的 dashboard、REPL 或管理 API。公网访问由嵌入的
Cloudflare Tunnel 连接器转发到这个 loopback origin。

内置模式有三种连接方式：

- **Quick Tunnel**：无需账号，生成随机 `trycloudflare.com` 地址。Cloudflare 将其定位为
  测试/开发用途，不提供 SLA 或 uptime 保证；当前每个 tunnel 最多 200 个并发中的请求，
  且不支持 SSE。每次新会话的随机地址可能变化。
- **Tunnel token**：使用用户已经创建的 remotely-managed tunnel。必须关闭自动端口，
  填写根路径 HTTPS 主机名，并让 Cloudflare dashboard 中现有 Public Hostname 的服务精确
  指向 `http://127.0.0.1:<固定端口>`。
- **Browser Login**：通过浏览器授权，只读列出并选择账号中已经存在的 remotely-managed
  tunnel 和已配置主机名；同样要求固定端口和匹配的根路径 HTTPS 主机名。授权页面无法
  自动打开时，可以显式复制授权 URL。

WeKit **不会**创建、删除或修改 Cloudflare tunnel、DNS 记录、hostname、route、ingress
或 Public Hostname 配置。Remotely-managed tunnel 的配置保存在 Cloudflare，并由用户在
dashboard/API 管理。相关官方说明：

- [Quick Tunnels](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/)
- [Cloudflare Tunnel useful terms](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/get-started/tunnel-useful-terms/)
- [Tunnel tokens](https://developers.cloudflare.com/tunnel/advanced/tunnel-tokens/)

## Android 前台服务

公网 tunnel 由 WeKit 自己的 Android 进程和 `specialUse` 前台服务持有。内置 origin 先启动，
tunnel 后启动；停止时顺序相反。必须从可见设置页面执行连接，且必须允许 WeKit 的通知
权限和通知渠道。Android 拒绝后台启动或无法显示持续通知时，WeKit 会要求用户打开通知
设置并重试，不会静默运行不可见 tunnel。持续通知提供停止入口。

Quick 地址、Token/Browser 主机名只有在公网 `GET /health` 精确返回空 `204` 后才会成为
可复制的已验证端点。网络丢失或重连时，旧地址不会继续被报告为可用。

## 凭据边界

Cloudflare 官方说明：持有 remotely-managed tunnel token 的任何人都可以运行该 tunnel。
应把 token 当作密码处理；泄漏后应在 Cloudflare 中轮换。WeKit 不把 token 或 origin
certificate 写入 MMKV、Intent、广播、通知、日志、剪贴板或保存的 UI 状态。通过验证的
凭据使用 Android Keystore AES-256-GCM 加密，保存在 `noBackupFilesDir` 下，并排除备份和
设备迁移；这样做用于无人值守重连，但不能替代设备本身的安全保护。

允许复制到剪贴板的只有用户明确操作的浏览器授权 URL 和已经通过公网健康检查的公共
端点；不会提供复制 token 或 certificate 的路径。

## 隐私与来源 IP

服务器保存发送者 `wxId`、明文消息内容、读取 IP 和时间。运营者必须自行取得必要授权、
制定保留/删除政策并遵守适用法律；公网传输必须使用 HTTPS。数据库、dashboard、REPL、
日志和备份都应按敏感数据保护。

内嵌 cloudflared bridge 在边缘请求转发到 loopback origin 之前读取 Cloudflare 提供的
`CF-Connecting-IP`，删除外部携带的内部/转发 IP 头，然后写入规范化 reader IP 和进程内
认证值。认证值不是第二份凭据：它复用控制器已经为 UID 授权 Binder START 消息生成的
24-byte 随机 nonce（`Base64.NO_WRAP` 后为 32 个 ASCII 字符），不会写入持久配置、通知或
日志。Rust origin 只有在 constant-time 验证该认证值后才采用 reader IP；直接访问
loopback origin、第三方部署以及认证失败的请求仍只使用真实 TCP 对端，不能通过伪造
`Forwarded`、`X-Forwarded-For`、`CF-Connecting-IP` 或 WeKit 内部头选择身份。

Cloudflare 对 `CF-Connecting-IP` 的定义及 Workers 子请求差异见
[HTTP request headers](https://developers.cloudflare.com/fundamentals/reference/http-request-headers/)；
若在 tunnel 前另放 Worker，统计身份会受该文档描述的 same-zone/cross-zone 语义影响。

## 使用步骤

1. 在设置中启用「已读追踪」，选择第三方或内置服务器。
2. 第三方模式填写 HTTPS 基础地址并测试；内置模式选择 Quick、Token 或 Browser Login，
   按上述要求配置端口、主机名、凭据和通知。
3. 按所选发送方式，使用长按菜单、配置前缀或被动模式发送。
4. 发送成功后，自己消息下方的计数会按配置间隔刷新。

真实浏览器授权、Cloudflare 凭据、Binder/前台服务、进程死亡、网络切换和 WeChat 渲染
行为必须在 Android 设备上手工验证；桌面测试和 APK 构建不能证明这些运行时行为。
