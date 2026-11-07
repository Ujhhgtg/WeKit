import { z } from "zod";
import { equal, hmac, opaqueId, random, unb64, utf8 } from "./crypto";
import { body, bytes, decodeJson, endpoint, fail, json } from "./http";
import { normalizeCode } from "./enrollment";
import type { Env } from "./env";
const userSchema = z.object({
  id: z.number().int().positive().max(Number.MAX_SAFE_INTEGER),
  is_bot: z.boolean(),
  first_name: z.string().max(256),
  last_name: z.string().max(256).optional(),
  username: z.string().max(64).optional(),
});
const chatSchema = z.object({
  id: z.number().int().max(Number.MAX_SAFE_INTEGER),
  type: z.string(),
});
const chatMemberSchema = z.discriminatedUnion("status", [
  z.object({ status: z.enum(["creator", "administrator", "member"]) }),
  z.object({ status: z.literal("restricted"), is_member: z.boolean() }),
  z.object({ status: z.enum(["left", "kicked"]) }),
]);
const updateSchema = z.object({
  update_id: z.number().int().nonnegative(),
  message: z
    .object({
      from: userSchema.optional(),
      chat: chatSchema,
      text: z.string().max(4096).optional(),
    })
    .optional(),
  callback_query: z
    .object({
      id: z.string().max(256),
      from: userSchema,
      data: z.string().max(64).optional(),
      message: z
        .object({
          message_id: z.number().int().positive().optional(),
          chat: chatSchema,
        })
        .optional(),
    })
    .optional(),
});
async function telegram(
  env: Env,
  method: string,
  value: unknown,
): Promise<unknown> {
  if (!/^\d+:[A-Za-z0-9_-]{20,}$/.test(env.TELEGRAM_BOT_TOKEN))
    throw new Error("Bot secret missing");
  const response = await fetch(
    `https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/${method}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(value),
      redirect: "manual",
    },
  );
  const data = z
    .object({ ok: z.boolean(), result: z.unknown().optional() })
    .parse(decodeJson(await bytes(response, 65536)));
  if (!response.ok || !data.ok) throw new Error("Telegram method failed");
  return data.result;
}
export async function webhook(req: Request, env: Env): Promise<Response> {
  if (new URL(req.url).protocol !== "https:")
    fail(400, "HTTPS_REQUIRED", "Webhook 只接受 HTTPS");
  if (req.method !== "POST") fail(405, "METHOD_NOT_ALLOWED", "只接受 POST");
  const secret = req.headers.get("x-telegram-bot-api-secret-token");
  if (!secret || !equal(utf8(secret), utf8(env.TELEGRAM_WEBHOOK_SECRET)))
    fail(401, "INVALID_WEBHOOK", "Webhook 认证失败");
  const { value: update } = await body(req, updateSchema, 65536);
  const attempt = opaqueId(),
    updateId = String(update.update_id),
    now = Date.now();
  const owner = `EXISTS(SELECT 1 FROM telegram_updates WHERE bot_id=? AND update_id=? AND attempt_id=?)`;
  const args = [env.TELEGRAM_BOT_ID, updateId, attempt];
  const insert = env.DB.prepare(
    "INSERT INTO telegram_updates VALUES(?,?,?,'IGNORED',NULL,?) ON CONFLICT(bot_id,update_id) DO NOTHING",
  ).bind(env.TELEGRAM_BOT_ID, updateId, attempt, now);
  const callback = update.callback_query;
  if (callback) {
    if (
      callback.from.is_bot ||
      callback.message?.chat.type !== "private" ||
      String(callback.message.chat.id) !== String(callback.from.id) ||
      !callback.data?.startsWith("bind:")
    ) {
      await env.DB.batch([insert]);
      return json({ ok: true });
    }
    const confirmation = callback.data.slice(5);
    unb64(confirmation, 32);
    const userId = String(callback.from.id),
      chatId = String(callback.message.chat.id);
    const results = await env.DB.batch([
      insert,
      env.DB.prepare(
        `UPDATE enrollment_sessions SET state='APPROVED' WHERE confirm_nonce=? AND candidate_user_id=? AND candidate_chat_id=? AND state='PENDING' AND expires_at>? AND EXISTS(SELECT 1 FROM users u WHERE u.telegram_user_id=candidate_user_id AND u.status='ACTIVE' AND u.auth_epoch=candidate_epoch) AND ${owner} RETURNING binding_session_id`,
      ).bind(confirmation, userId, chatId, now, ...args),
      env.DB.prepare(
        `UPDATE telegram_updates SET result=CASE WHEN EXISTS(SELECT 1 FROM enrollment_sessions WHERE confirm_nonce=? AND state='APPROVED' AND candidate_user_id=?) THEN 'APPROVED' ELSE 'REJECTED' END,binding_session_id=(SELECT binding_session_id FROM enrollment_sessions WHERE confirm_nonce=?) WHERE ${owner}`,
      ).bind(confirmation, userId, confirmation, ...args),
    ]);
    if (results[0]!.meta.changes === 1) {
      try {
        if (callback.message?.message_id !== undefined) {
          await telegram(env, "deleteMessage", {
            chat_id: chatId,
            message_id: callback.message.message_id,
          });
        }
      } catch {
        console.error(
          JSON.stringify({
            event: "telegram_reply_failed",
            stage: "callback_delete",
          }),
        );
      }
      try {
        const approved = results[1]!.meta.changes === 1;
        await telegram(env, "sendMessage", {
          chat_id: chatId,
          text: approved
            ? "已确认，请返回微信。\n\n如果 WeKit 对你有帮助，欢迎在 GitHub 为项目点个 Star：\nhttps://github.com/Ujhhgtg/WeKit\n\nConfirmed. Please return to WeChat.\n\nIf WeKit helps you, please star the project on GitHub:\nhttps://github.com/Ujhhgtg/WeKit"
            : "确认已失效或已处理。\n\nThe confirmation has expired or was already processed.",
        });
      } catch {
        console.error(
          JSON.stringify({
            event: "telegram_reply_failed",
            stage: "callback_message",
          }),
        );
      }
    }
    return json({ ok: true });
  }
  const message = update.message;
  const sender = message?.from;
  if (
    !message ||
    !sender ||
    sender.is_bot ||
    message.chat.type !== "private" ||
    String(message.chat.id) !== String(sender.id) ||
    !message.text
  ) {
    await env.DB.batch([insert]);
    return json({ ok: true });
  }
  const userId = String(sender.id),
    chatId = String(message.chat.id);
  let code: string;
  try {
    code = normalizeCode(message.text.trim());
  } catch {
    const result = await env.DB.batch([insert]);
    if (result[0]!.meta.changes === 1) {
      try {
        await telegram(env, "sendMessage", {
          chat_id: chatId,
          text: "请发送微信中显示的 WeKit 12 位安装绑定码。\n\nPlease send the 12-character WeKit installation code shown in WeChat.",
        });
      } catch {
        console.error(
          JSON.stringify({ event: "telegram_reply_failed", stage: "help" }),
        );
      }
    }
    return json({ ok: true });
  }
  const member = chatMemberSchema.parse(
    await telegram(env, "getChatMember", {
      chat_id: -1004325719803,
      user_id: sender.id,
    }),
  );
  const joined =
    member.status === "creator" ||
    member.status === "administrator" ||
    member.status === "member" ||
    (member.status === "restricted" && member.is_member);
  if (!joined) {
    const result = await env.DB.batch([insert]);
    if (result[0]!.meta.changes === 1) {
      try {
        await telegram(env, "sendMessage", {
          chat_id: chatId,
          text: "绑定前请先加入 WeKit 唯一官方群组：\nhttps://t.me/+7j5dJ6g16B43OWVl\n\n除上述群组外，任何其他自称 WeKit 官方的频道和群组均非官方，属于假冒。官方无法确保其中分发的软件的安全性，建议退出这些频道和群组，并加入官方群组。\n\n入群后，请重新发送微信中显示的绑定码；如已过期，请先在微信中获取新的绑定码。\n\nBefore binding, please join the only official WeKit group:\nhttps://t.me/+7j5dJ6g16B43OWVl\n\nAny other channel or group claiming to be official is an impersonation. We cannot ensure the safety of software distributed there. Please leave those channels and groups and join the official group.\n\nAfter joining, resend the binding code shown in WeChat. If it has expired, obtain a new code in WeChat first.",
          reply_markup: {
            inline_keyboard: [
              [
                {
                  text: "加入官方群组 / Join official group",
                  url: "https://t.me/+7j5dJ6g16B43OWVl",
                },
              ],
            ],
          },
        });
      } catch {
        console.error(
          JSON.stringify({
            event: "telegram_reply_failed",
            stage: "join_group",
          }),
        );
      }
    }
    return json({ ok: true });
  }
  const codeHash = await hmac(
      env.BIND_CODE_PEPPER,
      `WEKIT-BINDING-CODE-V1\n${code}`,
    ),
    confirmation = random(32);
  const results = await env.DB.batch([
    insert,
    env.DB.prepare(
      `INSERT INTO users(telegram_user_id,username,display_name,created_at,last_seen_at) SELECT ?,?,?,?,? WHERE ${owner} ON CONFLICT(telegram_user_id) DO UPDATE SET username=excluded.username,display_name=excluded.display_name,last_seen_at=excluded.last_seen_at`,
    ).bind(
      userId,
      sender.username ?? null,
      [sender.first_name, sender.last_name].filter(Boolean).join(" "),
      now,
      now,
      ...args,
    ),
    env.DB.prepare(
      `UPDATE enrollment_sessions SET candidate_user_id=?,candidate_chat_id=?,candidate_epoch=(SELECT auth_epoch FROM users WHERE telegram_user_id=?),confirm_nonce=? WHERE code_hmac=? AND state='PENDING' AND expires_at>? AND (candidate_user_id IS NULL OR candidate_user_id=?) AND EXISTS(SELECT 1 FROM users WHERE telegram_user_id=? AND status='ACTIVE') AND ${owner} RETURNING binding_session_id`,
    ).bind(
      userId,
      chatId,
      userId,
      confirmation,
      codeHash,
      now,
      userId,
      userId,
      ...args,
    ),
    env.DB.prepare(
      `UPDATE telegram_updates SET binding_session_id=(SELECT binding_session_id FROM enrollment_sessions WHERE code_hmac=? AND candidate_user_id=?),result='BINDING_CODE' WHERE ${owner}`,
    ).bind(codeHash, userId, ...args),
  ]);
  if (results[0]!.meta.changes === 0) return json({ ok: true });
  const session = await env.DB.prepare(
    "SELECT e.state,e.confirm_nonce,e.expires_at,i.public_key_sha256,i.device_json FROM enrollment_sessions e JOIN installations i ON i.installation_id=e.installation_id WHERE e.code_hmac=? AND e.candidate_user_id=?",
  )
    .bind(codeHash, userId)
    .first<{
      state: string;
      confirm_nonce: string | null;
      expires_at: number;
      public_key_sha256: string;
      device_json: string;
    }>();
  try {
    if (!session || session.expires_at <= now)
      await telegram(env, "sendMessage", {
        chat_id: chatId,
        text: "绑定码不可用、已过期或已由其他账号领取。\n\nThe binding code is invalid, expired, or already claimed by another account.",
      });
    else if (session.state === "APPROVED")
      await telegram(env, "sendMessage", {
        chat_id: chatId,
        text: "已确认，请返回微信。\n\nConfirmed. Please return to WeChat.",
      });
    else
      await telegram(env, "sendMessage", {
        chat_id: chatId,
        text: `确认绑定这次 WeKit 安装？\n公钥指纹：…${session.public_key_sha256.slice(-12)}\n\nConfirm this WeKit installation?\nPublic key fingerprint: …${session.public_key_sha256.slice(-12)}`,
        reply_markup: {
          inline_keyboard: [
            [
              {
                text: "确认此安装",
                callback_data: `bind:${session.confirm_nonce}`,
              },
            ],
          ],
        },
      });
  } catch (e) {
    console.error(
      JSON.stringify({
        event: "telegram_reply_failed",
        stage: "confirmation",
        type: e instanceof Error ? e.name : "unknown",
      }),
    );
  }
  return json({ ok: true });
}
export default {
  fetch(req: Request, env: Env) {
    return endpoint(req, () => webhook(req, env));
  },
} satisfies ExportedHandler<Env>;
