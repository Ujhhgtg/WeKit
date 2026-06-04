/**
 * 脚本全局 API 定义
 */

// --- 通用类型 ---

interface HttpResponse {
    /** 请求是否成功 (status < 400) */
    readonly ok: boolean;
    /** HTTP 状态码 */
    readonly status: number;
    /** 响应体字符串 */
    readonly body: string;
    /** 如果响应体是 JSON，则为自动解析好的对象；否则为 null */
    readonly json: unknown | null;
    /** 错误信息（如果有） */
    readonly error?: string;
}

/**
 * 钩子操作句柄
 */
interface HookHandle {
    /**
     * 移除已安装的钩子
     * @example
     * const handle = xposed.hookBefore("com.example.Cls", "method", () => {});
     * // 稍后取消钩子
     * handle.unhook();
     */
    unhook(): void;
}

/**
 * invoke 方法返回值
 */
interface MethodReturnValue {
    readonly value: unknown;
    readonly exception: boolean;
}

// --- 日志 API ---

declare namespace log {
    /**
     * 输出调试级别日志
     * @param message 要记录的消息，支持多个参数
     */
    function d(...message: unknown[]): void;
    
    /**
     * 输出信息级别日志
     * @param message 要记录的消息，支持多个参数
     */
    function i(...message: unknown[]): void;
    
    /**
     * 输出警告级别日志
     * @param message 要记录的消息，支持多个参数
     */
    function w(...message: unknown[]): void;
    
    /**
     * 输出错误级别日志
     * @param message 要记录的消息，支持多个参数
     */
    function e(...message: unknown[]): void;
}

// --- HTTP 请求 API ---

declare namespace http {
    /**
     * 发送 GET 请求
     * @param url 请求的 URL
     * @param params 查询参数对象，会自动编码并拼接到 URL
     * @param headers 请求头对象
     * @returns HTTP 响应对象
     * @example
     * const resp = http.get("https://api.example.com/user", { id: 123 }, { "Authorization": "Bearer token" });
     * if (resp.ok) {
     *   log.i("User:", resp.json);
     * }
     */
    function get(url: string, params?: Record<string, string | number>, headers?: Record<string, string>): HttpResponse;
    
    /**
     * 发送 POST 请求
     * @param url 请求的 URL
     * @param formData 表单数据对象（application/x-www-form-urlencoded）
     * @param jsonBody JSON 数据对象（application/json），与 formData 互斥
     * @param headers 请求头对象
     * @returns HTTP 响应对象
     * @example
     * // POST JSON
     * const resp = http.post("https://api.example.com/data", null, { key: "value" });
     * 
     * // POST Form
     * const resp2 = http.post("https://api.example.com/login", { username: "user", password: "pass" });
     */
    function post(url: string, formData?: Record<string, string | number>, jsonBody?: unknown, headers?: Record<string, string>): HttpResponse;
    
    /**
     * 下载文件到宿主缓存目录；下载位置保证可被宿主访问，可直接传入 sendImage(), sendFile() 等
     * @param url 文件的 URL
     * @param filename 自定义文件名（可选）
     * @returns 下载成功返回文件绝对路径，失败返回 null
     * @example
     * const path = http.download("https://example.com/image.jpg");
     * if (path) {
     *   replyImage(path);
     * }
     */
    function download(url: string, filename?: string): string | null;
}

// --- 持久化存储 API ---

declare namespace storage {
    /**
     * 获取指定键的值。若不存在返回 undefined。
     * @param key 键名
     * @returns 存储的值，不存在则返回 undefined
     * @example
     * const value = storage.get("counter");
     * if (value !== undefined) {
     *   log.i("Counter:", value);
     * }
     */
    function get(key: string): unknown;
    
    /**
     * 获取值，若不存在则返回提供的默认值 def。
     * @param key 键名
     * @param def 默认值
     * @returns 存储的值或默认值
     * @example
     * const count = storage.getOrDefault("counter", 0);
     * storage.set("counter", count + 1);
     */
    function getOrDefault(key: string, def: unknown): unknown;
    
    /**
     * 存入键值对。若 value 为 undefined 则移除该键。
     * @param key 键名
     * @param value 要存储的值
     * @example
     * storage.set("lastUser", "wxid_abc123");
     * storage.set("settings", { enabled: true, threshold: 10 });
     */
    function set(key: string, value: unknown): void;
    
    /**
     * 判断是否存在指定的键。
     * @param key 键名
     * @returns 是否存在该键
     * @example
     * if (storage.hasKey("token")) {
     *   log.i("已登录");
     * }
     */
    function hasKey(key: string): boolean;
    
    /**
     * 移除指定的键。
     * @param key 键名
     * @returns 被移除的值，不存在则返回 undefined
     * @example
     * const old = storage.remove("tempKey");
     */
    function remove(key: string): unknown;

    /**
     * 返回包含所有键名的字符串数组。
     * @returns 所有键名的数组
     * @example
     * const allKeys = storage.keys();
     * log.i("Total keys:", allKeys.length);
     */
    function keys(): string[];
    
    /**
     * 返回当前存储条目总数。
     * @returns 键值对数量
     */
    function size(): number;
    
