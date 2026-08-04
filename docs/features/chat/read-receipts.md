# 已读追踪

> 追踪文本消息已读人数, 并在自己发送的消息上实时显示「已读 x 人」

## 类别

聊天

## 类型

可开关 & 可点击

## 描述

通过关联的自建服务器, 追踪自己发送的文本消息被多少不同 IP 的设备阅读过。发送时消息会自动注册到服务器, 然后模块定期轮询服务器的读取计数, 并在消息下方实时显示「已读 x 人」。

使用方式:
- 在输入框中以 `#` 开头输入文本 (可自定义前缀), 发送后即变为可追踪的特殊消息
- 消息会附带一个透明追踪像素 URL, 收件人加载消息时自动向服务器上报
- 已读计数基于 IP 去重, 每 5 秒自动刷新

需要自行搭建配套的已读追踪服务器 (`wekit-read-receipts-server`), 并在功能配置中填写服务器地址。

## 使用方法

在模块设置中启用, 点击配置服务器地址、前缀和轮询间隔。在聊天输入框中以 `#` (或其他自定义前缀) 开头输入消息, 发送后即可追踪已读

### 0. 准备服务器

你必须有一个能持续无人值守运行、拥有公网 IP 的设备。你可以购买 VPS, 或在家用设备上部署后使用内网穿透工具。

### 1. 安装 Rust

##### A. Windows

1. 下载并运行 [rustup-init.exe](https://static.rust-lang.org/rustup/dist/x86_64-pc-windows-msvc/rustup-init.exe)
2. 按照提示安装, 选择默认选项即可 (default profile)
3. 安装完成后重启终端, 验证安装:
   ```
   rustc --version
   cargo --version
   ```
4. 需要安装 [Visual Studio Build Tools](https://visualstudio.microsoft.com/visual-cpp-build-tools/) (或 Visual Studio), 勾选「使用 C++ 的桌面开发」工作负载, 这样才能编译 Rust 的 MSVC ABI 目标

##### B. Arch Linux

```bash
yay -Syu rustup
rustup toolchain install stable
rustup default stable
```


##### C. Debian 系

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
rustup toolchain install stable
rustup default stable
```

此外需要安装构建依赖:

```bash
sudo apt install build-essential pkg-config libssl-dev
```

### 2. 编译与运行

```bash
git clone https://github.com/Ujhhgtg/WeKit.git
cd ./WeKit/services/read-receipts
cargo run --release
```

服务器默认绑定 `0.0.0.0:8080`, 并自动在当前目录下创建 `read_receipts.db` SQLite 数据库。

### 配置

| 环境变量                 | 说明                                      | 默认值                     |
|----------------------|-----------------------------------------|-------------------------|
| `TURSO_DATABASE_URL` | 数据库连接 URL。以 `file:` 开头使用本地, 否则连接远程      | `file:read_receipts.db` |
| `TURSO_AUTH_TOKEN`   | 远程数据库的认证令牌                              | 空                       |
| `RUST_LOG`           | 日志级别 (`debug`, `info`, `warn`, `error`) | `debug`                 |
| —                    | 绑定地址 (需修改源码中的 `0.0.0.0:8080`)           | `0.0.0.0:8080`          |

如果需要修改端口, 编辑 `src/main.rs` 中的 `SocketAddr::from(([0, 0, 0, 0], 8080))` 然后重新编译。

### REPL 命令

在终端中直接运行服务器时会进入交互式 REPL, 支持以下命令:

| 命令                      | 说明                   |
|-------------------------|----------------------|
| `/url <wxId> <message>` | 手动注册一条消息, 用于测试       |
| `/query <id>`           | 查询指定消息 ID 的读取次数      |
| `/tail [N]`             | 显示最近 N 条读取记录 (默认 10) |
| `/dashboard`            | 在浏览器中打开管理面板          |
| `exit` / `quit`         | 停止服务器                |

### 注意事项

1. **可见性**: 服务器默认监听 `0.0.0.0`, 即所有网络接口。如果部署在公网, **务必配置防火墙或反向代理**限制访问, 避免追踪像素被未授权访问。
2. **HTTPS**: 追踪像素通过 HTTP 请求上报, 如果服务器暴露在公网, 建议用 Nginx/Caddy 等反代加 HTTPS。
3. **隐私**: 已读计数基于请求来源 IP 去重。同一 NAT 下的设备会被视为同一读者; 使用 VPN 切换 IP 会被认为有多人阅读。
4. **数据库**: 默认使用本地 SQLite 文件, 直接删除 `read_receipts.db` 即可清空所有数据。
5. **编译耗时**: 首次 `cargo build --release` 需要下载和编译依赖, 可能花费数分钟。
