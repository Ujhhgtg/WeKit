# 脚本 API 文档

## 钩子函数

### onRequest

当请求即将发出时触发此函数。

```javascript
function onRequest(uri, cgiId, json) {
    json.someField = "someValue";
    return json;
}
```

### onResponse

当请求收到响应时触发此函数。

```javascript
同上
```

### 数据对象

| 字段名   | 类型     | 描述                  |
|-------|--------|---------------------|
| uri   | string | 请求的目标 URI 地址        |
| cgiId | string | 请求的 CGI ID，用于识别请求类型 |
| json  | object | 请求或响应的数据体（JSON 格式）  |

## 全局对象

### log

#### 使用方法

```javascript
log.d(message);
log.i(message);
log.w(message);
log.e(message);
```

### http

#### 使用方法

```javascript
var response = http.get(url, params, headers)
log.i(response.ok)
log.i(response.status)
log.i(response.body)
log.i(response.json)
```

```javascript
var response = http.post(url, formData, jsonBody, headers)
log.i(response.ok)
log.i(response.status)
log.i(response.body)
log.i(response.json)
```

```javascript
var result = http.download(url)
log.i(result.ok)
// path is located under WeChat's cache dir, guaranteed to be accessible by it
log.i(result.path)
```

### 后续更新计划

我们可能在稳定版本中提供更加完善和一致的日志功能接口，届时会提供更详细的文档和更稳定的 API 设计。