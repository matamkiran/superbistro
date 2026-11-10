import { useEffect, useState } from "react";
import { api } from "../api.js";

export default function Login({ onSignedIn }) {
  const [mode, setMode] = useState("login");
  const [allowSignup, setAllowSignup] = useState(false);
  const [first, setFirst] = useState(null); // ready-made login on a fresh install
  const [f, setF] = useState({ restaurantName: "", name: "", email: "", password: "" });
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api("GET", "auth/config").then((c) => {
      setAllowSignup(!!c.allowSignup);
      if (c.firstStart) setFirst({ login: c.defaultLogin, password: c.defaultPassword });
    }).catch(() => {});
  }, []);

  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });

  const submit = async (e) => {
    e.preventDefault();
    setErr("");
    setBusy(true);
    try {
      const s = mode === "login"
        ? await api("POST", "auth/login", { email: f.email, password: f.password })
        : await api("POST", "auth/signup", f);
      onSignedIn(s);
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

        {allowSignup && (
          <div className="seg" role="group" aria-label="Sign in or create">
            <button type="button" aria-pressed={mode === "login"} onClick={() => setMode("login")}>Sign in</button>
            <button type="button" aria-pressed={mode === "signup"} onClick={() => setMode("signup")}>Create restaurant</button>
          </div>
        )}

        <form className="form-grid" onSubmit={submit}>
          {mode === "signup" && (
            <>
              <div className="field">
                <label htmlFor="l-rest">Restaurant name</label>
                <input className="input" id="l-rest" value={f.restaurantName} onChange={set("restaurantName")} required placeholder="e.g. Super Bistro" />
              </div>
              <div className="field">
                <label htmlFor="l-name">Your name</label>
                <input className="input" id="l-name" value={f.name} onChange={set("name")} required autoComplete="name" />
              </div>
            </>
          )}
          <div className="field">
            <label htmlFor="l-email">{mode === "login" ? "Login (email or username)" : "Email"}</label>
            <input className="input" id="l-email" type={mode === "login" ? "text" : "email"} autoCapitalize="none" value={f.email} onChange={set("email")} required autoComplete="username" />
          </div>
          <div className="field">
            <label htmlFor="l-pass">Password</label>
            <input className="input" id="l-pass" type="password" value={f.password} onChange={set("password")} required minLength={mode === "signup" ? 8 : undefined}
              autoComplete={mode === "login" ? "current-password" : "new-password"} placeholder={mode === "signup" ? "At least 8 characters" : ""} />
          </div>
          {mode === "login" && first && (
            <div className="first-start">
              <p><b>First start.</b> Sign in with <code>{first.login}</code> and password <code>{first.password}</code>.
                You'll choose your own password next.</p>
              <button type="button" className="btn sm" onClick={() => setF({ ...f, email: first.login, password: first.password })}>Fill in</button>
            </div>
          )}
          {err && <div className="form-err" role="alert">{err}</div>}
          <button className="btn primary place" disabled={busy}>
            {busy ? "Please wait…" : mode === "login" ? "Sign in" : "Create restaurant"}
          </button>
        </form>
        {mode === "signup" && <p className="login-foot">You become the owner. Add cashier and kitchen logins later in Settings.</p>}
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
