import {
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import {
  exportBatch,
  query,
  read,
  write,
  type Envelope,
  type Page,
  type Row,
  type Session,
} from "./api";

type Section =
  | "overview"
  | "users"
  | "initializations"
  | "releases"
  | "publications"
  | "audit";
type Action = {
  title: string;
  description: string;
  path: string;
  method?: "POST" | "PATCH" | "DELETE";
  body: Row;
  channel?: boolean;
  tone?: "danger";
  confirm?: string;
};
type Column = { label: string; key?: string; render?: (row: Row) => ReactNode };
const sections: [Section, string, string][] = [
  ["overview", "概览", "◈"],
  ["users", "用户与安装", "◎"],
  ["initializations", "初始化历史", "◷"],
  ["releases", "发行管理", "◇"],
  ["publications", "发布任务", "↗"],
  ["audit", "管理审计", "≡"],
];
const labels: Record<string, string> = {
  ACTIVE: "正常",
  DISABLED: "已停用",
  REVOKED: "已撤销",
  ENDED: "已结束",
  ALLOWED: "允许授权",
  STOPPED: "停止授权",
  READY: "待允许",
  UPLOADING: "上传中",
  FAILED: "失败",
  AUTHORIZED: "授权通过",
  TEE: "TEE",
  STRONGBOX: "StrongBox",
  disable: "停用用户",
  restore: "恢复用户",
  reconfirm: "要求重新绑定",
  revoke: "撤销安装",
  allow: "允许发行",
  stop: "停止发行",
  delete: "删除发行",
  DELETED: "已删除",
  channel: "分配发布渠道",
  notes: "编辑备注",
  export: "导出记录",
  attestation_refresh: "更新认证资料",
};
const text = (value: unknown) =>
  value == null || value === "" ? "—" : String(value);
const date = (value: unknown) =>
  value
    ? new Intl.DateTimeFormat("zh-CN", {
        dateStyle: "medium",
        timeStyle: "medium",
        timeZone: "Asia/Singapore",
      }).format(new Date(Number(value)))
    : "—";
function badge(value: unknown) {
  const status = text(value);
  const tone = ["ACTIVE", "ALLOWED", "AUTHORIZED", "READY"].includes(status)
    ? "good"
    : ["DISABLED", "REVOKED", "STOPPED", "FAILED", "DELETED"].includes(
          status,
        ) || status.includes("DENIED")
      ? "bad"
      : "neutral";
  return (
    <span className={`badge ${tone}`}>
      <i />
      {labels[status] ?? status}
    </span>
  );
}
const releaseStatus = (r: Row) => (r.deleted_at ? "DELETED" : r.status);
function go(path: string) {
  window.location.hash = `/${path}`;
}
function useRoute() {
  const [route, setRoute] = useState(
    () => window.location.hash.slice(2) || "overview",
  );
  useEffect(() => {
    const handler = () => setRoute(window.location.hash.slice(2) || "overview");
    window.addEventListener("hashchange", handler);
    return () => window.removeEventListener("hashchange", handler);
  }, []);
  return route;
}
function useData<T>(path: string, revision = 0) {
  const [data, setData] = useState<T>();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(true);
  useEffect(() => {
    let current = true;
    setBusy(true);
    setError("");
    read<T>(path)
      .then((v) => {
        if (current) setData(v);
      })
      .catch((e) => {
        if (current) setError((e as Error).message);
      })
      .finally(() => {
        if (current) setBusy(false);
      });
    return () => {
      current = false;
    };
  }, [path, revision]);
  return { data, error, busy };
}
function Feedback({ error, busy }: { error: string; busy: boolean }) {
  return error ? (
    <div className="alert" role="alert">
      {error}
      <button onClick={() => window.location.reload()}>重新载入</button>
    </div>
  ) : busy ? (
    <div className="loading" role="status">
      <span />
      正在读取…
    </div>
  ) : null;
}
function Table({
  rows,
  columns,
  busy = false,
}: {
  rows: Row[];
  columns: Column[];
  busy?: boolean;
}) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.label}>{c.label}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr
              key={text(
                row.initialization_id ??
                  row.audit_id ??
                  row.job_id ??
                  row.binding_id ??
                  row.credential_id ??
                  row.installation_id ??
                  row.payload_id ??
                  row.telegram_user_id ??
                  index,
              )}
            >
              {columns.map((c) => (
                <td key={c.label}>
                  {c.render ? c.render(row) : text(row[c.key!])}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {!rows.length && !busy && (
        <div className="empty">
          <span>◌</span>
          <strong>暂无记录</strong>
          <p>当前筛选范围内没有数据。</p>
        </div>
      )}
    </div>
  );
}
const when: Column = {
  label: "时间",
  render: (r) => <span className="nowrap muted">{date(r.created_at)}</span>,
};
const user: Column = {
  label: "Telegram 用户",
  render: (r) => (
    <button
      className="link mono"
      onClick={() => go(`users/${text(r.telegram_user_id)}`)}
    >
      {text(r.telegram_user_id)}
    </button>
  ),
};
const installation: Column = {
  label: "安装实例",
  render: (r) => (
    <button
      className="link mono"
      onClick={() => go(`installations/${text(r.installation_id)}`)}
    >
      {text(r.installation_id)}
    </button>
  ),
};
function Json({ value }: { value: unknown }) {
  let parsed = value;
  if (typeof value === "string") {
    try {
      parsed = JSON.parse(value);
    } catch {
      parsed = value;
    }
  }
  return <pre className="json">{JSON.stringify(parsed, null, 2)}</pre>;
}
function Fact({
  label,
  value,
  mono = false,
}: {
  label: string;
  value: unknown;
  mono?: boolean;
}) {
  return (
    <div className="fact">
      <dt>{label}</dt>
      <dd className={mono ? "mono" : ""}>{text(value)}</dd>
    </div>
  );
}
function Card({
  title,
  aside,
  children,
}: {
  title: string;
  aside?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="card">
      <div className="card-heading">
        <h2>{title}</h2>
        {aside}
      </div>
      {children}
    </section>
  );
}
function ActionDialog({
  action,
  onClose,
  onDone,
}: {
  action: Action;
  onClose: () => void;
  onDone: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const [channel, setChannel] = useState("stable");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const { data: channelData } = useData<Envelope<Row[]>>("channels");
  const danger = action.tone === "danger";
  useEffect(() => {
    const dialog = ref.current!;
    dialog.showModal();
    // Destructive actions start on "取消" so Enter never confirms by accident.
    dialog
      .querySelector<HTMLElement>(
        danger || action.channel ? "[data-cancel]" : "[data-confirm]",
      )
      ?.focus();
  }, []);
  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      const target = action.channel
        ? `channels/${encodeURIComponent(channel.trim())}`
        : action.path;
      await write(target, action.method ?? "POST", action.body);
      onDone();
      onClose();
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  }
  return (
    <dialog
      ref={ref}
      className="action-dialog"
      aria-labelledby="action-title"
      onCancel={(event) => {
        if (busy) event.preventDefault();
        else onClose();
      }}
      onClick={(event) => {
        if (event.target === ref.current && !busy) onClose();
      }}
    >
      <form onSubmit={submit}>
        <div className="dialog-heading">
          <span className={`dialog-icon ${danger ? "danger" : ""}`} aria-hidden>
            {danger ? "!" : "?"}
          </span>
          <h2 id="action-title">{action.title}</h2>
        </div>
        <p className="dialog-description">{action.description}</p>
        {action.channel && (
          <label>
            渠道名称
            <input
              value={channel}
              onChange={(e) => setChannel(e.target.value)}
              list="channel-options"
              required
              pattern="[a-z0-9_\-]{1,64}"
              title="仅限小写字母、数字、下划线和连字符，最长 64 位"
            />
            <datalist id="channel-options">
              {(channelData?.data ?? []).map((c) => (
                <option key={text(c.name)} value={text(c.name)} />
              ))}
            </datalist>
          </label>
        )}
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        <div className="dialog-actions">
          <button
            type="button"
            className="button secondary"
            data-cancel
            disabled={busy}
            onClick={onClose}
          >
            取消
          </button>
          <button
            className={`button ${danger ? "danger" : ""}`}
            data-confirm
            disabled={busy}
          >
            {busy ? "正在提交…" : (action.confirm ?? "确认")}
          </button>
        </div>
      </form>
    </dialog>
  );
}
function DetailDialog({
  title,
  children,
  onClose,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    ref.current!.showModal();
  }, []);
  return (
    <dialog
      ref={ref}
      className="detail-dialog"
      onCancel={onClose}
      onClick={(event) => {
        if (event.target === ref.current) onClose();
      }}
    >
      <div className="card-heading">
        <h2>{title}</h2>
        <button className="icon-button" aria-label="关闭详情" onClick={onClose}>
          ×
        </button>
      </div>
      {children}
    </dialog>
  );
}
function Overview({
  revision,
  act,
}: {
  revision: number;
  act: (a: Action) => void;
}) {
  const { data, error, busy } = useData<Envelope<Row>>("overview", revision);
  const overview = data?.data;
  const recent = (overview?.recentInitializations ?? []) as Row[];
  const releases = (overview?.payloads ?? []) as Row[];
  const channels = (overview?.channels ?? []) as Row[];
  const snapshot = overview?.attestationSnapshot as Row | null;
  return (
    <>
      <Feedback error={error} busy={busy} />
      {overview && (
        <>
          <div className="metrics">
            <Metric
              label="Telegram 用户"
              value={overview.users}
              hint="已建立绑定的用户"
            />
            <Metric
              label="安装实例"
              value={overview.installations}
              hint="独立硬件公钥身份"
            />
            <Metric
              label="最近初始化"
              value={recent.length}
              hint="最近 20 次授权请求"
            />
            <Metric
              label="默认渠道"
              value={overview.defaultChannel ?? "stable"}
              hint={text(
                channels.find(
                  (c) => c.name === (overview.defaultChannel ?? "stable"),
                )?.payload_id,
              )}
            />
          </div>
          <div className="overview-grid">
            <Card
              title="最近初始化"
              aside={
                <button className="link" onClick={() => go("initializations")}>
                  查看全部 →
                </button>
              }
            >
              <Table
                rows={recent}
                busy={busy}
                columns={[
                  user,
                  { label: "结果", render: (r) => badge(r.result) },
                  when,
                ]}
              />
            </Card>
            <div className="stack">
              <Card
                title="认证资料"
                aside={badge(snapshot ? "ACTIVE" : "FAILED")}
              >
                <div className="card-body">
                  <p className="muted">最近更新</p>
                  <strong>
                    {snapshot ? date(snapshot.fetched_at) : "尚未获取"}
                  </strong>
                  <p className="help">
                    初始化授权使用最新有效的硬件证明撤销信息。
                  </p>
                  <button
                    className="button secondary"
                    onClick={() =>
                      act({
                        title: "更新认证资料",
                        confirm: "立即更新",
                        description:
                          "从官方来源重新读取证明根与撤销资料。更新成功后用于后续注册与授权。",
                        path: "attestation/refresh",
                        body: {},
                      })
                    }
                  >
                    立即更新
                  </button>
                </div>
              </Card>
              <Card
                title="最新发行"
                aside={
                  <button className="link" onClick={() => go("releases")}>
                    管理 →
                  </button>
                }
              >
                <div className="release-list">
                  {releases.slice(0, 4).map((r) => (
                    <div key={text(r.payload_id)}>
                      <button
                        className="link mono"
                        onClick={() =>
                          go(
                            `releases?payloadId=${encodeURIComponent(text(r.payload_id))}`,
                          )
                        }
                      >
                        {text(r.payload_id)}
                      </button>
                      {badge(releaseStatus(r))}
                    </div>
                  ))}
                  {!releases.length && <p className="muted">尚无发行版本</p>}
                </div>
              </Card>
            </div>
          </div>
        </>
      )}
    </>
  );
}
function Metric({
  label,
  value,
  hint,
}: {
  label: string;
  value: unknown;
  hint: string;
}) {
  return (
    <div className="metric">
      <span>{label}</span>
      <strong>{text(value)}</strong>
      <small>{hint}</small>
    </div>
  );
}
const filterDefinitions: Record<
  Exclude<Section, "overview">,
  [string, string][]
