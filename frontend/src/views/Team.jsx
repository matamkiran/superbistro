import { useEffect, useState } from "react";
import { api } from "../api.js";

export default function Team({ session, setSession, toast }) {
  const [staff, setStaff] = useState([]);
  const R = session.restaurant;
  const [r, setR] = useState({ name: R.name, gstPct: String(R.gstPct), address: R.address || "", phone: R.phone || "", gstin: R.gstin || "", billFooter: R.billFooter || "" });
  const [n, setN] = useState({ name: "", email: "", password: "", role: "STAFF" });
  const [confirm, setConfirm] = useState(null);

  const load = () => api("GET", "staff").then(setStaff).catch((e) => toast(e.message, true));
  useEffect(() => { load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const saveRestaurant = async (e) => {
    e.preventDefault();
    try {
      const saved = await api("PUT", "restaurant", { ...r, gstPct: parseFloat(r.gstPct || "0") });
      setSession({ ...session, restaurant: saved });
      toast("Saved");
    } catch (ex) {
      toast(ex.message, true);
    }
  };

  const addStaff = async (e) => {
    e.preventDefault();
    try {
      await api("POST", "staff", n);
      toast(`Added ${n.name}. They can sign in with ${n.email}.`);
      setN({ name: "", email: "", password: "", role: "STAFF" });
      load();
    } catch (ex) {
      toast(ex.message, true);
    }
  };

  const remove = async (u) => {
    setConfirm(null);
    try {
      await api("DELETE", "staff/" + u.id);
      toast(`Removed ${u.name}`);
      load();
    } catch (ex) {
      toast(ex.message, true);
    }
  };

  return (
    <>
      <div className="section-head"><h1>Settings</h1></div>
      <div className="menu-layout">
        <div className="side">
          <form className="panel form" onSubmit={saveRestaurant} autoComplete="off">
            <h2>Restaurant</h2>
            <div className="field"><label htmlFor="s-name">Name</label>
              <input className="input" id="s-name" value={r.name} onChange={(e) => setR({ ...r, name: e.target.value })} required /></div>
            <div className="field"><label htmlFor="s-gst">GST % added to bills</label>
              <input className="input mono" id="s-gst" value={r.gstPct} onChange={(e) => setR({ ...r, gstPct: e.target.value })} inputMode="decimal" placeholder="5" /></div>
            <p className="muted">Most restaurants without AC charge 5%. Set 0 if prices already include GST.</p>
            <div className="field"><label htmlFor="s-addr">Address on the bill</label>
              <input className="input" id="s-addr" value={r.address} onChange={(e) => setR({ ...r, address: e.target.value })} placeholder="Shop no, street, area, city" /></div>
            <div className="two">
              <div className="field"><label htmlFor="s-ph">Phone on the bill</label>
                <input className="input mono" id="s-ph" value={r.phone} onChange={(e) => setR({ ...r, phone: e.target.value })} placeholder="8147284442" /></div>
              <div className="field"><label htmlFor="s-gstin">GSTIN</label>
                <input className="input mono" id="s-gstin" value={r.gstin} onChange={(e) => setR({ ...r, gstin: e.target.value })} placeholder="Optional" /></div>
            </div>
            <div className="field"><label htmlFor="s-foot">Bill footer</label>
              <input className="input" id="s-foot" value={r.billFooter} onChange={(e) => setR({ ...r, billFooter: e.target.value })} placeholder="Thank you! Visit again." /></div>
            <button className="btn">Save</button>
          </form>

          <form className="panel form" onSubmit={addStaff} autoComplete="off">
            <h2>Add a login</h2>
            <div className="field"><label htmlFor="t-name">Name</label>
              <input className="input" id="t-name" value={n.name} onChange={(e) => setN({ ...n, name: e.target.value })} required placeholder="e.g. Ravi (cashier)" /></div>
            <div className="field"><label htmlFor="t-email">Email</label>
              <input className="input" id="t-email" type="email" value={n.email} onChange={(e) => setN({ ...n, email: e.target.value })} required /></div>
            <div className="field"><label htmlFor="t-pass">Password</label>
              <input className="input" id="t-pass" type="text" value={n.password} onChange={(e) => setN({ ...n, password: e.target.value })} required minLength={8} placeholder="At least 8 characters" /></div>
            <div className="field"><label htmlFor="t-role">Role</label>
              <select className="input" id="t-role" value={n.role} onChange={(e) => setN({ ...n, role: e.target.value })}>
                <option value="STAFF">Staff: takes orders, runs the kitchen board</option>
                <option value="MANAGER">Manager: also store room, purchases and day book</option>
                <option value="OWNER">Owner: also edits menu, prices and logins</option>
              </select></div>
            <button className="btn primary">Add login</button>
          </form>
        </div>

        <section className="panel mtable-wrap">
          <div className="panel-h"><h2>People who can sign in</h2></div>
          <table className="mtable">
            <thead><tr><th>Name</th><th>Email</th><th>Role</th><th /></tr></thead>
            <tbody>
              {staff.map((u) => (
                <tr key={u.id}>
                  <td>{u.name}{u.id === session.user.id && <span className="label"> · you</span>}</td>
                  <td>{u.email}</td>
                  <td><span className="chip neutral">{u.role === "OWNER" ? "Owner" : u.role === "MANAGER" ? "Manager" : "Staff"}</span></td>
                  <td><div className="acts">
                    {u.id !== session.user.id && (confirm === u.id ? (
                      <><button className="btn sm danger" onClick={() => remove(u)}>Remove</button><button className="btn sm" onClick={() => setConfirm(null)}>Keep</button></>
                    ) : <button className="btn sm danger" onClick={() => setConfirm(u.id)}>Remove</button>)}
                  </div></td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      </div>
    </>
  );
}
