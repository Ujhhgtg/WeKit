# 脚本 API 文档

## Beta 状态警告

**重要提醒：此 API 目前处于 Beta 测试阶段，在后续更新中可能出现不兼容的更改，使用时请密切关注相关更新通知。**

**目前暂无提供监听信息发送等的 API 的想法，请自行根据 cgiId 判断实现相关监听。**

## 目录

1. [钩子函数](#钩子函数)
   - [onRequest](#onrequest)
   - [onResponse](#onresponse)
   - [数据对象说明](#数据对象说明)
2. [WEKit 对象](#wekit-对象)
   - [概述](#概述)
   - [WEKit Log 函数](#wekit-log-函数)
   - [WEKit isMMAtLeast 函数](#wekit-ismmatleast-函数)
   - [WEKit isGooglePlayVersion 函数](#wekit-isgoogleplayversion-函数)
   - [WEKit sendCgi 函数](#wekit-sendcgi-函数)
   - [WEKit getSelfWxId 函数](#wekit-getselfwxid-函数)
   - [WEKit getSelfAlias 函数](#wekit-getselfalias-函数)
   - [WEKit proto 对象](#wekit-proto-对象)
   - [WEKit database 对象](#wekit-database-对象)
   - [WEKit message 对象](#wekit-message-对象)
3. [注意事项](#注意事项)
4. [后续更新计划](#后续更新计划)

## 钩子函数

### onRequest

当请求即将发出时触发此函数。

```javascript
function onRequest(data) {
    // data 对象包含以下字段：
    const {uri,cgiId,jsonData} = data;

    // 示例：修改请求数据
    if (data.cgiId === '111') {
        data.jsonData.newField = 'newValue';
    }
}
```

### onResponse

当请求收到响应时触发此函数。

```javascript
function onResponse(data) {
    // data 对象包含以下字段：
    const {uri,cgiId,jsonData} = data;

    // 示例：修改响应数据
    if (data.cgiId === 'some_cgi_id') {
        data.jsonData.newField = 'newValue';
    }
}
```

### 数据对象说明

每个钩子函数接收一个 `data` 参数，该参数是一个对象，包含以下字段：

| 字段名   | 类型   | 描述                            |
| -------- | ------ | ------------------------------- |
| uri      | string | 请求的目标 URI 地址             |
| cgiId    | string | 请求的 CGI ID，用于识别请求类型 |
| jsonData | object | 请求或响应的数据体（JSON 格式） |

**重要说明：**

- 如果想要修改响应或者发送请求，**请返回修改后的 JSON 对象**，而不是直接修改 `data.jsonData`
- **不要直接修改传入的 data.jsonData 对象**
- **如果未做任何修改，直接 return null 或不 return**，返回的 `jsonData` 将保持原始状态

## WEKit 对象

### 概述

`wekit` 是一个特殊的全局对象，是软件为脚本环境设计的api，提供了与底层系统交互的各种功能接口。该对象在脚本执行环境中自动可用，无需额外导入或初始化。

### WEKit Log 函数

`wekit.log` 是 `wekit` 对象提供的日志输出函数，专门用于在脚本执行过程中打印调试信息。所有通过此函数输出的日志都会被收集并可在脚本日志查看器中查看。

#### 使用方法

```javascript
wekit.log(message);
```

#### 示例

```javascript
function onRequest(data) {
    wekit.log('请求发起:', data.uri);
    wekit.log('CGI ID:', data.cgiId);
    wekit.log('原始数据:', JSON.stringify(data.jsonData));
    
    // 修改数据...
}
```

### WEKit isMMAtLeast 函数

`wekit.isMMAtLeast` 是 `wekit` 对象提供的版本检查函数。

#### 使用方法

```javascript
wekit.isMMAtLeast(field);
```

#### 参数说明

| 参数名 | 类型   | 描述                       |
| ------ | ------ | -------------------------- |
| field  | string | `MMVersion` 提供的版本常量 |

#### 返回值

| 类型    | 描述                                      |
| ------- | ----------------------------------------- |
| boolean | 如果当前版本号大于等于指定版本则返回 true |

#### 示例

```javascript
function onRequest(data) {
    if (wekit.isMMAtLeast("MM_8_0_67")) {
        wekit.log("当前版本高于或等于指定版本");
    } else {
        wekit.log("当前版本低于指定版本");
    }
}
```

### WEKit isGooglePlayVersion 函数

`wekit.isGooglePlayVersion` 是 `wekit` 对象提供的 Google Play 版本判断函数。

#### 使用方法

```javascript
wekit.isGooglePlayVersion();
```

#### 返回值

| 类型    | 描述                                      |
| ------- | ----------------------------------------- |
| boolean | 如果是 Google Play 版本则返回 true，否则返回 false |

#### 示例

```javascript
function onRequest(data) {
    if (wekit.isGooglePlayVersion()) {
        wekit.log("当前是 Google Play 版本");
        // Google Play 版本的特定逻辑
    } else {
        wekit.log("当前是非 Google Play 版本");
        // 普通版本的特定逻辑
    }
}
```

#### 结合版本判断使用

```javascript
function onRequest(data) {
    if (wekit.isGooglePlayVersion()) {
        // Google Play 版本逻辑
        if (wekit.isMMAtLeast("MM_8_0_48_Play")) {
            wekit.log("Google Play 8.0.48 及以上版本");
        }
    } else {
        // 普通版本逻辑
        if (wekit.isMMAtLeast("MM_8_0_90")) {
            wekit.log("普通版 8.0.90 及以上版本");
        }
    }
}
```

### WEKit sendCgi 函数

`wekit.sendCgi` 是 `wekit` 对象提供的发送异步无返回值的 CGI 请求函数。

#### 使用方法

```javascript
wekit.sendCgi(uri, cgiId, funcId, routeId, jsonPayload);
```

#### 参数说明

| 参数名      | 类型   | 描述                        |
| ----------- | ------ | --------------------------- |
| uri         | string | 请求的目标 URI 地址         |
| cgiId       | number | 请求的 CGI ID               |
| funcId      | number | 功能 ID                     |
| routeId     | number | 路由 ID                     |
| jsonPayload | string | 请求的数据体（JSON 字符串） |

#### 示例

```javascript
function onRequest(data) {
    wekit.log('准备发送 CGI 请求');
    wekit.sendCgi('/cgi-bin/micromsg-bin/newgetcontact', 12345, 1, 1, JSON.stringify(data.jsonData));
}
```

#### wekit.getSelfWxId

获取当前用户的微信id。

```javascript
wekit.getSelfWxId();
```

##### 返回值

| 类型   | 描述             |
| ------ | ---------------- |
| string | 当前用户的微信id |

##### 示例

```javascript
function onRequest(data) {
    const wxid = wekit.getSelfWxId();
    wekit.log('当前用户微信id:', wxid);
}
```

#### wekit.getSelfAlias

获取当前用户的微信号。

```javascript
wekit.getSelfAlias();
```

##### 返回值

| 类型   | 描述             |
| ------ | ---------------- |
| string | 当前用户的微信号 |

##### 示例

```javascript
function onRequest(data) {
    const alias = wekit.message.getSelfAlias();
    wekit.log('当前用户微信号:', alias);
}
```

### WEKit proto 对象

`wekit.proto` 是 `wekit` 对象提供的处理 JSON 数据的工具对象。

#### wekit.proto.replaceUtf8ContainsInJson

在 JSON 数据中替换包含指定字符串的内容。

```javascript
wekit.proto.replaceUtf8ContainsInJson(json, needle, replacement);
```

##### 参数说明

| 参数名      | 类型   | 描述               |
| ----------- | ------ | ------------------ |
| json        | object | 要处理的 JSON 对象 |
| needle      | string | 要查找的字符串     |
| replacement | string | 替换字符串         |

##### 返回值

| 类型   | 描述               |
| ------ | ------------------ |
| object | 处理后的 JSON 对象 |

##### 示例

```javascript
function onRequest(data) {
    const modifiedJson = wekit.proto.replaceUtf8ContainsInJson(
        data.jsonData,
        "oldValue",
        "newValue"
    );
    return modifiedJson;
}
```

#### wekit.proto.replaceUtf8RegexInJson

在 JSON 数据中使用正则表达式替换内容。

```javascript
wekit.proto.replaceUtf8RegexInJson(json, pattern, replacement);
```

##### 参数说明

| 参数名      | 类型   | 描述               |
| ----------- | ------ | ------------------ |
| json        | object | 要处理的 JSON 对象 |
| pattern     | string | 正则表达式字符串   |
| replacement | string | 替换字符串         |

##### 返回值

| 类型   | 描述               |
| ------ | ------------------ |
| object | 处理后的 JSON 对象 |

##### 示例

```javascript
function onResponse(data) {
    const modifiedJson = wekit.proto.replaceUtf8RegexInJson(
        data.jsonData,
        "\\d+",
        "REPLACED"
    );
    return modifiedJson;
}
```

### WEKit database 对象

`wekit.database` 是 `wekit` 对象提供的数据库操作工具对象。

#### wekit.database.query

执行 SQL 查询语句。

```javascript
wekit.database.query(sql);
```

##### 参数说明

| 参数名 | 类型   | 描述         |
| ------ | ------ | ------------ |
| sql    | string | SQL 查询语句 |

##### 返回值

| 类型  | 描述                 |
| ----- | -------------------- |
| array | 查询结果的 JSON 数组 |

##### 示例

```javascript
function onRequest(data) {
    const result = wekit.database.query("SELECT * FROM contacts WHERE nickname LIKE '%张%'");
    wekit.log('查询结果:', result);
}
```

#### wekit.database.getAllContacts

获取所有联系人列表。

```javascript
wekit.database.getAllContacts();
```

##### 返回值

| 类型  | 描述                   |
| ----- | ---------------------- |
| array | 所有联系人的 JSON 数组 |

##### 示例

```javascript
function onRequest(data) {
    const contacts = wekit.database.getAllContacts();
    wekit.log('所有联系人:', contacts);
}
```

#### wekit.database.getContactList

获取好友列表。

```javascript
wekit.database.getContactList();
```

##### 返回值

| 类型  | 描述                 |
| ----- | -------------------- |
| array | 好友列表的 JSON 数组 |

##### 示例

```javascript
function onRequest(data) {
    const friends = wekit.database.getContactList();
    wekit.log('好友列表:', friends);
}
```

#### wekit.database.getChatrooms

获取群聊列表。

```javascript
wekit.database.getChatrooms();
```

##### 返回值

| 类型  | 描述                 |
| ----- | -------------------- |
| array | 群聊列表的 JSON 数组 |

##### 示例

```javascript
function onRequest(data) {
    const chatrooms = wekit.database.getChatrooms();
    wekit.log('群聊列表:', chatrooms);
}
```

#### wekit.database.getOfficialAccounts

获取公众号列表。

```javascript
wekit.database.getOfficialAccounts();
```

##### 返回值

| 类型  | 描述                   |
| ----- | ---------------------- |
| array | 公众号列表的 JSON 数组 |

##### 示例

```javascript
function onRequest(data) {
    const accounts = wekit.database.getOfficialAccounts();
    wekit.log('公众号列表:', accounts);
}
```

#### wekit.database.getMessages

获取指定用户的聊天记录。

```javascript
wekit.database.getMessages(wxid, page, pageSize);
```

##### 参数说明

| 参数名   | 类型   | 描述                        |
| -------- | ------ | --------------------------- |
| wxid     | string | 用户的微信 ID               |
| page     | number | 页码（可选，默认为 1）      |
| pageSize | number | 每页大小（可选，默认为 20） |

##### 返回值

| 类型  | 描述                 |
| ----- | -------------------- |
| array | 聊天记录的 JSON 数组 |

##### 示例

```javascript
function onRequest(data) {
    const messages = wekit.database.getMessages('wxid_123456', 1, 50);
    wekit.log('聊天记录:', messages);
}
```

#### wekit.database.getAvatarUrl

获取用户的头像 URL。

```javascript
wekit.database.getAvatarUrl(wxid);
```

##### 参数说明

| 参数名 | 类型   | 描述          |
| ------ | ------ | ------------- |
| wxid   | string | 用户的微信 ID |

##### 返回值

| 类型   | 描述           |
| ------ | -------------- |
| string | 用户的头像 URL |

##### 示例

```javascript
function onRequest(data) {
    const avatarUrl = wekit.database.getAvatarUrl('wxid_123456');
    wekit.log('用户头像:', avatarUrl);
}
```

#### wekit.database.getGroupMembers

获取群成员列表。

```javascript
wekit.database.getGroupMembers(chatroomId);
```

##### 参数说明

| 参数名     | 类型   | 描述          |
| ---------- | ------ | ------------- |
| chatroomId | string | 群聊的微信 ID |

##### 返回值

| 类型  | 描述                   |
| ----- | ---------------------- |
| array | 群成员列表的 JSON 数组 |

##### 示例

```javascript
function onRequest(data) {
    const members = wekit.database.getGroupMembers('group_123456@chatroom');
    wekit.log('群成员列表:', members);
}
```

### WEKit message 对象

`wekit.message` 是 `wekit` 对象提供的微信消息发送工具对象。

#### wekit.message.sendText

发送文本消息。

```javascript
wekit.message.sendText(toUser, text);
```

##### 参数说明

| 参数名 | 类型   | 描述       |
| ------ | ------ | ---------- |
| toUser | string | 目标用户ID |
| text   | string | 消息内容   |

##### 返回值

| 类型    | 描述                              |
| ------- | --------------------------------- |
| boolean | 发送成功返回 true，否则返回 false |

##### 示例

```javascript
function onRequest(data) {
    const success = wekit.message.sendText('wxid_123456', 'Hello World!');
    if (success) {
        wekit.log('文本消息发送成功');
    } else {
        wekit.log('文本消息发送失败');
    }
}
```

#### wekit.message.sendImage

发送图片消息。

```javascript
wekit.message.sendImage(toUser, imgPath);
```

##### 参数说明

| 参数名  | 类型   | 描述       |
| ------- | ------ | ---------- |
| toUser  | string | 目标用户ID |
| imgPath | string | 图片路径   |

##### 返回值

| 类型    | 描述                              |
| ------- | --------------------------------- |
| boolean | 发送成功返回 true，否则返回 false |

##### 示例

```javascript
function onRequest(data) {
    const success = wekit.message.sendImage('wxid_123456', '/sdcard/DCIM/screenshot.png');
    if (success) {
        wekit.log('图片消息发送成功');
    } else {
        wekit.log('图片消息发送失败');
    }
}
```

#### wekit.message.sendFile

发送文件消息。

```javascript
wekit.message.sendFile(talker, filePath, title, appid);
```

##### 参数说明

| 参数名   | 类型   | 描述           |
| -------- | ------ | -------------- |
| talker   | string | 目标用户ID     |
| filePath | string | 文件路径       |
| title    | string | 文件标题       |
| appid    | string | 应用ID（可选） |

##### 返回值

| 类型    | 描述                              |
| ------- | --------------------------------- |
| boolean | 发送成功返回 true，否则返回 false |

##### 示例

```javascript
function onRequest(data) {
    const success = wekit.message.sendFile('wxid_123456', '/sdcard/Documents/file.pdf', '文档文件', '');
    if (success) {
        wekit.log('文件消息发送成功');
    } else {
        wekit.log('文件消息发送失败');
    }
}
```

#### wekit.message.sendVoice

发送语音消息。

```javascript
wekit.message.sendVoice(toUser, path, durationMs);
```

##### 参数说明

| 参数名     | 类型   | 描述             |
| ---------- | ------ | ---------------- |
| toUser     | string | 目标用户ID       |
| path       | string | 语音文件路径     |
| durationMs | number | 语音时长（毫秒） |

##### 返回值

| 类型    | 描述                              |
| ------- | --------------------------------- |
| boolean | 发送成功返回 true，否则返回 false |

##### 示例

```javascript
function onRequest(data) {
    const success = wekit.message.sendVoice('wxid_123456', '/sdcard/Recordings/audio.amr', 5000);
    if (success) {
        wekit.log('语音消息发送成功');
    } else {
        wekit.log('语音消息发送失败');
    }
}
```

#### wekit.message.sendXmlAppMsg

发送XML应用消息。

```javascript
wekit.message.sendXmlAppMsg(toUser, xmlContent);
```

##### 参数说明

| 参数名     | 类型   | 描述       |
| ---------- | ------ | ---------- |
| toUser     | string | 目标用户ID |
| xmlContent | string | XML内容    |

##### 返回值

| 类型    | 描述                              |
| ------- | --------------------------------- |
| boolean | 发送成功返回 true，否则返回 false |

##### 示例

```javascript
function onRequest(data) {
    const xmlContent = '<msg><appmsg><title>分享链接</title></appmsg></msg>';
    const success = wekit.message.sendXmlAppMsg('wxid_123456', xmlContent);
    if (success) {
        wekit.log('XML应用消息发送成功');
    } else {
        wekit.log('XML应用消息发送失败');
    }
}
```

## 注意事项

1. 日志输出将显示在脚本日志查看器中
2. 当 API 发生变更时，请及时更新相关脚本代码
3. 所有工具方法都应在脚本环境中可用
4. 钩子函数中修改数据时，请遵循返回值规则，不要直接修改传入参数

## 后续更新计划

我们可能在稳定版本中提供更加完善和一致的日志功能接口，届时会提供更详细的文档和更稳定的 API 设计。