# WeKit Read Receipts Server

WeKit「已读追踪」功能的配套服务端，通过透明追踪像素记录消息的读取请求，并按来源 IP 去重后向 WeKit 返回已读人数。

> [!IMPORTANT]
> 本项目是一个**参考实现（Reference Implementation）**，主要用于展示 WeKit 已读追踪服务端的工作方式和 API 契约，而不是长期维护的官方托管服务。
>
> 本实现不再进行功能性更新，也大概率不会接受 Pull Request。若你需要更完整的功能、更强的安全性或适合生产环境的部署方式，建议寻找兼容的第三方实现，或者参考下方 API 契约，使用 AI 实现一个满足自己需求的服务端。

> [!NOTE]
> 如果现有 API 契约无法满足你的需求，欢迎提交用于调整或扩展 API 契约/规范的 Pull Request；此类 PR 是*接受*的。这里接受的是协议层面的讨论与改进，不代表本参考实现重新接受常规功能开发。

## 功能

- 注册待追踪的文本消息，并生成稳定的消息 ID
- 提供 1×1 透明 PNG 追踪像素
- 记录追踪像素请求的来源 IP 和时间
- 按消息及来源 IP 去重统计已读人数
- 提供消息、已读记录查询和清理 API
- 提供简单的 Web 管理页面
- 提供交互式 REPL 管理命令
- 支持本地 SQLite/libSQL 数据库和远程 Turso 数据库

## 工作方式

1. WeKit 在发送消息时调用 `POST /register` 注册消息。
2. 服务端根据发送者 wxId、消息内容和创建时间生成消息 ID。
3. WeKit 将指向 `GET /pixel` 的透明图片地址附加到消息中。
4. 收件人加载图片时，服务端记录请求来源 IP。
5. 发送者的 WeKit 客户端定期调用 `GET /count`，获取按 IP 去重后的已读人数。

来源 IP 只能近似表示读者身份。同一 NAT 下的多个设备可能被计为一人，切换网络或使用 VPN 也可能导致同一人被重复计算。

## 环境要求

- Rust stable 工具链
- 可持续运行并可被消息接收方访问的设备或服务器
- 公网部署时建议使用域名、HTTPS 反向代理和防火墙

## 快速开始

在 WeKit 仓库中运行：

```bash
cd services/read-receipts
cargo run --release
```

默认监听 `0.0.0.0:8080`，并在当前工作目录创建 `read_receipts.db`。启动后可通过以下地址打开管理页面：

```text
http://localhost:8080/
```

随后在 WeKit 的「已读追踪」设置中填写外部设备实际能够访问的服务地址。更完整的客户端使用说明见 [已读追踪功能文档](../../docs/features/chat/read-receipts.md)。

## 配置

服务通过环境变量配置：

| 环境变量 | 说明 | 默认值 |
| --- | --- | --- |
| `BIND_ADDR` | 监听的 IP 地址 | `0.0.0.0` |
| `PORT` | 监听端口 | `8080` |
| `TURSO_DATABASE_URL` | 数据库 URL；以 `file:` 开头时使用本地数据库 | `file:read_receipts.db` |
| `TURSO_AUTH_TOKEN` | 远程 Turso/libSQL 数据库的认证令牌 | 空 |
| `RUST_LOG` | 日志过滤级别，例如 `debug`、`info`、`warn` | `debug` |

示例：

```bash
BIND_ADDR=127.0.0.1 PORT=3000 RUST_LOG=info cargo run --release
```

## API 契约

WeKit 客户端所需的核心接口如下：

### 注册消息

```http
POST /register
Content-Type: application/json
```

请求体：

```json
{
  "wxId": "wxid_example",
  "content": "需要追踪的消息内容",
  "createTime": 1785859200000
}
```

`createTime` 为 Unix 毫秒时间戳。成功响应：

```json
{
  "id": "生成的 SHA-256 消息 ID"
}
```

消息 ID 的计算方式为：