    /**
     * 清空所有键值对。
     * @example
     * storage.clear();
     * log.i("Storage cleared");
     */
    function clear(): void;
    
    /**
     * 检查存储是否为空。
     * @returns 是否为空
     */
    function isEmpty(): boolean;
}

// --- 日期/时间 API ---

declare namespace datetime {
    /**
     * 休眠指定的秒数
     * @param seconds 休眠的秒数
     * @example
     * log.i("开始等待...");
     * datetime.sleepS(3);
     * log.i("等待结束");
     */
    function sleepS(seconds: number): void;
    
    /**
     * 休眠指定的毫秒数
     * @param milliseconds 休眠的毫秒数
     * @example
     * log.i("短暂延迟...");
     * datetime.sleepMs(500);
     * log.i("延迟结束");
     */
    function sleepMs(milliseconds: number): void;
    
    /**
     * 获取当前 Unix 时间戳（秒）
     * @returns 当前时间的 Unix 时间戳
     * @example
     * const now = datetime.getCurrentUnixEpoch();
     * log.i("当前 Unix 时间戳:", now);
     */
    function getCurrentUnixEpoch(): number;
}

// --- 微信 API ---

declare namespace wechat {

    // --- 消息发送 API (指定接收人) ---

    /**
     * 向指定用户发送文本消息
     * @param to 接收者的 wxid 或群 ID
     * @param text 消息文本内容
     * @example
     * wechat.sendText("wxid_abc123", "你好！");
     */
    function sendText(to: string, text: string): void;

    /**
     * 向指定用户发送图片消息
     * @param to 接收者的 wxid 或群 ID
     * @param path 图片文件的绝对路径
     * @example
     * const result = http.download("https://example.com/image.jpg");
     * if (result.ok) {
     *   wechat.sendImage("wxid_abc123", result.path);
     * }
     */
    function sendImage(to: string, path: string): void;

    /**
     * 向指定用户发送文件消息
     * @param to 接收者的 wxid 或群 ID
     * @param path 文件的绝对路径
     * @param title 文件显示名称（可选）
     * @example
     * wechat.sendFile("wxid_abc123", "/sdcard/document.pdf", "重要文档.pdf");
     */
    function sendFile(to: string, path: string, title?: string): void;

    /**
     * 向指定用户发送语音消息
     * @param to 接收者的 wxid 或群 ID
     * @param path 语音文件的绝对路径（需为 AMR 格式）
     * @param durationMs 语音时长（毫秒）
     * @example
     * wechat.sendVoice("wxid_abc123", "/data/voice.amr", 3000);
     */
    function sendVoice(to: string, path: string, durationMs: number): void;

    /**
     * 向指定用户发送卡片消息
     * @param to 接收者的 wxid 或群 ID
     * @param content 消息内容
     * @example
     * wechat.sendAppMsg("wxid_abc123", "<msg>...</msg>");
     */
    function sendAppMsg(to: string, content: string): void;

    // --- 消息回复 API (自动回复至发送者) ---

    /**
     * 回复文本消息给当前发送者
     * @param text 消息文本内容
     * @example
     * function onMessage(talker, content, type, isSend) {
     *   if (content === "ping") {
     *     wechat.replyText("pong");
     *   }
     * }
     */
    function replyText(text: string): void;

    /**
     * 回复图片消息给当前发送者
     * @param path 图片文件的绝对路径
     */
    function replyImage(path: string): void;

    /**
     * 回复文件消息给当前发送者
     * @param path 文件的绝对路径
     * @param title 文件显示名称（可选）
     */
    function replyFile(path: string, title?: string): void;

    /**
     * 回复语音消息给当前发送者
     * @param path 语音文件的绝对路径（需为 AMR 格式）
     * @param durationMs 语音时长（毫秒）
     */
    function replyVoice(path: string, durationMs: number): void;

    /**
     * 回复卡片消息给当前发送者
     * @param content 消息内容
     * @example
     * wechat.replyAppMsg("<msg>...</msg>");
     */
    function replyAppMsg(content: string): void;

    // --- 其他 API ---

    /**
     * 异步发送 CGI 请求
     * @param uri 请求的 URI
     * @param cgiId 请求的 CGI ID
     * @param funcId 请求的 Func ID
     * @param routeId 请求的 Route ID
     * @param jsonPayload JSON 负载字符串
     * @param onSuccess 成功回调，接收 JSON 字符串
     * @param onFailure 失败回调，接收错误信息字符串
     * @example
     * wechat.sendCgi("/cgi-bin/mmbiz-bin/xxx", 123, 0, 0, '{"key":"value"}', function(json) {
     *   log.i("Success:", json);
     * }, function(err) {
     *   log.e("Failed:", err);
     * });
     */
    function sendCgi(uri: string, cgiId: number, funcId: number, routeId: number, jsonPayload: string, onSuccess?: (json: string) => void, onFailure?: (errMsg: string) => void): void;

    /**
     * 获取当前用户的微信 ID
     * @returns 当前用户的微信 ID 字符串
     */
    function getSelfWxId(): string;

