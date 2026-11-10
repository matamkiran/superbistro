import { useMemo, useRef, useState } from "react";
import { api, catIcon, inr, PAYMENTS, SOURCES, TYPES, startOfDay } from "../api.js";
import ItemPopup from "./ItemPopup.jsx";
import Invoice from "./Invoice.jsx";

const blankDraft = (source = "OFFLINE") => ({
  source, type: "DINE_IN", table: "", customer: "", phone: "", platformId: "", note: "", discount: "", payment: "CASH",
});

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

export default function Pos({ session, isOwner, menu, orders, setOrders, toast, go }) {
  const [lines, setLines] = useState([]);
  const [d, setD] = useState(blankDraft());
  const [cat, setCat] = useState("All");
  const [q, setQ] = useState("");
  const [busy, setBusy] = useState(false);
  const [popup, setPopup] = useState(null); // { dish, line? }
  const [askDetails, setAskDetails] = useState(readPopupPref);
  const [lastOrder, setLastOrder] = useState(null);
  const [bill, setBill] = useState(null);
  const ticketRef = useRef(null);

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

  const place = async () => {
    if (!rows.length) return toast("Add at least one dish first", true);
    setBusy(true);
    try {
      const o = await api("POST", "orders", {
        source: d.source,
        type: isOff ? d.type : undefined,
        payment: isOff ? d.payment : undefined,
        table: d.table, customer: d.customer, phone: d.phone, platformId: d.platformId, note: d.note,
        discount,
        items: rows.map((r) => ({
          menuItemId: r.id,
          qty: r.qty,
          price: r.price !== r.dish.price ? r.price : undefined,
          discount: r.disc > 0 ? r.disc : undefined,
          discountType: r.disc > 0 ? r.discType : undefined,
          note: r.note || undefined,
        })),
      });
      setOrders((list) => [o, ...list.filter((x) => x.id !== o.id)]);
      setLines([]);
      setD(blankDraft(d.source));
      setLastOrder(o);
      toast(`Order #${o.no} placed · ${inr(o.total)}`);
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
          <div className="ticket-title"><h2>New order</h2><span className="ticket-no">#{nextNo} · today</span></div>
          {lastOrder && (
            <div className="last-order">
              <span>Last: <b>#{lastOrder.no}</b> · {inr(lastOrder.total)}{lastOrder.phone ? ` · ${lastOrder.phone}` : ""}</span>
              <button className="btn sm" onClick={() => setBill(lastOrder)}>Bill</button>
            </div>
          )}
          <div className="seg src" role="group" aria-label="Order source">
            {Object.entries(SOURCES).map(([k, v]) => (
              <button key={k} data-src={k} aria-pressed={d.source === k} onClick={() => setD({ ...d, source: k })}>{v}</button>
            ))}
          </div>

          {isOff ? (
            <>
              <div className="seg" role="group" aria-label="Order type">
                {Object.entries(TYPES).map(([k, v]) => (
                  <button key={k} aria-pressed={d.type === k} onClick={() => setD({ ...d, type: k })}>{v}</button>
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
                <div className="field"><label htmlFor="d-note">Address / note</label>
                  <input className="input" id="d-note" value={d.note} onChange={set("note")} placeholder="Flat, street, landmark" autoComplete="off" /></div>
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
          {isOff ? (
            <div className="two">
              <div className="field"><label htmlFor="d-pay">Payment</label>
                <select className="input" id="d-pay" value={d.payment} onChange={set("payment")}>
                  {["CASH", "UPI", "CARD", "UNPAID"].map((k) => <option key={k} value={k}>{PAYMENTS[k]}</option>)}
                </select></div>
              <div className="field"><label htmlFor="d-disc">Bill discount ₹</label>
                <input className="input mono" id="d-disc" value={d.discount} onChange={set("discount")} inputMode="decimal" placeholder="0" autoComplete="off" /></div>
            </div>
          ) : <div className="label">Paid on {SOURCES[d.source]}</div>}
          <div className="sums">
            <div><span>Subtotal ({count} item{count === 1 ? "" : "s"})</span><span>{inr(subtotal)}</span></div>
            {discount > 0 && <div><span>Bill discount</span><span>−{inr(discount)}</span></div>}
            {gstPct > 0 && <div><span>GST {gstPct}%</span><span>{inr(gstAmt)}</span></div>}
            <div className="grand"><span>Total</span><span>{inr(total)}</span></div>
          </div>
          <div className="two">
            <button className="btn" onClick={() => setLines([])} disabled={!rows.length}>Clear</button>
            <button className="btn primary" onClick={place} disabled={!rows.length || busy}>{busy ? "Placing…" : "Place order"}</button>
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
