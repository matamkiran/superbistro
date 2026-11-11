import { useEffect, useState } from "react";
import { api } from "../api.js";

export default function Login({ onSignedIn }) {
  const [first, setFirst] = useState(null); // ready-made login on a fresh install / after a reset
  const [f, setF] = useState({ email: "", password: "" });
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api("GET", "auth/config").then((c) => {
      if (c.firstStart) setFirst({ login: c.defaultLogin, password: c.defaultPassword });
    }).catch(() => {});
  }, []);

  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });

  const submit = async (e) => {
    e.preventDefault();
    setErr("");
    setBusy(true);
    try {
      onSignedIn(await api("POST", "auth/login", { email: f.email, password: f.password }));
    } catch (ex) {
      setErr(ex.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login">
      <section className="login-card panel">
        <div className="brand login-brand">
          <span className="brand-mark" aria-hidden="true" />
          <span>Super Bistro</span>
        </div>
        <p className="login-sub">Offline, Zomato and Swiggy orders in one place.</p>

        <form className="form-grid" onSubmit={submit}>
          <div className="field">
            <label htmlFor="l-email">Login (email)</label>
            <input className="input" id="l-email" type="text" autoCapitalize="none" value={f.email} onChange={set("email")} required autoComplete="username" />
          </div>
          <div className="field">
            <label htmlFor="l-pass">Password</label>
            <input className="input" id="l-pass" type="password" value={f.password} onChange={set("password")} required autoComplete="current-password" />
          </div>
          {first && (
            <div className="first-start">
              <p>Sign in with <code>{first.login}</code> and password <code>{first.password}</code>.
                You'll choose your own password next.</p>
              <button type="button" className="btn sm" onClick={() => setF({ email: first.login, password: first.password })}>Fill in</button>
            </div>
          )}
          {err && <div className="form-err" role="alert">{err}</div>}
          <button className="btn primary place" disabled={busy}>{busy ? "Please wait…" : "Sign in"}</button>
        </form>
        <About />
        <p className="login-foot">Forgot the password? On the billing PC: close Super Bistro, then Start → Super Bistro → Reset-Super-Bistro-Password.</p>
      </section>
    </div>
  );
}

/** Shown right after signing in with the ready-made login: pick a private password. */
export function ChangePassword({ onDone, onCancel }) {
  const [f, setF] = useState({ current: "", password: "", again: "" });
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });

  const submit = async (e) => {
    e.preventDefault();
    setErr("");
    if (f.password !== f.again) { setErr("The two new passwords don't match"); return; }
    setBusy(true);
    try {
      onDone(await api("POST", "auth/password", { current: f.current, password: f.password }));
    } catch (ex) {
      setErr(ex.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login">
      <section className="login-card panel">
        <div className="brand login-brand">
          <span className="brand-mark" aria-hidden="true" />
          <span>Super Bistro</span>
        </div>
        <p className="login-sub">Choose your own password. Only you will know it; the first-start password stops working.</p>
        <form className="form-grid" onSubmit={submit}>
          <div className="field">
            <label htmlFor="cp-cur">Current password</label>
            <input className="input" id="cp-cur" type="password" value={f.current} onChange={set("current")} required autoComplete="current-password" />
          </div>
          <div className="field">
            <label htmlFor="cp-new">New password</label>
            <input className="input" id="cp-new" type="password" value={f.password} onChange={set("password")} required minLength={8} autoComplete="new-password" placeholder="At least 8 characters" />
          </div>
          <div className="field">
            <label htmlFor="cp-again">New password again</label>
            <input className="input" id="cp-again" type="password" value={f.again} onChange={set("again")} required minLength={8} autoComplete="new-password" />
          </div>
          {err && <div className="form-err" role="alert">{err}</div>}
          <button className="btn primary place" disabled={busy}>{busy ? "Please wait…" : "Save password"}</button>
          <button type="button" className="btn" onClick={onCancel}>Sign out</button>
        </form>
      </section>
    </div>
  );
}

const ADDRESS = "Sri Vara Nilayam, 148, 2nd Cross Rd, Sahakar Nagar, H A Farm Post, Maruthi Nagar, Dasarahalli, Bengaluru, Karnataka 560024";
const MAP = "https://maps.app.goo.gl/14f1R2zu5z1gddjq6";

/** About Super Bistro: address, phone and the way there. */
export function About() {
  return (
    <div className="about">
      <h2>About Super Bistro</h2>
      <p>{ADDRESS}</p>
      <p>Very near Manyata Tech Park and Brigade Magnum.</p>
      <p>Phone: <a href="tel:+918147284442">081472 84442</a></p>
      <a className="btn sm" href={MAP} target="_blank" rel="noopener noreferrer">Open in Google Maps</a>
    </div>
  );
}
