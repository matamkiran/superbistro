import { useEffect, useMemo, useRef, useState } from "react";
import { api, catIcon, inr, PAYMENTS, SOURCES, TYPES, startOfDay } from "../api.js";
import ItemPopup from "./ItemPopup.jsx";
import Invoice from "./Invoice.jsx";

const blankDraft = (source = "OFFLINE") => ({
  source, type: "DINE_IN", table: "", customer: "", phone: "", platformId: "", note: "", address: "", discount: "", payment: "UNPAID",
});

const KOT_KEY = "superbistro.autoKot";
const readKotPref = () => {
  try {
    return localStorage.getItem(KOT_KEY) === "on";
  } catch {
    return false;
  }
};
const phoneKey = (p) => {
  const d = String(p || "").replace(/\D/g, "");
  return d.length >= 10 ? d.slice(-10) : "";
};
const ago = (ms) => {
  const days = Math.floor((Date.now() - ms) / 86400000);
  return days <= 0 ? "today" : days === 1 ? "yesterday" : `${days} days ago`;
};

const POPUP_KEY = "superbistro.itemPopup";
const readPopupPref = () => {
  try {
    return localStorage.getItem(POPUP_KEY) !== "off";
  } catch {
    return true;
  }
};

let lineSeq = 0;
const lineKey = () => "l" + ++lineSeq;

/**
 * A cart line: { key, id (menu item), qty, price (null = menu price), disc (the % or ₹ typed), discType, note }.
 * Same rounding as the server so the screen and the bill agree.
 */
function lineMath(l, dish) {
  const price = l.price ?? dish.price;
  const gross = l.qty * price;
  const raw = l.disc > 0 ? (l.discType === "PCT" ? (gross * Math.min(l.disc, 100)) / 100 : l.disc) : 0;
  const discAmt = Math.round(Math.min(gross, raw) * 100) / 100;
  return { price, gross, discAmt, total: gross - discAmt };
}

