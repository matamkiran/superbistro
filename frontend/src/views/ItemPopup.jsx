import { useEffect, useRef, useState } from "react";
import { catIcon, inr } from "../api.js";

const QUICK_NOTES = ["Less spicy", "Extra spicy", "No onion", "No garlic", "Jain", "Parcel", "Extra cheese"];
const toNum = (s) => (s === "" || s === "." ? 0 : parseFloat(s));

/**
 * Item details: quantity, price per unit, discount (% or ₹) and a kitchen note,
 * with a large number pad for touch screens. Physical keyboard works too.
 */
export default function ItemPopup({ dish, line, onSave, onRemove, onClose }) {
  const [f, setF] = useState(() => ({
    qty: String(line?.qty ?? 1),
    price: String(line?.price ?? dish.price),
    disc: line?.disc ? String(line.disc) : "",
    discType: line?.discType ?? "PCT",
    note: line?.note ?? "",
  }));
  const [field, setField] = useState("qty");
  // Refs, not state, so several quick key presses before a re-render all land correctly.
  const fieldRef = useRef("qty");
  const freshRef = useRef(true); // the first key press after choosing a field replaces its value
  const setFresh = (v) => { freshRef.current = v; };

  const qty = Math.max(0, Math.floor(toNum(f.qty)));
  const price = toNum(f.price);
  const gross = qty * price;
  const discVal = toNum(f.disc);
  const discount = Math.min(gross, f.discType === "PCT" ? (gross * Math.min(discVal, 100)) / 100 : discVal);
  const total = gross - discount;
  const changedPrice = Math.abs(price - dish.price) > 0.001;

  const press = (k) => {
    const fld = fieldRef.current;
    const isFresh = freshRef.current;
    freshRef.current = false;
    setF((cur) => {
      let v = isFresh ? "" : cur[fld];
      if (k === "back") v = (isFresh ? cur[fld] : v).slice(0, -1);
      else if (k === "clear") v = "";
      else if (k === ".") { if (fld === "qty" || v.includes(".")) return cur; v = (v || "0") + "."; }
      else v = (v + k).replace(/^0+(?=\d)/, "");
      if (fld === "qty" && v.length > 3) return cur;
      if (v.length > 8) return cur;
      return { ...cur, [fld]: v };
    });
  };
  const pick = (name) => { fieldRef.current = name; freshRef.current = true; setField(name); };

  const save = () => {
    if (qty < 1) return onRemove();
    onSave({
      qty,
      price: changedPrice ? Math.round(price * 100) / 100 : null,
      disc: discVal > 0 ? discVal : 0,
      discType: f.discType,
      note: f.note.trim().slice(0, 120),
    });
  };

  const saveRef = useRef(save);
  saveRef.current = save;
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    const onKey = (e) => {
      if (e.target.tagName === "INPUT") {
        if (e.key === "Enter") { e.preventDefault(); saveRef.current(); }
        if (e.key === "Escape") closeRef.current();
        return;
      }
      if (/^[0-9]$/.test(e.key)) press(e.key);
      else if (e.key === ".") press(".");
      else if (e.key === "Backspace") press("back");
      else if (e.key === "Enter") { e.preventDefault(); saveRef.current(); }
      else if (e.key === "Escape") closeRef.current();
      else if (e.key === "Tab") {
        e.preventDefault();
        const order = ["qty", "price", "disc"];
        pick(order[(order.indexOf(fieldRef.current) + 1) % 3]);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const Box = ({ name, label, value, sub }) => (
    <button type="button" className={"ip-field" + (field === name ? " on" : "")} onClick={() => pick(name)} aria-pressed={field === name}>
      <span className="ip-label">{label}</span>
      <span className="ip-value">{value}</span>
      {sub && <span className="ip-sub">{sub}</span>}
    </button>
  );

  return (
    <div className="ip-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="ip" role="dialog" aria-modal="true" aria-label={`Details for ${dish.name}`}>
        <div className="ip-head">
          {catIcon(dish.category) && <img src={catIcon(dish.category)} alt="" />}
          <div>
            <h2><span className={"dot " + (dish.veg ? "veg" : "nonveg")} />{dish.name}</h2>
            <div className="ip-meta">Menu price {inr(dish.price)} · {dish.category}</div>
          </div>
          <button className="ip-x" onClick={onClose} aria-label="Close">×</button>
        </div>

        <div className="ip-body">
          <div className="ip-fields">
            <Box name="qty" label="Qty" value={f.qty || "0"} />
            <Box name="price" label="Price / unit" value={"₹" + (f.price || "0")} sub={changedPrice ? `menu ${inr(dish.price)}` : null} />
            <div className={"ip-field ip-disc" + (field === "disc" ? " on" : "")}>
              <button type="button" className="ip-disc-main" onClick={() => pick("disc")}>
                <span className="ip-label">Discount</span>
                <span className="ip-value">{f.disc ? (f.discType === "PCT" ? f.disc + "% off" : "₹" + f.disc + " off") : "None"}</span>
                {discount > 0 && <span className="ip-sub">−{inr(discount)}</span>}
              </button>
              <div className="seg ip-pct" role="group" aria-label="Discount type">
                <button type="button" aria-pressed={f.discType === "PCT"} onClick={() => { setF({ ...f, discType: "PCT" }); pick("disc"); }}>%</button>
                <button type="button" aria-pressed={f.discType === "AMT"} onClick={() => { setF({ ...f, discType: "AMT" }); pick("disc"); }}>₹</button>
              </div>
            </div>
            <div className="ip-field ip-note">
              <label className="ip-label" htmlFor="ip-note">Note for kitchen</label>
              <input id="ip-note" className="input" value={f.note} maxLength={120} placeholder="e.g. less spicy"
                onChange={(e) => setF({ ...f, note: e.target.value })} />
              <div className="ip-chips">
                {QUICK_NOTES.map((n) => (
                  <button key={n} type="button" className="ip-chip"
                    onClick={() => setF((c) => ({ ...c, note: c.note.toLowerCase().includes(n.toLowerCase()) ? c.note : (c.note ? c.note + ", " : "") + n }))}>{n}</button>
                ))}
              </div>
            </div>
          </div>

          <div className="ip-pad">
            <div className="ip-readout">
              <span className="ip-label">{field === "qty" ? "Qty" : field === "price" ? "Price ₹" : f.discType === "PCT" ? "Discount %" : "Discount ₹"}</span>
              <span className="ip-big">{f[field] || "0"}</span>
            </div>
            <div className="ip-keys">
              {["1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "back"].map((k) => (
                <button key={k} type="button" className="ip-key" onClick={() => press(k)} disabled={k === "." && field === "qty"}
                  aria-label={k === "back" ? "Delete last digit" : k}>{k === "back" ? "⌫" : k}</button>
              ))}
            </div>
            <div className="ip-steps">
              <button type="button" className="btn" onClick={() => { setF({ ...f, qty: String(Math.max(0, qty - 1)) }); setFresh(true); }}>− 1</button>
              <button type="button" className="btn" onClick={() => { setF({ ...f, qty: String(Math.min(999, qty + 1)) }); setFresh(true); }}>+ 1</button>
              <button type="button" className="btn" onClick={() => press("clear")}>Clear</button>
            </div>
          </div>
        </div>

        <div className="ip-foot">
          <div className="ip-total">
            <span className="ip-label">{qty} × {inr(price)}{discount > 0 ? ` − ${inr(discount)}` : ""}</span>
            <span className="ip-big">{inr(total)}</span>
          </div>
          {line && <button className="btn danger" onClick={onRemove}>Remove</button>}
          <button className="btn" onClick={onClose}>Cancel</button>
          <button className="btn primary" onClick={save}>{line ? "Update" : "Add to order"}</button>
        </div>
      </div>
    </div>
  );
}