    /**
     * 获取当前用户的微信号
     * @returns 当前用户的微信号字符串
     */
    function getSelfCustomWxId(): string;
}

// --- 异步任务 API ---

declare namespace task {
    /**
     * 在独立线程中执行一个函数，避免阻塞主脚本执行
     * @param fn 要执行的函数。将在新线程的独立 JS 上下文中运行
     * @example
     * task.run(function() {
     *   const resp = http.get("https://api.example.com/data");
     *   log.i("Got response:", resp.body);
     * });
     */
    function run(fn: () => void): void;
}

// --- 宿主信息 API ---

declare namespace hostinfo {
    /**
     * 当前宿主 (WeChat) 的 Application 对象，可作为 Context 使用
     * @example
     * const ctx = hostinfo.application;
     * android.widget.Toast.makeText(ctx, "Hello", 0).show();
     */
    const application: unknown;

    /**
     * 宿主包名，例如 "com.tencent.mm"
     */
    const packageName: string;

    /**
     * 宿主版本号 (versionCode)
     */
    const versionCode: number;

    /**
     * 宿主版本名 (versionName)
     */
    const versionName: string;
}

// --- Xposed Hook API ---

declare namespace xposed {
    /**
     * 在目标 Java 方法执行前插入钩子（通过 JavaMethod 对象匹配）
     * @param method 通过 reflect.findMethods/findFirstMethod 获取的 JavaMethod 对象
     * @param hookFunc 钩子回调函数，接收 (thisObj, args)
     *   若返回非 undefined 的值，将作为方法的返回值
     * @example
     * const m = reflect.findFirstMethod("com.example.Cls", function(name, pt, ret, mods) {
     *   return name === "targetMethod";
     * });
     * xposed.hookBefore(m, function(thisObj, args) {
     *   log.i("hooked via reflect!");
     * });
     */
    function hookBefore(method: JavaMethod, hookFunc: (thisObj: unknown, args: unknown[]) => unknown | void): HookHandle;
    
    /**
     * 在目标 Java 方法执行前插入钩子（通过类名与方法名匹配）
     * ⚠️ 警告：本方法*无法*区分方法重载，如有需要，请使用 reflect API
     * @param className 目标类的全限定名（例如 "com.tencent.mm.ui.LauncherUI"）
     * @param methodName 目标方法名
     * @param hookFunc 钩子回调函数，接收参数：
     *   - thisObj: 方法调用的 this 对象（静态方法为 null）
     *   - args: 方法参数数组
     *   若返回非 undefined 的值，将作为方法的返回值
     * @example
     * xposed.hookBefore("com.example.TargetClass", "targetMethod", function(thisObj, args) {
     *   log.i("方法调用，参数：", args);
     *   // 修改第一个参数
     *   args[0] = "modified";
     * });
     */
    function hookBefore(className: string, methodName: string, hookFunc: (thisObj: unknown, args: unknown[]) => unknown | void): HookHandle;

    /**
     * 在目标 Java 方法执行后插入钩子（通过 JavaMethod 对象匹配）
     * @param method 通过 reflect.findMethods/findFirstMethod 获取的 JavaMethod 对象
     * @param hookFunc 钩子回调函数，接收 (thisObj, args, originalResult)
     *   若返回非 undefined 的值，将作为方法的新返回值
     */
    function hookAfter(method: JavaMethod, hookFunc: (thisObj: unknown, args: unknown[], originalResult: unknown) => unknown | void): HookHandle;

    /**
     * 在目标 Java 方法执行后插入钩子（通过类名与方法名匹配）
     * ⚠️ 警告：本方法*无法*区分方法重载，如有需要，请使用 reflect API
     * @param className 目标类的全限定名（例如 "com.tencent.mm.ui.LauncherUI"）
     * @param methodName 目标方法名
     * @param hookFunc 钩子回调函数，接收参数：
     *   - thisObj: 方法调用的 this 对象（静态方法为 null）
     *   - args: 方法参数数组
     *   - originalResult: 方法的原始返回值
     *   若返回非 undefined 的值，将作为方法的新返回值
     * @example
     * xposed.hookAfter("com.example.TargetClass", "targetMethod", function(thisObj, args, result) {
     *   log.i("方法返回：", result);
     *   return result + "（已修改）";
     * });
     */
    function hookAfter(className: string, methodName: string, hookFunc: (thisObj: unknown, args: unknown[], originalResult: unknown) => unknown | void): HookHandle;
}

// --- 反射 API ---

