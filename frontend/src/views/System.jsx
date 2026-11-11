import { useCallback, useEffect, useState } from "react";
import { api } from "../api.js";

const size = (b) => (b == null ? "" : b < 1024 * 1024 ? Math.max(1, Math.round(b / 1024)) + " KB" : (b / 1024 / 1024).toFixed(1) + " MB");
const when = (ms) => new Date(ms).toLocaleString("en-IN", { day: "numeric", month: "short", hour: "numeric", minute: "2-digit" });
const TRIGGER = { MANUAL: "manual", SCHEDULED: "daily", BEFORE_RESTORE: "before restore" };
const STATUS = { OK: ["ok", "Backed up"], PARTIAL: ["warn", "Saved locally only"], FAILED: ["bad", "Failed"], RUNNING: ["", "Running…"] };

export default function System({ toast }) {
  const [d, setD] = useState(null);
  const [b, setB] = useState(null);
  const [g, setG] = useState({ clientId: "", clientSecret: "" });
  const [busy, setBusy] = useState(false);
  const [running, setRunning] = useState(false);
  const [restoring, setRestoring] = useState(false);

  const load = useCallback(async () => {
    const data = await api("GET", "system");
    setD(data);
    setB({ enabled: data.backup.enabled, time: data.backup.time, keepLocal: data.backup.keepLocal, keepDrive: data.backup.keepDrive });
    setG((x) => ({ ...x, clientId: x.clientId || data.google.clientId || "" }));
  }, []);

  useEffect(() => {
    load().catch((e) => toast(e.message, true));
    const p = new URLSearchParams(location.search);
    const msg = p.get("google");
    if (msg) {
      toast(msg === "connected" ? "Google Drive connected. Backups will be uploaded there." : msg, msg !== "connected");
      history.replaceState(null, "", "/#system");
    }
  }, [load, toast]);

  const call = async (fn, ok) => {
    setBusy(true);
    try {
      const r = await fn();
      if (ok) toast(ok);
      return r;
    } catch (e) {
      toast(e.message, true);
    } finally {
      setBusy(false);
    }
  };

  if (!d || !b) return <div className="panel empty">Loading…</div>;
  const last = d.history[0];
  const lastOk = d.history.find((h) => h.status === "OK" || h.status === "PARTIAL");

  const runNow = async () => {
    setRunning(true);
    try {
      const r = await api("POST", "system/backup/run");
      toast(r.status === "OK" ? "Backup finished" : r.status === "PARTIAL" ? "Backup saved on this computer, but not on Google Drive" : "Backup failed: " + r.message, r.status !== "OK");
      await load();
    } catch (e) {
      toast(e.message, true);
    } finally {
      setRunning(false);
    }
  };

  const restore = async (name, file) => {
    if (!confirm(`Restore the backup "${name}"?\n\nThis REPLACES ALL current data (orders, menu, store, day book, staff, settings) with the data in the backup. Everyone has to log in again.`)) return;
    setRestoring(true);
    try {
      const r = file ? await api("POST", "system/restore", file) : await api("POST", "system/restore/local/" + encodeURIComponent(name));
      alert(`Restored ${r.rows} records from the backup of ${new Date(r.backupCreatedAt).toLocaleString("en-IN")}.\n\nPlease log in again.`);
      location.href = "/";
    } catch (e) {
      toast(e.message, true);
    } finally {
      setRestoring(false);
    }
  };

  const copy = async (text) => {
    try {
      await navigator.clipboard.writeText(text);
      toast("Copied");
    } catch {
      toast("Select the address and copy it", true);
    }
  };

  return (
    <>
      <div className="section-head">
        <div>
          <h1>System</h1>
          <p className="muted">Backups for the whole installation. Only you (the main admin) see this page.</p>
        </div>
        <button className="btn primary" onClick={runNow} disabled={running}>{running ? "Backing up…" : "Back up now"}</button>
      </div>

      <div className="kpis sys-kpis">
        <div className="panel kpi">
          <div className="label">Last backup</div>
          <div className="v sm">{lastOk ? when(lastOk.startedAt) : "None yet"}</div>
          <div className="s">{lastOk ? `${size(lastOk.sizeBytes)} · ${lastOk.onDrive ? "on Google Drive" : "this computer only"}` : "Run one with Back up now"}</div>
        </div>
        <div className="panel kpi">
          <div className="label">Next backup</div>
          <div className="v sm">{d.backup.nextRun ? when(new Date(d.backup.nextRun).getTime()) : "Off"}</div>
          <div className="s">every day at {d.backup.time}</div>
        </div>
        <div className="panel kpi">
          <div className="label">Google Drive</div>
          <div className={"v sm " + (d.google.connected ? "good" : "warnc")}>{d.google.connected ? "Connected" : "Not connected"}</div>
          <div className="s">{d.google.connected ? d.google.account : "backups stay on this computer"}</div>
        </div>
        <div className="panel kpi">
          <div className="label">Database</div>
          <div className="v sm">{d.backup.embeddedDb ? "Built-in" : "PostgreSQL server"}</div>
          <div className="s">{d.backup.embeddedDb ? "kept on this computer" : "your own PostgreSQL"}</div>
        </div>
      </div>

      {last && last.status === "FAILED" && <div className="form-err sys-alert">Last backup failed: {last.message}</div>}

      <div className="menu-layout">
        <div className="side">
          <form className="panel form" onSubmit={async (e) => { e.preventDefault(); const r = await call(() => api("PUT", "system/backup", { ...b, keepLocal: +b.keepLocal, keepDrive: +b.keepDrive }), "Backup schedule saved"); if (r) setD(r); }}>
            <h2>Schedule</h2>
            <label className="check"><input type="checkbox" checked={b.enabled} onChange={(e) => setB({ ...b, enabled: e.target.checked })} /> Back up automatically every day</label>
            <div className="field"><label htmlFor="b-time">Time ({d.timeZone})</label>
              <input className="input mono" id="b-time" type="time" value={b.time} onChange={(e) => setB({ ...b, time: e.target.value })} /></div>
            <p className="muted">Pick a time after closing. If the computer is off then, the backup runs as soon as Super Bistro starts again.</p>
            <div className="two">
              <div className="field"><label htmlFor="b-kl">Keep on this PC</label>
                <input className="input mono" id="b-kl" type="number" min="1" max="365" value={b.keepLocal} onChange={(e) => setB({ ...b, keepLocal: e.target.value })} /></div>
              <div className="field"><label htmlFor="b-kd">Keep on Drive</label>
                <input className="input mono" id="b-kd" type="number" min="1" max="365" value={b.keepDrive} onChange={(e) => setB({ ...b, keepDrive: e.target.value })} /></div>
            </div>
            <p className="muted">Number of most recent backups kept. Older ones are deleted.</p>
            <button className="btn" disabled={busy}>Save schedule</button>
          </form>

          <section className="panel form">
            <h2>Google Drive</h2>
            {d.google.connected ? (
              <>
                <p>Backups go to the <b>{d.google.folderName}</b> folder in <b>{d.google.account}</b>'s Google Drive.</p>
                <p className="muted">Super Bistro can only see files it uploaded, not the rest of your Drive.</p>
                <button className="btn danger" disabled={busy} onClick={async () => { const r = await call(() => api("POST", "system/google/disconnect"), "Google Drive disconnected"); if (r) setD(r); }}>Disconnect</button>
              </>
            ) : (
              <>
                <ol className="steps">
                  <li>In <a href="https://console.cloud.google.com/apis/library/drive.googleapis.com" target="_blank" rel="noreferrer">Google Cloud Console</a>, create a project and enable the <b>Google Drive API</b>.</li>
                  <li>Under <b>Google Auth Platform</b>, set up the consent screen (External) and click <b>Publish app</b>. While it is in Testing, Google disconnects it after 7 days.</li>
                  <li>Create an <b>OAuth client</b> of type <b>Web application</b> with this redirect URI:
                    <div className="copy-row"><code>{d.google.redirectUri}</code><button type="button" className="btn sm" onClick={() => copy(d.google.redirectUri)}>Copy</button></div>
                  </li>
                  <li>Paste the client ID and secret below, then press Connect.</li>
                </ol>
                <form className="form-grid" onSubmit={async (e) => { e.preventDefault(); const r = await call(() => api("PUT", "system/google/client", g), "Saved. Now press Connect Google Drive."); if (r) setD(r); }}>
                  <div className="field"><label htmlFor="g-id">Client ID</label>
                    <input className="input mono" id="g-id" value={g.clientId} onChange={(e) => setG({ ...g, clientId: e.target.value })} placeholder="1234-abc.apps.googleusercontent.com" /></div>
                  <div className="field"><label htmlFor="g-secret">Client secret</label>
                    <input className="input mono" id="g-secret" type="password" value={g.clientSecret} onChange={(e) => setG({ ...g, clientSecret: e.target.value })} placeholder={d.google.configured ? "Saved (enter to replace)" : "GOCSPX-…"} /></div>
                  <button className="btn" disabled={busy || !g.clientId || !g.clientSecret}>Save client</button>
                </form>
                <button className="btn primary" disabled={busy || !d.google.configured}
                  onClick={async () => { const r = await call(() => api("GET", "system/google/connect")); if (r) location.href = r.url; }}>
                  Connect Google Drive
                </button>
                <p className="muted">Open this page on the server computer itself (http://localhost) when connecting: Google only accepts the exact address above.</p>
              </>
            )}
          </section>

          <ReportEmail toast={toast} />
        </div>

        <section className="panel mtable-wrap">
          <div className="panel-h"><h2>Backup history</h2><span className="hint">kept in {d.backup.folder}</span></div>
          {d.history.length ? (
            <table className="mtable">
              <thead><tr><th>When</th><th>Result</th><th className="r">Size</th><th>Where</th><th /></tr></thead>
              <tbody>
                {d.history.map((h) => {
                  const [cls, label] = STATUS[h.status] || ["", h.status];
                  return (
                    <tr key={h.id} title={h.message || ""}>
                      <td className="mono">{when(h.startedAt)}<div className="muted-cell">{TRIGGER[h.trigger] || "daily"}</div></td>
                      <td><span className={"pill " + cls}>{label}</span>{h.status !== "OK" && h.message && <div className="muted-cell clip">{h.message}</div>}</td>
                      <td className="mono r">{size(h.sizeBytes)}</td>
                      <td className="muted-cell">{h.fileName ? (h.onDrive ? "PC + Drive" : "PC") : ""}</td>
                      <td className="row-actions">
                        {h.fileName && <a className="btn sm" href={"/api/system/backup/file/" + h.fileName}>Download</a>}
                        {h.fileName && h.fileName.endsWith(".zip") && <button className="btn sm" disabled={restoring} onClick={() => restore(h.fileName, null)}>Restore</button>}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          ) : <div className="empty">No backups yet. Press <b>Back up now</b> to make the first one.</div>}
          <div className="restore">
            <h2>Restore a backup</h2>
            <p className="muted">Puts back all orders, menu, store, day book, staff and settings exactly as they were in the backup.
              Use it on a new computer, or to undo a mistake. <b>Everything entered after that backup is replaced.</b> A safety backup of the current data is made first.</p>
            <p className="muted">Pick a backup above (<b>Restore</b>), or a <code>.zip</code> backup file from Google Drive, a USB stick or another PC:</p>
            <label className="btn">
              {restoring ? "Restoring…" : "Choose backup file…"}
              <input type="file" accept=".zip,application/zip" hidden disabled={restoring}
                onChange={(e) => { const f = e.target.files[0]; e.target.value = ""; if (f) restore(f.name, f); }} />
            </label>
            <p className="muted">Old <code>.dump</code> backups (from before this version) restore with PostgreSQL's <code>pg_restore</code>, see the README.</p>
          </div>
        </section>
      </div>
    </>
  );
}

/** Daily and monthly report emails: who gets them, when, and the account they are sent from. */
function ReportEmail({ toast }) {
  const [r, setR] = useState(null);
  const [f, setF] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api("GET", "system/report").then((x) => { setR(x); setF({ ...x, password: "" }); }).catch((e) => toast(e.message, true));
  }, [toast]);

  if (!r || !f) return null;
  const set = (k) => (e) => setF({ ...f, [k]: e.target.type === "checkbox" ? e.target.checked : e.target.value });

  const save = async (e) => {
    e?.preventDefault();
    setBusy(true);
    try {
      const x = await api("PUT", "system/report", { to: f.to, enabled: f.enabled, time: f.time, host: f.host, port: String(f.port), user: f.user, password: f.password });
      setR(x);
      setF({ ...x, password: "" });
      toast("Email settings saved");
      return x;
    } catch (ex) {
      toast(ex.message, true);
      return null;
    } finally {
      setBusy(false);
    }
  };

  const send = async (kind) => {
    const saved = await save();
    if (!saved) return;
    setBusy(true);
    try {
      const res = await api("POST", "system/report/send?kind=" + kind);
      toast(kind === "test" ? "Test email sent. Check the inbox (and Spam)." : "Today's report sent");
      setR({ ...saved, lastResult: res.result });
    } catch (ex) {
      toast(ex.message, true);
      setR(await api("GET", "system/report"));
    } finally {
      setBusy(false);
    }
  };

  const failed = r.lastResult.startsWith("FAILED");
  return (
    <form className="panel form" onSubmit={save}>
      <h2>Email reports</h2>
      <p className="muted">Every night: sales, payments, Zomato/Swiggy, top dishes, cancelled bills, cash drawer, expenses, stock to buy, with the day's orders attached (opens in Excel). On the 1st: last month's report. If the PC was off, the missed report is sent when Super Bistro starts.</p>
      <div className="field"><label htmlFor="r-to">Send reports to</label>
        <input className="input" id="r-to" value={f.to} onChange={set("to")} placeholder="owner@gmail.com (several: separate with commas)" /></div>
      <div className="two">
        <label className="check"><input type="checkbox" checked={f.enabled} onChange={set("enabled")} /> Send every day</label>
        <div className="field"><label htmlFor="r-time">at</label>
          <input className="input mono" id="r-time" type="time" value={f.time} onChange={set("time")} /></div>
      </div>
      <details open={!r.configured}>
        <summary>Sending account {r.configured ? "(set)" : "(needed once)"}</summary>
        <p className="muted">Use a Gmail account with an <b>App password</b>: Google Account → Security → turn on 2-Step Verification → <a href="https://myaccount.google.com/apppasswords" target="_blank" rel="noreferrer">App passwords</a> → create one named "Super Bistro" and paste the 16 letters here. It can be the same Gmail that receives the reports.</p>
        <div className="field"><label htmlFor="r-user">Gmail address (sends the reports)</label>
          <input className="input" id="r-user" value={f.user} onChange={set("user")} placeholder="yourname@gmail.com" autoComplete="off" /></div>
        <div className="field"><label htmlFor="r-pass">App password</label>
          <input className="input mono" id="r-pass" type="password" value={f.password} onChange={set("password")} autoComplete="new-password"
            placeholder={r.hasPassword ? "Saved (enter to replace)" : "abcd efgh ijkl mnop"} /></div>
        <div className="two">
          <div className="field"><label htmlFor="r-host">Mail server</label>
            <input className="input mono" id="r-host" value={f.host} onChange={set("host")} /></div>
          <div className="field"><label htmlFor="r-port">Port</label>
            <input className="input mono" id="r-port" value={f.port} onChange={set("port")} inputMode="numeric" /></div>
        </div>
      </details>
      {r.lastResult && <p className={failed ? "form-err" : "muted"}>{failed ? "Last email failed: " : "Last email: "}{r.lastResult.replace(/^(OK|FAILED) /, "")}</p>}
      <div className="two">
        <button className="btn" disabled={busy}>Save</button>
        <button type="button" className="btn" disabled={busy || !f.to} onClick={() => send("test")}>Send test email</button>
      </div>
      <button type="button" className="btn primary" disabled={busy || !f.to} onClick={() => send("today")}>Email today's report now</button>
      <p className="muted"><a href="/api/system/report/preview" target="_blank" rel="noreferrer">See today's report</a> · <a href={"/api/system/report/preview?month=" + new Date(new Date().getFullYear(), new Date().getMonth() - 1, 1).toISOString().slice(0, 7)} target="_blank" rel="noreferrer">last month's</a></p>
    </form>
  );
}
