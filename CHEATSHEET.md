# ⚡ 快速参考卡片

## 问题与解决

```
❌ 问题：脚本中的 java.lang.Thread 无法工作
✅ 解决：使用新的 task.runAsync() API
```

---

## 最需要了解的 3 行代码

```javascript
// 在后台线程中执行 CGI 操作（推荐用法）
task.runAsync(function() {
    var result = wechat.sendCgi(url, cgiId, funcId, routeId, json);
});
```

---

## 已支持的 API 命名空间

```javascript
log.i("日志")                    // 日志
http.get("url")               // HTTP 请求
storage.get("key")            // 本地存储
datetime.sleepMs(500)          // 延迟
task.runAsync(fn)              // 异步执行 ⭐ 新增
wechat.sendCgi(...)           // 微信 CGI
```

---

## 常见用法速查表

### ✅ 异步执行 CGI（正确）
```javascript
task.runAsync(function() {
    var result = wechat.sendCgi(url, 1089, 0, 0, json);
    log.i(result);
});
```

### ❌ 同步执行 CGI（错误）
```javascript
// 这会导致主线程卡住和超时
var result = wechat.sendCgi(url, 1089, 0, 0, json);
```

### ✅ 延迟任务（正确）
```javascript
datetime.sleepMs(500);  // 延迟 500ms
datetime.sleepS(2);     // 延迟 2 秒
```

### ✅ 顺序执行（正确）
```javascript
task.runAsync(function() {
    var r1 = wechat.sendCgi(...);
    datetime.sleepMs(500);
    var r2 = wechat.sendCgi(...);
});
```

---

## 类型对应表

| 需要 | 不能用 | 改用 |
|------|--------|------|
| 整数 | `java.lang.Integer.valueOf(123)` | `123` |
| 线程 | `new java.lang.Thread(fn)` | `task.runAsync(fn)` |
| 延迟 | `Thread.sleep()` | `datetime.sleepMs()` |

---

## 错误处理

```javascript
task.runAsync(function() {
    try {
        var result = wechat.sendCgi(url, cgiId, funcId, routeId, json);
        if (result.startsWith("Error:")) {
            log.e("请求失败: " + result);
        } else {
            log.i("请求成功: " + result);
        }
    } catch (e) {
        log.e("异常: " + e);
    }
});
```

---

## 脚本钩子

```javascript
// 脚本加载时调用（一次）
function onLoad() {
    task.runAsync(function() {
        // 后台任务
    });
}

// 收到消息时调用
function onMessage(talker, content, type, isSend) {
    if (isSend === 0) { // 接收消息
        task.runAsync(function() {
            // 处理消息
        });
    }
    return null;
}

// 拦截 CGI 请求
function onRequest(uri, cgiId, json) {
    return json; // 必须返回
}

// 拦截 CGI 响应
function onResponse(uri, cgiId, json) {
    return json; // 必须返回
}
```

---

## 调试技巧

```javascript
// 添加日志
log.i("开始");      // 绿色，信息
log.e("错误");      // 红色，错误
log.w("警告");      // 黄色，警告

// 在 Logcat 中搜索
// 搜索关键词：WeKit, JsEngine, AsyncTask
```

---

## 5 分钟快速测试

创建测试脚本并运行：

```javascript
function onLoad() {
    log.i("TEST: 脚本加载");
    
    task.runAsync(function() {
        log.i("TEST: 后台开始");
        
        var result = wechat.sendCgi(
            "/cgi-bin/mmbiz-bin/js-login",
            1089, 0, 0,
            '{}'
        );
        
        log.i("TEST: 结果 = " + result.substring(0, 50) + "...");
    });
    
    log.i("TEST: 加载完成");
}
```

预期：日志快速输出，没有超时。

---

## 常见错误

| 错误 | 原因 | 修复 |
|------|------|------|
| "java is not defined" | 使用了 java.lang.* | 改用 task.runAsync() |
| Timeout 30s | 在主线程阻塞 | 用 task.runAsync() 包装 |
| task is undefined | 使用了旧版本 | 重新编译 |

---

## 文件位置

| 内容 | 文件 |
|------|------|
| 脚本示例 | `SCRIPT_EXAMPLES_FIXED.js` |
| API 参考 | `SCRIPT_ENGINE_API_REFERENCE.md` |
| 快速指南 | `QUICK_FIX_GUIDE_CN.md` |
| 完整参考 | `COMPLETE_SOLUTION_CN.md` |

---

## 一行总结

```
使用 task.runAsync() 替代 java.lang.Thread ，
并在其内部调用 wechat.sendCgi() 。
```

---

**更多问题？查阅：** `SCRIPT_ENGINE_API_REFERENCE.md`