interface JavaField {
    /** 字段名 */
    readonly name: string;
    /** 所属类的 JavaClass 对象 */
    readonly clazz: JavaClass;
    /** 字段类型的全限定名 */
    readonly type: JavaClass;
    /**
     * 修饰符数组
     * @example ["public", "static", "final"]
     */
    readonly modifiers: string[];
    /**
     * 获取字段的值
     * @param instance 实例对象（静态字段无需传入）
     * @returns 字段的值
     * @example
     * // 读取静态 int 字段
     * const f = reflect.findFirstField("com.example.Cls", false, (n, t, m) => n === "count" && t.name === "int");
     * const val = f.get();
     * log.i("count =", val);
     *
     * // 读取实例字段
     * const nameField = reflect.findFirstField("com.example.User", false, (n) => n === "name");
     * const userName = nameField.get(userInstance);
     */
    get(instance?: object): unknown;
    /**
     * 设置字段的值
     * @param instanceOrValue 如果是静态字段则为要设置的值；如果是实例字段则为实例对象
     * @param value 要设置的值（实例字段需要此参数）
     * @example
     * // 设置静态字段
     * reflect.findFirstField("com.example.Config", false, (n) => n === "debug")
     *   .set(true);
     *
     * // 设置实例字段：set(instance, newValue)
     * const balanceField = reflect.findFirstField("com.example.Account", false, (n) => n === "balance");
     * balanceField.set(accountObj, 100);
     */
    set(instanceOrValue: object, value?: object): void;
}

interface JavaConstructor {
    /**
     * 构造器名（JVM 名 `<init>`）
     */
    readonly name: string;
    /** 所属类的 JavaClass 对象 */
    readonly clazz: JavaClass;
    /** JVM 方法描述符 */
    readonly descriptor: string;
    /** 参数类型全限定名数组 */
    readonly paramTypes: JavaClass[];
    /** 返回值类型（即构造器所属类） */
    readonly returnType: JavaClass;
    /** 修饰符数组 */
    readonly modifiers: string[];
    /**
     * 调用该构造器创建新实例
     * @param args 构造器参数数组
     * @returns 新创建的实例
     * @example
     * // 创建有参实例
     * const ctor = reflect.findFirstConstructor("java.lang.StringBuilder", false,
     *   (name, pt) => pt.length === 1 && pt[0].name === "java.lang.String"
     * );
     * if (ctor) {
     *   const sb = ctor.invoke(["Hello"]);
     *   log.i("StringBuilder:", sb);
     * }
     */
    invoke(args: unknown[]): unknown;
}

interface JavaMethod {
    /** 方法名 */
    readonly name: string;
    /** 所属类的 JavaClass 对象 */
    readonly clazz: JavaClass;
    /** JVM 方法描述符，例如 "(Landroid/os/Bundle;)V" */
    readonly descriptor: string;
    /** 参数类型全限定名数组 */
    readonly paramTypes: JavaClass[];
    /** 返回值类型的全限定名 */
    readonly returnType: JavaClass;
    /** 修饰符数组 */
    readonly modifiers: string[];
    /**
     * 在方法执行前插入钩子
     * @param callback 钩子回调，接收 (thisObj, args)
     *   若返回非 undefined 的值，将作为方法的返回值
     * @remarks 此钩子绑定到精确重载（通过 reflect 查找时由 paramTypes 确定），
     *   不会被同名其他重载触发。
     * @example
     * // 钩住特定重载的 onCreate
     * const m = reflect.findFirstMethod("com.tencent.mm.ui.LauncherUI",
     *   (name, pt) => name === "onCreate" && pt.length === 1 && pt[0].name === "android.os.Bundle"
     * );
     * m.hookBefore((thisObj, args) => {
     *   log.i("LauncherUI.onCreate 被调用");
     *   // 修改第一个参数（Bundle）
     *   if (args[0]) args[0].putString("extra", "injected");
     * });
     */
    hookBefore(callback: (thisObj: unknown, args: unknown[]) => unknown | void): HookHandle;
    /**
     * 在方法执行后插入钩子
     * @param callback 钩子回调，接收 (thisObj, args, originalResult)
     *   若返回非 undefined 的值，将作为方法的新返回值
     * @remarks 此钩子绑定到精确重载，不会影响同名其他重载。
     * @example
     * // 修改方法返回值
     * const m = reflect.findFirstMethod("com.example.Calculator",
     *   (name, pt, ret) => name === "add" && pt.length === 2 && ret.name === "int"
     * );
     * m.hookAfter((thisObj, args, result) => {
     *   log.i("原始结果:", result);
     *   // 将结果翻倍
     *   return (result as number) * 2;
     * });
     */
    hookAfter(callback: (thisObj: unknown, args: unknown[], originalResult: unknown) => unknown | void): HookHandle;

    /**
     * 调用该 Java 方法
     * @param instance 实例对象（静态方法传 null 或不传）
     * @param args 方法参数数组
     * @returns MethodReturnValue，包含返回值与异常标识
     *   若调用成功，exception 为 false，value 为返回值（void 方法为 undefined）
     *   若调用抛出异常，exception 为 true，value 为异常对象
     * @example
     * // 调用静态方法
     * const m = reflect.findFirstMethod("com.example.Util", (n) => n === "getVersion");
     * const rv = m.invoke(null, []);
     * if (!rv.exception) log.i("版本:", rv.value);
     *
     * // 调用实例方法
     * const m2 = reflect.findFirstMethod("com.example.User", (n, pt) => n === "getName" && pt.length === 0);
     * const rv2 = m2.invoke(userInstance, []);
     * log.i("用户名:", rv2.value);
     */
    invoke(instance: object | null, args: unknown[]): MethodReturnValue;
}

