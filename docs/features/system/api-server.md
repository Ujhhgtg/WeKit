# API + MCP 服务器

> 启用 REST API 与 MCP 服务器, 让人类与 AI 能够访问微信能力

## 类别

系统与隐私

## 类型

可开关，可点击配置

## 描述

API 服务器基于 Ktor 框架构建, 提供 MCP (Model Context Protocol) 和 REST API 两种接口, 使外部程序 (包括 AI Agent) 能够通过 HTTP 协议访问微信的各类能力。

## 使用方法

在模块设置中启用后, 点击配置端口和认证令牌

### 配置

- **端口**: 默认为 `3001`, 可自定义
- **认证令牌**: Bearer Token 认证, 默认为 `your_token`, 建议修改为安全的值

### API 端点

API 服务器提供以下能力:

- **会话管理**: 获取当前对话信息、获取联系人列表
- **消息发送**: 发送文本、图片、文件、语音消息
- **数据查询**: 获取聊天记录、数据库查询
- **MCP 协议**: 支持 MCP Streamable HTTP 传输协议, AI Agent 可直接接入

### 认证

所有请求需要在 HTTP Header 中添加 `Authorization: Bearer <token>` 进行认证。
