# 脚本引擎 (Java)

入口：WeKit 设置 → 功能 → 脚本 (Java) → 脚本引擎 (Java)。

开启后加载 `<模块数据>/scripts_java/` 下的脚本目录，每个脚本至少包含：

```text
scripts_java/example/
├── info.prop
└── main.java
```

`info.prop` 可填写 `name`、`author`、`version` 和 `updateTime`，`main.java` 为 BeanShell/Java 脚本正文。例如：

```properties
name=示例脚本
author=作者
version=1.0
```

点击功能进入脚本列表，分别启用或禁用脚本。目录中的 `disabled.flag` 表示禁用状态；新放入或编辑文件后应重新加载功能或重启微信检查日志。

脚本在微信进程内运行，可使用模块提供的宿主事件和[Hook 服务](java-hook-api.md)。只运行可信脚本；关闭脚本不能保证撤销它已经造成的任意修改。