interface JavaClass {
    /** 类的全限定名 */
    readonly name: string;
    /**
     * 创建该 Java 类的新实例
     * @param args 构造方法参数数组
     * @returns 新创建的实例
     * @example
     * // 创建无参实例
     * const clazz = reflect.toClass("com.example.User");
     * const user = clazz.createInstance([]);
     *
     * // 创建有参实例
     * const bufClazz = reflect.toClass("java.lang.StringBuilder");
     * const buf = bufClazz.createInstance(["Hello"]);
     */
    createInstance(args: unknown[]): unknown;

    /**
     * 获取该类的所有声明方法
     * @returns JavaMethod 数组
     * @example
     * const clazz = reflect.toClass("com.example.User");
     * const methods = clazz.getMethods();
     * for (let i = 0; i < methods.length; i++) {
     *   log.i(methods[i].name);
     * }
     */
    getMethods(): JavaMethod[];

    /**
     * 获取该类的所有声明字段
     * @returns JavaField 数组
     * @example
     * const clazz = reflect.toClass("com.example.User");
     * const fields = clazz.getFields();
     * for (let i = 0; i < fields.length; i++) {
     *   log.i(fields[i].name);
     * }
     */
    getFields(): JavaField[];
}

declare namespace reflect {
    /**
     * 查找类中所有符合条件的字段
     * @param className 目标类的全限定名
     * @param superclass 是否搜索继承的 public 字段（true 时使用 Class.getFields()，会包含父类的 public 字段）
     * @param condition 过滤条件，接收 (name, type, modifiers)
     * @returns 匹配的字段数组
     * @example
      * const intFields = reflect.findFields("com.tencent.mm.some.Class", false,
     *   (name, type, mods) => mods.includes("static") && type.name === "int"
     * );
     * for (let i = 0; i < intFields.length; i++) {
     *   log.i(intFields[i].name, "=", intFields[i].get());
     * }
     *
     * // 查找 String 类型的字段
     * const strFields = reflect.findFields("com.example.Config", false,
     *   (name, type) => type.name === "java.lang.String"
     * );
     * log.i("找到", strFields.length, "个 String 字段");
     *
     * // 结合 modifiers 过滤：查找 public 字段
     * const pubFields = reflect.findFields("com.example.Data", false,
     *   (name, type, mods) => mods.includes("public")
     * );
     * for (let i = 0; i < pubFields.length; i++) {
     *   log.i("公开字段:", pubFields[i].name, "=", pubFields[i].get());
     * }
     */
    function findFields(className: string, superclass: boolean, condition: (name: string, type: JavaClass, modifiers: string[]) => boolean): JavaField[];

    /**
     * 查找类中所有符合条件的方法
     * @param className 目标类的全限定名
     * @param superclass 是否搜索继承的 public 方法（true 时使用 Class.getMethods()，会包含父类的 public 方法）
     * @param condition 过滤条件，接收 (name, paramTypes, returnType, modifiers)
     * @returns 匹配的方法数组
     * @example
     * // 精确匹配带 Bundle 参数的 onCreate（避免匹配无参的 onCreate）
     * const onCreateMethods = reflect.findMethods("com.tencent.mm.ui.LauncherUI", false,
     *   (name, paramTypes) =>
     *     name === "onCreate" &&
     *     paramTypes.length === 1 &&
     *     paramTypes[0].name === "android.os.Bundle"
     * );
     * if (onCreateMethods.length > 0) {
     *   onCreateMethods[0].hookBefore((thisObj, args) => {
     *     log.i("LauncherUI.onCreate(Bundle) 被调用");
     *   });
     * }
     *
     * // 查找所有返回 boolean 的无参方法
     * const boolMethods = reflect.findMethods("com.example.Checker", false,
     *   (name, paramTypes, retType) =>
     *     paramTypes.length === 0 && retType.name === "boolean"
     * );
     * for (let i = 0; i < boolMethods.length; i++) {
     *   log.i("无参 boolean 方法:", boolMethods[i].name);
     * }
     *
     * // 使用 modifiers 筛选私有方法
     * const privateMethods = reflect.findMethods("com.example.Internal", false,
     *   (name, pt, ret, mods) => mods.includes("private")
     * );
     * log.i("私有方法数量:", privateMethods.length);
     *
     * // 批量钩住特定模式的方法
     * const setters = reflect.findMethods("com.example.User", false,
     *   (name, pt, ret) => name.startsWith("set") && pt.length === 1 && ret.name === "void"
     * );
     * for (let i = 0; i < setters.length; i++) {
     *   setters[i].hookBefore((thisObj, args) => {
     *     log.i("调用 setter:", setters[i].name, "值:", args[0]);
     *   });
     * }
     *
     * // 配合 xposed.hookBefore 使用 JavaMethod（等效）
     * const m = reflect.findFirstMethod("com.example.Service",
     *   (name, pt) => name === "handle" && pt.length === 2
     * );
     * xposed.hookBefore(m, (thisObj, args) => {
     * });
     */
    function findMethods(className: string, superclass: boolean, condition: (name: string, paramTypes: JavaClass[], returnType: JavaClass, modifiers: string[]) => boolean): JavaMethod[];

