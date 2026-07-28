# 脚本引擎 API 参考

脚本引擎基于 Rhino JavaScript 引擎, 提供完整的微信扩展 API。

## 脚本目录

脚本文件存放于 `<模块数据目录>/scripts/` 目录下, 支持 `.js` 扩展名。模块启动时会自动加载并执行该目录下的所有脚本。

## 全局函数

### onLoad()

```typescript
declare function onLoad(): void;
```

当全部脚本加载完成后触发。可用于初始化操作。

### onMessage()

```typescript
declare function onMessage(
    talker: string,
    content: string,
    type: number,
    isSend: number
): string | MessageResponse | null | void;
```

收到新消息时触发。可通过返回值拦截或修改消息。

**示例:**

```javascript
function onMessage(talker, content, type, isSend) {
    if (content === "ping") {
        return "pong";
    }
    return null;
}
```

### onRequest()

```typescript
declare function onRequest(uri: string, cgiId: number, json: Record<string, unknown>): Record<string, unknown>;
```

拦截并修改微信发出的网络请求。返回修改后的 JSON 对象。

### onResponse()

```typescript
declare function onResponse(uri: string, cgiId: number, json: Record<string, unknown>): Record<string, unknown>;
```

拦截并修改微信收到的网络响应。返回修改后的 JSON 对象。

## API 命名空间

### log — 日志 API

```typescript
declare namespace log {
    function d(...message: unknown[]): void;  // 调试级别日志
    function i(...message: unknown[]): void;  // 信息级别日志
    function w(...message: unknown[]): void;  // 警告级别日志
    function e(...message: unknown[]): void;  // 错误级别日志
}
```

**示例:**

```javascript
log.i("当前用户:", wxid);
log.e("发生错误:", errorMsg);
```

### http — HTTP 请求 API

```typescript
declare namespace http {
    function get(url: string, params?: Record<string, string | number>, headers?: Record<string, string>): HttpResponse;
    function post(url: string, formData?: Record<string, string | number>, jsonBody?: unknown, headers?: Record<string, string>): HttpResponse;
    function download(url: string, filename?: string): string | null;
}
```

**HttpResponse** 接口:

```typescript
interface HttpResponse {
    readonly ok: boolean;
    readonly status: number;
    readonly headers: Record<string, string>;
    readonly body: string;
    readonly json: Record<string, unknown> | null;
}
```

**示例:**

```javascript
// GET 请求
const resp = http.get("https://api.example.com/user", { id: 123 });
if (resp.ok) {
    log.i("用户数据:", resp.json);
}

// POST JSON
const resp2 = http.post("https://api.example.com/data", null, { key: "value" });

// POST 表单
const resp3 = http.post("https://api.example.com/login", { username: "user", password: "pass" });

// 下载文件
const path = http.download("https://example.com/image.jpg");
if (path) {
    replyImage(path);
}
```

### storage — 持久化存储 API

```typescript
declare namespace storage {
    function get(key: string): unknown;
    function getOrDefault(key: string, def: unknown): unknown;
    function set(key: string, value: unknown): void;
    function hasKey(key: string): boolean;
    function remove(key: string): unknown;
    function keys(): string[];
    function size(): number;
    function clear(): void;
    function isEmpty(): boolean;
}
```

**示例:**

```javascript
// 计数器
let count = storage.getOrDefault("counter", 0);
storage.set("counter", count + 1);

// 存储配置
storage.set("settings", { enabled: true, threshold: 10 });

// 检查并读取
if (storage.hasKey("token")) {
    const token = storage.get("token");
}
```

### datetime — 日期/时间 API

```typescript
declare namespace datetime {
    function sleepS(seconds: number): void;
    function sleepMs(milliseconds: number): void;
    function getCurrentUnixEpoch(): number;
}
```

**示例:**

```javascript
datetime.sleepMs(500);  // 等待 500ms
const now = datetime.getCurrentUnixEpoch();
log.i("当前时间戳:", now);
```

