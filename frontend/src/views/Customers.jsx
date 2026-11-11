import { useEffect, useMemo, useState } from "react";
import { api, inr, inr0, SOURCES, STATUS_LABEL } from "../api.js";

const DAY = 86400000;
const FILTERS = [
  ["all", "All"],
  ["regular", "Regulars (5+ orders)"],
  ["new", "New (1 order)"],
  ["away", "Not seen 30+ days"],
  ["big", "Spent ₹2,000+"],
];

const daysAgo = (ms) => Math.floor((Date.now() - ms) / DAY);
const agoText = (ms) => {
  const d = daysAgo(ms);
  return d <= 0 ? "today" : d === 1 ? "yesterday" : `${d} days ago`;
};
const dateText = (ms) => new Date(ms).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });

function tags(c) {
  const t = [];
  if (c.visits >= 5) t.push(["Regular", "good"]);
  if (c.visits === 1) t.push(["New", ""]);
  if (daysAgo(c.lastAt) >= 30) t.push(["Not seen 30+ days", "warn"]);
  return t;
}

function matches(c, f) {
  if (f === "regular") return c.visits >= 5;
  if (f === "new") return c.visits === 1;
  if (f === "away") return daysAgo(c.lastAt) >= 30;
  if (f === "big") return Number(c.spent) >= 2000;
  return true;
}

const csvCell = (v) => {
  const s = String(v ?? "");
  return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
};

/** Everyone who gave a mobile number: visits, money spent, history; lists for WhatsApp offers. */
export default function Customers({ toast, session }) {
  const [list, setList] = useState(null);
  const [q, setQ] = useState("");
  const [filter, setFilter] = useState("all");
  const [open, setOpen] = useState(null); // { customer, orders }
  const [offer, setOffer] = useState(`Hi! It's ${session.restaurant.name}. We miss you — show this message for 10% off your next order.`);

  useEffect(() => {
    api("GET", "customers").then(setList).catch((e) => { toast(e.message, true); setList([]); });
  }, [toast]);

  const needle = q.trim().toLowerCase();
  const shown = useMemo(() => (list || []).filter((c) => matches(c, filter)
    && (!needle || c.name.toLowerCase().includes(needle) || c.key.includes(needle.replace(/\D/g, "") || "~"))), [list, filter, needle]);

  const openCustomer = async (c) => {
    setOpen({ customer: c, orders: null });
    try {
      setOpen({ customer: c, orders: await api("GET", `customers/${c.key}/orders`) });
    } catch (e) {
      toast(e.message, true);
    }
  };

  const wa = (c) => `https://wa.me/91${c.key}?text=${encodeURIComponent(offer.replace("{name}", c.name || "there"))}`;

  const exportCsv = () => {
    const rows = [["Name", "Mobile", "Orders", "Spent", "First order", "Last order", "Address"],
      ...shown.map((c) => [c.name, c.key, c.visits, c.spent, dateText(c.firstAt), dateText(c.lastAt), c.address])];
    const blob = new Blob(["﻿" + rows.map((r) => r.map(csvCell).join(",")).join("\r\n")], { type: "text/csv;charset=utf-8" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `customers-${filter}-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  };

  if (list === null) return <div className="panel empty">Loading customers…</div>;

  const total = list.length;
  const repeat = list.filter((c) => c.visits >= 2).length;

  return (
    <>
      <div className="section-head">
        <h1>Customers</h1>
        <div className="filters">
          <input className="input" type="search" placeholder="Search name or mobile…" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search customers" />
          <button className="btn" onClick={exportCsv} disabled={!shown.length}>Export list (Excel)</button>
        </div>
      </div>
      <p className="label summary">
        {total} customer{total === 1 ? "" : "s"} · {repeat} came back more than once{total ? ` (${Math.round((repeat / total) * 100)}%)` : ""}
        {" "}· customers are everyone who gave a mobile number on an order
      </p>

      <div className="seg chips" role="group" aria-label="Show">
        {FILTERS.map(([k, v]) => <button key={k} aria-pressed={filter === k} onClick={() => setFilter(k)}>{v}</button>)}
      </div>

      <section className="panel form offer">
        <label htmlFor="c-offer">WhatsApp message for offers <small className="muted">({"{name}"} becomes the customer's name)</small></label>
        <textarea className="input" id="c-offer" rows={2} value={offer} onChange={(e) => setOffer(e.target.value)} maxLength={500} />
        <p className="muted">Tap <b>WhatsApp</b> on a customer to open the chat with this message ready to send.</p>
      </section>

      {shown.length ? (
        <div className="panel cust-table">
          <table>
            <thead><tr><th>Customer</th><th>Mobile</th><th className="r">Orders</th><th className="r">Spent</th><th>Last order</th><th /></tr></thead>
            <tbody>
              {shown.map((c) => (
                <tr key={c.key}>
                  <td>
                    <button className="linklike" onClick={() => openCustomer(c)}>{c.name || "No name"}</button>
                    <span className="tags">{tags(c).map(([t, cls]) => <span key={t} className={"chip " + cls}>{t}</span>)}</span>
                  </td>
                  <td className="mono">{c.key}</td>
                  <td className="r mono">{c.visits}</td>
                  <td className="r mono">{inr0(c.spent)}</td>
                  <td>{agoText(c.lastAt)}</td>
                  <td className="r"><a className="btn sm wa" href={wa(c)} target="_blank" rel="noreferrer">WhatsApp</a></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : <div className="panel empty">{total ? "No customers match." : "Customers appear here once orders have a mobile number."}</div>}

      {open && (
        <div className="ip-backdrop" onClick={(e) => e.target === e.currentTarget && setOpen(null)}>
          <section className="ip cust-detail" role="dialog" aria-modal="true" aria-label={`Customer ${open.customer.name || open.customer.key}`}>
            <div className="ip-head">
              <h2>{open.customer.name || "No name"}</h2>
              <button className="btn sm" onClick={() => setOpen(null)} aria-label="Close">✕</button>
            </div>
            <p className="mono">{open.customer.key}{open.customer.address ? " · " + open.customer.address : ""}</p>
            <p>{open.customer.visits} order{open.customer.visits === 1 ? "" : "s"} · {inr(open.customer.spent)} spent · first {dateText(open.customer.firstAt)} · last {agoText(open.customer.lastAt)}</p>
            <a className="btn wa" href={wa(open.customer)} target="_blank" rel="noreferrer">Send offer on WhatsApp</a>
            <h3>Orders</h3>
            {open.orders === null ? <p className="muted">Loading…</p> : (
              <ul className="cust-orders">
                {open.orders.map((o) => (
                  <li key={o.id} className={o.status === "CANCELLED" ? "cancelled" : undefined}>
                    <span className="mono"><b>#{o.no}</b> {dateText(o.createdAt)}</span>
                    <span>{o.items}</span>
                    <span className="mono">{o.status === "CANCELLED" ? STATUS_LABEL.CANCELLED : inr(o.total)} · {SOURCES[o.source]}</span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}
    </>
  );
}
