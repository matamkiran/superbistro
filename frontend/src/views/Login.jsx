import { useEffect, useState } from "react";
import { api } from "../api.js";

export default function Login({ onSignedIn }) {
  const [mode, setMode] = useState("login");
  const [allowSignup, setAllowSignup] = useState(true);
  const [f, setF] = useState({ restaurantName: "", name: "", email: "", password: "" });
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api("GET", "auth/config").then((c) => setAllowSignup(!!c.allowSignup)).catch(() => {});
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
            <label htmlFor="l-email">Email</label>
            <input className="input" id="l-email" type="email" value={f.email} onChange={set("email")} required autoComplete="username" />
          </div>
          <div className="field">
            <label htmlFor="l-pass">Password</label>
            <input className="input" id="l-pass" type="password" value={f.password} onChange={set("password")} required minLength={mode === "signup" ? 8 : undefined}
              autoComplete={mode === "login" ? "current-password" : "new-password"} placeholder={mode === "signup" ? "At least 8 characters" : ""} />
          </div>
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