    /**
     * 获取 Java 类的封装对象
     * @param className 目标类的全限定名
     * @returns JavaClass 对象，失败返回 null
     * @example
     * // 创建无参实例
     * const clazz = reflect.toClass("com.example.User");
     * if (clazz) {
     *   const user = clazz.createInstance([]);
     *   log.i("创建成功:", user);
     * }
     *
     * // 创建有参实例
     * const sb = reflect.toClass("java.lang.StringBuilder");
     * if (sb) {
     *   const instance = sb.createInstance(["Hello"]);
     *   log.i("StringBuilder 实例:", instance);
     * }
     *
     * // 配合 reflect.*Method 使用
     * const clz = reflect.toClass("com.example.Config");
     * const cfg = clz.createInstance([]);
     * const m = reflect.findFirstMethod("com.example.Config", (n) => n === "setup");
     * if (m) m.invoke(cfg, []);
     */
    function toClass(className: string): JavaClass | null;

    /**
     * 查找类中第一个符合条件的字段
     * @param className 目标类的全限定名
     * @param superclass 是否搜索继承的 public 字段（true 时使用 Class.getFields()，会包含父类的 public 字段）
     * @param condition 过滤条件，接收 (name, type, modifiers)
     * @returns 匹配的 JavaField，未找到返回 null
     * @example
     * const countField = reflect.findFirstField("com.example.Config", false,
     *   (n, t, m) => n === "debug" && t.name === "boolean"
     * );
     * if (countField) {
     *   log.i("debug =", countField.get());
     * }
     */
    function findFirstField(className: string, superclass: boolean, condition: (name: string, type: JavaClass, modifiers: string[]) => boolean): JavaField | null;

    /**
     * 查找类中第一个符合条件的方法
     * @param className 目标类的全限定名
     * @param condition 过滤条件，接收 (name, paramTypes, returnType, modifiers)
     * @returns 匹配的 JavaMethod，未找到返回 null
     * @example
     * const m = reflect.findFirstMethod("com.example.Service",
     *   (name, pt) => name === "handle" && pt.length === 2
     * );
     * if (m) {
     *   m.hookBefore((thisObj, args) => {
     *     log.i("handle 方法被调用");
     *   });
     * }
     */
    function findFirstMethod(className: string, condition: (name: string, paramTypes: JavaClass[], returnType: JavaClass, modifiers: string[]) => boolean): JavaMethod | null;

    /**
     * 查找类中所有符合条件的构造器
     * @param className 目标类的全限定名
     * @param superclass 是否仅搜索 public 构造器（true 时使用 Class.getConstructors()，仅返回 public 构造器）
     * @param condition 过滤条件，接收 (name, paramTypes, returnType, modifiers)
     * @returns 匹配的 JavaConstructor 数组
     * @example
     * const ctors = reflect.findConstructors("com.example.User", false,
     *   (name, pt) => pt.length === 2
     * );
     * for (let i = 0; i < ctors.length; i++) {
     *   log.i("构造器参数:", ctors[i].paramTypes.map(t => t.name).join(", "));
     * }
     */
    function findConstructors(className: string, superclass: boolean, condition: (name: string, paramTypes: JavaClass[], returnType: JavaClass, modifiers: string[]) => boolean): JavaConstructor[];

    /**
     * 查找类中第一个符合条件的构造器
     * @param className 目标类的全限定名
     * @param superclass 是否仅搜索 public 构造器（true 时使用 Class.getConstructors()，仅返回 public 构造器）
     * @param condition 过滤条件，接收 (name, paramTypes, returnType, modifiers)
     * @returns 匹配的 JavaConstructor，未找到返回 null
     * @example
     * const ctor = reflect.findFirstConstructor("java.lang.StringBuilder", false,
     *   (name, pt) => pt.length === 1 && pt[0].name === "java.lang.String"
     * );
     * if (ctor) {
     *   const sb = ctor.invoke(["Hello"]);
     * }
     */
    function findFirstConstructor(className: string, superclass: boolean, condition: (name: string, paramTypes: JavaClass[], returnType: JavaClass, modifiers: string[]) => boolean): JavaConstructor | null;
}

// --- DexKit API (基于字节码的搜索) ---
interface MethodSearcher {
    /** 搜索的包名前缀，例如 ["com.tencent.mm.storage"] */
    searchPackages?: string[];
    /** 精确字符串匹配，方法体内需要包含这些字符串 */
    usingEqStrings?: string[];
    /** 数值匹配，方法体内需要包含这些数值常量 */
    usingNumbers?: number[];
    /** 声明方法的类全限定名 */
    declaringClass?: string;
    /** 方法名 */
    name?: string;
    /** 参数类型全限定名数组 */
    paramTypes?: (string | JavaClass)[];
    /** 返回值类型全限定名 */
    returnType?: string | JavaClass;
    /** 参数数量 */
    paramCount?: number;
}

interface ClassSearcher {
    /** 搜索的包名前缀 */
    searchPackages?: string[];
    /** 精确字符串匹配 */
    usingEqStrings?: string[];
    /** 类名（支持通配符） */
    name?: string;
    /** 基类全限定名 */
    superclass?: string | JavaClass;
    /** 实现的接口全限定名列表 */
    interfaces?: (string | JavaClass)[];
}

