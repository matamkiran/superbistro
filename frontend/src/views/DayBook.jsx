import { useCallback, useEffect, useState } from "react";
import { api, inr, inr0 } from "../api.js";

const PAY_ROWS = [["CASH", "Cash"], ["UPI", "UPI"], ["CARD", "Card"], ["PLATFORM", "Zomato / Swiggy (online)"], ["UNPAID", "Pay later (not received)"]];
const METHOD = { CASH: "Cash", UPI: "UPI", CARD: "Card", BANK: "Bank", CREDIT: "On credit" };
const OUT_CATS = ["Vegetables", "Milk", "Meat", "Groceries", "Gas", "Salary", "Rent", "Electricity", "Packaging", "Maintenance", "Other"];
const IN_CATS = ["Cash added", "Owner deposit", "Other income"];
const addDays = (iso, d) => { const x = new Date(iso + "T12:00:00"); x.setDate(x.getDate() + d); return x.toISOString().slice(0, 10); };
const nice = (iso) => new Date(iso + "T12:00:00").toLocaleDateString("en-IN", { weekday: "short", day: "numeric", month: "short" });
const hm = (ms) => new Date(ms).toLocaleTimeString("en-IN", { hour: "numeric", minute: "2-digit" });

export default function DayBook({ toast }) {
  const [day, setDay] = useState(null);
  const [d, setD] = useState(null);
  const [sum, setSum] = useState([]);
  const [busy, setBusy] = useState(false);
  const [f, setF] = useState({ direction: "OUT", method: "CASH", category: "Vegetables", amount: "", note: "" });
  const [opening, setOpening] = useState("");
  const [counted, setCounted] = useState("");
  const [confirm, setConfirm] = useState(null);

  const load = useCallback(async (which) => {
    const data = await api("GET", "daybook" + (which ? "?day=" + which : ""));
    setD(data);
    setDay(data.day);
    setOpening(data.drawer.opening != null ? String(data.drawer.opening) : data.drawer.suggestedOpening != null ? String(data.drawer.suggestedOpening) : "");
    setCounted(data.drawer.counted != null ? String(data.drawer.counted) : "");
  }, []);
  const loadSum = useCallback(async () => setSum(await api("GET", "daybook/summary?days=14")), []);
  useEffect(() => { load().catch((e) => toast(e.message, true)); loadSum().catch(() => {}); }, [load, loadSum, toast]);

  const run = async (fn, ok) => {
    setBusy(true);
    try {
      const r = await fn();
      if (ok) toast(ok);
      return r ?? true;
    } catch (e) {
      toast(e.message, true);
      return null;
    } finally {
      setBusy(false);
    }
  };

  if (!d) return <div className="panel empty">Loading day book…</div>;
  const isToday = d.day === d.today;
  const dr = d.drawer;
  const maxPay = Math.max(1, ...PAY_ROWS.map(([k]) => Number(d.byPayment[k] || 0)));

  const addEntry = async (e) => {
    e.preventDefault();
    const amount = parseFloat(f.amount);
    if (!(amount > 0)) return toast("Enter an amount", true);
    const r = await run(() => api("POST", "daybook/entries", { ...f, amount, day: d.day }),
      `${f.direction === "OUT" ? "Expense" : "Money in"} of ${inr(amount)} saved`);
    if (r) { setD(r); setF({ ...f, amount: "", note: "" }); loadSum(); }
  };
  const saveDrawer = async (body, msg) => {
    const r = await run(() => api("PUT", `daybook/${d.day}/drawer`, body), msg);
    if (r) { setD(r); loadSum(); }
  };
  const del = async (id) => {
    setConfirm(null);
    const ok = await run(() => api("DELETE", "daybook/entries/" + id), "Entry deleted");
    if (ok) { load(d.day); loadSum(); }
  };
  const diff = dr.difference;

  return (
    <>
      <div className="section-head">
        <div><h1>Day book</h1><p className="muted">Money in and out for one day, and the cash drawer check.</p></div>
        <div className="day-nav">
          <button className="btn" onClick={() => load(addDays(d.day, -1))} aria-label="Previous day">‹</button>
          <input className="input mono" type="date" value={day || ""} max={d.today} onChange={(e) => e.target.value && load(e.target.value)} aria-label="Day" />
          <button className="btn" onClick={() => load(addDays(d.day, 1))} disabled={isToday} aria-label="Next day">›</button>
          {!isToday && <button className="btn" onClick={() => load()}>Today</button>}
        </div>
      </div>

      <div className="kpis">
        <div className="panel kpi"><div className="label">Sales</div><div className="v">{inr0(d.sales)}</div><div className="s">{d.orders} order{d.orders === 1 ? "" : "s"}{d.cancelled ? ` · ${d.cancelled} cancelled` : ""}</div></div>
        <div className="panel kpi"><div className="label">Money received</div><div className="v">{inr0(Number(d.received) + Number(d.otherIn))}</div><div className="s">sales paid + money added</div></div>
        <div className="panel kpi"><div className="label">Paid out</div><div className="v">{inr0(d.paidOut)}</div><div className="s">{Number(d.onCredit) > 0 ? `+ ${inr0(d.onCredit)} on credit (unpaid)` : "expenses and purchases"}</div></div>
        <div className="panel kpi"><div className="label">Net for the day</div><div className={"v " + (Number(d.net) < 0 ? "badc" : "good")}>{inr0(d.net)}</div><div className="s">received − paid out</div></div>
      </div>

      <div className="daybook-grid">
        <section className="panel form">
          <h2>Sales by payment</h2>
          <ul className="paylist">
            {PAY_ROWS.map(([k, label]) => (
              <li key={k}>
                <span>{label}<small className="muted"> · {d.countByPayment[k] || 0} order{(d.countByPayment[k] || 0) === 1 ? "" : "s"}</small></span>
                <span className="mono">{inr(d.byPayment[k])}</span>
                <span className="paybar"><span className={k.toLowerCase()} style={{ width: (Number(d.byPayment[k] || 0) / maxPay) * 100 + "%" }} /></span>
              </li>
            ))}
          </ul>
          <p className="muted">By channel: Offline {inr0(d.bySource.OFFLINE)} · Zomato {inr0(d.bySource.ZOMATO)} · Swiggy {inr0(d.bySource.SWIGGY)}</p>
          {d.cancelledOrders?.length > 0 && (
            <div className="cancelled-list">
              <h3>Cancelled bills ({d.cancelledOrders.length})</h3>
              <ul>
                {d.cancelledOrders.map((c) => (
                  <li key={c.no}>
                    <span className="mono"><b>#{c.no}</b> {inr(c.total)}</span>
                    <span>{c.reason || "No reason"}</span>
                    <span className="muted">{c.by || "?"} · {new Date(c.at).toLocaleTimeString("en-IN", { hour: "numeric", minute: "2-digit" })}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </section>

        <section className="panel form drawer">
          <h2>Cash drawer</h2>
          <div className="drawer-row">
            <span>Opening cash</span>
            <span className="drawer-in">
              <input className="input mono" inputMode="decimal" value={opening} onChange={(e) => setOpening(e.target.value)} aria-label="Opening cash" placeholder="0" />
              <button className="btn sm" disabled={busy || opening === "" || Number(opening) === Number(dr.opening)}
                onClick={() => saveDrawer({ openingCash: parseFloat(opening) || 0 }, "Opening cash saved")}>Save</button>
            </span>
          </div>
          {dr.opening == null && dr.suggestedOpening != null && <p className="muted hint">Filled in from the last day's counted cash. Press Save to use it.</p>}
          <div className="drawer-row"><span>+ Cash sales</span><span className="mono">{inr(dr.cashSales)}</span></div>
          <div className="drawer-row"><span>+ Cash added</span><span className="mono">{inr(dr.cashIn)}</span></div>
          <div className="drawer-row"><span>− Cash paid out</span><span className="mono">{inr(dr.cashOut)}</span></div>
          <div className="drawer-row total"><span>Should be in drawer</span><span className="mono">{inr(dr.expected)}</span></div>
          <div className="drawer-row">
            <span>Counted at closing</span>
            <span className="drawer-in">
              <input className="input mono" inputMode="decimal" value={counted} onChange={(e) => setCounted(e.target.value)} aria-label="Counted cash" placeholder="Count the cash" />
              <button className="btn sm primary" disabled={busy || counted === ""} onClick={() => saveDrawer({ countedCash: parseFloat(counted) || 0 }, "Day closed with counted cash")}>
                {dr.counted == null ? "Close day" : "Update"}</button>
            </span>
          </div>
          {diff != null && (
            <div className={"drawer-diff " + (Math.abs(diff) < 0.5 ? "ok" : diff < 0 ? "bad" : "warn")}>
              {Math.abs(diff) < 0.5 ? "Cash matches" : diff < 0 ? `Short by ${inr(-diff)}` : `Extra ${inr(diff)} in drawer`}
            </div>
          )}
        </section>

        <section className="panel form">
          <h2>Add money in / out</h2>
          <form className="form-grid" onSubmit={addEntry}>
            <div className="seg" role="group" aria-label="Type">
              <button type="button" aria-pressed={f.direction === "OUT"} onClick={() => setF({ ...f, direction: "OUT", category: "Vegetables" })}>Expense / paid out</button>
              <button type="button" aria-pressed={f.direction === "IN"} onClick={() => setF({ ...f, direction: "IN", category: "Cash added" })}>Money in</button>
            </div>
            <div className="chips">
              {(f.direction === "OUT" ? OUT_CATS : IN_CATS).map((c) => (
                <button key={c} type="button" className="cat" aria-pressed={f.category === c} onClick={() => setF({ ...f, category: c })}>{c}</button>
              ))}
            </div>
            <div className="two">
              <div className="field"><label htmlFor="e-amt">Amount ₹</label><input className="input mono" id="e-amt" inputMode="decimal" value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} placeholder="0" /></div>
              <div className="field"><label htmlFor="e-m">{f.direction === "OUT" ? "Paid by" : "Received as"}</label>
                <select className="input" id="e-m" value={f.method} onChange={(e) => setF({ ...f, method: e.target.value })}>
                  {Object.entries(METHOD).filter(([k]) => f.direction === "OUT" || k !== "CREDIT").map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select></div>
            </div>
            <div className="field"><label htmlFor="e-note">Note</label><input className="input" id="e-note" value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} placeholder="e.g. 2 cylinders, Ramesh salary" /></div>
            <button className="btn primary" disabled={busy}>Save</button>
            <p className="muted">Store purchases are added here automatically when goods are received.</p>
          </form>
        </section>
      </div>

      <section className="panel mtable-wrap" style={{ marginTop: 16 }}>
        <div className="panel-h"><h2>Entries on {nice(d.day)}</h2>
          <span className="hint">{Object.entries(d.outByCategory).map(([k, v]) => `${k} ${inr0(v)}`).join(" · ")}</span></div>
        {d.entries.length ? (
          <table className="mtable">
            <thead><tr><th>Time</th><th>What</th><th>Paid by</th><th className="r">Amount</th><th /></tr></thead>
            <tbody>{d.entries.map((e) => (
              <tr key={e.id}>
                <td className="mono">{hm(e.createdAt)}</td>
                <td><b>{e.category}</b>{e.note && <span className="muted-cell"> · {e.note}</span>}{e.by && <div className="muted-cell">{e.by}</div>}</td>
                <td><span className={"pill " + (e.method === "CREDIT" ? "warn" : "")}>{METHOD[e.method]}</span></td>
                <td className={"mono r " + (e.direction === "OUT" ? "minus" : "plus")}>{e.direction === "OUT" ? "−" : "+"}{inr(e.amount)}</td>
                <td><div className="acts">{e.fromPurchase ? <span className="muted-cell">store purchase</span> : confirm === e.id
                  ? <><button className="btn sm danger" onClick={() => del(e.id)}>Delete</button><button className="btn sm" onClick={() => setConfirm(null)}>Keep</button></>
                  : <button className="btn sm" onClick={() => setConfirm(e.id)}>Delete</button>}</div></td>
              </tr>
            ))}</tbody>
          </table>
        ) : <div className="empty sm">No expenses or money added on this day.</div>}
      </section>

      <section className="panel mtable-wrap" style={{ marginTop: 16 }}>
        <div className="panel-h"><h2>Last 14 days</h2><span className="hint">tap a day to open it</span></div>
        <table className="mtable summary-table">
          <thead><tr><th>Day</th><th className="r">Orders</th><th className="r">Sales</th><th className="r">Cash</th><th className="r">UPI + Card</th><th className="r">Online</th><th className="r">Paid out</th><th className="r">Net</th><th>Drawer</th></tr></thead>
          <tbody>{sum.map((r) => (
            <tr key={r.day} className={"clickable" + (r.day === d.day ? " editing" : "")} onClick={() => load(r.day)}>
              <td>{nice(r.day)}</td>
              <td className="mono r">{r.orders}</td>
              <td className="mono r">{inr0(r.sales)}</td>
              <td className="mono r">{inr0(r.cash)}</td>
              <td className="mono r">{inr0(Number(r.upi) + Number(r.card))}</td>
              <td className="mono r">{inr0(r.online)}</td>
              <td className="mono r minus">{Number(r.paidOut) ? "−" + inr0(r.paidOut) : ""}</td>
              <td className={"mono r " + (Number(r.net) < 0 ? "minus" : "")}>{inr0(r.net)}</td>
              <td>{r.counted != null ? <span className="pill ok">closed</span> : ""}</td>
            </tr>
          ))}</tbody>
        </table>
      </section>
    </>
  );
}
