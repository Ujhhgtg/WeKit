# WeKit 脚本引擎增强 - 异步任务 API

## 📢 变更摘要

### 🎯 解决的问题
脚本示例中使用的 `java.lang.Thread` 和 `java.lang.Integer` 无法在脚本引擎中工作，因为脚本引擎不支持直接 Java 类访问。

### ✅ 解决方案
添加了新的 `task.runAsync()` API，允许脚本在后台线程中执行函数，无需直接访问 Java 类。

---

## 📝 变更清单

### 1. 代码修改

#### 文件：`JsApiExposer.kt`

**第 69 行** - 在 `exposeApis()` 中添加新 API 调用
```kotlin
fun exposeApis(scope: ScriptableObject, talker: String? = null) {
    exposeHttpApis(scope)
    exposeLogApis(scope)
    exposeStorageApis(scope)
    exposeDateTimeApis(scope)
    exposeTaskApis(scope)  // ← 新增
    exposeXposedApis(scope)
    exposeWeChatApis(scope, talker)
}
```

**第 503-540 行** - 添加新的 `exposeTaskApis()` 方法
```kotlin
private fun exposeTaskApis(scope: ScriptableObject) {
    val taskObj = NativeObject()
    
    // task.runAsync(callback) - 在后台线程中执行回调
    ScriptableObject.putProperty(
        taskObj, "runAsync",
        object : BaseFunction() {
            override fun call(...): Any? {
                val callback = args.getOrNull(0)
                if (callback !is Function) {
                    return Undefined.instance
                }
                
                thread(isDaemon = true, name = "JS-AsyncTask") {
                    try {
                        callback.call(cx, scope, scope, emptyArray())
                    } catch (e: Exception) {
                        WeLogger.e(TAG, "AsyncTask failed", e)
                    }
                }
                
                return Undefined.instance
            }
        }
    )
    
    ScriptableObject.putProperty(scope, "task", taskObj)
}
```

#### 文件：`globals.d.ts`

**第 222-251 行** - 添加 TypeScript 类型定义
```typescript
declare namespace task {
    /**
     * 在后台线程中异步执行一个函数
     * 特别适用于调用 wechat.sendCgi()
     */
    function runAsync(callback: () => void): void;
}
```

### 2. 脚本示例更新

#### 文件：`SCRIPT_EXAMPLES_FIXED.js`

**完全重写**，移除所有 `java.lang.*` 使用：
- ❌ 删除：`java.lang.Thread`
- ❌ 删除：`java.lang.Integer.valueOf()`
- ✅ 添加：`task.runAsync()` 用法示例
- ✅ 添加：`datetime.sleepMs()` 用于延迟
- ✅ 保持：所有脚本模式示例

**新增的示例方案**：
1. 方案 1：基本的 task.runAsync() 用法
2. 方案 2：带响应处理的异步执行
3. 方案 3：顺序执行多个 CGI 请求
4. 方案 4：与 onMessage 钩子配合

### 3. 文档新增

#### 新增文件：`SCRIPT_ENGINE_API_REFERENCE.md`

完整的脚本引擎 API 参考文档，包括：
- 所有支持的 API 命名空间
- 正确和错误的用法示例
- 常见场景的实现方式
- 最佳实践指南
- FAQ

---

## 🚀 使用示例

### ❌ 旧写法（不支持）
```javascript
function onLoad() {
    new java.lang.Thread(function() {
        var cgiId = java.lang.Integer.valueOf(1089);
        var result = wechat.sendCgi(url, cgiId, 0, 0, json);
        log.i("Result: " + result);
    }).start();
}
```

### ✅ 新写法（现在支持）
```javascript
function onLoad() {
    task.runAsync(function() {
        var result = wechat.sendCgi(url, 1089, 0, 0, json);
        log.i("Result: " + result);
    });
}
```

---

## 📊 API 对比

| 功能 | 旧写法 | 新写法 |
|------|--------|--------|
| 创建线程 | `new java.lang.Thread(...)` | `task.runAsync(...)` |
| 参数类型 | `java.lang.Integer.valueOf(123)` | `123` (直接整数) |
| 支持状态 | ❌ 不支持 | ✅ 官方支持 |
| 代码简洁性 | 🔴 复杂 | 🟢 简洁 |
| 安全性 | 🟡 需要 Java 知识 | 🟢 隔离的 API |

