// ===== 优化的脚本示例（推荐方式） =====
// 本脚本演示了如何正确使用 wechat.sendCgi() 避免超时问题
// 注意：使用 task.runAsync() 来在后台执行异步任务

/**
 * 方案1：使用 task.runAsync() 在后台执行（推荐）
 * 适用场景：任何需要调用 sendCgi() 的脚本
 */
function onLoad() {
    log.i("-- 脚本初始化 --");
    
    // 在后台线程中执行 CGI 操作
    task.runAsync(function() {
        log.i("后台任务开始执行");
        
        var url = "/cgi-bin/mmbiz-bin/js-login";
        var cgiId = 1089;
        var funcId = 0;
        var routeId = 0;
        var jsonPayload = '{"1":{"1":"","2":1429982960,"3":"A3410715acaf899","4":671106352,"5":{"12":3687714073890415726},"6":0},"2":"wxde49dccaca3d346d","4":1,"5":"","6":"","7":0,"8":{"2":1089,"3":1,"4":0}}';
        
        var result = wechat.sendCgi(url, cgiId, funcId, routeId, jsonPayload);
        
        log.i("url: " + url);
        log.i("Result: " + result);
        log.i("后台任务执行完成");
    });
    
    log.i("-- 脚本初始化完成（异步执行） --");
}

/**
 * 方案2：使用 task.runAsync() 处理响应
 * 适用场景：需要按顺序处理多个 CGI 请求
 */
function onLoadWithResponse() {
    log.i("-- 脚本初始化 --");
    
    task.runAsync(function() {
        var url = "/cgi-bin/mmbiz-bin/js-login";
        var result = wechat.sendCgi(url, 1089, 0, 0, buildJsonPayload());
        
        log.i("请求完成");
        if (!result.startsWith("Error:")) {
            try {
                var json = JSON.parse(result);
                log.i("响应数据: " + JSON.stringify(json, null, 2));
                processResponse(json);
            } catch (e) {
                log.e("JSON 解析失败: " + e);
            }
        } else {
            log.e("请求失败: " + result);
        }
    });
    
    log.i("-- 脚本初始化完成 --");
}

function buildJsonPayload() {
    return '{"1":{"1":"","2":1429982960,"3":"A3410715acaf899","4":671106352,"5":{"12":3687714073890415726},"6":0},"2":"wxde49dccaca3d346d","4":1,"5":"","6":"","7":0,"8":{"2":1089,"3":1,"4":0}}';
}

function processResponse(json) {
    log.i("处理响应数据...");
    // 在这里处理响应数据
}

/**
 * 方案3：顺序执行多个 CGI 请求
 * 适用场景：需要按顺序发送多个相关的请求
 */
function onLoadSequential() {
    log.i("-- 脚本初始化（顺序执行）--");
    
    task.runAsync(function() {
        try {
            // 第一个请求
            var result1 = wechat.sendCgi(
                "/cgi-bin/mmbiz-bin/js-login",
                1089, 0, 0,
                buildJsonPayload()
            );
            log.i("第一个请求结果: " + 
                  (result1.length > 100 ? result1.substring(0, 100) + "..." : result1));
            
            if (!result1.startsWith("Error:")) {
                // 短暂延迟，然后执行第二个请求
                datetime.sleepMs(500);
                
                // 如果第一个成功，执行第二个
                var result2 = wechat.sendCgi(
                    "/cgi-bin/mmbiz-bin/getinfo",
                    1090, 0, 0,
                    buildSecondPayload()
                );
                log.i("第二个请求结果: " + 
                      (result2.length > 100 ? result2.substring(0, 100) + "..." : result2));
            }
        } catch (e) {
            log.e("顺序执行异常: " + e);
        }
    });
    
    log.i("-- 脚本已提交顺序任务 --");
}

function buildSecondPayload() {
    return '{"1":{"1":"test"}}';
}

/**
 * 方案4：与 onMessage 钩子配合
 * 适用场景：需要在特定事件中发送 CGI 请求
 */
function onMessage(talker, content, type, isSend) {
    // 如果是接收消息且包含特定关键词
    if (isSend === 0 && content.includes("检查")) {
        // 在后台线程中发送 CGI 请求
        task.runAsync(function() {
            var result = wechat.sendCgi(
                "/cgi-bin/mmbiz-bin/js-login",
                1089, 0, 0,
                buildJsonPayload()
            );
            log.i("消息事件中发送的 CGI 结果: " + 
                  (result.length > 100 ? result.substring(0, 100) + "..." : result));
        });
    }
    return null;
}

// ===== 故障排除指南 =====
/*
常见问题与解决方案：

1. 仍然出现 "Timeout waiting for CGI response" 错误：
   - 确保使用了 task.runAsync() 包装 sendCgi() 调用
   - 增加超时时间（第6个参数，例如 sendCgi(..., 60)）
   - 检查 Logcat 中是否有其他错误信息

2. 脚本似乎没有执行 CGI 请求：
   - 检查是否正确调用了 task.runAsync()
   - 查看 Logcat 中的日志输出（搜索脚本中的 log.i() 语句）
   - 确保 JSON payload 格式正确

3. task.runAsync 无法找到：
   - 确保使用的是最新的 WeKit 版本（包含 task API）
   - 重新编译项目

4. 多个 CGI 请求并发执行而导致问题：
   - 在第一个请求后使用 datetime.sleepMs() 进行延迟
   - 或嵌套 task.runAsync() 调用以确保顺序执行

建议的调试方法：
- 启用详细日志：在 WeKit 设置中打开"详细日志"
- 在脚本中添加大量 log.i() 调用来追踪执行流程
- 在 Android Studio logcat 中搜索 "WeKit" 或 "JsEngine"
- 查看后台任务的执行时间
*/

// ===== 最简单的完整示例 =====
function onLoadSimple() {
    log.i("== 脚本启动 ==");
    
    // 简单地在后台执行即可，避免任何阻塞
    task.runAsync(function() {
        try {
            var result = wechat.sendCgi(
                "/cgi-bin/mmbiz-bin/js-login",
                1089,
                0,
                0,
                '{"1":{"1":"","2":1429982960,"3":"A3410715acaf899","4":671106352,"5":{"12":3687714073890415726},"6":0},"2":"wxde49dccaca3d346d","4":1,"5":"","6":"","7":0,"8":{"2":1089,"3":1,"4":0}}'
            );
            log.i("执行结果: " + 
                  (result.length > 100 ? result.substring(0, 100) + "..." : result));
        } catch(e) {
            log.e("执行异常: " + e);
        }
    });
    
    log.i("== 脚本初始化完成 ==");
}
