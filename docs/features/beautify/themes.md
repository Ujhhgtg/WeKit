# 主题

入口：WeKit 设置 → 功能 → 界面美化 → 主题。

## 安装主题

将主题目录放入 `<模块数据>/themes/`，每个子目录代表一个主题。目录结构示例：

```text
themes/my-theme/
├── manifest.json
├── colors.json
├── strings.json
├── home/
├── chat/
│   └── bubbles/
├── plus/
├── settings/
└── splash/
```

`manifest.json` 描述名称、作者、版本与说明；颜色和字符串分别保存在 `colors.json`、`strings.json`。图片按对应场景目录放置。缺失的资源不会凭空生成，应使用与当前主题格式相符的主题包。

## 应用

开启功能后点击主题列表，选择一个主题，然后重启微信。选择「无」表示不应用主题。设置页显示实际主题目录、作者与版本信息。

主题修改的是本地界面资源，不会把主题同步给其他聊天参与者。
