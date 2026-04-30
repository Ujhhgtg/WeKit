/// <reference path="./globals.d.ts" />

function getCleanContent(content) {
    // Remove "wxid_xxx:\n" prefix in group chats
    var match = content.match(/^wxid_[^:]+:\n(.*)$/s);
    if (match) {
        return match[1];
    }
    return content;
}

function commandWeather(content) {
    log.i("fetching weather...");

    var cityName = content.substring(8).trim();

    // Default to Shanghai if no city specified
    if (cityName === "") {
        cityName = "上海";
    }

    log.i("querying weather for:", cityName);

    // City code mapping (you can expand this)
    var cityCodeMap = {
        北京: "101010100",
        上海: "101020100",
        广州: "101280101",
        深圳: "101280601",
        杭州: "101210101",
        成都: "101270101",
        武汉: "101200101",
        西安: "101110101",
        重庆: "101040100",
        天津: "101030100",
        南京: "101190101",
        苏州: "101190401",
        郑州: "101180101",
        长沙: "101250101",
        沈阳: "101070101",
        青岛: "101120201",
        厦门: "101230201",
        大连: "101070201",
        济南: "101120101",
        哈尔滨: "101050101"
    };

    var cityCode = cityCodeMap[cityName];

    if (!cityCode) {
        log.w("city not found in map:", cityName);
        return (
            "暂不支持查询该城市天气.\n支持的城市:" +
            Object.keys(cityCodeMap).join(", ")
        );
    }

    // Make request to Xiaomi Weather API
    var response = http.get(
        "https://weatherapi.market.xiaomi.com/wtr-v3/weather/all",
        {
            latitude: "0",
            longitude: "0",
            locationKey: "weathercn:" + cityCode,
            sign: "zUFJoAR2ZVrDy1vF3D07",
            isGlobal: "false",
            locale: "zh_cn",
            days: "1",
            appKey: "weather20151024"
        }
    );

    log.i("api response status:", response.status);

    if (!response.ok) {
        log.e("weather api request failed");
        log.e("status:", response.status);
        log.e("error:", response.error);
        return "天气查询失败，请稍后重试";
    }

    if (!response.json) {
        log.e("response is not json");
        log.e("body:", response.body);
        return "天气数据解析失败";
    }

    var data = response.json;
    log.d("full response:", JSON.stringify(data));

    // Check if current weather data exists
    if (!data.current) {
        log.e("no current weather data in response");
        return "未获取到天气数据";
    }

    var current = data.current;

    // Weather code to description mapping
    var weatherMap = {
        0: "晴",
        1: "多云",
        2: "阴",
        3: "阵雨",
        4: "雷阵雨",
        5: "雷阵雨伴有冰雹",
        6: "雨夹雪",
        7: "小雨",
        8: "中雨",
        9: "大雨",
        10: "暴雨",
        11: "大暴雨",
        12: "特大暴雨",
        13: "阵雪",
        14: "小雪",
        15: "中雪",
        16: "大雪",
        17: "暴雪",
        18: "雾",
        19: "冻雨",
        20: "沙尘暴",
        21: "小到中雨",
        22: "中到大雨",
        23: "大到暴雨",
        24: "暴雨到大暴雨",
        25: "大暴雨到特大暴雨",
        26: "小到中雪",
        27: "中到大雪",
        28: "大到暴雪",
        29: "浮尘",
        30: "扬沙",
        31: "强沙尘暴",
        32: "霾",
        53: "霾"
    };

    var weatherDesc = weatherMap[current.weather] || "未知";
    var temperature = current.temperature.value + current.temperature.unit;
    var feelsLike = current.feelsLike.value + current.feelsLike.unit;
    var humidity = current.humidity.value + current.humidity.unit;
    var pressure = current.pressure.value + current.pressure.unit;
    var windSpeed = current.wind.speed.value + current.wind.speed.unit;
    var windDir = current.wind.direction.value + current.wind.direction.unit;
    var uvIndex = current.uvIndex;

    log.i("weather parsed successfully for", cityName);

    var message =
        "📍 " +
        cityName +
        " 天气\n" +
        "━━━━━━━━━━━━\n" +
        "🌡️ 温度：" +
        temperature +
        "\n" +
        "🤚 体感：" +
        feelsLike +
        "\n" +
        "☁️ 天气：" +
        weatherDesc +
        "\n" +
        "💧 湿度：" +
        humidity +
        "\n" +
        "🎐 气压：" +
        pressure +
        "\n" +
        "💨 风速：" +
        windSpeed +
        "\n" +
        "🧭 风向：" +
        windDir +
        "\n" +
        "☀️ 紫外线：" +
        uvIndex +
        "\n" +
        "━━━━━━━━━━━━\n" +
        "⏰ 更新时间：" +
        current.pubTime;

    return message;
}