```text
sha256(wxId + "\0" + content + "\0" + createTime)
```

结果使用小写十六进制编码。

### 记录读取

```http
GET /pixel?wxId=<wxId>&id=<messageId>
```

服务端记录请求的 TCP 来源 IP，并始终返回禁止缓存的 1×1 透明 PNG。

### 查询已读人数

```http
GET /count?wxId=<wxId>&id=<messageId>
```

成功响应：

```json
{
  "count": 1
}
```

`count` 是指定消息记录中不同来源 IP 的数量。

### 管理接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/` | Web 管理页面 |
| `GET` | `/messages?q=<keyword>` | 查询全部消息，可按内容过滤 |
| `DELETE` | `/messages` | 删除全部消息及读取记录 |
| `GET` | `/messages/{wxId}?q=<keyword>` | 查询指定发送者的消息 |
| `DELETE` | `/messages/{wxId}` | 删除指定发送者的消息及读取记录 |
| `GET` | `/reads/{messageId}` | 查询指定消息按 IP 去重后的读取记录 |

这些管理接口没有身份认证，不应直接暴露到不可信网络。

## REPL 命令

在交互式终端中运行时，可以使用以下命令：

| 命令 | 用途 |
| --- | --- |
| `/help` | 显示帮助 |
| `/status` | 显示消息、发送者和读取统计 |
| `/url <wxId> <message>` | 注册测试消息并输出追踪地址 |
| `/tail [count]` | 查看最近的读取记录 |
| `/query <wxId>` | 查询指定发送者的消息及已读人数 |
| `/clear` | 清空消息及读取记录 |
| `/open` | 在默认浏览器中打开管理页面 |
| `/sql <query>` | 执行 SQL 语句 |
| `/exit` | 关闭服务并退出 |

在 systemd 等无交互终端的环境中，服务会持续运行并等待 `SIGTERM` 或 `SIGINT`。

## systemd 部署

目录中提供了参考 service 文件：[wekit-read-receipts-server.service](wekit-read-receipts-server.service)。使用前至少需要修改其中的 `User`、`Group`、`WorkingDirectory`、`ExecStart` 和 `ReadWritePaths`，使其符合实际部署环境。

先构建服务：

```bash
cargo build --release
```

再根据自己的系统配置安装和启用 service 文件。示例文件中的路径仅适用于原作者的本地环境，不应原样用于其他机器。

## 安全与隐私

- 本实现没有身份认证、访问控制、速率限制或滥用防护，不应直接作为生产级公共服务部署。
- 服务会保存发送者 wxId、明文消息内容、读取请求来源 IP 和时间戳。部署者必须自行确认当地法律、隐私政策及用户授权要求。
- 公网传输应使用 HTTPS，避免消息内容和标识符以明文形式经过网络。
- `/pixel` 使用 TCP 对端地址作为读者 IP。若服务位于反向代理后方，所有请求可能会被记录为代理服务器的 IP；本参考实现不会读取 `X-Forwarded-For` 等请求头。
- 删除 `read_receipts.db` 会清除本地数据库；执行前请自行备份。

## 已知限制

- IP 去重不能准确识别真实用户或设备。
- 没有用户系统、多租户隔离和权限模型。
- 没有正式的数据库迁移、备份或恢复机制。
- 管理页面和管理 API 默认对所有能够访问服务的客户端开放。
- 不保证未来 WeKit 客户端协议变化后的兼容性。

## 维护与贡献

此服务端仅作为 Reference Implementation 保留：

- 不再规划或实现新功能。
- 功能需求和常规改进 PR 大概率不会被接受。
- 是否处理严重安全问题或必要的兼容性修复，由维护者自行决定。
- 推荐有长期使用需求的用户选择第三方实现，或 Fork 本项目后自行维护。
- 下方 API 契约可以直接作为自建兼容服务或 AI 的实现依据。

## 许可证

本项目随 WeKit 按 [GNU General Public License v3.0](../../LICENSE) 发布。