> = {
  users: [
    ["telegramUserId", "Telegram ID"],
    ["selfWxId", "历史 wxId"],
    ["selfAlias", "历史 alias"],
    ["status", "用户状态"],
  ],
  initializations: [
    ["telegramUserId", "Telegram ID"],
    ["installationId", "安装实例"],
    ["selfWxId", "wxId"],
    ["selfAlias", "alias"],
    ["sourceIp", "来源 IP"],
    ["payloadId", "发行 ID"],
    ["result", "授权结果"],
  ],
  releases: [
    ["payloadId", "发行 ID"],
    ["status", "发行状态"],
  ],
  publications: [
    ["payloadId", "发行 ID"],
    ["state", "发布状态"],
  ],
  audit: [
    ["subject", "操作者标识"],
    ["target", "操作对象"],
    ["action", "操作类型"],
  ],
};
function ListPage({
  kind,
  initial,
  revision,
  act,
}: {
  kind: Exclude<Section, "overview">;
  initial: Record<string, string>;
  revision: number;
  act: (a: Action) => void;
}) {
  const [draft, setDraft] = useState(initial);
  const [filters, setFilters] = useState(initial);
  const [cursors, setCursors] = useState<(string | null)[]>([null]);
  const [detail, setDetail] = useState<Row | null>(null);
  const [exportCursor, setExportCursor] = useState<string | null>(null);
  const [exportBatchNumber, setExportBatchNumber] = useState(1);
  const [exportDone, setExportDone] = useState(false);
  const [exportBusy, setExportBusy] = useState(false);
  const [exportError, setExportError] = useState("");
  const cursor = cursors.at(-1)!;
  const { data, error, busy } = useData<Page>(
    `${kind}?${query(filters, cursor)}`,
    revision,
  );
  const columns = columnsFor(kind, act, setDetail);
  const canExport = kind === "initializations" || kind === "audit";
  function apply(event: FormEvent) {
    event.preventDefault();
    setFilters({ ...draft });
    setCursors([null]);
    setExportCursor(null);
    setExportBatchNumber(1);
    setExportDone(false);
    setExportError("");
  }
  async function download() {
    setExportBusy(true);
    setExportError("");
    try {
      const next = await exportBatch(
        kind as "initializations" | "audit",
        filters,
        exportCursor,
        exportBatchNumber,
      );
      setExportCursor(next);
      setExportDone(!next);
      setExportBatchNumber((n) => n + 1);
    } catch (e) {
      setExportError((e as Error).message);
    } finally {
      setExportBusy(false);
    }
  }
  return (
    <>
      <form className="filters" onSubmit={apply}>
        {filterDefinitions[kind].map(([key, label]) => (
          <label key={key}>
            {label}
            {key === "status" || key === "state" ? (
              <select
                value={draft[key] ?? ""}
                onChange={(e) => setDraft({ ...draft, [key]: e.target.value })}
              >
                <option value="">全部</option>
                {(kind === "users"
                  ? ["ACTIVE", "DISABLED"]
                  : kind === "releases"
                    ? ["UPLOADING", "READY", "ALLOWED", "STOPPED", "FAILED"]
                    : ["UPLOADING", "READY", "ALLOWED", "FAILED"]
                ).map((s) => (
                  <option value={s} key={s}>
                    {labels[s]}
                  </option>
                ))}
              </select>
            ) : (
              <input
                value={draft[key] ?? ""}
                onChange={(e) => setDraft({ ...draft, [key]: e.target.value })}
                placeholder={label}
              />
            )}
          </label>
        ))}
        <div className="filter-actions">
          <button className="button" type="submit">
            查询
          </button>
          <button
            className="button secondary"
            type="button"
            onClick={() => {
              setDraft({});
              setFilters({});
              setCursors([null]);
              setExportCursor(null);
              setExportBatchNumber(1);
              setExportDone(false);
              setExportError("");
            }}
          >
            重置
          </button>
        </div>
      </form>
      {kind === "releases" && <Channels revision={revision} />}
      <Feedback error={error} busy={busy} />
      <section className="card">
        <div className="list-toolbar">
          <span className="muted">本页 {data?.data.length ?? 0} 条</span>
          {canExport && (
            <div className="export-tools">
              <span>
                {exportDone
                  ? "已导出全部匹配记录"
                  : `每批最多 200 条${exportBatchNumber > 1 ? ` · 已导出 ${exportBatchNumber - 1} 批` : ""}`}
              </span>
              <button
                className="button secondary small"
                disabled={exportBusy || exportDone}
                onClick={download}
              >
                {exportBusy
                  ? "正在导出…"
                  : exportBatchNumber === 1
                    ? "导出 NDJSON"
                    : "导出下一批"}
              </button>
            </div>
          )}
        </div>
        {exportError && (
          <p className="error padded" role="alert">
            {exportError}
          </p>
        )}
        <Table rows={data?.data ?? []} columns={columns} busy={busy} />
        <div className="pagination">
          <span>第 {cursors.length} 页</span>
          <div>
            <button
              className="button secondary small"
              disabled={busy || cursors.length === 1}
              onClick={() => setCursors((v) => v.slice(0, -1))}
            >
              ← 上一页
            </button>
            <button
              className="button secondary small"
              disabled={busy || !data?.nextCursor}
              onClick={() => setCursors((v) => [...v, data!.nextCursor!])}
            >
              下一页 →
            </button>
          </div>
        </div>
      </section>
      {detail && (
        <DetailDialog
          title={
            kind === "audit"
              ? "审计详情"
              : kind === "releases"
                ? "发行详情"
                : "初始化详情"
          }
          onClose={() => setDetail(null)}
        >
          {kind === "releases" ? (
            <ReleaseDetail row={detail} />
          ) : kind === "audit" ? (
            <AuditDetail row={detail} />
          ) : (
            <InitializationDetail row={detail} />
          )}
        </DetailDialog>
      )}
    </>
  );
}
function Channels({ revision }: { revision: number }) {
  const { data } = useData<Envelope<Row[]>>("channels", revision);
  const channels = data?.data ?? [];
  return (
    <section className="card channels">
      <div className="card-heading">
        <h2>发布渠道</h2>
        <span className="muted">
          用户读取所分配渠道的当前版本；local 使用设备上的本地载荷，未分配时使用 stable
        </span>
      </div>
      {channels.length ? (
        <div className="channel-list">
          {channels.map((c) => (
            <div key={text(c.name)}>
              <strong>{text(c.name)}</strong>
              <button
                className="link mono"
                onClick={() =>
                  go(
                    `releases?payloadId=${encodeURIComponent(text(c.payload_id))}`,
                  )
                }
              >
                {text(c.payload_id)}
              </button>
              <span className="muted">更新于 {date(c.updated_at)}</span>
            </div>
          ))}
        </div>
      ) : (
        <p className="muted padded">
          还没有可用渠道。首次使用 x publish --channel 发布后会自动创建。
        </p>
      )}
    </section>
  );
}
function columnsFor(
  kind: Exclude<Section, "overview">,
  act: (a: Action) => void,
  detail: (r: Row) => void,
): Column[] {
  switch (kind) {
    case "users":
      return [
        user,
        {
          label: "资料",
          render: (r) => (
            <div>
              <strong>{text(r.display_name)}</strong>
              <div className="muted">
                {r.username ? `@${r.username}` : "未设置用户名"}
              </div>
            </div>
          ),
        },
        { label: "状态", render: (r) => badge(r.status) },
        { label: "发布渠道", key: "release_channel" },
        { label: "最近访问", render: (r) => date(r.last_seen_at) },
        { label: "备注", key: "notes" },
        {
          label: "操作",
          render: (r) => (
            <button
              className="link"
              onClick={() => go(`users/${text(r.telegram_user_id)}`)}
            >
              查看详情 →
            </button>
          ),
        },
      ];
    case "initializations":
      return [
        when,
        user,
        {
          label: "微信身份",
          render: (r) => (
            <div>
              <div className="mono">{text(r.self_wx_id)}</div>
              <div className="muted">{text(r.self_alias)}</div>
            </div>
          ),
        },
        installation,
        {
          label: "来源 IP",
          render: (r) => (
            <span className="mono nowrap">{text(r.source_ip)}</span>
          ),
        },
        { label: "发行", key: "payload_id" },
        { label: "结果", render: (r) => badge(r.result) },
        {
          label: "操作",
          render: (r) => (
            <button className="link" onClick={() => detail(r)}>
              详情
            </button>
          ),
        },
      ];
    case "releases":
      return [
        {
          label: "发行 ID",
          render: (r) => (
            <button className="link mono" onClick={() => detail(r)}>
              {text(r.payload_id)}
            </button>
          ),
        },
        {
          label: "版本",
          render: (r) => text(releaseManifest(r)?.businessVersion),
        },
        { label: "状态", render: (r) => badge(releaseStatus(r)) },
        { label: "包体大小", render: (r) => size(r.apk_size) },
        when,
        {
          label: "操作",
          render: (r) => (
            <div className="row-actions">
              {r.deleted_at ? (
                <span className="muted">已删除，无法恢复</span>
              ) : null}
              {!r.deleted_at &&
                r.status !== "ALLOWED" &&
                r.status !== "UPLOADING" &&
                r.status !== "FAILED" && (
                  <button
                    className="link"
                    onClick={() =>
                      act({
                        title: "允许发行授权",
                        confirm: "允许",
                        description: `允许后，安装实例可以领取发行 ${r.payload_id} 的内容密钥。`,
                        path: `releases/${r.payload_id}/actions`,
                        body: { action: "allow" },
                      })
                    }
                  >
                    允许
                  </button>
                )}
              {!r.deleted_at && r.status !== "UPLOADING" && (
                <button
                  className="link danger-text"
                  onClick={() =>
                    act({
                      title: "删除发行",
                      tone: "danger",
                      confirm: "删除",
                      description: `将永久删除发行 ${r.payload_id} 的加密包体与内容密钥，并立即撤销已签发的下载许可。该发行无法再次启用；渠道、初始化与发布任务记录会保留，仍指向该发行的渠道将不可用，直到切换到其他发行。`,
                      path: `releases/${r.payload_id}`,
                      method: "DELETE",
                      body: {},
                    })
                  }
                >
                  删除
                </button>
              )}
              {!r.deleted_at && r.status === "ALLOWED" && (
                <>
                  <button
                    className="link danger-text"
                    onClick={() =>
                      act({
                        title: "停止发行授权",
                        tone: "danger",
                        confirm: "停止授权",
                        description: `后续新初始化与下载将不能使用发行 ${r.payload_id}。已经运行的进程继续运行。`,
                        path: `releases/${r.payload_id}/actions`,
                        body: { action: "stop" },
                      })
                    }
                  >
                    停止
                  </button>
                  <button
                    className="link"
                    onClick={() =>
                      act({
                        title: "切换渠道当前版本",
                        confirm: "切换",
                        description: `手动把指定渠道切换到发行 ${r.payload_id}。这会覆盖该渠道当前版本，适合回滚或紧急切换；正常发布请使用 x publish --channel。`,
                        path: "",
                        channel: true,
                        body: { payloadId: r.payload_id },
                      })
                    }
                  >
                    切换渠道版本
                  </button>
                </>
              )}
            </div>
          ),
        },
      ];
    case "publications":
      return [
        { label: "任务 ID", key: "job_id" },
        { label: "发行 ID", key: "payload_id" },
        { label: "状态", render: (r) => badge(r.state) },
        {
          label: "源码提交",
          render: (r) => <span className="mono">{text(r.source_commit)}</span>,
        },
        { label: "构建运行", key: "run_id" },
        { label: "错误", key: "error_code" },
        { label: "最后更新", render: (r) => date(r.updated_at) },
      ];
    case "audit":
      return [
        when,
        {
          label: "操作者",
          render: (r) => (
            <div>
              {text(r.email)}
              <div className="muted mono small-text">{text(r.subject)}</div>
            </div>
          ),
        },
        {
          label: "操作类型",
          render: (r) => labels[text(r.action)] ?? text(r.action),
        },
        { label: "对象", key: "target" },
        {
          label: "来源 IP",
          render: (r) => (
            <span className="mono nowrap">{text(r.source_ip)}</span>
          ),
        },
        {
          label: "操作",
          render: (r) => (
            <button className="link" onClick={() => detail(r)}>
              前后状态
            </button>
          ),
        },
      ];
  }
}
function size(value: unknown) {
  const bytes = Number(value);
  return Number.isFinite(bytes) ? `${(bytes / 1048576).toFixed(2)} MB` : "—";
}
function releaseManifest(row: Row): Row | null {
  try {
    const envelope = JSON.parse(text(row.manifest_json));
    const raw = envelope.manifest.replace(/-/g, "+").replace(/_/g, "/");
    return JSON.parse(
      new TextDecoder().decode(
        Uint8Array.from(
          atob(raw + "=".repeat((4 - (raw.length % 4)) % 4)),
          (c) => c.charCodeAt(0),
        ),
      ),
    ) as Row;
  } catch {
    return null;
  }
}
function ReleaseDetail({ row }: { row: Row }) {
  const manifest = releaseManifest(row);
  return (
    <>
      <dl className="facts">
        <Fact label="发行 ID" value={row.payload_id} mono />
        <Fact label="版本" value={manifest?.businessVersion} />
        <Fact label="状态" value={labels[text(releaseStatus(row))]} />
        <Fact label="创建时间" value={date(row.created_at)} />
        {row.deleted_at ? (
          <Fact label="删除时间" value={date(row.deleted_at)} />
        ) : null}
        <Fact label="源码提交" value={manifest?.sourceCommit} mono />
        <Fact label="业务包大小" value={size(row.apk_size)} />
        <Fact label="APK SHA-256" value={row.apk_sha256} mono />
        <Fact label="分发包 SHA-256" value={row.cipher_sha256} mono />
      </dl>
    </>
  );
}
function InitializationDetail({ row }: { row: Row }) {
  return (
    <>
      <dl className="facts">
        <Fact label="初始化 ID" value={row.initialization_id} mono />
        <Fact label="Telegram 用户" value={row.telegram_user_id} />
        <Fact label="wxId（客户端报告）" value={row.self_wx_id} mono />
        <Fact label="alias（客户端报告）" value={row.self_alias} />
        <Fact label="来源 IP（边缘观测）" value={row.source_ip} mono />
        <Fact label="来源类型" value={row.source_type} />
        <Fact label="安装实例" value={row.installation_id} mono />
        <Fact label="发行 ID" value={row.payload_id} mono />
        <Fact label="进程" value={row.process_name} />
        <Fact label="宿主版本" value={row.host_version_name} />
        <Fact label="引导版本" value={row.bootstrap_version} />
        <Fact label="结果" value={labels[text(row.result)] ?? row.result} />
        <Fact label="时间" value={date(row.created_at)} />
      </dl>
      {row.report_json != null && (
        <details>
          <summary>完整客户端报告</summary>
          <Json value={row.report_json} />
        </details>
      )}
    </>
  );
}
function AuditDetail({ row }: { row: Row }) {
  return (
    <>
      <dl className="facts">
        <Fact label="操作者" value={row.email} />
        <Fact label="对象" value={row.target} />
        <Fact label="操作" value={labels[text(row.action)] ?? row.action} />
        <Fact label="来源 IP" value={row.source_ip} mono />
        <Fact label="时间" value={date(row.created_at)} />
      </dl>
      <h3>操作前</h3>
      <Json value={row.before_json} />
      <h3>操作后</h3>
      <Json value={row.after_json} />
    </>
  );
}
function UserDetail({
  id,
  revision,
  act,
}: {
  id: string;
  revision: number;
  act: (a: Action) => void;
}) {
  const { data, error, busy } = useData<
    Envelope<{
      user: Row;
      bindings: Row[];
      installations: Row[];
      initializations: Row[];
    }>
  >(`users/${encodeURIComponent(id)}`, revision);
  const { data: channelData } = useData<Envelope<Row[]>>("channels", revision);
  const u = data?.data.user;
  const [notes, setNotes] = useState("");
  const [channel, setChannel] = useState("stable");
  useEffect(() => {
    if (u) {
      setNotes(String(u.notes ?? ""));
      setChannel(String(u.release_channel ?? "stable"));
    }
  }, [u]);
  return (
    <>
      <Feedback error={error} busy={busy} />
      {data && u && (
        <>
          <Card title={text(u.display_name)} aside={badge(u.status)}>
            <div className="profile-grid">
              <dl className="facts">
                <Fact label="Telegram ID" value={u.telegram_user_id} mono />
                <Fact
                  label="用户名"
                  value={u.username ? `@${u.username}` : null}
                />
                <Fact label="首次绑定" value={date(u.created_at)} />
                <Fact label="最近访问" value={date(u.last_seen_at)} />
                <Fact
                  label="当前发布渠道"
                  value={u.release_channel ?? "stable"}
                />
              </dl>
              <div className="profile-actions">
                <button
                  className={`button ${u.status === "DISABLED" ? "secondary" : "danger"}`}
                  onClick={() =>
                    act({
                      title: u.status === "DISABLED" ? "恢复用户" : "停用用户",
                      tone: u.status === "DISABLED" ? undefined : "danger",
                      confirm: u.status === "DISABLED" ? "恢复" : "停用",
                      description:
                        u.status === "DISABLED"
                          ? "允许有效凭证继续请求；已经撤销的凭证不会自动恢复。"
                          : "该用户的所有安装将不能领取新的内容密钥或下载业务载荷，已运行的进程继续运行。",
                      path: `users/${id}/actions`,
                      body: {
                        action: u.status === "DISABLED" ? "restore" : "disable",
                      },
                    })
                  }
                >
                  {u.status === "DISABLED" ? "恢复用户" : "停用用户"}
                </button>
                <button
                  className="button secondary"
                  onClick={() =>
                    act({
                      title: "要求全部安装重新绑定",
                      tone: "danger",
                      confirm: "要求重新绑定",
                      description:
                        "使该用户现有安装凭证失效。各安装必须重新通过 Telegram Bot 确认后才能初始化。",
                      path: `users/${id}/actions`,
                      body: { action: "reconfirm" },
                    })
                  }
                >
                  要求重新绑定
                </button>
              </div>
            </div>
            <div className="notes">
              <label>
                分配发布渠道
                <select
                  value={channel}
                  onChange={(e) => setChannel(e.target.value)}
                >
                  <option value="local">local（本地载荷）</option>
                  {(channelData?.data ?? []).map((row) => (
                    <option key={text(row.name)} value={text(row.name)}>
                      {text(row.name)}
                    </option>
                  ))}
                </select>
              </label>
              <p className="help">
                未分配用户默认使用
                stable；选择 local 后，后续初始化只使用设备上由 `x payload install` 写入的本地载荷；修改只影响后续初始化，已运行进程不会切换。
              </p>
              <button
                className="button secondary"
                disabled={channel === String(u.release_channel ?? "stable")}
                onClick={() =>
                  act({
                    title: "分配发布渠道",
                    confirm: "保存",
                    description: `后续初始化将使用渠道 ${channel} 的最新发行。已经运行的进程保持当前版本。`,
                    path: `users/${id}/actions`,
                    body: { action: "channel", channel },
                  })
                }
              >
                保存渠道
              </button>
            </div>
            <div className="notes">
              <label>
                管理员备注
                <input
                  value={notes}
                  onChange={(e) => setNotes(e.target.value)}
                  placeholder="添加备注"
                />
              </label>
              <button
                className="button secondary"
                disabled={notes === String(u.notes ?? "")}
                onClick={() =>
                  act({
                    title: "保存管理员备注",
                    confirm: "保存",
                    description: `更新用户 ${id} 的备注，并记录此次操作。`,
                    path: `users/${id}/notes`,
                    method: "PATCH",
                    body: { notes },
                  })
                }
              >
                保存备注
              </button>
            </div>
          </Card>
          <Card title="安装实例">
            <Table
              rows={data.data.installations}
              columns={[
                installation,
                { label: "硬件证明", render: (r) => badge(r.security_level) },
                {
                  label: "公钥指纹",
                  render: (r) => (
                    <span className="mono">{text(r.public_key_sha256)}</span>
                  ),
                },
                when,
              ]}
            />
          </Card>
          <Card title="绑定时间线">
            <Table
              rows={data.data.bindings}
              columns={[
                { label: "绑定 ID", key: "binding_id" },
                installation,
                { label: "状态", render: (r) => badge(r.status) },
                when,
                { label: "结束时间", render: (r) => date(r.ended_at) },
              ]}
            />
          </Card>
          <Card
            title="最近初始化"
            aside={
              <button
                className="link"
                onClick={() =>
                  go(`initializations?telegramUserId=${encodeURIComponent(id)}`)
                }
              >
                完整历史 →
              </button>
            }
          >
            <Table
              rows={data.data.initializations}
              columns={[
                when,
                { label: "wxId", key: "self_wx_id" },
                { label: "alias", key: "self_alias" },
                {
                  label: "来源 IP",
                  render: (r) => (
                    <span className="mono nowrap">{text(r.source_ip)}</span>
                  ),
                },
                { label: "发行", key: "payload_id" },
                { label: "结果", render: (r) => badge(r.result) },
              ]}
            />
          </Card>
        </>
      )}
    </>
  );
}
function InstallationDetail({
  id,
  revision,
  act,
}: {
  id: string;
  revision: number;
  act: (a: Action) => void;
}) {
  const { data, error, busy } = useData<
    Envelope<{ installation: Row; bindings: Row[]; credentials: Row[] }>
  >(`installations/${encodeURIComponent(id)}`, revision);
  const i = data?.data.installation;
  return (
    <>
      <Feedback error={error} busy={busy} />
      {data && i && (
        <>
          <Card
            title="安装详情"
            aside={
              <button
                className="button danger"
                onClick={() =>
                  act({
                    title: "撤销此安装凭证",
                    tone: "danger",
                    confirm: "撤销凭证",
                    description:
                      "此安装的有效凭证与下载许可将被撤销，用户需要重新通过 Telegram Bot 绑定。",
                    path: `installations/${id}/revoke`,
                    body: {},
                  })
                }
              >
                撤销安装凭证
              </button>
            }
          >
            <dl className="facts padded">
              <Fact label="安装实例" value={i.installation_id} mono />
              <Fact
                label="硬件证明级别"
                value={labels[text(i.security_level)] ?? i.security_level}
              />
              <Fact label="登记时间" value={date(i.created_at)} />
              <Fact label="公钥指纹" value={i.public_key_sha256} mono />
              <Fact label="证明证据摘要" value={i.evidence_sha256} mono />
            </dl>
            <div className="padded">
              <details>
                <summary>设备报告资料</summary>
                <Json value={i.device_json} />
              </details>
              <details>
                <summary>硬件证明资料</summary>
                <Json value={i.attestation_json} />
              </details>
            </div>
          </Card>
          <Card title="绑定时间线">
            <Table
              rows={data.data.bindings}
              columns={[
                user,
                { label: "绑定 ID", key: "binding_id" },
                { label: "状态", render: (r) => badge(r.status) },
                when,
                { label: "结束时间", render: (r) => date(r.ended_at) },
              ]}
            />
          </Card>
          <Card title="凭证状态">
            <Table
              rows={data.data.credentials}
              columns={[
                { label: "凭证 ID", key: "credential_id" },
                { label: "状态", render: (r) => badge(r.status) },
                when,
                { label: "撤销时间", render: (r) => date(r.revoked_at) },
              ]}
            />
          </Card>
          <button
            className="button secondary"
            onClick={() =>
              go(`initializations?installationId=${encodeURIComponent(id)}`)
            }
          >
            查看此安装的全部初始化 →
          </button>
        </>
      )}
    </>
  );
}

