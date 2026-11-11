import { useEffect, useState } from "react";
import Invoice from "./Invoice.jsx";
import { api, DAY, hhmm, inr, inr0, NEXT_LABEL, NEXT_STATUS, PAYMENTS, SOURCES, STATUS_LABEL, TYPES, startOfDay, srcClass } from "../api.js";

const LATE_MIN = 20;

function useMinuteTick() {
  const [, set] = useState(0);
  useEffect(() => {
    const id = setInterval(() => set((n) => n + 1), 30000);
    return () => clearInterval(id);
  }, []);
}

function where(o) {
  if (o.source !== "OFFLINE") return o.platformId ? "ID " + o.platformId : "No platform ID";
  if (o.type === "DINE_IN") return o.table ? "Table " + o.table : "Dine-in";
  return TYPES[o.type];
}
const itemsText = (o) => o.items.map((i) => `${i.qty}× ${i.name}`).join(", ");
const REASONS = ["Customer left", "Wrong order", "Dish not available", "Duplicate order", "Test order"];

export default function Orders({ orders, setOrders, toast, session, canStore, setAddTo, printKot, go }) {
  const [day, setDay] = useState("today");
  const [source, setSource] = useState("ALL");
  const [confirm, setConfirm] = useState(null); // { id, reason } while choosing why to cancel
  const [bill, setBill] = useState(null);
  useMinuteTick();

  const today = startOfDay(Date.now());
  const list = orders.filter((o) => {
    if (day === "today" && o.createdAt < today) return false;
    if (day === "yesterday" && (o.createdAt < today - DAY || o.createdAt >= today)) return false;
    return source === "ALL" || o.source === source;
  });
  const col = (s) => list.filter((o) => o.status === s).sort((a, b) => a.createdAt - b.createdAt);
  const done = list.filter((o) => o.status === "COMPLETED" || o.status === "CANCELLED").sort((a, b) => b.createdAt - a.createdAt);
  const billed = list.filter((o) => o.status !== "CANCELLED").reduce((s, o) => s + o.total, 0);

  const update = async (o, patch, msg) => {
    const before = o;
    setOrders((all) => all.map((x) => (x.id === o.id ? { ...x, ...patch } : x)));
    try {
      const saved = await api("PATCH", "orders/" + o.id, patch);
      setOrders((all) => all.map((x) => (x.id === o.id ? saved : x)));
      if (msg) toast(msg);
    } catch (e) {
      setOrders((all) => all.map((x) => (x.id === o.id ? before : x)));
      toast(e.message, true);
    }
  };

  const kot = (o) => {
    const age = Math.max(0, Math.floor((Date.now() - o.createdAt) / 60000));
    return (
      <article key={o.id} className={"kot " + srcClass(o.source)}>
        <div className="kot-top"><span className="kot-no">#{o.no}</span><span className={"chip " + srcClass(o.source)}>{SOURCES[o.source]}</span></div>
        <div className="kot-meta">
          <span>{where(o)}</span>{o.customer && <span>{o.customer}</span>}{o.address && <span>{o.address}</span>}{o.phone && <span className="mono">{o.phone}</span>}<span>{hhmm(o.createdAt)}</span>
          <span className={"age" + (age >= LATE_MIN ? " late" : "")}>{age} min</span>
        </div>
        <ul className="kot-items">{o.items.map((i, n) => (
          <li key={n} className={i.round > 1 ? "added" : undefined}><b>{i.qty}×</b>{i.name}
            {i.round > 1 && <span className="round-tag">added</span>}
            {i.note && <span className="kot-note-line">↳ {i.note}</span>}</li>
        ))}</ul>
        {o.note && <div className="kot-note">{o.note}</div>}
        <div className="kot-foot">
          <span className="kot-total">{inr(o.total)} <small>{PAYMENTS[o.payment]}</small></span>
          <div className="kot-actions">
            {canStore && <button className="btn sm" onClick={() => setConfirm({ id: o.id, reason: "" })} aria-label={`Cancel order ${o.no}`}>Cancel</button>}
            <button className="btn sm" onClick={() => printKot(o, null)} title="Print the kitchen slip again">KOT</button>
            <button className="btn sm" onClick={() => { setAddTo(o); go("pos"); }} title="Add more dishes to this order (running bill)">Add items</button>
            <button className="btn sm" onClick={() => setBill(o)}>Bill</button>
            <button className="btn sm primary" onClick={() => update(o, { status: NEXT_STATUS[o.status] }, o.status === "READY" ? `Order #${o.no} completed` : null)}>
              {NEXT_LABEL[o.status]}
            </button>
          </div>
        </div>
        {confirm?.id === o.id && (
          <div className="cancel-box">
            <span className="label">Why cancel order #{o.no}?</span>
            <div className="reasons">
              {REASONS.map((r) => <button key={r} className="btn sm" aria-pressed={confirm.reason === r} onClick={() => setConfirm({ ...confirm, reason: r })}>{r}</button>)}
            </div>
            <input className="input" value={REASONS.includes(confirm.reason) ? "" : confirm.reason} placeholder="Other reason…"
              onChange={(e) => setConfirm({ ...confirm, reason: e.target.value })} maxLength={200} />
            <div className="reasons">
              <button className="btn sm danger" disabled={!confirm.reason.trim()}
                onClick={() => { const reason = confirm.reason.trim(); setConfirm(null); update(o, { status: "CANCELLED", reason }, `Order #${o.no} cancelled`); }}>Cancel order</button>
              <button className="btn sm" onClick={() => setConfirm(null)}>Keep order</button>
            </div>
          </div>
        )}
        {o.payment === "UNPAID" && (
          <div className="kot-pay">
            <span className="label">Collect payment:</span>
            {["CASH", "UPI", "CARD"].map((p) => <button key={p} className="btn sm" onClick={() => update(o, { payment: p }, `#${o.no} paid by ${PAYMENTS[p]}`)}>{PAYMENTS[p]}</button>)}
          </div>
        )}
      </article>
    );
  };

  return (
    <>
      <div className="section-head">
        <h1>Orders</h1>
        <div className="filters">
          <div className="seg" role="group" aria-label="Day">
            {[["today", "Today"], ["yesterday", "Yesterday"]].map(([k, v]) => <button key={k} aria-pressed={day === k} onClick={() => setDay(k)}>{v}</button>)}
          </div>
          <div className="seg src" role="group" aria-label="Source">
            {[["ALL", "All"], ...Object.entries(SOURCES)].map(([k, v]) => <button key={k} data-src={k} aria-pressed={source === k} onClick={() => setSource(k)}>{v}</button>)}
          </div>
        </div>
      </div>
      <p className="label summary">{list.length} order{list.length === 1 ? "" : "s"} · {inr0(billed)} billed{day === "today" ? " · updates every 5 seconds" : ""}</p>

      <div className="board">
        {["NEW", "PREPARING", "READY"].map((s) => {
          const items = col(s);
          return (
            <section className="col" key={s} aria-label={STATUS_LABEL[s]}>
              <div className="col-head"><h3>{STATUS_LABEL[s]}</h3><span className="n">{items.length}</span></div>
              {items.length ? items.map(kot) : <div className="empty sm">None</div>}
            </section>
          );
        })}
        <section className="col" aria-label="Done">
          <div className="col-head"><h3>Done</h3><span className="n">{done.length}</span></div>
          {done.length ? (
            <div className="done-list">
              {done.map((o) => (
                <div key={o.id} className={"done-row " + o.status.toLowerCase()}>
                  <span className="mono"><b>#{o.no}</b> {hhmm(o.createdAt)}</span>
                  <span className="items" title={itemsText(o)}>{itemsText(o)}</span>
                  <span className={"chip " + srcClass(o.source)}>{SOURCES[o.source]}</span>
                  <span className="mono" title={o.cancelReason || undefined}>{o.status === "CANCELLED" ? "Cancelled" + (o.cancelReason ? " · " + o.cancelReason : "") : inr(o.total)}</span>
                  {o.status !== "CANCELLED" && <button className="btn sm" onClick={() => setBill(o)}>Bill</button>}
                </div>
              ))}
            </div>
          ) : <div className="empty sm">Completed orders are listed here.</div>}
        </section>
      </div>
      {bill && <Invoice order={bill} restaurant={session.restaurant} onClose={() => setBill(null)} toast={toast} />}
    </>
  );
}