interface MethodResult {
    /** 匹配到的方法数组 */
    methods: JavaMethod[];
    /** 如果仅匹配到一个方法则返回之，否则返回 null */
    single(): JavaMethod | null;
}

interface ClassResult {
    /** 匹配到的 JavaClass 对象数组 */
    classes: JavaClass[];
    /** 如果仅匹配到一个类则返回其 JavaClass 对象，否则返回 null */
    single(): JavaClass | null;
}

declare namespace dexkit {
    /**
     * 使用 DexKit 基于字节码特征查找方法（支持方法重载）
     * @param searcher 搜索条件对象
     * @returns 方法搜索结果
     * @example
     * // --- 按字符串特征查找 ---
     * // 查找方法体内包含特定日志字符串的方法
     * const logMethods = dexkit.findMethod({
     *   searchPackages: ["com.tencent.mm.model"],
     *   usingEqStrings: ["MicroMsg.MMCore", "initialize"]
     * });
     * if (logMethods.methods.length > 0) {
     *   log.i("找到", logMethods.methods.length, "个匹配的方法");
     *   logMethods.methods[0].hookBefore((thisObj, args) => {
     *     log.i("方法被调用:", logMethods.methods[0].name);
     *   });
     * }
     *
     * // --- 按方法和参数名查找 ---
     * const namedMethods = dexkit.findMethod({
     *   name: "onCreate",
     *   paramCount: 1
     * });
     * const single = namedMethods.single();
     * if (single) {
     *   single.hookBefore((thisObj, args) => {
     *     log.i("精确匹配到的 onCreate");
     *   });
     * } else {
     *   log.i("找到", namedMethods.methods.length, "个重载");
     * }
     *
     * // --- 综合多条件过滤 ---
     * const filtered = dexkit.findMethod({
     *   searchPackages: ["com.tencent.mm.storage"],
     *   declaringClass: "com.tencent.mm.storage.MsgInfoStorage",
     *   usingEqStrings: ["MicroMsg.MsgInfoStorage", "insert"],
     *   paramTypes: ["com.tencent.mm.storage.MsgInfo"],
     *   returnType: "long"
     * });
     * if (filtered.methods.length > 0) {
     *   log.i("精确匹配:", filtered.methods[0].name);
     *   log.i("描述符:", filtered.methods[0].descriptor);
     *   filtered.methods[0].hookAfter((thisObj, args, result) => {
     *     log.i("insert 返回:", result);
     *   });
     * }
     *
     * // --- 按数值常量查找 ---
     * const numMethods = dexkit.findMethod({
     *   searchPackages: ["com.tencent.mm.plugin"],
     *   usingNumbers: [60000, 1],
     *   paramCount: 3
     * });
     * log.i("包含数值 60000 的方法数:", numMethods.methods.length);
     */
    function findMethod(searcher: MethodSearcher): MethodResult;

    /**
     * 使用 DexKit 基于字节码特征查找类
     * @param searcher 搜索条件对象
     * @returns 类搜索结果
     * @example
     * // --- 按字符串特征查找类 ---
     * const classesResult = dexkit.findClass({
     *   searchPackages: ["com.tencent.mm.storage"],
     *   usingEqStrings: ["MicroMsg.MsgInfo", "insert db error"]
     * });
     * if (classesResult.classes.length > 0) {
     *   log.i("找到类:", classesResult.classes.map(c => c.name).join(", "));
     *   const singleOne = classesResult.single();
     *   if (singleOne) {
     *     log.i("唯一匹配:", singleOne.name);
     *     // 配合 reflect API 继续查找
     *     const methods = reflect.findMethods(singleOne.name, false, (name) => name === "insert");
     *     log.i("该类有", methods.length, "个 insert 方法");
     *   }
     * }
     *
     * // --- 按类名搜索（支持通配符） ---
     * const wildcard = dexkit.findClass({
     *   name: "*Config*"
     * });
     * log.i("匹配 Config 的类:", wildcard.classes.map(c => c.name));
     *
     * // --- 按基类搜索 ---
     * const subClasses = dexkit.findClass({
     *   searchPackages: ["com.tencent.mm.plugin"],
     *   superclass: "com.tencent.mm.plugin.BasePlugin"
     * });
     * log.i("BasePlugin 的子类:", subClasses.classes.map(c => c.name));
     *
     * // --- 按实现的接口搜索 ---
     * const implClasses = dexkit.findClass({
     *   searchPackages: ["com.tencent.mm.model"],
     *   interfaces: ["com.tencent.mm.model.IOnAccountInitialized"]
     * });
     * log.i("实现了接口的类:", implClasses.classes.map(c => c.name));
     *
     * // --- 综合条件查找 ---
     * const strict = dexkit.findClass({
     *   searchPackages: ["com.tencent.mm.storage"],
     *   usingEqStrings: ["MicroMsg.MsgInfoStorage"],
     *   interfaces: ["com.tencent.mm.storage.IMsgInfoProvider"]
     * });
     * if (strict.classes.length === 1) {
     *   log.i("唯一匹配:", strict.single().name);
     * }
     */
    function findClass(searcher: ClassSearcher): ClassResult;
}

