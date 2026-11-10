import { useMemo, useState } from "react";
import { api, catIcon, inr } from "../api.js";
import { parseMenu, TEMPLATE } from "../menuImport.js";

const blank = { name: "", price: "", category: "", veg: true };
const csvCell = (v) => (/[",\n]/.test(String(v)) ? `"${String(v).replace(/"/g, '""')}"` : String(v));

/**
 * Menu admin. Owners: add, edit in place, delete, rename sections, import/export.
 * Staff: search and switch dishes or whole sections sold out / back on.
 */
export default function Menu({ menu, setMenu, loadMenu, isOwner, toast }) {
  const [q, setQ] = useState("");
  const [cat, setCat] = useState("All");
  const [show, setShow] = useState("all"); // all | off
  const [edit, setEdit] = useState(null); // { id, name, price, category, veg }
  const [renaming, setRenaming] = useState(null); // { from, to }
  const [confirm, setConfirm] = useState(null);
  const [adding, setAdding] = useState(false);
  const [f, setF] = useState(blank);
  const [importing, setImporting] = useState(false);
  const [paste, setPaste] = useState("");
  const [busy, setBusy] = useState(false);

  const cats = useMemo(() => [...new Set(menu.map((m) => m.category))].sort((a, b) => a.localeCompare(b)), [menu]);
  const needle = q.trim().toLowerCase();
  const shown = menu.filter((m) =>
    (cat === "All" || m.category === cat) && (show === "all" || !m.active) && (!needle || m.name.toLowerCase().includes(needle)));
  const grouped = {};
  shown.forEach((m) => (grouped[m.category] ||= []).push(m));
  const offCount = menu.filter((m) => !m.active).length;
  const parsed = paste.trim() ? parseMenu(paste) : null;

  const run = async (fn, okMsg) => {
    setBusy(true);
    try {
      const res = await fn();
      if (okMsg) toast(typeof okMsg === "function" ? okMsg(res) : okMsg);
      return res;
    } catch (ex) {
      toast(ex.message, true);
      return undefined;
    } finally {
      setBusy(false);
    }
  };

  // ---------- add / edit / delete
  const addDish = async (e) => {
    e.preventDefault();
    const price = parseFloat(f.price);
    if (!f.name.trim()) return toast("Enter the dish name", true);
    if (!(price >= 0)) return toast("Enter a price in rupees, like 120", true);
    const ok = await run(() => api("POST", "menu", { name: f.name, price, category: f.category || "Other", veg: f.veg }), `Added ${f.name.trim()}`);
    if (ok) {
      setF({ ...blank, category: f.category });
      await loadMenu();
      document.getElementById("a-name")?.focus();
    }
  };

  const startEdit = (m) => { setEdit({ id: m.id, name: m.name, price: String(m.price), category: m.category, veg: m.veg }); setConfirm(null); };
  const saveEdit = async () => {
    const price = parseFloat(edit.price);
    if (!edit.name.trim()) return toast("Enter the dish name", true);
    if (!(price >= 0)) return toast("Enter a price in rupees, like 120", true);
    const saved = await run(() => api("PUT", "menu/" + edit.id, { name: edit.name, price, category: edit.category || "Other", veg: edit.veg }), `Saved ${edit.name.trim()}`);
    if (saved) {
      setMenu((all) => all.map((x) => (x.id === saved.id ? saved : x)));
      setEdit(null);
    }
  };
  const editKeys = (e) => {
    if (e.key === "Enter") { e.preventDefault(); saveEdit(); }
    if (e.key === "Escape") setEdit(null);
  };

  const remove = async (m) => {
    setConfirm(null);
    const ok = await run(() => api("DELETE", "menu/" + m.id), `Deleted ${m.name}`);
    if (ok !== undefined) setMenu((all) => all.filter((x) => x.id !== m.id));
  };

  const toggle = async (m) => {
    setMenu((all) => all.map((x) => (x.id === m.id ? { ...x, active: !m.active } : x)));
    try {
      await api("PATCH", `menu/${m.id}/availability`, { active: !m.active });
    } catch (ex) {
      toast(ex.message, true);
      loadMenu();
    }
  };

  // ---------- sections
  const setSection = async (c, active) => {
    const res = await run(() => api("POST", "menu/category/availability", { category: c, active }), `${c}: ${active ? "all dishes back on" : "all sold out"}`);
    if (res) setMenu(res);
  };
  const saveRename = async () => {
    const to = renaming.to.trim();
    if (!to || to === renaming.from) return setRenaming(null);
    const res = await run(() => api("POST", "menu/category/rename", { from: renaming.from, to }), `Renamed ${renaming.from} to ${to}`);
    if (res) {
      setMenu(res);
      if (cat === renaming.from) setCat(to);
      setRenaming(null);
    }
  };

  // ---------- import / export
  const readFile = (e) => {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    if (/\.xlsx?$/i.test(file.name)) return toast("In Excel use File → Save As → CSV, or copy the cells and paste them in the box", true);
    const r = new FileReader();
    r.onload = () => setPaste(String(r.result || ""));
    r.readAsText(file);
  };
  const doImport = async () => {
    if (!parsed?.items.length) return;
    const res = await run(() => api("POST", "menu/import", { items: parsed.items }), (r) => `Menu imported: ${r.added} added, ${r.updated} updated`);
    if (res) { setMenu(res.menu); setPaste(""); setImporting(false); }
  };
  const exportCsv = () => {
    const rows = [["Name", "Category", "Price", "Veg", "Available"], ...menu.map((m) => [m.name, m.category, m.price, m.veg ? "yes" : "no", m.active ? "yes" : "no"])];
    const blob = new Blob(["﻿" + rows.map((r) => r.map(csvCell).join(",")).join("\n")], { type: "text/csv;charset=utf-8" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "menu.csv";
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  };

  const cols = isOwner ? 5 : 4;

  return (
    <>
      <div className="section-head">
        <div>
          <h1>Menu</h1>
          <p className="muted">{menu.length} dishes in {cats.length} sections · {offCount ? `${offCount} sold out` : "all available"}</p>
        </div>
        {isOwner && (
          <div className="filters">
            <button className="btn" onClick={exportCsv} disabled={!menu.length}>Export CSV</button>
            <button className="btn" aria-pressed={importing} onClick={() => { setImporting(!importing); setAdding(false); }}>Import</button>
            <button className="btn primary" onClick={() => { setAdding(!adding); setImporting(false); setTimeout(() => document.getElementById("a-name")?.focus(), 30); }}>
              {adding ? "Close" : "Add dish"}
            </button>
          </div>
        )}
      </div>

      {isOwner && adding && (
        <form className="panel admin-add" onSubmit={addDish} autoComplete="off">
          <div className="field grow"><label htmlFor="a-name">Dish name</label>
            <input className="input" id="a-name" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} placeholder="e.g. Ghee Roast Dosa" /></div>
          <div className="field"><label htmlFor="a-price">Price ₹</label>
            <input className="input mono" id="a-price" value={f.price} onChange={(e) => setF({ ...f, price: e.target.value })} inputMode="decimal" placeholder="120" /></div>
          <div className="field"><label htmlFor="a-cat">Section</label>
            <input className="input" id="a-cat" list="cat-list" value={f.category} onChange={(e) => setF({ ...f, category: e.target.value })} placeholder="Starters" /></div>
          <label className="check"><input type="checkbox" checked={f.veg} onChange={(e) => setF({ ...f, veg: e.target.checked })} /> Veg</label>
          <button className="btn primary" disabled={busy}>Add</button>
        </form>
      )}

      {isOwner && importing && (
        <section className="panel form import-panel">
          <h2>Import from a spreadsheet</h2>
          <p className="muted">Columns: <b>Name, Category, Price, Veg</b> (yes/no). Paste cells from Excel or Google Sheets, or choose a CSV. Dishes with the same name get the new price and section. Nothing is deleted.</p>
          <textarea className="input mono" id="m-import" rows={6} value={paste} onChange={(e) => setPaste(e.target.value)}
            placeholder={"Name\tCategory\tPrice\tVeg"} />
          <div className="import-row">
            <label className="btn sm file-btn">Choose CSV file<input type="file" accept=".csv,.txt,.tsv,.xls,.xlsx" onChange={readFile} /></label>
            <a className="btn sm" href={"data:text/csv;charset=utf-8," + encodeURIComponent(TEMPLATE)} download="menu-template.csv">Download template</a>
          </div>
          {parsed && (
            <div className="import-check">
              <b>{parsed.items.length} dish{parsed.items.length === 1 ? "" : "es"} ready</b>
              {parsed.errors.slice(0, 5).map((er) => <div key={er} className="form-err">{er}</div>)}
              {parsed.errors.length > 5 && <div className="muted">…and {parsed.errors.length - 5} more rows to fix</div>}
            </div>
          )}
          <div><button type="button" className="btn primary" disabled={!parsed?.items.length || busy} onClick={doImport}>
            {parsed?.items.length ? `Import ${parsed.items.length} dishes` : "Import"}
          </button></div>
        </section>
      )}

      <div className="admin-bar">
        <input className="input" type="search" placeholder="Search dishes…" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search dishes" />
        <select className="input" value={cat} onChange={(e) => setCat(e.target.value)} aria-label="Section">
          <option value="All">All sections</option>
          {cats.map((c) => <option key={c} value={c}>{c}</option>)}
        </select>
        <div className="seg" role="group" aria-label="Show">
          <button aria-pressed={show === "all"} onClick={() => setShow("all")}>All</button>
          <button aria-pressed={show === "off"} onClick={() => setShow("off")}>Sold out{offCount ? ` (${offCount})` : ""}</button>
        </div>
      </div>
      <datalist id="cat-list">{cats.map((c) => <option key={c} value={c} />)}</datalist>

      <section className="panel mtable-wrap">
        {!menu.length ? (
          <div className="empty">
            <h3>No dishes yet</h3>
            <p>{isOwner ? "Use Import to load your whole menu from a spreadsheet, or Add dish." : "The owner hasn't added dishes yet."}</p>
          </div>
        ) : !shown.length ? (
          <div className="empty">No dishes match. Clear the search or pick another section.</div>
        ) : (
          <table className="mtable admin">
            <thead><tr><th>Dish</th><th>Section</th><th className="r">Price</th><th>Available</th>{isOwner && <th />}</tr></thead>
            <tbody>
              {Object.keys(grouped).sort((a, b) => a.localeCompare(b)).map((c) => {
                const all = menu.filter((m) => m.category === c);
                const allOn = all.every((m) => m.active);
                return [
                  <tr key={"c-" + c} className="cat-row">
                    <td colSpan={cols}>
                      <div className="cat-head">
                        {renaming?.from === c ? (
                          <span className="rename">
                            <input className="input" autoFocus value={renaming.to} aria-label={`New name for ${c}`}
                              onChange={(e) => setRenaming({ ...renaming, to: e.target.value })}
                              onKeyDown={(e) => { if (e.key === "Enter") saveRename(); if (e.key === "Escape") setRenaming(null); }} />
                            <button className="btn sm primary" onClick={saveRename} disabled={busy}>Save</button>
                            <button className="btn sm" onClick={() => setRenaming(null)}>Cancel</button>
                          </span>
                        ) : (
                          <span className="cat-name">{catIcon(c) && <img src={catIcon(c)} alt="" />}{c} <span className="label">· {all.length}</span></span>
                        )}
                        <span className="cat-acts">
                          {isOwner && renaming?.from !== c && <button className="btn sm" onClick={() => setRenaming({ from: c, to: c })}>Rename</button>}
                          <button className="btn sm" onClick={() => setSection(c, !allOn)} disabled={busy}>{allOn ? "All sold out" : "All available"}</button>
                        </span>
                      </div>
                    </td>
                  </tr>,
                  ...grouped[c].map((m) => edit?.id === m.id ? (
                    <tr key={m.id} className="editing">
                      <td>
                        <div className="edit-name">
                          <label className="check" title="Vegetarian"><input type="checkbox" checked={edit.veg} onChange={(e) => setEdit({ ...edit, veg: e.target.checked })} /> Veg</label>
                          <input className="input" autoFocus value={edit.name} onChange={(e) => setEdit({ ...edit, name: e.target.value })} onKeyDown={editKeys} aria-label="Dish name" />
                        </div>
                      </td>
                      <td><input className="input" list="cat-list" value={edit.category} onChange={(e) => setEdit({ ...edit, category: e.target.value })} onKeyDown={editKeys} aria-label="Section" /></td>
                      <td className="r"><input className="input mono price-in" value={edit.price} onChange={(e) => setEdit({ ...edit, price: e.target.value })} onKeyDown={editKeys} inputMode="decimal" aria-label="Price" /></td>
                      <td />
                      <td><div className="acts">
                        <button className="btn sm primary" onClick={saveEdit} disabled={busy}>Save</button>
                        <button className="btn sm" onClick={() => setEdit(null)}>Cancel</button>
                      </div></td>
                    </tr>
                  ) : (
                    <tr key={m.id} className={m.active ? "" : "off"} onDoubleClick={() => isOwner && startEdit(m)}>
                      <td><span className="item-name"><span className={"dot " + (m.veg ? "veg" : "nonveg")} title={m.veg ? "Veg" : "Non-veg"} />{m.name}</span></td>
                      <td className="muted-cell">{m.category}</td>
                      <td className="mono r">{inr(m.price)}</td>
                      <td><button className="switch" role="switch" aria-checked={m.active} aria-label={`${m.name} available`} onClick={() => toggle(m)} /></td>
                      {isOwner && (
                        <td><div className="acts">
                          {confirm === m.id ? (
                            <><button className="btn sm danger" onClick={() => remove(m)}>Delete</button><button className="btn sm" onClick={() => setConfirm(null)}>Keep</button></>
                          ) : (
                            <><button className="btn sm" onClick={() => startEdit(m)}>Edit</button>
                              <button className="btn sm danger" onClick={() => setConfirm(m.id)} aria-label={`Delete ${m.name}`}>Delete</button></>
                          )}
                        </div></td>
                      )}
                    </tr>
                  )),
                ];
              })}
            </tbody>
          </table>
        )}
      </section>
      {isOwner && menu.length > 0 && <p className="muted hint-line">Tip: double-click a dish to edit it. Enter saves, Esc cancels.</p>}
    </>
  );
}
