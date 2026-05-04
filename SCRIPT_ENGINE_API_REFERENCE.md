# WeKit 脚本引擎 API 支持说明

## 📌 重要：Java 类访问限制

我们的脚本引擎（基于 Mozilla Rhino）**不支持** 直接访问 Java 类，如：
- ❌ `java.lang.Thread`
- ❌ `java.lang.Integer` 
- ❌ `java.util.Date`
- ❌ 其他 `java.*` 命名空间

## ✅ 支持的脚本 API

### 日志 - `log` 命名空间
```javascript
log.i("信息消息");
log.e("错误消息");
log.w("警告消息");
log.d("调试消息");
```

### HTTP 请求 - `http` 命名空间
```javascript
var resp = http.get("https://example.com/api", { id: 123 });
if (resp.ok) {
  log.i("状态: " + resp.status);
  log.i("响应: " + resp.body);
  if (resp.json) log.i("JSON: " + JSON.stringify(resp.json));
}

var resp2 = http.post("https://example.com/api", { key: "value" });

var result = http.download("https://example.com/image.jpg");
if (result.ok) {
  log.i("文件保存到: " + result.path);
}
```

### 本地存储 - `storage` 命名空间
```javascript
storage.set("key", "value");
var value = storage.get("key");
var value2 = storage.getOrDefault("key", "默认值");
storage.remove("key");
var hasKey = storage.hasKey("key");
storage.clear();
var size = storage.size();
var isEmpty = storage.isEmpty();
var keys = storage.keys();
var popped = storage.pop("key"); // 获取并删除
```

### 日期时间 - `datetime` 命名空间
```javascript
datetime.sleepS(5);      // 延迟 5 秒
datetime.sleepMs(500);   // 延迟 500 毫秒
var now = datetime.getCurrentUnixEpoch(); // 获取当前 Unix 时间戳
```

### 异步任务 - `task` 命名空间 ⭐ **新增**
```javascript
// 在后台线程中执行函数（推荐用于 sendCgi）
task.runAsync(function() {
  var result = wechat.sendCgi(uri, cgiId, funcId, routeId, json);
  log.i("结果: " + result);
});
```

### 微信 API - `wechat` 命名空间

#### 发送消息
```javascript
wechat.sendText("wxid_xxx", "消息文本");
wechat.sendImage("wxid_xxx", "/path/to/image.jpg");
wechat.sendFile("wxid_xxx", "/path/to/file.zip", "文件名");
wechat.sendVoice("wxid_xxx", "/path/to/voice.m4a", 10000); // 毫秒
wechat.sendAppMsg("wxid_xxx", "<msg>...</msg>");
```

#### 回复消息（在 onMessage 钩子中）
```javascript
function onMessage(talker, content, type, isSend) {
  if (content.includes("关键词")) {
    replyText("回复文本");
    // 或
    replyImage("/path/to/image.jpg");
    replyFile("/path/to/file.zip", "文件名");
    replyVoice("/path/to/voice.m4a", 10000);
  }
  return null;
}
```

#### 发送 CGI 请求 ⭐ **需要在后台线程中使用**
```javascript
// ✅ 正确用法
task.runAsync(function() {
  var result = wechat.sendCgi(url, cgiId, funcId, routeId, jsonPayload);
  log.i("结果: " + result);
});

// ❌ 错误用法（会导致超时）
var result = wechat.sendCgi(url, cgiId, funcId, routeId, jsonPayload);
```

## 🔄 脚本钩子函数

### onLoad()
在脚本加载时调用，执行一次。

```javascript
function onLoad() {
  log.i("脚本加载完成");
  
  // 在后台执行 CGI 请求
  task.runAsync(function() {
    var result = wechat.sendCgi(...);
  });
}
```

### onMessage(talker, content, type, isSend)
当消息被插入数据库时调用。

```javascript
function onMessage(talker, content, type, isSend) {
  log.i("收到消息来自: " + talker); // isSend=0 表示收到消息
  
  if (content.includes("特定关键词")) {
    replyText("自动回复");
  }
  
  return null; // 可以阻止消息插入数据库吗？暂时不支持
}
```

### onRequest(uri, cgiId, json)
拦截微信发出的 CGI 请求。

```javascript
function onRequest(uri, cgiId, json) {
  log.i("拦截请求: " + uri + ", CGI=" + cgiId);
  
  // 修改请求数据
  if (cgiId === 522) {
    json.customField = "注入的值";
  }
  
  return json; // 必须返回修改后的 json
}
```

### onResponse(uri, cgiId, json)
拦截微信收到的 CGI 响应。

