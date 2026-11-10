import { PAYMENTS, SOURCES, TYPES } from "../api.js";

const dt = (ms) => new Date(ms).toLocaleString("en-IN", { day: "2-digit", month: "short", year: "numeric", hour: "numeric", minute: "2-digit" });
const money = (n) => Number(n || 0).toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** Customer bill for one order: printable on an 80 mm receipt printer, or sent on WhatsApp. */
export default function Invoice({ order: o, restaurant: r, onClose, toast }) {
  const digits = (o.phone || "").replace(/\D/g, "");
  const waNumber = digits.length === 10 ? "91" + digits : digits; // Indian mobiles without country code
  const billNo = `${o.day.replace(/-/g, "")}-${String(o.no).padStart(3, "0")}`;
  const lineDisc = o.items.reduce((s, i) => s + Number(i.lineDiscount || 0), 0);

  const text = [
    `*${r.name}*`,
    r.address || null, r.phone && `Ph: ${r.phone}`, r.gstin && `GSTIN: ${r.gstin}`,
    "",
    `Bill ${billNo} · Order #${o.no}`,
    dt(o.createdAt),
    o.customer && `Customer: ${o.customer}`,
    o.phone && `Mobile: ${o.phone}`,
    "",
    ...o.items.map((i) => `${i.qty} x ${i.name} @ ${money(i.price)} = ₹${money(i.lineTotal)}${Number(i.lineDiscount) > 0 ? ` (−${money(i.lineDiscount)})` : ""}`),
    "",
    `Subtotal: ₹${money(o.subtotal)}`,
    Number(o.discount) > 0 && `Discount: −₹${money(o.discount)}`,
    Number(o.gstAmt) > 0 && `GST ${Number(o.gstPct)}%: ₹${money(o.gstAmt)}`,
    `*Total: ₹${money(o.total)}*`,
    `Paid: ${PAYMENTS[o.payment] || o.payment}`,
    "",
    r.billFooter || "Thank you! Visit again.",
  ].filter((x) => typeof x === "string").join("\n").replace(/\n{3,}/g, "\n\n");

  const waUrl = `https://wa.me/${waNumber}?text=${encodeURIComponent(text)}`;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      toast("Bill copied");
    } catch {
      toast("Couldn't copy the bill", true);
    }
  };

  return (
    <div className="ip-backdrop invoice-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="invoice-wrap" role="dialog" aria-modal="true" aria-label={`Bill for order ${o.no}`}>
        <div className="invoice-actions no-print">
          <button className="btn primary" onClick={() => window.print()}>Print bill</button>
          {waNumber.length >= 10
            ? <a className="btn wa" href={waUrl} target="_blank" rel="noreferrer">Send on WhatsApp to {o.phone}</a>
            : <span className="muted">Add the customer's mobile on the order to send the bill on WhatsApp.</span>}
          <button className="btn" onClick={copy}>Copy text</button>
          <button className="btn" onClick={onClose}>Close</button>
        </div>

        <article className="invoice" id="invoice-print">
          <header className="inv-head">
            <h2>{r.name}</h2>
            {r.address && <div>{r.address}</div>}
            {r.phone && <div>Ph: {r.phone}</div>}
            {r.gstin && <div>GSTIN: {r.gstin}</div>}
            <div className="inv-title">{Number(o.gstAmt) > 0 ? "TAX INVOICE" : "BILL"}</div>
          </header>

          <div className="inv-meta">
            <div><span>Bill no</span><b>{billNo}</b></div>
            <div><span>Order</span><b>#{o.no}</b></div>
            <div><span>Date</span><b>{dt(o.createdAt)}</b></div>
            <div><span>Type</span><b>{o.source === "OFFLINE" ? TYPES[o.type] + (o.table ? ` · Table ${o.table}` : "") : `${SOURCES[o.source]}${o.platformId ? ` · ${o.platformId}` : ""}`}</b></div>
            {o.customer && <div><span>Customer</span><b>{o.customer}</b></div>}
            {o.phone && <div className="inv-phone"><span>Mobile</span><b>{o.phone}</b></div>}
            {o.type === "DELIVERY" && o.note && <div><span>Address</span><b>{o.note}</b></div>}
          </div>

          <table className="inv-items">
            <thead><tr><th>Item</th><th className="r">Qty</th><th className="r">Rate</th><th className="r">Amount</th></tr></thead>
            <tbody>
              {o.items.map((i, k) => (
                <tr key={k}>
                  <td>{i.name}{Number(i.lineDiscount) > 0 && <div className="inv-sub">discount −{money(i.lineDiscount)}</div>}</td>
                  <td className="r">{i.qty}</td>
                  <td className="r">{money(i.price)}</td>
                  <td className="r">{money(i.lineTotal)}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="inv-sums">
            <div><span>Subtotal</span><span>{money(o.subtotal)}</span></div>
            {lineDisc > 0 && <div className="inv-sub"><span>(includes item discounts)</span><span>−{money(lineDisc)}</span></div>}
            {Number(o.discount) > 0 && <div><span>Bill discount</span><span>−{money(o.discount)}</span></div>}
            {Number(o.gstAmt) > 0 && (
              <>
                <div><span>CGST {Number(o.gstPct) / 2}%</span><span>{money(Number(o.gstAmt) / 2)}</span></div>
                <div><span>SGST {Number(o.gstPct) / 2}%</span><span>{money(Number(o.gstAmt) / 2)}</span></div>
              </>
            )}
            <div className="inv-total"><span>Total</span><span>₹{money(o.total)}</span></div>
            <div><span>Paid by</span><span>{PAYMENTS[o.payment] || o.payment}</span></div>
          </div>

          <footer className="inv-foot">{r.billFooter || "Thank you! Visit again."}</footer>
        </article>
      </div>
    </div>
  );
}
