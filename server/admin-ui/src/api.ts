export type Row = Record<string, unknown>;
export interface Page {
  data: Row[];
  nextCursor?: string | null;
}
export interface Session {
  email: string;
  subject: string;
  csrfToken: string;
}
export interface Envelope<T> {
  data: T;
  nextCursor?: string | null;
}
export interface WatermarkTrace {
  matched: boolean;
  epochSeconds: number;
  wxId: string;
  identity: Row | null;
  candidates: Row[];
}

export async function read<T>(path: string): Promise<T> {
  const response = await fetch(`/admin/api/${path}`, {
    credentials: "same-origin",
    cache: "no-store",
    headers: { Accept: "application/json" },
  });
  return decode<T>(response);
}

async function decode<T>(response: Response): Promise<T> {
  if (!response.headers.get("content-type")?.includes("application/json"))
    throw new Error("管理员会话已失效，请重新载入页面。");
  const value = await response.json();
  if (!response.ok)
    throw new Error(value.error?.message ?? `请求失败（${response.status}）`);
  return value as T;
}

export async function write(
  path: string,
  method: "POST" | "PATCH" | "DELETE",
  body: Row,
): Promise<void> {
  // Refresh the short-lived token immediately before the user-confirmed write.
  const session = await read<Envelope<Session>>("session");
  const response = await fetch(`/admin/api/${path}`, {
    method,
    credentials: "same-origin",
    cache: "no-store",
    headers: {
      "Content-Type": "application/json",
      "X-WeKit-CSRF-Token": session.data.csrfToken,
    },
    body: JSON.stringify(body),
  });
  await decode(response);
}

export async function traceWatermark(
  ciphertext: string,
): Promise<WatermarkTrace> {
  const session = await read<Envelope<Session>>("session");
  const response = await fetch("/admin/api/watermark/trace", {
    method: "POST",
    credentials: "same-origin",
    cache: "no-store",
    headers: {
      "Content-Type": "application/json",
      "X-WeKit-CSRF-Token": session.data.csrfToken,
    },
    body: JSON.stringify({ ciphertext }),
  });
  return (await decode<Envelope<WatermarkTrace>>(response)).data;
}

export function query(
  filters: Record<string, string>,
  cursor?: string | null,
  limit = 50,
): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filters))
    if (value.trim()) params.set(key, value.trim());
  params.set("limit", String(limit));
  if (cursor) params.set("cursor", cursor);
  return params.toString();
}

export async function exportBatch(
  kind: "initializations" | "audit",
  filters: Record<string, string>,
  cursor: string | null,
  batch: number,
): Promise<string | null> {
  const response = await fetch(
    `/admin/api/exports/${kind}?${query(filters, cursor, 200)}`,
    { credentials: "same-origin", cache: "no-store" },
  );
  if (!response.ok) {
    await decode(response);
    throw new Error("导出失败");
  }
  if (!response.headers.get("content-type")?.includes("application/x-ndjson"))
    throw new Error("管理员会话已失效，请重新载入页面。");
  // One bounded server batch is downloaded; historical pages are never accumulated.
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = `wekit-${kind}-${new Date().toISOString().slice(0, 10)}-${batch}.ndjson`;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  return response.headers.get("X-Next-Cursor");
}
