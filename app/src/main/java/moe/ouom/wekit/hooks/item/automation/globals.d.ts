/**
 * 脚本全局 API 定义
 */

interface HttpResponse {
    /** 请求是否成功 (status < 400) */
    ok: boolean;
    /** HTTP 状态码 */
    status: number;
    /** 响应体字符串 */
    body: string;
    /** 如果响应体是 JSON，则为自动解析好的对象；否则为 null */
    json: any | null;
    /** 错误信息（如果有） */
    error?: string;
}

interface DownloadResult {
    /** 下载是否成功 */
    ok: boolean;
    /** 文件保存的绝对路径 */
    path: string;
}

declare namespace log {
    /**
     * 输出调试级别日志
     * @param message 要记录的消息，支持多个参数
     */
    function d(...message: any[]): void;
    
    /**
     * 输出信息级别日志
     * @param message 要记录的消息，支持多个参数
     */
    function i(...message: any[]): void;
    
    /**
     * 输出警告级别日志
     * @param message 要记录的消息，支持多个参数
     */
    function w(...message: any[]): void;
    
    /**
     * 输出错误级别日志
     * @param message 要记录的消息，支持多个参数
     */
    function e(...message: any[]): void;
}

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
     *   log.i("User:", resp.json.name);
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
    function post(url: string, formData?: Record<string, string | number>, jsonBody?: any, headers?: Record<string, string>): HttpResponse;
    
    /**
     * 下载文件到宿主缓存目录
     * @param url 文件的 URL
     * @returns 下载结果，包含文件路径
     * @example
     * const result = http.download("https://example.com/image.jpg");
     * if (result.ok) {
     *   replyImage(result.path);
     * }
     */
    function download(url: string): DownloadResult;
}

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
    function get(key: string): any;
    
    /**
     * 获取值，若不存在则返回提供的默认值 def。
     * @param key 键名
     * @param def 默认值
     * @returns 存储的值或默认值
     * @example
     * const count = storage.getOrDefault("counter", 0);
     * storage.set("counter", count + 1);
     */
    function getOrDefault(key: string, def: any): any;
    
    /**
     * 存入键值对。若 value 为 undefined 则移除该键。
     * @param key 键名
     * @param value 要存储的值
     * @example
     * storage.set("lastUser", "wxid_abc123");
     * storage.set("settings", { enabled: true, threshold: 10 });
     */
    function set(key: string, value: any): void;
    
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
     * @example
     * storage.remove("tempData");
     */
    function remove(key: string): void;
    
    /**
     * 取出并立即移除该键。若不存在返回 undefined。
     * @param key 键名
     * @returns 该键的值
     * @example
     * const tempValue = storage.pop("oneTimeToken");
     */
    function pop(key: string): any;
    
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

declare namespace time {
    /**
     * 休眠指定的秒数
     * @param seconds 休眠的秒数
     * @example
     * log.i("开始等待...");
     * time.sleepS(3);
     * log.i("等待结束");
     */
    function sleepS(seconds: number): void;
    
    /**
     * 休眠指定的毫秒数
     * @param milliseconds 休眠的毫秒数
     * @example
     * log.i("短暂延迟...");
     * time.sleepMs(500);
     * log.i("延迟结束");
     */
    function sleepMs(milliseconds: number): void;
    
    /**
     * 获取当前 Unix 时间戳（秒）
     * @returns 当前时间的 Unix 时间戳
     * @example
     * const now = time.getCurrentUnixEpoch();
     * log.i("当前 Unix 时间戳:", now);
     */
    function getCurrentUnixEpoch(): number;
}

// --- 消息发送 API (指定接收人) ---

/**
 * 向指定用户发送文本消息
 * @param to 接收者的 wxid 或群 ID
 * @param text 消息文本内容
 * @example
 * sendText("wxid_abc123", "你好！");
 */
declare function sendText(to: string, text: string): void;

/**
 * 向指定用户发送图片消息
 * @param to 接收者的 wxid 或群 ID
 * @param path 图片文件的绝对路径
 * @example
 * const result = http.download("https://example.com/image.jpg");
 * if (result.ok) {
 *   sendImage("wxid_abc123", result.path);
 * }
 */
declare function sendImage(to: string, path: string): void;

/**
 * 向指定用户发送文件消息
 * @param to 接收者的 wxid 或群 ID
 * @param path 文件的绝对路径
 * @param title 文件显示名称（可选）
 * @example
 * sendFile("wxid_abc123", "/sdcard/document.pdf", "重要文档.pdf");
 */
declare function sendFile(to: string, path: string, title?: string): void;

/**
 * 向指定用户发送语音消息
 * @param to 接收者的 wxid 或群 ID
 * @param path 语音文件的绝对路径（需为 AMR 格式）
 * @param durationMs 语音时长（毫秒）
 * @example
 * sendVoice("wxid_abc123", "/data/voice.amr", 3000);
 */
declare function sendVoice(to: string, path: string, durationMs: number): void;

// --- 消息回复 API (自动回复至发送者) ---

/**
 * 回复文本消息给当前发送者
 * @param text 消息文本内容
 * @example
 * function onMessage(talker, content, type, isSend) {
 *   if (content === "ping") {
 *     replyText("pong");
 *   }
 * }
 */
declare function replyText(text: string): void;

/**
 * 回复图片消息给当前发送者
 * @param path 图片文件的绝对路径
 */
declare function replyImage(path: string): void;

/**
 * 回复文件消息给当前发送者
 * @param path 文件的绝对路径
 * @param title 文件显示名称（可选）
 */
declare function replyFile(path: string, title?: string): void;

/**
 * 回复语音消息给当前发送者
 * @param path 语音文件的绝对路径（需为 AMR 格式）
 * @param durationMs 语音时长（毫秒）
 */
declare function replyVoice(path: string, durationMs: number): void;

// --- 钩子函数定义 ---

/**
 * onMessage 钩子可以返回的消息对象结构
 */
interface MessageResponse {
    /** * 消息类型 
     * @default "text"
     */
    type?: "text" | "image" | "file" | "voice";
    /** 文本消息的内容 (仅当 type 为 "text" 时有效) */
    content?: string;
    /** 文件、图片或语音的绝对路径 (仅当 type 为 "image"/"file"/"voice" 时有效) */
    path?: string;
    /** 文件标题/显示名称 (可选，仅用于 "file") */
    title?: string;
    /** 语音时长（毫秒，仅用于 "voice") */
    duration?: number;
}

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
 *     // 修改请求数据
 *     json.customField = "injected value";
 *   }
 *   
 *   return json;  // 必须返回
 * }
 */
declare function onRequest(uri: string, cgiId: number, json: any): any;

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
 *     // 修改响应数据
 *     json.vipLevel = 10;
 *   }
 *   
 *   // 记录到外部服务
 *   http.post("https://analytics.example.com/log", null, {
 *     uri: uri,
 *     timestamp: time.getCurrentUnixEpoch()
 *   });
 *   
 *   return json;  // 必须返回
 * }
 */
declare function onResponse(uri: string, cgiId: number, json: any): any;

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