// --- 入口点函数定义 ---

/**
 * onMessage 钩子可以返回的消息对象结构
 */
interface MessageResponse {
    /** 消息类型
     * @default "text"
     */
    type?: "text" | "image" | "file" | "voice";
    /** 文本消息的内容 (仅当 type 为 "text" 时有效) */
    content?: string;
    /** 文件、图片或语音的绝对路径 (仅当 type 为 "image"/"file"/"voice" 时有效) */
    path?: string;
    /** 文件标题/显示名称 (可选，仅用于 "file") */
    title?: string;
    /** 语音时长（毫秒，仅用于 "voice"） */
    duration?: number;
}

/**
 * 加载钩子 - 当全部脚本加载完成后触发
 */
declare function onLoad(): void;

/**
 * 消息钩子 - 当收到消息时触发
 * 
 * @param talker 发送者的 ID (wxid)，如果是群消息则为群 ID
 * @param content 消息内容。注意：群消息会包含 "wxid_xxx:\n" 前缀，建议使用 getCleanContent() 处理
 * @param type 消息类型
 *   - 1: 文本消息
 *   - 3: 图片消息
 *   - 43: 视频消息
 *   - 49: 分享/链接消息
 *   - 10000: 系统消息
 * @param isSend 是否为自己发出的消息 (0=接收, 1=发送)
 * @returns 可以返回以下任意类型：
 *   - string: 直接发送文本消息
 *   - MessageResponse: 发送复杂消息（图片、文件、语音）
 *   - null/undefined: 不回复
 * 
 * @example
 * // 简单文本回复
 * function onMessage(talker, content, type, isSend) {
 *   const cleanContent = getCleanContent(content);
 *   if (cleanContent === "ping") {
 *     return "pong";
 *   }
 *   return null;
 * }
 * 
 * @example
 * // 使用 API 和返回值混合
 * function onMessage(talker, content, type, isSend) {
 *   if (content.includes("天气")) {
 *     const resp = http.get("https://api.weather.com/...");
 *     if (resp.ok) {
 *       return "今日天气: " + resp.json.temp + "°C";
 *     }
 *   }
 *   return null;
 * }
 * 
 * @example
 * // 返回复杂消息
 * function onMessage(talker, content, type, isSend) {
 *   if (content === "发图") {
 *     return {
 *       type: "image",
 *       path: "/sdcard/picture.jpg"
 *     };
 *   }
 *   return null;
 * }
 */
declare function onMessage(
    talker: string, 
    content: string, 
    type: number, 
    isSend: number
): string | MessageResponse | null | void;

/**
 * 请求钩子 - 拦截并修改微信发出的网络请求
 * 
 * @param uri 请求的目标 URI
 * @param cgiId 请求的 CGI ID
 * @param json 请求数据体对象。可以直接修改它的属性
 * @returns 必须返回修改后的对象，否则修改不会生效
 * 
 * @warning 此函数会阻塞请求发送，避免进行耗时操作
 * @warning API 可能在后续版本中改变以支持修改 uri 和 cgiId
 * 
 * @example
 * function onRequest(uri, cgiId, json) {
 *   log.i("Intercepting request to:", uri);
 *   
 *   if (uri.includes("/upload")) {
 *     json.customField = "injected value";
 *   }
 *   
 *   return json;
 * }
 */
declare function onRequest(uri: string, cgiId: number, json: Record<string, unknown>): Record<string, unknown>;

/**
 * 响应钩子 - 拦截并修改微信收到的网络响应
 * 
 * @param uri 请求的目标 URI
 * @param cgiId 请求的 CGI ID
 * @param json 响应数据体对象。可以直接修改它的属性
 * @returns 必须返回修改后的对象，否则修改不会生效
 * 
 * @warning 此函数会阻塞响应处理，避免进行耗时操作
 * @warning API 可能在后续版本中改变以支持修改 uri 和 cgiId
 * 
 * @example
 * function onResponse(uri, cgiId, json) {
 *   log.i("Intercepting response from:", uri);
 *   
 *   if (uri.includes("/getUserInfo")) {
 *     json.vipLevel = 10;
 *   }
 *   
 *   http.post("https://analytics.example.com/log", null, {
 *     uri: uri,
 *     timestamp: datetime.getCurrentUnixEpoch()
 *   });
 *   
 *   return json;
 * }
 */
declare function onResponse(uri: string, cgiId: number, json: Record<string, unknown>): Record<string, unknown>;

// --- 常用辅助函数（建议在脚本中定义） ---

/**
 * 推荐在脚本开头定义这些辅助函数：
 * 
 * @example
 * function getCleanContent(content) {
 *   var match = content.match(/^wxid_[^:]+:\n(.*)$/s);
 *   return match ? match[1] : content;
 * }
 * 
 * function getSenderWxid(content) {
 *   var match = content.match(/^(wxid_[^:]+):\n/);
 *   return match ? match[1] : null;
 * }
 * 
 * function isGroupMessage(content) {
 *   return /^wxid_[^:]+:\n/.test(content);
 * }
 */
