import { z } from "zod";
import { decodeBody, encodeBody } from "./body-transform";
export class HttpError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public retryable = false,
  ) {
    super(message);
  }
}
export function fail(
  status: number,
  code: string,
  message: string,
  retryable = false,
): never {
  throw new HttpError(status, code, message, retryable);
}
export function json(value: unknown, status = 200): Response {
  return Response.json(value, {
    status,
    headers: {
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    },
  });
}
export async function endpoint(
  request: Request,
  handler: () => Promise<Response>,
  env?: { DB: D1Database; PSEUDO_IPV4_MODE?: string },
): Promise<Response> {
  const transform = request.headers.get("x-wekit-body-transform") === "WKX1";
  const wireResponse = async (response: Response): Promise<Response> => {
    if (!transform || !response.headers.get("content-type")?.startsWith("application/json")) return response;
    const encoded = encodeBody(new Uint8Array(await response.arrayBuffer()));
    const headers = new Headers(response.headers);
    headers.set("X-WeKit-Body-Transform", "WKX1");
    headers.set("Content-Length", String(encoded.byteLength));
    return new Response(encoded, { status: response.status, statusText: response.statusText, headers });
  };
  try {
    return await wireResponse(await handler());
  } catch (e) {
    const known = e instanceof HttpError;
    if (!known)
      console.error(
        JSON.stringify({
          event: "request_failure",
          type: e instanceof Error ? e.name : "unknown",
        }),
      );
    const requestId = request.headers.get("x-wekit-request-id");
    if (
      env &&
      known &&
      [
        "INVALID_TOKEN",
        "INVALID_SIGNATURE",
        "CHALLENGE_INVALID",
        "UNTRUSTED_ATTESTATION",
        "SOFTWARE_KEY_REJECTED",
      ].includes(e.code)
    ) {
      const ip = request.headers.get("cf-connecting-ip");
      try {
        await env.DB.prepare(
          "INSERT INTO auth_rejections(code,request_id,source_ip,created_at) VALUES(?,?,?,?)",
        )
          .bind(
            e.code,
            requestId && /^[A-Za-z0-9_-]{1,64}$/.test(requestId)
              ? requestId
              : null,
            ip,
            Date.now(),
          )
          .run();
      } catch {
        console.error(JSON.stringify({ event: "rejection_log_failed" }));
      }
    }
    return await wireResponse(json(
      {
        error: {
          code: known ? e.code : "SERVICE_UNAVAILABLE",
          message: known ? e.message : "服务暂不可用",
          retryable: known ? e.retryable : true,
          requestId:
            requestId && /^[A-Za-z0-9_-]{1,64}$/.test(requestId)
              ? requestId
              : null,
        },
      },
      known ? e.status : 503,
    ));
  }
}
export async function bytes(
  request: Request | Response,
  maximum: number,
): Promise<Uint8Array<ArrayBuffer>> {
  const declared = request.headers.get("content-length");
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > maximum))
    fail(413, "INPUT_TOO_LARGE", "请求过大");
  if (!request.body) return new Uint8Array();
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      length += next.value.length;
      if (length > maximum) {
        await reader.cancel();
        fail(413, "INPUT_TOO_LARGE", "请求过大");
      }
      chunks.push(next.value);
    }
  } finally {
    reader.releaseLock();
  }
  const out = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }
  return out;
}
// JSON.parse alone silently discards duplicate keys. Check decoded names at
// every object (including escaped spellings), before the schema sees the data.
export function strictJson(text: string): unknown {
  let i = 0;
  const white = () => {
    while (/[\x20\t\r\n]/.test(text[i] ?? "!")) i++;
  };
  const string = (): string => {
    const start = i++;
    let escaped = false;
    while (i < text.length) {
      const c = text[i++]!;
      if (c === '"' && !escaped) {
        try {
          return JSON.parse(text.slice(start, i)) as string;
        } catch {
          fail(400, "INVALID_JSON", "JSON 字符串无效");
        }
      }
      if (c === "\\" && !escaped) escaped = true;
      else escaped = false;
    }
    fail(400, "INVALID_JSON", "JSON 字符串未结束");
  };
  const value = (depth: number): void => {
    if (depth > 32) fail(400, "INVALID_JSON", "JSON 嵌套过深");
    white();
    const c = text[i];
    if (c === '"') {
      string();
      return;
    }
    if (c === "{" || c === "[") {
      const object = c === "{";
      i++;
      white();
      const close = object ? "}" : "]";
      const seen = new Set<string>();
      if (text[i] === close) {
        i++;
        return;
      }
      while (true) {
        if (object) {
          if (text[i] !== '"') fail(400, "INVALID_JSON", "JSON key 无效");
          const key = string();
          if (seen.has(key)) fail(400, "DUPLICATE_JSON_KEY", "JSON key 重复");
          seen.add(key);
          white();
          if (text[i++] !== ":") fail(400, "INVALID_JSON", "JSON 对象无效");
        }
        value(depth + 1);
        white();
        if (text[i] === close) {
          i++;
          return;
        }
        if (text[i++] !== ",") fail(400, "INVALID_JSON", "JSON 分隔无效");
        white();
      }
    }
    const match =
      /^(?:true|false|null|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/.exec(
        text.slice(i),
      );
    if (!match) fail(400, "INVALID_JSON", "JSON 值无效");
    i += match[0].length;
  };
  value(0);
  white();
  if (i !== text.length) fail(400, "INVALID_JSON", "JSON 含多余内容");
  try {
    return JSON.parse(text) as unknown;
  } catch {
    fail(400, "INVALID_JSON", "JSON 无效");
  }
}
export function decodeJson(raw: Uint8Array): unknown {
  try {
    return strictJson(
      new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(raw),
    );
  } catch (e) {
    if (e instanceof HttpError) throw e;
    fail(400, "INVALID_JSON", "UTF-8 无效");
  }
}
export async function body<T>(
  req: Request,
  schema: z.ZodType<T>,
  maximum = 32768,
): Promise<{ value: T; raw: Uint8Array<ArrayBuffer> }> {
  if (
    req.headers.get("content-type")?.split(";")[0]?.trim() !==
    "application/json"
  )
    fail(415, "CONTENT_TYPE", "使用 application/json");
  const wire = await bytes(req, maximum + 4);
  let raw: Uint8Array<ArrayBuffer>;
  if (req.headers.get("x-wekit-body-transform") === "WKX1") {
    try { raw = new Uint8Array(decodeBody(wire)); }
    catch { fail(400, "INVALID_BODY_TRANSFORM", "请求编码无效"); }
  } else raw = wire;
  const result = schema.safeParse(decodeJson(raw));
  if (!result.success) fail(400, "INVALID_INPUT", "请求字段无效");
  return { value: result.data, raw };
}
export const id = z.string().regex(/^[A-Za-z0-9_-]{1,64}$/);
export const hash = z.string().regex(/^[0-9a-f]{64}$/);
export const binary = z.string().regex(/^[A-Za-z0-9_-]+$/);
export const nonce = binary.length(43);
export const text = (maximum: number, allowNewlines = false) =>
  z
    .string()
    .refine(
      (v) =>
        new TextEncoder().encode(v).length <= maximum &&
        !(allowNewlines ? /\0/ : /[\r\n\0]/).test(v),
    );
export function path(req: Request): string {
  const url = new URL(req.url);
  if (url.search || /%|\\|[^\x21-\x7e]/.test(url.pathname))
    fail(400, "INVALID_PATH", "路径非规范");
  return url.pathname;
}
export function source(
  req: Request,
  env?: { PSEUDO_IPV4_MODE?: string },
): { ip: string; sourceType: string } {
  const ip = req.headers.get("cf-connecting-ip");
  if (!ip) fail(503, "SOURCE_UNAVAILABLE", "连接资料不可用", true);
  const ipv6 =
    env?.PSEUDO_IPV4_MODE === "OVERWRITE"
      ? req.headers.get("cf-connecting-ipv6")
      : null;
  const worker = req.headers.get("cf-worker");
  return {
    ip: ipv6 ?? ip,
    sourceType: worker
      ? "WORKER_SUBREQUEST"
      : ipv6
        ? "IPV6_PSEUDO_IPV4"
        : "EDGE",
  };
}
