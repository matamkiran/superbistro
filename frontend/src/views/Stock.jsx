import { useCallback, useEffect, useMemo, useState } from "react";
import { api, inr, inr0 } from "../api.js";

const UNITS = ["kg", "g", "L", "ml", "pcs", "pack", "dozen", "box", "bottle", "tray", "bag"];
const PAY = { CASH: "Cash", UPI: "UPI", CARD: "Card", BANK: "Bank transfer", CREDIT: "On credit (pay later)" };
const ACTIONS = { USE: "Used", WASTE: "Wasted", COUNT: "Counted", IN: "Added" };
const n = (v) => (v === "" || v == null ? NaN : Number(v));
const fmt = (q) => Number(q).toLocaleString("en-IN", { maximumFractionDigits: 3 });
const when = (ms) => new Date(ms).toLocaleString("en-IN", { day: "numeric", month: "short", hour: "numeric", minute: "2-digit" });

/** Message after a stock change, saying whether the item is now on the To buy list. */
function stockMsg(before, after) {
  if (!after) return "Saved";
  const qty = `${after.name}: now ${fmt(after.currentQty)} ${after.unit}`;
  const wasLow = before && before.status !== "OK";
  const isLow = after.status !== "OK";
  if (isLow && !wasLow) return `${qty}. Added to To buy.`;
  if (!isLow && wasLow) return `${qty}. Removed from To buy.`;
  if (isLow) return `${qty}. Still on To buy.`;
  return qty;
}

function parseStock(text) {
  const sep = text.includes("\t") ? "\t" : ",";
  const rows = text.trim().split(/\r?\n/).map((r) => r.split(sep).map((c) => c.trim().replace(/^"|"$/g, "")));
  if (!rows.length) return [];
  const head = rows[0].map((h) => h.toLowerCase());
  const has = head.some((h) => h.includes("name") || h.includes("item"));
  const idx = (...k) => head.findIndex((h) => k.some((x) => h.includes(x)));
  const I = has ? { name: idx("name", "item"), unit: idx("unit"), cat: idx("categ", "type"), cur: idx("current", "stock", "have", "qty"),
    min: idx("min"), re: idx("reorder", "buy"), cost: idx("cost", "price", "rate") } : { name: 0, unit: 1, cat: 2, cur: 3, min: 4, re: 5, cost: 6 };
  return (has ? rows.slice(1) : rows).filter((r) => r[I.name]).map((r) => ({
    name: r[I.name], unit: (I.unit >= 0 && r[I.unit]) || "pcs", category: (I.cat >= 0 && r[I.cat]) || "Other",
    currentQty: I.cur >= 0 && r[I.cur] !== "" ? n(r[I.cur]) || 0 : undefined,
    minQty: I.min >= 0 ? n(r[I.min]) || 0 : 0, reorderQty: I.re >= 0 ? n(r[I.re]) || 0 : 0,
    lastCost: I.cost >= 0 && r[I.cost] ? n(String(r[I.cost]).replace(/[₹,]/g, "")) || undefined : undefined,
  }));
}