### wechat — 微信 API

#### 消息发送 (指定接收人)

```typescript
declare namespace wechat {
    function sendText(to: string, text: string): void;
    function sendImage(to: string, path: string): void;
    function sendFile(to: string, path: string, title?: string): void;
    function sendVoice(to: string, path: string, durationMs: number): void;
    function sendAppMsg(to: string, content: string): void;
}
```

**示例:**

```javascript
wechat.sendText("wxid_abc123", "你好！");

const imgPath = http.download("https://example.com/image.jpg");
if (imgPath) {
    wechat.sendImage("wxid_abc123", imgPath);
}

wechat.sendFile("wxid_abc123", "/sdcard/document.pdf", "重要文档.pdf");
wechat.sendVoice("wxid_abc123", "/data/voice.amr", 3000);
wechat.sendAppMsg("wxid_abc123", "<msg>...</msg>");
```

#### 消息回复 (自动回复至发送者)

```typescript
declare namespace wechat {
    function replyText(text: string): void;
    function replyImage(path: string): void;
    function replyFile(path: string, title?: string): void;
    function replyVoice(path: string, durationMs: number): void;
    function replyAppMsg(content: string): void;
}
```

**示例:**

```javascript
function onMessage(talker, content, type, isSend) {
    if (content === "ping") {
        wechat.replyText("pong");
    }
}
```

#### 其他

```typescript
declare namespace wechat {
    function sendCgi(uri: string, cgiId: number, funcId: number, routeId: number, jsonPayload: string, onSuccess?: (json: string) => void, onFailure?: (errMsg: string) => void): void;
    function getSelfWxId(): string;
    function getSelfCustomWxId(): string;
}
```

**示例:**

```javascript
const myWxId = wechat.getSelfWxId();
log.i("我的微信 ID:", myWxId);

// 发送 CGI 请求
wechat.sendCgi("/cgi-bin/mmbiz-bin/xxx", 123, 0, 0, '{"key":"value"}',
    function(json) { log.i("成功:", json); },
    function(err) { log.e("失败:", err); }
);
```

### task — 异步任务 API

```typescript
declare namespace task {
    function run(fn: () => void): void;
}
```

在独立线程中执行函数, 避免阻塞主脚本。

**示例:**

```javascript
task.run(function() {
    const resp = http.get("https://api.example.com/data");
    log.i("Got response:", resp.body);
});
```

### hostinfo — 宿主信息 API

```typescript
declare namespace hostinfo {
    const application: unknown;   // 宿主的 Application 对象
    const packageName: string;    // 宿主包名 ("com.tencent.mm")
    const versionCode: number;    // 宿主版本号
    const versionName: string;    // 宿主版本名
}
```

**示例:**

```javascript
const ctx = hostinfo.application;
log.i("微信版本:", hostinfo.versionName);
```

### xposed — Xposed Hook API

```typescript
declare namespace xposed {
    function hookBefore(method: JavaMethod, hookFunc: (thisObj: unknown, args: unknown[]) => unknown | void): HookHandle;
    function hookBefore(className: string, methodName: string, hookFunc: (thisObj: unknown, args: unknown[]) => unknown | void): HookHandle;
    function hookAfter(method: JavaMethod, hookFunc: (thisObj: unknown, args: unknown[], originalResult: unknown) => unknown | void): HookHandle;
    function hookAfter(className: string, methodName: string, hookFunc: (thisObj: unknown, args: unknown[], originalResult: unknown) => unknown | void): HookHandle;
}
```

**示例:**

```javascript
// 通过类名和方法名 Hook
xposed.hookBefore("com.example.TargetClass", "targetMethod", function(thisObj, args) {
    log.i("方法被调用, 参数:", args);
    args[0] = "modified";  // 修改参数
});

// 通过反射 API 查找精确重载后 Hook
const m = reflect.findFirstMethod("com.example.Cls", function(name, pt, ret, mods) {
    return name === "targetMethod";
});
xposed.hookBefore(m, function(thisObj, args) {
    log.i("Hooked!");
});
```

