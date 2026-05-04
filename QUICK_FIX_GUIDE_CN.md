# WeKit CGI 超时问题 - 快速修复指南

## 问题描述
您的脚本在调用 `wechat.sendCgi()` 时收到错误：
```
Error: Timeout waiting for CGI response
```  
即使网络正常。

## 原因（技术性）
🔴 **死锁**：主线程在等待回调，但回调也需要在主线程执行 → 互相卡住 → 30秒超时

## 解决方案

### ✅ 方案 1：最小改动（推荐首先尝试）

**原脚本**：
```javascript
function onLoad() { 
    var result = wechat.sendCgi(url, cgiId, funcId, routeId, jsonPayload); 
}
```

**改为**：
```javascript
function onLoad() { 
    new java.lang.Thread(function() {
        var result = wechat.sendCgi(url, cgiId, funcId, routeId, jsonPayload); 
    }).start();
}
```

**说明**：在后台线程中运行 CGI 操作，不会阻塞主线程。

---

### ✅ 方案 2：代码修复（框架层面，已实施）

**修改文件**：`JsApiExposer.kt` 第 812-864 行

**修改内容**：
- 自动检测是否在主线程上
- 如果在主线程：在后台线程中进行阻塞等待
- 如果已在后台线程：直接等待（安全）

**结果**：之前的脚本现在也能工作（不需要改动脚本）

---

## 您现在应该做什么？

### 情景 A：已编译并运行新代码
✅ **您的原脚本应该现在能工作了**
- 如果还有问题，请检查：
  1. 网络是否真的可达 (ping 服务器)
  2. CGI ID 和参数是否正确
  3. Logcat 中是否有其他错误

### 情景 B：还在使用旧代码
✅ **使用方案 1 改进您的脚本**
- 在任何 `wechat.sendCgi()` 调用外包裹后台线程
- 这样保证脚本不会阻塞

### 情景 C：想要最佳实践
✅ **参考 `SCRIPT_EXAMPLES_FIXED.js`**
- 包含 Promise 风格、顺序执行等高级用法
- 适合复杂的脚本需求

---

## 测试您的修复

### 方法 1：创建简单测试脚本
```javascript
function onLoad() {
    log.i("开始测试");
    
    new java.lang.Thread(function() {
        var result = wechat.sendCgi(
            "/cgi-bin/mmbiz-bin/js-login",
            1089,
            0,
            0,
            '{"test":"data"}'
        );
        log.i("结果: " + result);
    }).start();
    
    log.i("测试完成");
}
```

### 方法 2：查看 Logcat 日志
- 打开 Android Studio Logcat
- 搜索 `"CGI 结果"` 或 `"Result"`
- 应该看到 JSON 响应而不是 "Timeout" 错误

---

## 常见问题速查表

| 问题 | 原因 | 解决 |
|------|------|------|
| 仍然超时 | 可能网络不通 | Ping 目标服务器 |
| 脚本不执行 | 语法错误 | 查看 Logcat 错误 |
| 返回 JSON 错误 | CGI ID/参数错误 | 验证参数正确性 |
| 多个结果输出 | 脚本重复执行 | 检查加载路径 |

---

## 关键要点

🟢 **修复的作用**：
- 解决了主线程死锁问题
- 脚本现在能正常完成
- 不再出现莫名的超时

🟡 **仍需注意**：
- 网络连接必须真实可用
- CGI 参数必须正确
- 服务器必须返回有效响应

🔵 **最佳实践**：
- 总是在后台线程中执行 CGI 操作
- 不要在主线程中长时间阻塞
- 使用异步模式处理响应

---

## 文件参考

- 📄 **完整分析**：查看 `DEADLOCK_ANALYSIS.md`
- 📘 **修复总结**：查看 `FIX_SUMMARY_CN.md`
- 📜 **脚本示例**：查看 `SCRIPT_EXAMPLES_FIXED.js`
- 💻 **代码修改**：`JsApiExposer.kt` 第 812-864 行

---

## 验证修复成功的标志

✅ 脚本初始化迅速返回（不卡顿）  
✅ 30秒内收到 CGI 响应  
✅ Logcat 显示正常的 JSON 数据而不是"Timeout"  
✅ 没有"互锁"或"DEADLOCK"相关错误  

---

**需要进一步帮助？**
- 检查 Logcat 中的完整错误堆栈
- 验证所有参数的正确性
- 尝试用简单的测试脚本排查