export default function Stock({ toast }) {
  const [tab, setTab] = useState("have");
  const [items, setItems] = useState(null);
  const [orders, setOrders] = useState([]);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const [s, p] = await Promise.all([api("GET", "stock"), api("GET", "purchases")]);
    setItems(s);
    setOrders(p);
  }, []);
  useEffect(() => { load().catch((e) => toast(e.message, true)); }, [load, toast]);

  const run = async (fn, ok) => {
    setBusy(true);
    try {
      const r = await fn();
      if (ok) toast(typeof ok === "function" ? ok(r) : ok);
      return r ?? true;
    } catch (e) {
      toast(e.message, true);
      return null;
    } finally {
      setBusy(false);
    }
  };

  if (!items) return <div className="panel empty">Loading store…</div>;
  const low = items.filter((i) => i.status !== "OK");
  const open = orders.filter((o) => o.status === "ORDERED");
  const value = items.reduce((s, i) => s + (i.lastCost ? i.currentQty * i.lastCost : 0), 0);
  const ctx = { items, setItems, orders, load, run, busy, toast };

  return (
    <>
      <div className="section-head">
        <div>
          <h1>Store</h1>
          <p className="muted">{items.length} items · stock worth about {inr0(value)}</p>
        </div>
        <div className="seg big-seg" role="tablist" aria-label="Store">
          <button aria-pressed={tab === "have"} onClick={() => setTab("have")}>In stock</button>
          <button aria-pressed={tab === "buy"} onClick={() => setTab("buy")}>To buy {low.length > 0 && <span className="count warnbg">{low.length}</span>}</button>
          <button aria-pressed={tab === "bring"} onClick={() => setTab("bring")}>To bring in {open.length > 0 && <span className="count">{open.length}</span>}</button>
        </div>
      </div>
      {tab === "have" && <Have {...ctx} />}
      {tab === "buy" && <Buy {...ctx} low={low} onDone={() => setTab("bring")} />}
      {tab === "bring" && <Bring {...ctx} />}
    </>
  );
}