### reflect — 反射 API

```typescript
interface JavaField {
    readonly name: string;
    readonly clazz: JavaClass;
    readonly type: JavaClass;
    readonly modifiers: string[];
    get(instance?: object): unknown;
    set(instanceOrValue: object, value?: object): void;
}

interface JavaMethod {
    readonly name: string;
    readonly clazz: JavaClass;
    readonly descriptor: string;
    readonly paramTypes: JavaClass[];
    readonly returnType: JavaClass;
    readonly modifiers: string[];
    hookBefore(callback: (thisObj: unknown, args: unknown[]) => unknown | void): HookHandle;
    hookAfter(callback: (thisObj: unknown, args: unknown[], originalResult: unknown) => unknown | void): HookHandle;
    invoke(instance: object | null, args: unknown[]): MethodReturnValue;
}

interface JavaConstructor {
    readonly name: string;
    readonly clazz: JavaClass;
    readonly descriptor: string;
    readonly paramTypes: JavaClass[];
    readonly returnType: JavaClass;
    readonly modifiers: string[];
    invoke(args: unknown[]): unknown;
}

interface JavaClass {
    readonly name: string;
    createInstance(args: unknown[]): unknown;
    getMethods(): JavaMethod[];
    getFields(): JavaField[];
}

declare namespace reflect {
    function findFirstMethod(className: string, predicate: (name: string, paramTypes: JavaClass[], returnType: JavaClass, modifiers: string[]) => boolean): JavaMethod | null;
    function findMethods(className: string, predicate: (name: string, paramTypes: JavaClass[], returnType: JavaClass, modifiers: string[]) => boolean): JavaMethod[];
    function findFirstField(className: string, isStatic: boolean, predicate: (name: string, type: JavaClass, modifiers: string[]) => boolean): JavaField | null;
    function findFirstConstructor(className: string, isStatic: boolean, predicate: (name: string, paramTypes: JavaClass[]) => boolean): JavaConstructor | null;
    function toClass(className: string): JavaClass;
}
```

**示例:**

```javascript
// 查找并调用方法
const m = reflect.findFirstMethod("com.example.Util", function(name) {
    return name === "getVersion";
});
const rv = m.invoke(null, []);
if (!rv.exception) {
    log.i("版本:", rv.value);
}

// 查找并读取字段
const f = reflect.findFirstField("com.example.Config", false, function(n, t, m) {
    return n === "count" && t.name === "int";
});
const val = f.get();
log.i("count =", val);

// 创建实例
const clazz = reflect.toClass("com.example.User");
const user = clazz.createInstance(["Alice"]);
```

### dexkit — DexKit API (基于字节码的搜索)

```typescript
interface MethodSearcher { /* 方法搜索器 */ }
interface ClassSearcher { /* 类搜索器 */ }
interface MethodResult { /* 搜索结果 */ }
interface ClassResult { /* 搜索结果 */ }

declare namespace dexkit {
    function findMethod(search: (searcher: MethodSearcher) => void): MethodResult[];
    function findClass(search: (searcher: ClassSearcher) => void): ClassResult[];
}
```

## 常用辅助函数

建议在脚本开头定义:

```javascript
function getCleanContent(content) {
   var match = content.match(/^wxid_[^:]+:\n(.*)$/s);
   return match ? match[1] : content;
}

function getSenderWxid(content) {
   var match = content.match(/^(wxid_[^:]+):\n/);
   return match ? match[1] : null;
}

function isGroupMessage(content) {
   return /^wxid_[^:]+:\n/.test(content);
}
```

## 注意事项

- 脚本引擎在启用「脚本引擎」功能后才会加载脚本
- `onMessage()`、`onRequest()`、`onResponse()` 触发器需在模块设置中分别启用
- 脚本中可以使用 Java 类的全限定名访问 Android/Java API。对于不常用的类, 需在全限定名前添加 `Packages.` 前缀。