export default function App() {
  const route = useRoute();
  const [revision, setRevision] = useState(0);
  const [action, setAction] = useState<Action | null>(null);
  const [notice, setNotice] = useState("");
  const noticeTimer = useRef<number>(0);
  const session = useData<Envelope<Session>>("session");
  const [pathname, search = ""] = route.split("?");
  const parts = pathname.split("/");
  const selected = (
    sections.some(([id]) => id === parts[0])
      ? parts[0]
      : parts[0] === "installations"
        ? "users"
        : "overview"
  ) as Section;
  const title =
    parts[0] === "users" && parts[1]
      ? "用户详情"
      : parts[0] === "installations" && parts[1]
        ? "安装详情"
        : sections.find(([id]) => id === selected)![1];
  function completed() {
    setRevision((v) => v + 1);
    setNotice("操作已完成，管理审计已记录。");
    window.clearTimeout(noticeTimer.current);
    noticeTimer.current = window.setTimeout(() => setNotice(""), 5000);
  }
  let content: ReactNode;
  if (parts[0] === "users" && parts[1])
    content = <UserDetail id={parts[1]} revision={revision} act={setAction} />;
  else if (parts[0] === "installations" && parts[1])
    content = (
      <InstallationDetail id={parts[1]} revision={revision} act={setAction} />
    );
  else if (selected === "overview")
    content = <Overview revision={revision} act={setAction} />;
  else
    content = (
      <ListPage
        key={route}
        kind={selected}
        initial={Object.fromEntries(new URLSearchParams(search))}
        revision={revision}
        act={setAction}
      />
    );
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a className="brand" href="#/overview">
          <span className="brand-mark">W</span>
          <div>
            <strong>WeKit</strong>
            <small>管理后台</small>
          </div>
        </a>
        <div className="nav-label">工作台</div>
        <nav>
          {sections.map(([id, label, icon]) => (
            <a
              key={id}
              className={selected === id ? "active" : ""}
              href={`#/${id}`}
            >
              <span>{icon}</span>
              {label}
              {selected === id && <i />}
            </a>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <span className="avatar">
            {session.data?.data.email?.slice(0, 1).toUpperCase() ?? "A"}
          </span>
          <div>
            <strong>管理员</strong>
            <small title={session.data?.data.email}>
              {session.data?.data.email ?? "正在验证身份"}
            </small>
          </div>
        </div>
      </aside>
      <main>
        <header className="topbar">
          <div className="breadcrumbs">
            WeKit <span>/</span> {title}
          </div>
          <span className="secure-dot">受保护的管理入口</span>
        </header>
        <div className="workspace">
          <div className="page-heading">
            <div>
              {parts[1] && (
                <button className="link back" onClick={() => go("users")}>
                  ← 返回用户列表
                </button>
              )}
              <h1>{title}</h1>
              <p>
                {selected === "overview"
                  ? "查看授权、安装与发行的最新状态"
                  : selected === "users"
                    ? "Telegram 绑定身份与客户端报告的微信身份分别保留"
                    : selected === "initializations"
                      ? "逐次查看身份、来源 IP、版本与实际授权结果"
                      : selected === "releases"
                        ? "控制后续初始化允许使用的业务发行"
                        : selected === "publications"
                          ? "查看构建发布任务与完成状态"
                          : "每次管理操作的对象、操作者与前后状态"}
              </p>
            </div>
            <button
              className="button secondary"
              onClick={() => setRevision((v) => v + 1)}
            >
              ↻ 刷新
            </button>
          </div>
          {notice && (
            <div className="notice" role="status">
              <span>✓ {notice}</span>
              <button aria-label="关闭提示" onClick={() => setNotice("")}>
                ×
              </button>
            </div>
          )}
          <Feedback error={session.error} busy={session.busy} />
          {session.data && content}
          <footer>
            时间以新加坡时间（UTC+8）显示。微信身份为客户端报告，来源 IP
            为边缘观测。
          </footer>
        </div>
      </main>
      {action && (
        <ActionDialog
          action={action}
          onClose={() => setAction(null)}
          onDone={completed}
        />
      )}
    </div>
  );
}
