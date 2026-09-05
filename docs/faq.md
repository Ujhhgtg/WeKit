# 常见问题

## 1. 模块不加载, 日志也没有报错

Xposed / 免 Root 模式按照
[此处说明](installation.md#修复微信热更新导致的模块不加载) 进行修复。

Zygisk 模式先确认 WebUI 中对应 Android 用户和微信的开关已经打开, 再完全结束并
重新启动微信。仍未加载时参见 [Zygisk 常见问题](zygisk.md#常见问题)。

## 2. 我的微信突然卡得要死, 狂吃内存

尝试禁用「Xposed API 调用保护」和「隐藏应用列表」。

## 3. 模块数据在哪?

`/sdcard/Android/data/<宿主包名>/WeKit`

## 4. 不受支持的旧版本启动一直弹 DEX 缓存更新怎么办?

模块通用设置启用 「禁用自动解析」 或更新到 >= 8.0.65。

## 5. 如何发送卡片消息？

在聊天分类启用[发送卡片消息](features/chat/send-card-message.md)，输入有效 XML 后长按发送或加号按钮选择该操作。

## 6. LSPosed 提示「此模块是为较新的 Xposed 版本设计的, 因此某些功能可能无法使用」

忽略即可; 模块提供传统 Xposed API（51+）与 libxposed（101–102）入口，需使用匹配的框架与 APK 变体。

## 7. 模块出现问题 (例如找不到入口, 功能失效) 怎么办?

请参考 [问题反馈指南](bug-report-guide.md) 提交 Issue。

## 8. 如何解密微信数据库?

1. 开启「模块设置 -> 调试 -> 详细日志」并重启微信
2. 在日志中寻找:

    ```text
    WeDatabaseApi: openDatabase() called with: name=/data/user/0/com.tencent.mm/MicroMsg/xxxxxxxxx/EnMicroMsg.db, password=xxxxxxx, cipherSpec=0,false,0,4000,1024
    ```

3. 使用 `sqlcipher` 逐行执行 (不要连续输入多行):

    ```bash
    sqlcipher ./EnMicroMsg.db

    PRAGMA key = 'xxxxxxx';
    PRAGMA cipher_compatibility = 1;

    ATTACH DATABASE 'decrypted_wechat.db' AS decrypted KEY '';

    SELECT sqlcipher_export('decrypted');
    DETACH DATABASE decrypted;

    .exit
    ```

4. 用 `DB Browser for SQLite` 或类似工具打开 `decrypted_wechat.db`

## 9. 「指纹支付」在分身微信里加解密崩溃

原因为 ROM 提供的应用分身功能不兼容指纹, 不会修复

## 10. 其他问题

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ujhhgtg/WeKit)