export default function Pos({ session, isOwner, menu, orders, setOrders, toast, go, addTo, setAddTo, printKot }) {
  const [lines, setLines] = useState([]);
  const [d, setD] = useState(blankDraft());
  const [cat, setCat] = useState("All");
  const [q, setQ] = useState("");
  const [busy, setBusy] = useState(false);
  const [popup, setPopup] = useState(null); // { dish, line? }
  const [askDetails, setAskDetails] = useState(readPopupPref);
  const [lastOrder, setLastOrder] = useState(null);
  const [bill, setBill] = useState(null);
  const [autoKot, setAutoKot] = useState(readKotPref);
  const [known, setKnown] = useState(null); // { key, customer, favourites } for the typed mobile number
  const ticketRef = useRef(null);

  // Typed a 10-digit mobile number: who is it? Fills in the name (and address) when they're empty.
  const pKey = phoneKey(d.phone);
  useEffect(() => {
    if (!pKey || addTo) {
      setKnown(null);
      return undefined;
    }
    let stop = false;
    const t = setTimeout(async () => {
      try {
        const r = await api("GET", "customers/lookup?phone=" + pKey);
        if (stop) return;
        setKnown({ key: pKey, ...r });
        if (r.customer) {
          setD((cur) => ({
            ...cur,
            customer: cur.customer || r.customer.name || "",
            address: cur.address || r.customer.address || "",
          }));
        }
      } catch {
        /* lookup is a convenience */
      }
    }, 350);
    return () => {
      stop = true;
      clearTimeout(t);
    };
  }, [pKey, addTo]);

  const active = useMemo(() => menu.filter((m) => m.active), [menu]);
  const cats = useMemo(() => ["All", ...new Set(active.map((m) => m.category))], [active]);
  const needle = q.trim().toLowerCase();
  const shown = active.filter((m) => (cat === "All" || m.category === cat) && (!needle || m.name.toLowerCase().includes(needle)));
  const byId = useMemo(() => Object.fromEntries(menu.map((m) => [m.id, m])), [menu]);

  const rows = lines.filter((l) => byId[l.id]).map((l) => ({ ...l, dish: byId[l.id], ...lineMath(l, byId[l.id]) }));
  const subtotal = rows.reduce((s, r) => s + r.total, 0);
  const isOff = d.source === "OFFLINE";
  const discount = isOff ? Math.min(Math.max(0, parseFloat(d.discount) || 0), subtotal) : 0;
  const gstPct = Number(session.restaurant.gstPct) || 0;
  const gstAmt = Math.round((subtotal - discount) * gstPct) / 100;
  const total = subtotal - discount + gstAmt;
  const count = rows.reduce((s, r) => s + r.qty, 0);
  const qtyOf = (id) => rows.filter((r) => r.id === id).reduce((s, r) => s + r.qty, 0);
  const plain = (l) => l.price == null && !l.disc && !l.note;

  const today = startOfDay(Date.now());
  const nextNo = orders.filter((o) => o.createdAt >= today).reduce((m, o) => Math.max(m, o.no), 0) + 1;
  const isOpen = (o) => o.status !== "COMPLETED" && o.status !== "CANCELLED";
  // Running table bill: this table already has an open order today.
  const tableOrder = !addTo && isOff && d.type === "DINE_IN" && d.table.trim()
    ? orders.find((o) => o.createdAt >= today && isOpen(o) && o.type === "DINE_IN" && o.table.toLowerCase() === d.table.trim().toLowerCase())
    : null;
  const addingTo = addTo ? orders.find((o) => o.id === addTo.id) || addTo : null;

  // Quick add: one more on the plain line for this dish (no price change, discount or note), else a new line.
  const quickAdd = (id) => setLines((ls) => {
    const i = ls.findIndex((l) => l.id === id && plain(l));
    if (i < 0) return [...ls, { key: lineKey(), id, qty: 1, price: null, disc: 0, discType: "PCT", note: "" }];
    return ls.map((l, j) => (j === i ? { ...l, qty: l.qty + 1 } : l));
  });
  const tapDish = (m) => (askDetails ? setPopup({ dish: m }) : quickAdd(m.id));
  const step = (key, by) => setLines((ls) => ls.map((l) => (l.key === key ? { ...l, qty: l.qty + by } : l)).filter((l) => l.qty > 0));

  const savePopup = (vals) => {
    if (popup.line) {
      setLines((ls) => ls.map((l) => (l.key === popup.line.key ? { ...l, ...vals } : l)));
    } else {
      setLines((ls) => {
        const i = plain(vals) ? ls.findIndex((l) => l.id === popup.dish.id && plain(l)) : -1;
        if (i >= 0) return ls.map((l, j) => (j === i ? { ...l, qty: l.qty + vals.qty } : l));
        return [...ls, { key: lineKey(), id: popup.dish.id, ...vals }];
      });
    }
    setPopup(null);
  };

  const togglePref = () => {
    const v = !askDetails;
    setAskDetails(v);
    try {
      localStorage.setItem(POPUP_KEY, v ? "on" : "off");
    } catch {
      /* private mode */
    }
  };

  const set = (k) => (e) => setD({ ...d, [k]: e.target.value });

  const toggleKot = () => {
    const v = !autoKot;
    setAutoKot(v);
    try {
      localStorage.setItem(KOT_KEY, v ? "on" : "off");
    } catch {
      /* private mode */
    }
  };

  const lineBody = () => rows.map((r) => ({
    menuItemId: r.id,
    qty: r.qty,
    price: r.price !== r.dish.price ? r.price : undefined,
    discount: r.disc > 0 ? r.disc : undefined,
    discountType: r.disc > 0 ? r.discType : undefined,
    note: r.note || undefined,
  }));

  // Running bill: send the ticket's dishes to the open order as its next kitchen round.
  const addToOrder = async () => {
    if (!rows.length) return toast("Add at least one dish first", true);
    setBusy(true);
    try {
      const o = await api("POST", `orders/${addingTo.id}/items`, { items: lineBody() });
      setOrders((list) => list.map((x) => (x.id === o.id ? o : x)));
      setLines([]);
      setAddTo(null);
      setLastOrder(o);
      toast(`Added to order #${o.no} · bill now ${inr(o.total)}`);
      if (autoKot) printKot(o, o.round);
    } catch (e) {
      toast(e.message, true);
    } finally {
      setBusy(false);
    }
  };

  const place = async () => {
    if (addingTo) return addToOrder();
    if (!rows.length) return toast("Add at least one dish first", true);
    setBusy(true);
    try {
      const o = await api("POST", "orders", {
        source: d.source,
        type: isOff ? d.type : undefined,
        payment: isOff ? d.payment : undefined,
        table: d.table, customer: d.customer, phone: d.phone, platformId: d.platformId, note: d.note,
        address: isOff && d.type === "DELIVERY" ? d.address : undefined,
        discount,
        items: lineBody(),
      });
      setOrders((list) => [o, ...list.filter((x) => x.id !== o.id)]);
      setLines([]);
      setD(blankDraft(d.source));
      setLastOrder(o);
      toast(`Order #${o.no} placed · ${inr(o.total)}`);
      if (autoKot) printKot(o, null);
    } catch (e) {
      toast(e.message, true);
    } finally {
      setBusy(false);
    }
  };

  if (!menu.length) {
    return (
      <div className="panel empty">
        <h3>Your menu is empty</h3>
        <p>{isOwner ? "Import your menu from a spreadsheet or add dishes one by one." : "Ask the owner to add dishes to the menu."}</p>
        {isOwner && <button className="btn primary" onClick={() => go("menu")}>Set up the menu</button>}
      </div>
    );
  }

  return (
    <div className="pos">
      <section className="pos-left" aria-label="Menu">
        <div className="pos-bar">
          <input className="input" type="search" placeholder="Search dishes…" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search dishes" />
          <label className="check pref" title="Open the details popup (qty, price, discount, note) when a dish is tapped">
            <input type="checkbox" checked={askDetails} onChange={togglePref} /> Details on tap
          </label>
        </div>
        <div className="cats" role="group" aria-label="Categories">
          {cats.map((c) => (
            <button key={c} className="cat" aria-pressed={cat === c} onClick={() => setCat(c)}>
              {catIcon(c) && <img src={catIcon(c)} alt="" />}{c}
            </button>
          ))}
        </div>
        <div className="grid">
          {shown.map((m) => (
            <button key={m.id} className="item" onClick={() => tapDish(m)} aria-label={`Add ${m.name}`}>
              {qtyOf(m.id) > 0 && <span className="qty-badge">{qtyOf(m.id)}</span>}
              <span className="item-name"><span className={"dot " + (m.veg ? "veg" : "nonveg")} title={m.veg ? "Veg" : "Non-veg"} />{m.name}</span>
              <span className="item-price">{inr(m.price)}</span>
              {catIcon(m.category) && <img className="item-icon" src={catIcon(m.category)} alt="" loading="lazy" />}
            </button>
          ))}
          {!shown.length && <div className="empty" style={{ gridColumn: "1/-1" }}>No dishes match “{q}”.</div>}
        </div>
      </section>

      <aside className="ticket" ref={ticketRef} aria-label="Current order">
        <div className="ticket-head">
          {addingTo ? (
            <div className="adding">
              <h2>Add to order #{addingTo.no}</h2>
              <span className="ticket-no">{addingTo.table ? "Table " + addingTo.table + " · " : ""}bill so far {inr(addingTo.total)}</span>
              <p className="muted">Dishes you tap now are added to this order and go to the kitchen as a new KOT.</p>
              <button className="btn sm" onClick={() => { setAddTo(null); setLines([]); }}>Stop adding, back to new order</button>
            </div>
          ) : (
            <div className="ticket-title"><h2>New order</h2><span className="ticket-no">#{nextNo} · today</span></div>
          )}
          {lastOrder && !addingTo && (
            <div className="last-order">
              <span>Last: <b>#{lastOrder.no}</b> · {inr(lastOrder.total)}{lastOrder.phone ? ` · ${lastOrder.phone}` : ""}</span>
              <button className="btn sm" onClick={() => setBill(lastOrder)}>Bill</button>
            </div>
          )}
          {!addingTo && <>
          <div className="seg src" role="group" aria-label="Order source">
            {Object.entries(SOURCES).map(([k, v]) => (
              <button key={k} data-src={k} aria-pressed={d.source === k} onClick={() => setD({ ...d, source: k })}>{v}</button>
            ))}
          </div>

          {isOff ? (
            <>
              <div className="seg" role="group" aria-label="Order type">
                {Object.entries(TYPES).map(([k, v]) => (
                  <button key={k} aria-pressed={d.type === k} onClick={() => setD({ ...d, type: k, payment: k === "DINE_IN" ? "UNPAID" : d.payment === "UNPAID" ? "CASH" : d.payment })}>{v}</button>
                ))}
              </div>
              <div className="two">
                {d.type === "DINE_IN" ? (
                  <div className="field"><label htmlFor="d-table">Table</label>
                    <input className="input" id="d-table" value={d.table} onChange={set("table")} placeholder="e.g. T4" autoComplete="off" /></div>
                ) : <span />}
                <div className="field"><label htmlFor="d-cust">Customer</label>
                  <input className="input" id="d-cust" value={d.customer} onChange={set("customer")} placeholder="Name (optional)" autoComplete="off" /></div>
              </div>
              <div className="field"><label htmlFor="d-phone">Customer mobile {d.type !== "DINE_IN" ? "" : "(for the bill on WhatsApp)"}</label>
                <input className="input mono" id="d-phone" value={d.phone} onChange={set("phone")} inputMode="tel" placeholder="98xxxxxx10" autoComplete="off" /></div>
              {d.type === "DELIVERY" && (
                <div className="field"><label htmlFor="d-addr">Delivery address</label>
                  <input className="input" id="d-addr" value={d.address} onChange={set("address")} placeholder="Flat, street, landmark" autoComplete="off" /></div>
              )}
              {tableOrder && (
                <div className="table-open">
                  <span>Table {tableOrder.table} already has order <b>#{tableOrder.no}</b> ({inr(tableOrder.total)}).</span>
                  <button className="btn sm primary" onClick={() => setAddTo(tableOrder)}>Add to #{tableOrder.no}</button>
                </div>
              )}
            </>
          ) : (
            <>
              <div className="two">
                <div className="field"><label htmlFor="d-pid">{SOURCES[d.source]} order ID</label>
                  <input className="input mono" id="d-pid" value={d.platformId} onChange={set("platformId")} placeholder="From the partner app" autoComplete="off" /></div>
                <div className="field"><label htmlFor="d-cust">Customer</label>
                  <input className="input" id="d-cust" value={d.customer} onChange={set("customer")} placeholder="Name (optional)" autoComplete="off" /></div>
              </div>
              <div className="field"><label htmlFor="d-phone">Customer mobile (optional)</label>
                <input className="input mono" id="d-phone" value={d.phone} onChange={set("phone")} inputMode="tel" placeholder="98xxxxxx10" autoComplete="off" /></div>
            </>
          )}
          {known && known.key === pKey && (
            known.customer ? (
              <div className="cust-card">
                <b>{known.customer.visits >= 2 ? "Repeat customer" : "Customer"}{known.customer.name ? " · " + known.customer.name : ""}</b>
                <span>{known.customer.visits} order{known.customer.visits === 1 ? "" : "s"} · {inr(known.customer.spent)} · last {ago(known.customer.lastAt)}</span>
                {(known.customer.visits + 1) % 10 === 0 && <span className="cust-reward">This is their {known.customer.visits + 1}th order. Thank them with a treat!</span>}
                {known.favourites?.length > 0 && <span className="muted">Usually: {known.favourites.join(", ")}</span>}
              </div>
            ) : <div className="cust-card new"><b>New customer</b><span className="muted">First order with this number.</span></div>
          )}
          </>}
        </div>

        <div className="ticket-body">
          {rows.length ? rows.map((r) => (
            <div className="line" key={r.key}>
              <button type="button" className="line-main" onClick={() => setPopup({ dish: r.dish, line: lines.find((l) => l.key === r.key) })}
                title="Change qty, price, discount or note">
                <span className="line-name">{r.dish.name}</span>
                <span className="line-each">
                  {inr(r.price)} each{r.price !== r.dish.price && <em> (menu {inr(r.dish.price)})</em>}
                  {r.discAmt > 0 && <em className="line-off"> · −{inr(r.discAmt)}{r.discType === "PCT" ? ` (${r.disc}%)` : ""}</em>}
                </span>
                {r.note && <span className="line-note">{r.note}</span>}
              </button>
              <div className="stepper">
                <button onClick={() => step(r.key, -1)} aria-label={`One less ${r.dish.name}`}>−</button>
                <span>{r.qty}</span>
                <button onClick={() => step(r.key, 1)} aria-label={`One more ${r.dish.name}`}>+</button>
              </div>
              <div className="line-amt">{inr(r.total)}</div>
            </div>
          )) : <div className="empty">Tap dishes to add them. Tap a line to change its qty, price, discount or note.</div>}
        </div>

        <div className="ticket-foot">
          {addingTo ? null : isOff ? (
            <div className="two">
              <div className="field"><label htmlFor="d-pay">Payment</label>
                <select className="input" id="d-pay" value={d.payment} onChange={set("payment")}>
                  {["CASH", "UPI", "CARD", "UNPAID"].map((k) => <option key={k} value={k}>{PAYMENTS[k]}</option>)}
                </select></div>
              <div className="field"><label htmlFor="d-disc">Bill discount ₹</label>
                <input className="input mono" id="d-disc" value={d.discount} onChange={set("discount")} inputMode="decimal" placeholder="0" autoComplete="off" /></div>
            </div>
          ) : <div className="label">Paid on {SOURCES[d.source]}</div>}
          <label className="check pref" title="Print a kitchen slip (KOT) each time an order is placed or dishes are added">
            <input type="checkbox" checked={autoKot} onChange={toggleKot} /> Print kitchen slip (KOT)
          </label>
          {addingTo ? (
            <div className="sums">
              <div><span>Adding ({count} item{count === 1 ? "" : "s"})</span><span>{inr(subtotal)}</span></div>
              <div className="grand"><span>Bill after adding</span><span>{inr(Number(addingTo.total) + (subtotal * (100 + gstPct)) / 100)}</span></div>
            </div>
          ) : (
          <div className="sums">
            <div><span>Subtotal ({count} item{count === 1 ? "" : "s"})</span><span>{inr(subtotal)}</span></div>
            {discount > 0 && <div><span>Bill discount</span><span>−{inr(discount)}</span></div>}
            {gstPct > 0 && <div><span>GST {gstPct}%</span><span>{inr(gstAmt)}</span></div>}
            <div className="grand"><span>Total</span><span>{inr(total)}</span></div>
          </div>
          )}
          <div className="two">
            <button className="btn" onClick={() => setLines([])} disabled={!rows.length}>Clear</button>
            <button className="btn primary" onClick={place} disabled={!rows.length || busy}>
              {busy ? "Saving…" : addingTo ? `Add to order #${addingTo.no}` : "Place order"}</button>
          </div>
        </div>
      </aside>

      {count > 0 && (
        <div className="mobile-sum">
          <span><b>{count} item{count === 1 ? "" : "s"}</b> · {inr(total)}</span>
          <button className="btn primary" onClick={() => ticketRef.current?.scrollIntoView({ behavior: "smooth" })}>Review order</button>
        </div>
      )}

      {bill && <Invoice order={bill} restaurant={session.restaurant} onClose={() => setBill(null)} toast={toast} />}

      {popup && (
        <ItemPopup
          key={popup.line?.key || popup.dish.id}
          dish={popup.dish}
          line={popup.line}
          onSave={savePopup}
          onRemove={() => { if (popup.line) setLines((ls) => ls.filter((l) => l.key !== popup.line.key)); setPopup(null); }}
          onClose={() => setPopup(null)}
        />
      )}
    </div>
  );
}
