/// <reference path="./globals.d.ts" />

/**
 * anti_message_recall3.js — replicates AntiMessageRecall3 behavior using JS scripting API.
 *
 * Hooks the WeChat SDK XmlParser to detect message revocations and blocks them.
 * Since the JS scripting API cannot invoke We*Api (WeMessageApi, WeDatabaseApi),
 * this script shows a log with the recall information instead of inserting
 * a system message into the chat database.
 *
 * Original: app/src/main/java/dev/ujhhgtg/wekit/hooks/items/chat/AntiMessageRecall3.kt
 */

var NAME_REGEX = /["「](.*?)["」]/;

function onLoad() {
  log.i("AntiMessageRecall3.js: searching for XmlParser method via DexKit...");

  var results = dexkit.findMethods({
    searchPackages: ["com.tencent.mm.sdk.platformtools"],
    usingEqStrings: ["MicroMsg.SDK.XmlParser", "[ %s ]"],
  });

  if (results.methods.length === 0) {
    log.e(
      "AntiMessageRecall3.js: XmlParser method not found — wrong WeChat version?",
    );
    return;
  }

  log.i(
    "AntiMessageRecall3.js: found " +
      results.methods.length +
      " method(s), installing hook...",
  );

  results.methods[0].hookAfter(function (thisObj, args, originalResult) {
    var xmlContent = args[0] || "";
    var rootTag = args[1] || "";

    if (rootTag !== "sysmsg" || xmlContent.indexOf("revokemsg") === -1) {
      return;
    }

    var typeKey = ".sysmsg.$type";

    const resultType = originalResult.get(typeKey);

    // mustn't use === here, that evaluates to false, idk why
    if (resultType == "revokemsg") {
      // don't use bracket syntax, that doesn't work
      var session = originalResult.get(".sysmsg.revokemsg.session");
      var replaceMsg = originalResult.get(".sysmsg.revokemsg.replacemsg");
      var msgSvrId = originalResult.get(".sysmsg.revokemsg.newmsgid");

      if (!replaceMsg) return;

      // Outgoing (self-)recalls have no quotes or brackets in replacemsg
      if (replaceMsg.indexOf('"') === -1 && replaceMsg.indexOf("「") === -1) {
        log.i("AntiMessageRecall3.js: outgoing message recall");
      }

      // Block the recall by clearing the type
      // don't use bracket syntax, that doesn't work
      originalResult.put(typeKey, null);

      // Extract sender name from replacemsg
      var match = NAME_REGEX.exec(replaceMsg);
      var senderName = match ? match[1] : "未知";
      var notice = "「" + senderName + "」尝试撤回上一条消息 (已阻止)";

      log.i("AntiMessageRecall3.js: blocked message revoke, notice=" + notice);
    }
  });

  log.i("AntiMessageRecall3.js: hook installed successfully");
}
