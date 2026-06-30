# AI 回复

在聊天工具栏中添加 AI 回复功能，支持自定义 AI API，实现智能回复当前聊天内容。

## 使用方式

1. 确保「聊天工具栏」功能已启用
2. 在聊天工具栏中找到 ✨ **AI 回复** 芯片
3. 点击 ⚙️ **AI 配置** 芯片配置你的 AI API
4. 配置完成后，点击 ✨ **AI 回复** 即可自动获取聊天上下文并生成回复

## 配置项

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| API 地址 | OpenAI 兼容的 Chat Completions API 地址 | `https://api.openai.com/v1/chat/completions` |
| API Key | API 鉴权密钥 | (空) |
| 模型名称 | 使用的模型名 | `gpt-4o-mini` |
| 温度 | 生成随机性 (0.0~2.0) | 0.7 |
| 系统提示词 | 设定 AI 回复风格和规则 | (默认中文助手提示词) |
| 上下文消息数 | 获取最近多少条消息作为上下文 | 10 |
| 最大 Token | 单次生成最大 token 数 | 1000 |

## 隐私说明

- API Key 保存在本地 MMKV 存储中，不会上传至其他地方
- 聊天内容仅发送到你自己配置的 API 地址
- 建议使用本地部署的 AI 模型以获得最佳隐私保护

## 支持的 API 后端

任何兼容 OpenAI Chat Completions 格式的 API 均可使用：

- OpenAI (GPT-4, GPT-4o, GPT-4o-mini 等)
- Anthropic (通过兼容代理)
- Groq
- 本地部署的 Ollama / vLLM / llama.cpp
- Azure OpenAI 服务
- 任何兼容的 API 代理