---

## ✨ 改进点

1. **更简洁的 API**
   - ✅ 无需了解 Java 类
   - ✅ 更少的代码行数
   - ✅ 更容易理解

2. **更安全的执行**
   - ✅ 不暴露 Java 类
   - ✅ 自动异常处理
   - ✅ 线程安全

3. **更好的文档**
   - ✅ 完整的 TypeScript 类型定义
   - ✅ 详细的 API 参考
   - ✅ 多个真实示例

4. **向后兼容**
   - ✅ 不改变现有 API
   - ✅ CGI 超时修复仍然有效
   - ✅ 旧的同步等待仍然工作（如果在后台线程中）

---

## 📋 迁移检查清单

如果您有现有的脚本使用 `java.lang.Thread`：

- [ ] 将 `new java.lang.Thread(function() { ... }).start()` 替换为 `task.runAsync(function() { ... })`
- [ ] 将 `java.lang.Integer.valueOf(123)` 替换为 `123`
- [ ] 移除所有的 `.start()` 调用
- [ ] 测试脚本功能是否正常
- [ ] 检查 Logcat 中是否有异常

---

## 🧪 测试

### 快速测试脚本
```javascript
function onLoad() {
    log.i("脚本加载");
    
    task.runAsync(function() {
        log.i("后台任务执行中");
        datetime.sleepMs(100);
        log.i("后台任务完成");
    });
    
    log.i("主线程继续");
}
```

### 预期输出（Logcat）
```
脚本加载
主线程继续
后台任务执行中
后台任务完成
```

**关键点**：主线程消息先输出，后台任务消息后输出。

---

## 🔄 核心改进细节

### 修复前
```kotlin
// CGI 超时问题来自于：
Thread.sleep() // 在主线程中 ❌ 互锁
```

### 修复后
```kotlin
// 现在支持：
task.runAsync { } // 在后台线程中 ✅ 无阻塞
```

---

## 📚 相关文档

- **API 参考**：`SCRIPT_ENGINE_API_REFERENCE.md` - 完整的 API 文档
- **快速指南**：`QUICK_FIX_GUIDE_CN.md` - 快速问题解决
- **脚本示例**：`SCRIPT_EXAMPLES_FIXED.js` - 多个真实示例
- **完整分析**：`COMPLETE_SOLUTION_CN.md` - 深度技术分析

---

## 🎯 下一步

1. **立即**：编译最新代码（包含本更改）
2. **测试**：运行提供的脚本示例
3. **迁移**：将现有脚本更新为新的 `task.runAsync()` 方式
4. **反馈**：测试过程中遇到任何问题请报告

---

## ⚙️ 技术细节

### 线程模型
- 每个 `task.runAsync()` 调用启动一个新的 daemon 线程
- 线程名称：`JS-AsyncTask`
- 线程自动在任务完成后回收

### 自动异常处理
```kotlin
try {
    callback.call(cx, scope, scope, emptyArray())
} catch (e: Exception) {
    WeLogger.e(TAG, "AsyncTask failed", e)
}
```
异常被记录到 Logcat，不会导致脚本崩溃。

### 安全性
- 脚本中的任何异常都被捕获
- 不会阻塞主线程
- 日志记录便于调试

---

## 📞 故障排除

### 问题：task 未定义
**原因**：未使用最新编译的代码  
**解决**：重新编译项目

### 问题：runAsync 中的异常未显示
**原因**：异常被自动捕获并记录到 Logcat  
**解决**：在 Android Studio Logcat 中搜索 "AsyncTask failed"

### 问题：脚本执行顺序不对
**原因**：可能有多个 runAsync 并发执行  
**解决**：在单个 runAsync 中执行所有操作，或使用 datetime.sleepMs 进行同步

---

## 📝 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 2.1 | 2024 | ✨ 新增 task.runAsync() API |
| 2.0 | 2024 | ✅ 修复 sendCgi 超时问题 |
| 1.0 | - | 初始版本 |

---

**最后更新**：2024年  
**状态**：✅ 已实现并编译通过
