import { useCallback, useEffect, useRef, useState } from "react";
import { api, setUnauthorizedHandler, startOfDay, DAY } from "./api.js";
import Login, { ChangePassword } from "./views/Login.jsx";
import Pos from "./views/Pos.jsx";
import Orders from "./views/Orders.jsx";
import Top from "./views/Top.jsx";
import Menu from "./views/Menu.jsx";
import Team from "./views/Team.jsx";
import System from "./views/System.jsx";
import Stock from "./views/Stock.jsx";
import DayBook from "./views/DayBook.jsx";
import Customers from "./views/Customers.jsx";
import KotSlip from "./views/KotSlip.jsx";

const POLL_MS = 5000;
const VIEWS = ["pos", "orders", "customers", "top", "menu", "stock", "daybook", "team", "system"];

function useToast() {
  const [toast, setToast] = useState(null);
  const timer = useRef(null);
  const show = useCallback((msg, isErr = false) => {
    setToast({ msg, isErr });
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setToast(null), isErr ? 5000 : 2600);
  }, []);
  return [toast, show];
}

export default function App() {
  const [session, setSession] = useState(undefined); // undefined = checking, null = signed out
  const [view, setView] = useState(() => {
    const h = location.hash.slice(1);
    return VIEWS.includes(h) ? h : "pos";
  });
  const [menu, setMenu] = useState([]);
  const [orders, setOrders] = useState([]);
  const [online, setOnline] = useState(true);
  const [toast, showToast] = useToast();
  const [addTo, setAddTo] = useState(null); // open order that the New order screen adds dishes to
  const [kotJob, setKotJob] = useState(null); // { order, round } being printed for the kitchen

  // Kitchen slip: render it, print it, then forget it.
  const printKot = useCallback((order, round = null) => setKotJob({ order, round, at: Date.now() }), []);
  useEffect(() => {
    if (!kotJob) return;
    document.body.classList.add("printing-kot");
    const done = () => {
      document.body.classList.remove("printing-kot");
      setKotJob(null);
    };
    window.addEventListener("afterprint", done, { once: true });
    const t = setTimeout(() => window.print(), 60);
    return () => {
      clearTimeout(t);
      window.removeEventListener("afterprint", done);
      document.body.classList.remove("printing-kot");
    };
  }, [kotJob]);

  useEffect(() => {
    const onHash = () => {
      const h = location.hash.slice(1);
      if (VIEWS.includes(h)) setView(h);
    };
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(() => setSession(null));
    api("GET", "auth/me").then(setSession).catch(() => setSession(null));
  }, []);

  const loadMenu = useCallback(async () => setMenu(await api("GET", "menu")), []);
  const loadOrders = useCallback(async () => {
    const from = startOfDay(Date.now()) - DAY; // yesterday + today for the board
    setOrders(await api("GET", "orders?from=" + from));
  }, []);

  // Load data once signed in, then keep orders fresh for every till / kitchen screen.
  useEffect(() => {
    if (!session || session.user.mustChangePassword) return;
    let stop = false;
    loadMenu().catch((e) => showToast(e.message, true));
    const tick = async () => {
      try {
        await loadOrders();
        if (!stop) setOnline(true);
      } catch {
        if (!stop) setOnline(false);
      }
    };
    tick();
    const id = setInterval(tick, POLL_MS);
    return () => {
      stop = true;
      clearInterval(id);
    };
  }, [session, loadMenu, loadOrders, showToast]);

  const go = (v) => {
    setView(v);
    history.replaceState(null, "", "#" + v);
    window.scrollTo(0, 0);
  };

  const logout = async () => {
    try {
      await api("POST", "auth/logout");
    } catch {
      /* already signed out */
    }
    setSession(null);
    setMenu([]);
    setOrders([]);
  };

  if (session === undefined) return <div className="boot">Loading…</div>;
  if (session === null) return <Login onSignedIn={(s) => { setSession(s); go("pos"); }} />;
  if (session.user.mustChangePassword) return <ChangePassword onDone={(s) => { setSession(s); go("pos"); }} onCancel={logout} />;

  const isOwner = session.user.role === "OWNER";
  const isAdmin = !!session.user.platformAdmin;
  const canStore = isOwner || session.user.role === "MANAGER";
  const live = orders.filter((o) => o.status !== "COMPLETED" && o.status !== "CANCELLED").length;
  const tabs = [
    ["pos", "New order"],
    ["orders", "Orders"],
    ...(canStore ? [["customers", "Customers"]] : []),
    ["top", "Top selling"],
    ["menu", "Menu"],
    ...(canStore ? [["stock", "Store"], ["daybook", "Day book"]] : []),
    ...(isOwner ? [["team", "Settings"]] : []),
    ...(isAdmin ? [["system", "System"]] : []),
  ];
  const blocked = (view === "team" && !isOwner) || (view === "system" && !isAdmin) || ((view === "stock" || view === "daybook" || view === "customers") && !canStore);
  const current = blocked ? "pos" : view;

  const ctx = {
    session, setSession, isOwner, canStore, menu, setMenu, loadMenu, orders, setOrders, loadOrders, toast: showToast, go,
    addTo, setAddTo, printKot,
  };

  return (
    <>
      <header className="topbar">
        <div className="topbar-in">
          <div className="brand">
            <span className="brand-mark" aria-hidden="true" />
            <span>{session.restaurant.name}</span>
          </div>
          <nav className="tabs" aria-label="Sections">
            {tabs.map(([k, label]) => (
              <button key={k} className="tab" aria-current={current === k ? "page" : undefined} onClick={() => go(k)}>
                {label}
                {k === "orders" && live > 0 && <span className="count">{live}</span>}
              </button>
            ))}
          </nav>
          <div className="who">
            <span className={"conn " + (online ? "ok" : "bad")} title="Connection to the server">
              {online ? "Live" : "Offline"}
            </span>
            <span className="who-name">{session.user.name}</span>
            <button className="btn sm" onClick={logout}>Sign out</button>
          </div>
        </div>
      </header>

      <main className="wrap">
        {current === "pos" && <Pos {...ctx} />}
        {current === "orders" && <Orders {...ctx} />}
        {current === "top" && <Top {...ctx} />}
        {current === "menu" && <Menu {...ctx} />}
        {current === "team" && <Team {...ctx} />}
        {current === "system" && <System {...ctx} />}
        {current === "stock" && <Stock {...ctx} />}
        {current === "daybook" && <DayBook {...ctx} />}
        {current === "customers" && <Customers {...ctx} />}
      </main>

      {kotJob && <KotSlip order={kotJob.order} round={kotJob.round} />}

      {toast && (
        <div className={"toast" + (toast.isErr ? " err" : "")} role="status" aria-live="polite">
          {toast.msg}
        </div>
      )}
    </>
  );
}