function commandRandomPic(content) {
    var sourceName = content.substring(11).trim().toLowerCase() || "wallhaven";
    log.i("fetching random picture from source: ", sourceName);

    const wallhavenPageIndexKey = "wallhaven_api_search_page_index";
    const wallhavenImageIndexKey = "wallhaven_api_search_image_index";
    var wallhavenPageIndex = storage.getOrDefault(wallhavenPageIndexKey, 1);
    var wallhavenImageIndex = storage.getOrDefault(wallhavenImageIndexKey, 1);

    const zerochanImageIndexKey = "zerochan_api_search_image_index";
    var zerochanImageIndex = storage.getOrDefault(zerochanImageIndexKey, 1);

    var sources = {
        alcy: {
            // no-parser api: use url as imageUrl directly
            url: "https://t.alcy.cc/ycy/?time=1999999999999"
        },
        "waifu.im": {
            url: "https://api.waifu.im/images?PageSize=1&Page=1&IncludedTags=waifu",
            parser: function (resp) {
                return resp.json.items[0].url;
            }
        },
        // TODO: deterministic results, add rotation like wallhaven
        "yande.re": {
            url: "https://yande.re/post.json?api_version=2&limit=1",
            parser: function (resp) {
                return resp.json.posts[0].file_url;
            }
        },
        // TODO: cloudflare
        konachan: {
            url: "https://konachan.com/post.json?limit=1",
            parser: function (resp) {
                return resp.json[0].file_url;
            }
        },
        zerochan: {
            url:
                "https://www.zerochan.net/Genshin+Impact?json&s=fav&t=0&l=1&p=" +
                zerochanImageIndex,
            parser: function (resp) {
                zerochanImageIndex += 1;
                storage.set(zerochanImageIndexKey, zerochanImageIndex);

                var id = resp.json.items[0].id;
                var req_url = "https://www.zerochan.net/" + id + "?json";
                var resp = http.get(req_url);
                if (!resp.ok) {
                    log.e("failed to send stage 2 request for zerochan");
                    return "";
                }
                if (!resp.json) {
                    log.e("failed to parse stage 2 json for zerochan");
                    return "";
                }
                return resp.json.full;
            }
        },
        danbooru: {
            url: "https://danbooru.donmai.us/posts.json?limit=1&tags=random%3A1",
            parser: function (resp) {
                return resp.json[0].file_url;
            }
        },
        wallhaven: {
            url:
                "https://wallhaven.cc/api/v1/search?q=%23genshin%20impact&categories=010&purity=100&sorting=favorites&order=desc&page=" +
                wallhavenPageIndex,
            parser: function (resp) {
                var images = resp.json.data;
                var url = images[wallhavenImageIndex].path;
                wallhavenImageIndex += 1;
                if (wallhavenImageIndex > resp.json.meta.per_page) {
                    wallhavenPageIndex += 1;
                    wallhavenImageIndex = 1;
                }
                storage.set(wallhavenPageIndexKey, wallhavenPageIndex);
                storage.set(wallhavenImageIndexKey, wallhavenImageIndex);
                return url;
            }
        }
    };

    var config = sources[sourceName];

    if (!config) {
        wechat.replyText(
            "暂不支持来源: " + sourceName + "\n输入 /help random-pic 查看可选项"
        );
    }

    if (config.parser) {
        var response = http.get(config.url);

        if (!response.ok) {
            log.e(sourceName, "api request failed: ", response.status);
            wechat.replyText("图片获取失败");
        }

        var imageUrl = config.parser(response);
    } else {
        var imageUrl = config.url;
    }

    log.d("extracted image url: ", imageUrl);

    var result = http.download(imageUrl);
    if (!result.ok) {
        log.e("failed to download: ", imageUrl);
        wechat.replyText("图片下载失败");
    }

    wechat.replyImage(result.path);
}

function commandHitokoto() {
    log.i("fetching sentence from hitokoto v1 api...");
    var response = http.get("https://v1.hitokoto.cn/");

    if (!response.ok) {
        log.e("hitokoto api request failed");
        log.e("status:", response.status);
        log.e("error:", response.error);
        wechat.replyText("一言获取失败");
    }

    if (!response.json) {
        log.e("response is not json");
        log.e("body:", response.body);
        return "一言数据解析失败";
    }

    var data = response.json;
    log.d("full response:", JSON.stringify(data));

    if (data.from_who) {
        var message =
            "『" +
            data.hitokoto +
            "』\n" +
            "             —— " +
            data.from_who +
            "「" +
            data.from +
            "」";
    } else {
        var message =
            "『" +
            data.hitokoto +
            "』\n" +
            "             —— " +
            "「" +
            data.from +
            "」";
    }

    return message;
}

