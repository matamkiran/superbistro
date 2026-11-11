import { hhmm, SOURCES, TYPES } from "../api.js";

/**
 * Kitchen order ticket (KOT) for an 80 mm printer. round: only the dishes added in that round
 * (running table bill), or null for the whole order. Off screen; App prints it.
 */
export default function KotSlip({ order: o, round }) {
  const items = round ? o.items.filter((i) => i.round === round) : o.items;
  const where = o.source !== "OFFLINE"
    ? `${SOURCES[o.source]}${o.platformId ? " · " + o.platformId : ""}`
    : o.type === "DINE_IN" ? (o.table ? "Table " + o.table : "Dine-in") : TYPES[o.type];
  const qty = items.reduce((s, i) => s + i.qty, 0);
  return (
    <article id="kot-print" className="kot-slip" aria-hidden="true">
      <div className="ks-title">KOT{round > 1 ? ` · ADDED (round ${round})` : ""}</div>
      <div className="ks-big">#{o.no} · {where}</div>
      <div className="ks-meta">{hhmm(Date.now())}{o.customer ? " · " + o.customer : ""}</div>
      <table className="ks-items">
        <tbody>
          {items.map((i, n) => (
            <tr key={n}>
              <td className="ks-q">{i.qty}</td>
              <td>{i.name}{i.note && <div className="ks-note">↳ {i.note}</div>}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {o.note && round <= 1 && <div className="ks-note ks-order-note">Note: {o.note}</div>}
      <div className="ks-foot">{qty} item{qty === 1 ? "" : "s"}</div>
    </article>
  );
}
