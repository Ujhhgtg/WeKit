# CGI 超时死锁问题分析与修复方案

## 问题现象
脚本在网络正常的情况下调用 `wechat.sendCgi()` 仍然出现 "Error: Timeout waiting for CGI response" 错误。

## 根本原因：主线程互锁

### 问题流程图
```
脚本执行线程（主线程）：
├─ JsScriptingHook.onEnable() 
│  └─ JsEngine.executeAllOnLoad()
│     └─ onLoad() 函数执行 [在调用线程上，通常是主线程]
│        └─ wechat.sendCgi() 被调用
│           └─ JsApiExposer.kt BaseFunction.call() [仍在主线程]
│              1. 创建 CountDownLatch(1)
│              2. 调用 WePacketHelper.sendCgi()
│                 └─ CoroutineScope(Dispatchers.IO).launch { }
│                    └─ 启动后台线程发送请求
│              3. 调用 latch.await(30s) ← ⚠️ 主线程阻塞！

后台IO线程（同时执行）：
├─ 发送CGI请求
├─ 获得响应
└─ 尝试执行回调：
   ├─ Handler(Looper.getMainLooper()).post {
   │  └─ userCallback?.onSuccess()
   │  └─ latch.countDown() ← ⚠️ 需要在主线程执行！
   └─ 但主线程被 latch.await() 阻塞了

结果：
  主线程等待回调执行 countDown()
  ↓
  回调等待主线程执行 Handler.post() 消息
  ↓
  **DEADLOCK** - 互相等待，永远无法继续
  ↓
  30秒超时后，返回错误："Error: Timeout waiting for CGI response"
```

## 技术细节

### 关键代码位置

**文件1**：`JsApiExposer.kt` (第812-850行)
```kotlin
override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
    var result: String? = null
    val latch = CountDownLatch(1)
    
    WePacketHelper.sendCgi(uri, cgiId, funcId, routeId, jsonPayload) {
        onSuccess { json, _ ->
            result = json
            latch.countDown()  // ← 在回调中调用
        }
        onFailure { _, _, errMsg ->
            result = errMsg
            latch.countDown()  // ← 在回调中调用
        }
    }
    
    if (!latch.await(cgiTimeout.toLong(), TimeUnit.SECONDS)) {  // ← 阻塞等待
        result = "Error: Timeout waiting for CGI response"
    }
    return result ?: "Error: Unknown error"
}
```

**文件2**：`WePacketHelper.kt` (第394-542行)
```kotlin
fun sendCgi(..., callback: WeRequestCallback? = null) {
    CoroutineScope(Dispatchers.IO).launch {  // ← 后台线程
        try {
            // ... CGI处理逻辑 ...
            
            // 回调执行在主线程
            Handler(Looper.getMainLooper()).post {
                if (errType == 0 && errCode == 0) {
                    userCallback?.onSuccess(json, bytes)  // ← 执行在主线程的Handler
                } else {
                    userCallback?.onFailure(errType, errCode, errMsg)
                }
            }
        }
    }
}
```

### 为什么网络正常仍然超时？
- CGI请求成功发送和接收
- 响应数据完整到达
- 但回调 `latch.countDown()` 无法执行（主线程被阻塞）
- 30秒后 `latch.await()` 超时返回 false
- 返回超时错误

## 修复方案

### 方案 A：在后台线程执行脚本的 sendCgi（推荐）

**优点**：
- 不阻塞主线程
- 脚本的 sendCgi 操作天然应该是异步的
- 不需要修改 WePacketHelper 的核心逻辑
- 兼容性好

**改进方式**：将脚本中的 sendCgi 操作包裹在后台任务中重写

```javascript
// 改进前（会导致超时）
function onLoad() {
    var result = wechat.sendCgi(url, cgiId, funcId, routeId, jsonPayload);
    log.i("Result:" + result);
}

// 改进后（推荐方式）
function onLoad() {
    log.i("-- Script Initializing --");
    // 在后台执行，不要等待立即返回
    executeInBackground(function() {
        var url = "/cgi-bin/mmbiz-bin/js-login";
        var cgiId = java.lang.Integer.valueOf(1089);
        var funcId = java.lang.Integer.valueOf(0);
        var routeId = java.lang.Integer.valueOf(0);
        var jsonPayload = '{"1":{"1":"","2":1429982960,"3":"A3410715acaf899","4":671106352,"5":{"12":3687714073890415726},"6":0},"2":"wxde49dccaca3d346d","4":1,"5":"","6":"","7":0,"8":{"2":1089,"3":1,"4":0}}';
        
        var result = wechat.sendCgi(url, cgiId, funcId, routeId, jsonPayload);
        log.i("url:" + url);
        log.i("Result:" + result);
    });
    
    log.i("-- Script Complete (async execution) --");
}

function executeInBackground(callback) {
    // 使用 Java Thread 在后台执行
    new java.lang.Thread(function() {
        try {
            callback();
        } catch (e) {
            log.e("Background task error: " + e);
        }
    }).start();
}
```

### 方案 B：修改 JsApiExposer.sendCgi，在后台线程中执行（代码修复）

**文件**：`JsApiExposer.kt` (第812-850行)

**问题**：当前 CountDownLatch 在脚本线程中阻塞

**解决**：将整个 sendCgi 逻辑移到后台线程