function commandDebugMsg(talker) {
    var key = talker + "_debug_msg_enabled";
    if (!storage.hasKey(key)) {
        storage.set(key, true);
        return "已启用消息调试模式, 将会输出下一条消息的原始对象";
    } else {
        var val = storage.get(key);
        storage.set(key, !val);
        if (val) {
            return "已禁用消息调试模式";
        } else {
            return "已启用消息调试模式, 将会输出下一条消息的原始对象";
        }
    }
}

function commandHelp(content) {
    var cmdName = content.substring(5).trim();

    if (cmdName === "help") {
        return (
            "命令: /help\n" +
            "功能: 输出命令帮助\n" +
            "用法: /help <命令>\n" +
            "参数:\n" +
            "1. 命令: 可选, 若不指定此参数则输出全部可用命令列表"
        );
    }

    if (cmdName === "changelog") {
        return (
            "命令: /changelog\n" +
            "功能: 输出更新内容\n" +
            "用法: /changelog\n" +
            "参数:\n" +
            "无"
        );
    }

    if (cmdName === "weather") {
        return (
            "命令: /weather\n" +
            "功能: 输出城市当前天气\n" +
            "用法: /weather <城市>\n" +
            "参数:\n" +
            "1. 城市: 可选, 默认为'上海'"
        );
    }

    if (cmdName === "random-pic") {
        return (
            "命令: /random-pic\n" +
            "功能: 获取随机二次元图片\n" +
            "用法: /random-pic <来源> <标签>\n" +
            "参数:\n" +
            "1. 来源: 可选, 默认为 'wallhaven', 可选项: alcy, yande.re, konachan, zerochan, danbooru, waifu.im, wallhaven\n" +
            "2. 标签: 可选 (功能还没写)"
        );
    }

    if (cmdName === "hitokoto") {
        return (
            "命令: /hitokoto\n" +
            "功能: 输出「一言」\n" +
            "用法: /hitokoto\n" +
            "参数:\n" +
            "无"
        );
    }

    if (cmdName === "debug-msg") {
        return (
            "命令: /debug-msg\n" +
            "功能: 为当前聊天启用或禁用消息调试模式, 启用该模式将输出下一条消息的原始对象\n" +
            "用法: /debug-msg\n" +
            "参数:\n" +
            "无"
        );
    }

    return (
        "可用命令 (可使用 /help <命令> 查看详细帮助):\n" +
        "/help\n" +
        "/changelog\n" +
        "/weather\n" +
        "/random-pic\n" +
        "/hitokoto\n" +
        "/debug-msg"
    );
}

function commmandChangelog() {
    return (
        "更新内容:\n" +
        "2026.02.17 - 模块添加 '自动回复' 功能\n" +
        "2026.02.18 - 功能重构为 '自动化', 与原 '脚本管理' 合并, 移除了除 JavaScript 以外的消息匹配方式\n" +
        "             添加命令 help, changelog, weather, random-pic, hitokoto, debug-msg\n" +
        "2026.02.22 - 将微信相关 API 移动至命名空间 wechat 下"
    );
}

function onLoad() {
    log.i("onLoad() triggered")
}

function onMessage(talker, content, type, isSend) {
    log.i("onMessage() triggered");

    content = getCleanContent(content);

    if (content.startsWith("/debug-msg")) {
        return commandDebugMsg(talker);
    }

    var debugMsgKey = talker + "_debug_msg_enabled";
    if (
        !content.startsWith("已启用消息调试:") && // do not debug itself
        storage.getOrDefault(debugMsgKey, false)
    ) {
        storage.set(debugMsgKey, false);

        var message =
            "消息调试:\n" +
            "talker=" +
            talker +
            "\n" +
            "content=" +
            content +
            "\n" +
            "type=" +
            type +
            "\n" +
            "isSend=" +
            isSend +
            "\n";

        return message;
    }

    if (content.startsWith("/help")) {
        return commandHelp(content);
    }

    if (content.startsWith("/changelog")) {
        return commmandChangelog();
    }

    if (content.startsWith("/weather")) {
        return commandWeather(content);
    }

    if (content.startsWith("/random-pic")) {
        commandRandomPic(content);
        return null;
    }

    if (content.startsWith("/hitokoto")) {
        return commandHitokoto();
    }

    if (
        content.startsWith("/time") ||
        content.startsWith("/kill") ||
        content.startsWith("/op") ||
        content.startsWith("/deop") ||
        content.startsWith("/ban") ||
        content.startsWith("/pardon") ||
        content.startsWith("/time")
    ) {
        return "bro这不是mc你发mc指令干甚么[骷髅]";
    }

    if (content.startsWith("/")) {
        return "暂不支持该命令，请等待开发者实现喵";
    }

    return null;
}