```javascript
function onResponse(uri, cgiId, json) {
  log.i("拦截响应: " + uri + ", CGI=" + cgiId);
  
  // 修改响应数据
  if (cgiId === 522) {
    json.modifiedField = "修改后的值";
  }
  
  return json; // 必须返回修改后的 json
}
```

## 📋 正确的 CGI 请求写法

### ❌ 错误写法（会导致超时）
```javascript
function onLoad() {
  // 在主线程中直接调用 sendCgi，会导致超时
  var result = wechat.sendCgi(url, 1089, 0, 0, json);
  log.i(result);
}
```

### ✅ 正确写法
```javascript
function onLoad() {
  // 使用 task.runAsync() 在后台线程中执行
  task.runAsync(function() {
    var result = wechat.sendCgi(url, 1089, 0, 0, json);
    log.i(result);
  });
}
```

## 🎯 常见场景

### 场景 1：初始化时获取数据
```javascript
function onLoad() {
  task.runAsync(function() {
    // 获取用户信息
    var result = wechat.sendCgi(
      "/cgi-bin/micromsg-bin/getContactByID",
      21, 0, 0,
      '{"1":{"1":"wxid_xxx"}}'
    );
    if (!result.startsWith("Error:")) {
      storage.set("user_info", result);
    }
  });
}
```

### 场景 2：消息触发后台任务
```javascript
function onMessage(talker, content, type, isSend) {
  if (isSend === 0 && content === "同步") {
    task.runAsync(function() {
      var result = wechat.sendCgi(...);
      replyText("已同步: " + result);
    });
  }
  return null;
}
```

### 场景 3：顺序执行多个请求
```javascript
task.runAsync(function() {
  // 第一个请求
  var result1 = wechat.sendCgi(url1, cgi1, 0, 0, json1);
  log.i("第一个完成");
  
  // 等待 500ms
  datetime.sleepMs(500);
  
  // 第二个请求
  var result2 = wechat.sendCgi(url2, cgi2, 0, 0, json2);
  log.i("第二个完成");
});
```

### 场景 4：定时任务（伪）
```javascript
function onLoad() {
  // 虽然不能真正定时，但可以在脚本加载时执行
  task.runAsync(function() {
    for (var i = 0; i < 60; i++) {
      datetime.sleepS(1); // 每秒等待一次
      
      var result = wechat.sendCgi(...);
      log.i("执行第 " + i + " 次");
      
      if (result.startsWith("Error:")) {
        break; // 如果出错则停止
      }
    }
  });
}
```

## 🚀 最佳实践

1. **总是在后台执行 CGI 请求**
   ```javascript
   task.runAsync(function() {
     wechat.sendCgi(...);
   });
   ```

2. **使用 try-catch 捕获异常**
   ```javascript
   task.runAsync(function() {
     try {
       var result = wechat.sendCgi(...);
     } catch (e) {
       log.e("异常: " + e);
     }
   });
   ```

3. **检查错误响应**
   ```javascript
   var result = wechat.sendCgi(...);
   if (result.startsWith("Error:")) {
     log.e("请求失败: " + result);
   } else {
     // 处理成功响应
   }
   ```

4. **使用 datetime.sleepMs 进行延迟**
   ```javascript
   task.runAsync(function() {
     var result1 = wechat.sendCgi(...);
     datetime.sleepMs(500);
     var result2 = wechat.sendCgi(...);
   });
   ```

5. **在 storage 中缓存数据**
   ```javascript
   var cached = storage.get("last_result");
   if (!cached) {
     task.runAsync(function() {
       var result = wechat.sendCgi(...);
       storage.set("last_result", result);
     });
   }
   ```

## 📖 完整脚本示例

详见 `SCRIPT_EXAMPLES_FIXED.js` 文件中的多个真实示例。

## ❓ FAQ

**Q: 为什么不能直接使用 java.lang.Thread？**  
A: 脚本引擎出于安全考虑，默认不允许访问 Java 类。使用 `task.runAsync()` 是推荐的安全方式。

**Q: task.runAsync() 何时执行？**  
A: 立即在新的后台线程中执行，不会阻塞主线程。

**Q: 能否在 task.runAsync() 中嵌套调用？**  
A: 可以，但通常不需要。顺序执行应该在同一个 runAsync 回调中进行。

**Q: 后台线程中的日志输出是否正常？**  
A: 可以，log API 是线程安全的。

**Q: 如何等待后台任务完成？**  
A: 后台任务将在后台执行，如果需要同步的结果，应该使用 storage 来存储中间结果。

---

**更新日期**：2024  
**API 版本**：2.0 (新增 task 命名空间)