// ---------------------------------------------------------------- In stock
function Have({ items, setItems, run, busy, toast }) {
  const [q, setQ] = useState("");
  const [act, setAct] = useState(null); // { id, type, qty, note }
  const [hist, setHist] = useState(null); // { id, moves }
  const [adding, setAdding] = useState(false);
  const [importing, setImporting] = useState(false);
  const [paste, setPaste] = useState("");
  const [edit, setEdit] = useState(null);
  const blank = { name: "", unit: "kg", category: "", currentQty: "", minQty: "", reorderQty: "", lastCost: "" };
  const [f, setF] = useState(blank);

  const needle = q.trim().toLowerCase();
  const shown = items.filter((i) => !needle || i.name.toLowerCase().includes(needle) || i.category.toLowerCase().includes(needle));
  const cats = [...new Set(items.map((i) => i.category))];
  const parsed = paste.trim() ? parseStock(paste) : [];

  const body = (x) => ({
    name: x.name, unit: x.unit, category: x.category || "Other",
    currentQty: n(x.currentQty) >= 0 ? n(x.currentQty) : undefined, minQty: n(x.minQty) || 0, reorderQty: n(x.reorderQty) || 0,
    lastCost: n(x.lastCost) >= 0 ? n(x.lastCost) : undefined,
  });

  const add = async (e) => {
    e.preventDefault();
    if (!f.name.trim()) return toast("Enter the item name", true);
    const r = await run(() => api("POST", "stock", body(f)), `Added ${f.name.trim()}`);
    if (r) { setItems(r); setF({ ...blank, unit: f.unit, category: f.category }); document.getElementById("s-name")?.focus(); }
  };
  const saveEdit = async () => {
    const before = items.find((x) => x.id === edit.id);
    const have = n(edit.currentQty);
    if (!(have >= 0)) return toast("Enter how much you have (0 or more)", true);
    let r = await run(() => api("PUT", "stock/" + edit.id, body({ ...edit, currentQty: undefined })));
    if (r && have !== Number(before.currentQty)) {
      r = await run(() => api("POST", `stock/${edit.id}/move`, { type: "COUNT", qty: have, note: "Updated in Edit" }));
    }
    if (r) { setItems(r); setEdit(null); toast(stockMsg(before, r.find((x) => x.id === edit.id))); }
  };
  const doMove = async () => {
    const qty = n(act.qty);
    if (!(qty >= 0)) return toast("Enter a quantity", true);
    const item = items.find((i) => i.id === act.id);
    const r = await run(() => api("POST", `stock/${act.id}/move`, { type: act.type, qty, note: act.note }));
    if (r) { setItems(r); setAct(null); toast(stockMsg(item, r.find((x) => x.id === item.id))); }
  };
  const showHist = async (i) => {
    if (hist?.id === i.id) return setHist(null);
    const moves = await run(() => api("GET", `stock/${i.id}/moves`));
    if (moves) setHist({ id: i.id, moves });
  };
  const doImport = async () => {
    const r = await run(() => api("POST", "stock/import", { items: parsed.map(body) }), (x) => `Store list imported: ${x.added} added, ${x.updated} updated`);
    if (r) { setItems(r.items); setPaste(""); setImporting(false); }
  };

  return (
    <>
      <div className="admin-bar">
        <input className="input" type="search" placeholder="Search store items…" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search store items" />
        <button className="btn" aria-pressed={importing} onClick={() => { setImporting(!importing); setAdding(false); }}>Import</button>
        <button className="btn primary" onClick={() => { setAdding(!adding); setImporting(false); setTimeout(() => document.getElementById("s-name")?.focus(), 30); }}>{adding ? "Close" : "Add item"}</button>
      </div>

      {adding && (
        <form className="panel admin-add" onSubmit={add} autoComplete="off">
          <div className="field grow"><label htmlFor="s-name">Item</label><input className="input" id="s-name" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} placeholder="e.g. Basmati rice" /></div>
          <div className="field sm"><label htmlFor="s-unit">Unit</label><input className="input" id="s-unit" list="unit-list" value={f.unit} onChange={(e) => setF({ ...f, unit: e.target.value })} /></div>
          <div className="field"><label htmlFor="s-cat">Group</label><input className="input" id="s-cat" list="scat-list" value={f.category} onChange={(e) => setF({ ...f, category: e.target.value })} placeholder="Grains" /></div>
          <div className="field sm"><label htmlFor="s-cur">Have now</label><input className="input mono" id="s-cur" inputMode="decimal" value={f.currentQty} onChange={(e) => setF({ ...f, currentQty: e.target.value })} placeholder="0" /></div>
          <div className="field sm"><label htmlFor="s-min">Minimum</label><input className="input mono" id="s-min" inputMode="decimal" value={f.minQty} onChange={(e) => setF({ ...f, minQty: e.target.value })} placeholder="5" /></div>
          <div className="field sm"><label htmlFor="s-re">Usual buy</label><input className="input mono" id="s-re" inputMode="decimal" value={f.reorderQty} onChange={(e) => setF({ ...f, reorderQty: e.target.value })} placeholder="20" /></div>
          <div className="field sm"><label htmlFor="s-cost">Cost / unit ₹</label><input className="input mono" id="s-cost" inputMode="decimal" value={f.lastCost} onChange={(e) => setF({ ...f, lastCost: e.target.value })} placeholder="60" /></div>
          <button className="btn primary" disabled={busy}>Add</button>
        </form>
      )}
      <datalist id="unit-list">{UNITS.map((u) => <option key={u} value={u} />)}</datalist>
      <datalist id="scat-list">{cats.map((c) => <option key={c} value={c} />)}</datalist>

      {importing && (
        <section className="panel form import-panel">
          <h2>Import store list</h2>
          <p className="muted">Columns: <b>Name, Unit, Group, Have now, Minimum, Usual buy, Cost</b>. Paste from Excel or Google Sheets. Same-name items are updated and their stock set to "Have now".</p>
          <textarea className="input mono" rows={6} value={paste} onChange={(e) => setPaste(e.target.value)} aria-label="Store list to import"
            placeholder={"Name\tUnit\tGroup\tHave now\tMinimum\tUsual buy\tCost"} />
          {parsed.length > 0 && <div className="import-check"><b>{parsed.length} items ready</b></div>}
          <div><button className="btn primary" disabled={!parsed.length || busy} onClick={doImport}>{parsed.length ? `Import ${parsed.length} items` : "Import"}</button></div>
        </section>
      )}

      {items.length > 0 && <p className="muted hint-line stock-hint">Tap a quantity to change it. Anything at 0 or below its minimum goes on the <b>To buy</b> list straight away.</p>}
      <section className="panel mtable-wrap">
        {!items.length ? (
          <div className="empty"><h3>No store items yet</h3><p>Add what you keep in the store room (rice, oil, chicken, milk…) with a minimum level, or import a list from Excel. You'll then see what is running low.</p></div>
        ) : (
          <table className="mtable admin stock-table">
            <thead><tr><th>Item</th><th className="r">Have</th><th className="r">Minimum</th><th>Status</th><th /></tr></thead>
            <tbody>
              {shown.map((i) => [
                edit?.id === i.id ? (
                  <tr key={i.id} className="editing">
                    <td colSpan={5}>
                      <div className="admin-add inline">
                        <div className="field grow"><label>Item</label><input className="input" value={edit.name} onChange={(e) => setEdit({ ...edit, name: e.target.value })} /></div>
                        <div className="field sm"><label>Have now</label><input className="input mono" inputMode="decimal" value={edit.currentQty} onChange={(e) => setEdit({ ...edit, currentQty: e.target.value })} /></div>
                        <div className="field sm"><label>Unit</label><input className="input" list="unit-list" value={edit.unit} onChange={(e) => setEdit({ ...edit, unit: e.target.value })} /></div>
                        <div className="field"><label>Group</label><input className="input" list="scat-list" value={edit.category} onChange={(e) => setEdit({ ...edit, category: e.target.value })} /></div>
                        <div className="field sm"><label>Minimum</label><input className="input mono" value={edit.minQty} onChange={(e) => setEdit({ ...edit, minQty: e.target.value })} /></div>
                        <div className="field sm"><label>Usual buy</label><input className="input mono" value={edit.reorderQty} onChange={(e) => setEdit({ ...edit, reorderQty: e.target.value })} /></div>
                        <div className="field sm"><label>Cost ₹</label><input className="input mono" value={edit.lastCost ?? ""} onChange={(e) => setEdit({ ...edit, lastCost: e.target.value })} /></div>
                        <button className="btn primary" onClick={saveEdit} disabled={busy}>Save</button>
                        <button className="btn" onClick={() => setEdit(null)}>Cancel</button>
                        <button className="btn danger" onClick={async () => { const ok = await run(() => api("DELETE", "stock/" + i.id), `Removed ${i.name}`); if (ok) setItems(items.filter((x) => x.id !== i.id)); }}>Remove</button>
                      </div>
                    </td>
                  </tr>
                ) : (
                  <tr key={i.id}>
                    <td><b>{i.name}</b><div className="muted-cell">{i.category}{i.lastCost ? ` · ${inr(i.lastCost)}/${i.unit}` : ""}</div></td>
                    <td className="r">
                      <button className="qty-edit mono big-num" title="Change how much you have"
                        onClick={() => setAct({ id: i.id, type: "COUNT", qty: String(i.currentQty), note: "" })}>
                        {fmt(i.currentQty)} <small>{i.unit}</small> <span aria-hidden="true">✎</span>
                      </button>
                    </td>
                    <td className="mono r muted-cell">{i.minQty > 0 ? `${fmt(i.minQty)} ${i.unit}` : "—"}</td>
                    <td><span className={"pill " + (i.status === "OUT" ? "bad" : i.status === "LOW" ? "warn" : "ok")}>{i.status === "OUT" ? "Out" : i.status === "LOW" ? "Low" : "OK"}</span>
                      {i.onOrderQty > 0 && <div className="muted-cell">{fmt(i.onOrderQty)} on order</div>}</td>
                    <td><div className="acts">
                      {["USE", "WASTE", "COUNT", "IN"].map((t) => (
                        <button key={t} className="btn sm" aria-pressed={act?.id === i.id && act.type === t}
                          onClick={() => setAct(act?.id === i.id && act.type === t ? null : { id: i.id, type: t, qty: t === "COUNT" ? String(i.currentQty) : "", note: "" })}>
                          {t === "USE" ? "Used" : t === "WASTE" ? "Waste" : t === "COUNT" ? "Count" : "+ Add"}
                        </button>
                      ))}
                      <button className="btn sm" onClick={() => showHist(i)} aria-pressed={hist?.id === i.id}>History</button>
                      <button className="btn sm" onClick={() => setEdit({ ...i, lastCost: i.lastCost ?? "" })}>Edit</button>
                    </div></td>
                  </tr>
                ),
                act?.id === i.id && (
                  <tr key={i.id + "-act"} className="act-row">
                    <td colSpan={5}>
                      <div className="act-form">
                        <span>{act.type === "COUNT" ? `${i.name}: how much do you have now?` : act.type === "IN" ? `Add ${i.name} (no purchase list):` : `${act.type === "USE" ? "Used" : "Wasted"} how much ${i.name}?`}</span>
                        <input className="input mono" autoFocus onFocus={(e) => e.target.select()} inputMode="decimal" value={act.qty} onChange={(e) => setAct({ ...act, qty: e.target.value })}
                          onKeyDown={(e) => { if (e.key === "Enter") doMove(); if (e.key === "Escape") setAct(null); }} aria-label="Quantity" />
                        <span className="muted">{i.unit}</span>
                        <input className="input" value={act.note} placeholder="Note (optional)" onChange={(e) => setAct({ ...act, note: e.target.value })}
                          onKeyDown={(e) => { if (e.key === "Enter") doMove(); }} aria-label="Note" />
                        <button className="btn primary" onClick={doMove} disabled={busy}>Save</button>
                        <button className="btn" onClick={() => setAct(null)}>Cancel</button>
                      </div>
                    </td>
                  </tr>
                ),
                hist?.id === i.id && (
                  <tr key={i.id + "-hist"} className="act-row">
                    <td colSpan={5}>
                      {hist.moves.length ? (
                        <ul className="hist">
                          {hist.moves.map((m, k) => (
                            <li key={k}><span className="mono">{when(m.createdAt)}</span>
                              <span>{ACTIONS[m.type] || m.type}</span>
                              <span className={"mono " + (m.qtyChange < 0 ? "minus" : "plus")}>{m.qtyChange > 0 ? "+" : ""}{fmt(m.qtyChange)} {i.unit}</span>
                              <span className="mono muted-cell">→ {fmt(m.qtyAfter)}</span>
                              <span className="muted-cell">{[m.note, m.by].filter(Boolean).join(" · ")}</span></li>
                          ))}
                        </ul>
                      ) : <span className="muted">No changes yet.</span>}
                    </td>
                  </tr>
                ),
              ])}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}

// ---------------------------------------------------------------- To buy
function Buy({ items, low, run, busy, load, toast, onDone }) {
  const [sel, setSel] = useState(() => Object.fromEntries(low.map((i) => [i.id, String(i.suggestQty || i.minQty || 1)])));
  const [supplier, setSupplier] = useState("");
  const [extra, setExtra] = useState("");
  const others = items.filter((i) => i.status === "OK" && !(i.id in sel));
  const chosen = items.filter((i) => i.id in sel);
  const est = chosen.reduce((s, i) => s + (i.lastCost ? (n(sel[i.id]) || 0) * i.lastCost : 0), 0);

  const listText = useMemo(() => [
    `Purchase list${supplier ? " for " + supplier : ""} (${new Date().toLocaleDateString("en-IN")})`,
    ...chosen.map((i) => `• ${i.name}: ${sel[i.id]} ${i.unit}`),
  ].join("\n"), [chosen, sel, supplier]);

  const save = async (receiveNow) => {
    const lines = chosen.map((i) => ({ stockItemId: i.id, qty: n(sel[i.id]), unitCost: i.lastCost ?? undefined })).filter((l) => l.qty > 0);
    if (!lines.length) return toast("Tick at least one item with a quantity", true);
    const r = await run(() => api("POST", "purchases", { supplier, lines }), `Saved: ${lines.length} item${lines.length === 1 ? "" : "s"} to bring in`);
    if (r) { await load(); onDone(); }
  };
  const copy = async () => {
    try { await navigator.clipboard.writeText(listText); toast("List copied. Paste it in WhatsApp to the supplier."); }
    catch { toast("Couldn't copy. Select the list text and copy it.", true); }
  };

  return (
    <div className="menu-layout">
      <section className="panel form">
        <h2>What to buy</h2>
        {!items.length ? <p className="muted">Add store items first (In stock tab).</p> : (
          <>
            <p className="muted">Items below their minimum are ticked with a suggested quantity. Change the numbers, add anything else, then save the list.</p>
            <ul className="buy-list">
              {chosen.map((i) => (
                <li key={i.id}>
                  <label className="check"><input type="checkbox" checked onChange={() => { const c = { ...sel }; delete c[i.id]; setSel(c); }} />
                    <span><b>{i.name}</b> <span className={"pill " + (i.status === "OUT" ? "bad" : i.status === "LOW" ? "warn" : "")}>{i.status === "OK" ? "added" : `have ${fmt(i.currentQty)} ${i.unit}`}</span></span></label>
                  <span className="buy-qty"><input className="input mono" inputMode="decimal" value={sel[i.id]} onChange={(e) => setSel({ ...sel, [i.id]: e.target.value })} aria-label={`${i.name} quantity`} /> {i.unit}</span>
                </li>
              ))}
              {!chosen.length && <li className="muted">Nothing is running low. Add items below if you want to buy anyway.</li>}
            </ul>
            {others.length > 0 && (
              <div className="buy-add">
                <select className="input" value={extra} onChange={(e) => setExtra(e.target.value)} aria-label="Add another item">
                  <option value="">Add another item…</option>
                  {others.map((i) => <option key={i.id} value={i.id}>{i.name} (have {fmt(i.currentQty)} {i.unit})</option>)}
                </select>
                <button className="btn" disabled={!extra} onClick={() => { const it = items.find((x) => x.id === extra); setSel({ ...sel, [extra]: String(it.reorderQty || it.minQty || 1) }); setExtra(""); }}>Add</button>
              </div>
            )}
            <div className="field"><label htmlFor="b-sup">Supplier (optional)</label><input className="input" id="b-sup" value={supplier} onChange={(e) => setSupplier(e.target.value)} placeholder="e.g. Ravi Traders" /></div>
            {est > 0 && <p className="muted">Estimated cost from last prices: <b>{inr0(est)}</b></p>}
            <div className="two">
              <button className="btn" onClick={copy} disabled={!chosen.length}>Copy list</button>
              <button className="btn primary" onClick={() => save(false)} disabled={busy || !chosen.length}>Save to "To bring in"</button>
            </div>
          </>
        )}
      </section>
      <section className="panel form">
        <h2>List preview</h2>
        <pre className="list-preview">{listText}</pre>
        <p className="muted">Copy sends this to the clipboard so you can paste it into WhatsApp or SMS for the supplier.</p>
      </section>
    </div>
  );
}

// ---------------------------------------------------------------- To bring in
function Bring({ orders, run, busy, load }) {
  const open = orders.filter((o) => o.status === "ORDERED");
  const done = orders.filter((o) => o.status !== "ORDERED").slice(0, 20);
  return (
    <>
      {open.length ? <div className="bring-grid">{open.map((o) => <ReceiveCard key={o.id} o={o} run={run} busy={busy} load={load} />)}</div>
        : <div className="panel empty"><h3>Nothing waiting to be brought in</h3><p>Lists saved from "To buy" appear here until the goods arrive.</p></div>}
      {done.length > 0 && (
        <section className="panel mtable-wrap" style={{ marginTop: 16 }}>
          <div className="panel-h"><h2>Recent purchases</h2></div>
          <table className="mtable">
            <thead><tr><th>Date</th><th>Supplier</th><th>Items</th><th>Paid</th><th className="r">Total</th></tr></thead>
            <tbody>{done.map((o) => (
              <tr key={o.id} className={o.status === "CANCELLED" ? "off" : ""}>
                <td className="mono">{when(o.receivedAt || o.createdAt)}</td>
                <td>{o.supplier || "—"}</td>
                <td className="muted-cell">{o.lines.map((l) => `${l.name} ${fmt(l.receivedQty ?? l.qty)} ${l.unit}`).join(", ")}</td>
                <td>{o.status === "CANCELLED" ? <span className="pill">Cancelled</span> : <span className={"pill " + (o.payment === "CREDIT" ? "warn" : "ok")}>{PAY[o.payment] || o.payment}</span>}</td>
                <td className="mono r">{o.total != null ? inr(o.total) : ""}</td>
              </tr>
            ))}</tbody>
          </table>
        </section>
      )}
    </>
  );
}

function ReceiveCard({ o, run, busy, load }) {
  const [lines, setLines] = useState(() => o.lines.map((l) => ({ ...l, got: String(l.qty), cost: l.unitCost != null ? String(l.unitCost) : "" })));
  const [payment, setPayment] = useState("CASH");
  const [confirm, setConfirm] = useState(false);
  const total = lines.reduce((s, l) => s + (n(l.got) || 0) * (n(l.cost) || 0), 0);
  const setL = (i, k, v) => setLines(lines.map((l, j) => (j === i ? { ...l, [k]: v } : l)));

  const receive = async () => {
    const r = await run(() => api("POST", `purchases/${o.id}/receive`, {
      payment, supplier: o.supplier,
      lines: lines.map((l) => ({ stockItemId: l.stockItemId, qty: n(l.got) || 0, unitCost: n(l.cost) >= 0 ? n(l.cost) : undefined })),
    }), `Received. Stock updated${total > 0 ? ` and ${inr0(total)} added to the day book` : ""}.`);
    if (r) load();
  };

  return (
    <section className="panel form receive">
      <div className="receive-head">
        <h2>{o.supplier || "Purchase list"}</h2>
        <span className="muted">saved {when(o.createdAt)}{o.by ? ` by ${o.by}` : ""}</span>
      </div>
      <table className="mtable compact">
        <thead><tr><th>Item</th><th className="r">Ordered</th><th className="r">Received</th><th className="r">₹ / unit</th></tr></thead>
        <tbody>{lines.map((l, i) => (
          <tr key={l.stockItemId}>
            <td>{l.name}</td>
            <td className="mono r muted-cell">{fmt(l.qty)} {l.unit}</td>
            <td className="r"><input className="input mono qty-in" inputMode="decimal" value={l.got} onChange={(e) => setL(i, "got", e.target.value)} aria-label={`${l.name} received`} /></td>
            <td className="r"><input className="input mono qty-in" inputMode="decimal" value={l.cost} placeholder="0" onChange={(e) => setL(i, "cost", e.target.value)} aria-label={`${l.name} cost per unit`} /></td>
          </tr>
        ))}</tbody>
      </table>
      <div className="two">
        <div className="field"><label>Paid by</label>
          <select className="input" value={payment} onChange={(e) => setPayment(e.target.value)}>{Object.entries(PAY).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></div>
        <div className="field"><label>Total</label><div className="receive-total mono">{inr(total)}</div></div>
      </div>
      <div className="two">
        {confirm ? <button className="btn danger" onClick={async () => { const r = await run(() => api("POST", `purchases/${o.id}/cancel`), "List cancelled"); if (r) load(); }}>Yes, cancel list</button>
          : <button className="btn" onClick={() => setConfirm(true)}>Cancel list</button>}
        <button className="btn primary" onClick={receive} disabled={busy}>Receive goods</button>
      </div>
    </section>
  );
}