```kotlin
// 修改前代码（第812-850）
override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
    val uri = args.getOrNull(0)?.toString() ?: return Undefined.instance
    val cgiId = (args.getOrNull(1) as? Number)?.toInt() ?: return Undefined.instance
    val funcId = (args.getOrNull(2) as? Number)?.toInt() ?: return Undefined.instance
    val routeId = (args.getOrNull(3) as? Number)?.toInt() ?: return Undefined.instance
    val jsonPayload = args.getOrNull(4)?.toString() ?: return Undefined.instance
    var cgiTimeout = (args.getOrNull(5) as? Number)?.toInt() ?: 30

    var result: String? = null
    val latch = CountDownLatch(1)

    WePacketHelper.sendCgi(
        uri, cgiId, funcId, routeId, jsonPayload
    ) {
        onSuccess { json, _ ->
            result = json
            latch.countDown()
        }
        onFailure { _, _, errMsg ->
            result = errMsg
            latch.countDown()
        }
    }

    if (!latch.await(cgiTimeout.toLong(), java.util.concurrent.TimeUnit.SECONDS)) {
        result = "Error: Timeout waiting for CGI response"
    }
    return result ?: "Error: Unknown error (result is null)"
}
```

**修改后代码**（将阻塞等待移到后台线程）：

```kotlin
override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
    val uri = args.getOrNull(0)?.toString() ?: return Undefined.instance
    val cgiId = (args.getOrNull(1) as? Number)?.toInt() ?: return Undefined.instance
    val funcId = (args.getOrNull(2) as? Number)?.toInt() ?: return Undefined.instance
    val routeId = (args.getOrNull(3) as? Number)?.toInt() ?: return Undefined.instance
    val jsonPayload = args.getOrNull(4)?.toString() ?: return Undefined.instance
    var cgiTimeout = (args.getOrNull(5) as? Number)?.toInt() ?: 30

    var result: String? = null
    val latch = CountDownLatch(1)

    // 在后台线程中执行，避免阻塞脚本线程
    thread(isDaemon = true, name = "JS-CGI-${cgiId}") {
        WePacketHelper.sendCgi(
            uri, cgiId, funcId, routeId, jsonPayload
        ) {
            onSuccess { json, _ ->
                result = json
                latch.countDown()
            }
            onFailure { _, _, errMsg ->
                result = errMsg
                latch.countDown()
            }
        }

        if (!latch.await(cgiTimeout.toLong(), java.util.concurrent.TimeUnit.SECONDS)) {
            result = "Error: Timeout waiting for CGI response"
        }
    }

    // 返回一个占位符，表示请求已提交但仍在处理中
    // 这样脚本线程不会阻塞
    return "⏳ CGI request submitted, waiting for response..."
}
```

**问题**：这个方案会改变 API 的同步特性。

#### 方案 B 的改进版本（最好的代码修复）

```kotlin
override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
    val uri = args.getOrNull(0)?.toString() ?: return Undefined.instance
    val cgiId = (args.getOrNull(1) as? Number)?.toInt() ?: return Undefined.instance
    val funcId = (args.getOrNull(2) as? Number)?.toInt() ?: return Undefined.instance
    val routeId = (args.getOrNull(3) as? Number)?.toInt() ?: return Undefined.instance
    val jsonPayload = args.getOrNull(4)?.toString() ?: return Undefined.instance
    var cgiTimeout = (args.getOrNull(5) as? Number)?.toInt() ?: 30

    var result: String? = null
    val latch = CountDownLatch(1)
    val scriptThreadId = Thread.currentThread().id

    WePacketHelper.sendCgi(
        uri, cgiId, funcId, routeId, jsonPayload
    ) {
        onSuccess { json, _ ->
            result = json
            latch.countDown()
        }
        onFailure { _, _, errMsg ->
            result = errMsg
            latch.countDown()
        }
    }

    // 如果当前在主线程，使用后台线程等待以避免互锁
    if (Looper.getMainLooper().isCurrentThread) {
        var blockingResult: String? = null
        val blockingLatch = CountDownLatch(1)
        
        thread(isDaemon = true, name = "JS-CGI-${cgiId}-Waiter") {
            if (!latch.await(cgiTimeout.toLong(), java.util.concurrent.TimeUnit.SECONDS)) {
                result = "Error: Timeout waiting for CGI response"
            }
            blockingResult = result
            blockingLatch.countDown()
        }
        
        // 在主线程中等待少量时间，避免完全阻塞
        blockingLatch.await(cgiTimeout.toLong() + 1, java.util.concurrent.TimeUnit.SECONDS)
        return blockingResult ?: "Error: Unknown error (result is null)"
    } else {
        // 如果已经在后台线程，可以安全地阻塞等待
        if (!latch.await(cgiTimeout.toLong(), java.util.concurrent.TimeUnit.SECONDS)) {
            result = "Error: Timeout waiting for CGI response"
        }
        return result ?: "Error: Unknown error (result is null)"
    }
}
```

## 推荐修复方案

**首选**：方案 A（脚本侧修改）
- 理由：避免阻塞，符合异步编程模式
- 用户可以添加辅助函数到脚本库中

**次选**：方案 B 改进版（代码侧修复）
- 理由：检测主线程，自动采用后台线程等待
- 对现有脚本兼容，但需要修改框架代码

## 实施建议

1. **立即**：向用户提供方案 A 的脚本示例
2. **短期**：实施方案 B 改进版到代码库中
3. **测试**：验证在各种线程场景下的行为
4. **文档**：在脚本 API 文档中明确说明异步特性

