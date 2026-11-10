import { useEffect, useState } from "react";
import { api, DAY, inr0, SOURCES, startOfDay, srcClass } from "../api.js";

const RANGES = [["today", "Today"], ["yesterday", "Yesterday"], ["7d", "7 days"], ["30d", "30 days"], ["90d", "90 days"]];

function bounds(range) {
  const t = startOfDay(Date.now());
  switch (range) {
    case "yesterday": return [t - DAY, t];
    case "7d": return [t - 6 * DAY, t + DAY];
    case "30d": return [t - 29 * DAY, t + DAY];
    case "90d": return [t - 89 * DAY, t + DAY];
    default: return [t, t + DAY];
  }
}

export default function Top({ orders, toast }) {
  const [range, setRange] = useState("today");
  const [source, setSource] = useState("ALL");
  const [st, setSt] = useState(null);

  // Refetch when the period or channel changes, and when new orders arrive.
  const ordersKey = orders.length + ":" + orders.filter((o) => o.status === "CANCELLED").length;
  useEffect(() => {
    let live = true;
    const [from, to] = bounds(range);
    const qs = `from=${from}&to=${to}` + (source === "ALL" ? "" : `&source=${source}`);
    api("GET", "stats?" + qs).then((d) => live && setSt(d)).catch((e) => live && toast(e.message, true));
    return () => { live = false; };
  }, [range, source, ordersKey, toast]);

  const head = (
    <div className="section-head">
      <h1>Top selling</h1>
      <div className="filters">
        <div className="seg" role="group" aria-label="Period">
          {RANGES.map(([k, v]) => <button key={k} aria-pressed={range === k} onClick={() => setRange(k)}>{v}</button>)}
        </div>
        <div className="seg src" role="group" aria-label="Source">
          {[["ALL", "All"], ...Object.entries(SOURCES)].map(([k, v]) => <button key={k} data-src={k} aria-pressed={source === k} onClick={() => setSource(k)}>{v}</button>)}
        </div>
      </div>
    </div>
  );
  if (!st) return <>{head}<div className="panel empty">Loading sales…</div></>;

  const aov = st.orders ? st.revenue / st.orders : 0;
  const revAll = Object.values(st.bySource).reduce((s, x) => s + Number(x.revenue), 0) || 1;
  const qtyTotal = st.top.reduce((s, x) => s + x.qty, 0);

  const kpis = (
    <div className="kpis">
      <div className="panel kpi"><div className="label">Orders</div><div className="v">{st.orders}</div><div className="s">cancelled orders excluded</div></div>
      <div className="panel kpi"><div className="label">Sales</div><div className="v">{inr0(st.revenue)}</div><div className="s">incl. GST, after discounts</div></div>
      <div className="panel kpi"><div className="label">Average bill</div><div className="v">{inr0(aov)}</div><div className="s">per order</div></div>
      <div className="panel kpi">
        <div className="label">Sales by channel</div>
        <div className="mix" role="img" aria-label={Object.entries(st.bySource).map(([k, v]) => `${SOURCES[k]} ${inr0(v.revenue)}`).join(", ")}>
          {Object.entries(st.bySource).map(([k, v]) => <span key={k} className={srcClass(k)} style={{ width: (Number(v.revenue) / revAll) * 100 + "%" }} />)}
        </div>
        <div className="s mix-legend">
          {Object.entries(st.bySource).map(([k, v]) => <span key={k}><i className={srcClass(k)} />{SOURCES[k]} {v.orders} · {inr0(v.revenue)}</span>)}
        </div>
      </div>
    </div>
  );

  if (!st.top.length) {
    return <>{head}{kpis}<div className="panel empty"><h3>No sales in this period</h3><p>Orders you place on the New order screen are ranked here.</p></div></>;
  }

  const maxQ = st.top[0].qty || 1;
  return (
    <>
      {head}
      {kpis}
      <div className="top-grid">
        <section className="panel">
          <div className="panel-h"><h2>Best sellers by quantity</h2><span className="hint">{qtyTotal} dishes sold</span></div>
          <div className="legend">{Object.entries(SOURCES).map(([k, v]) => <span key={k}><i className={srcClass(k)} />{v}</span>)}</div>
          <ol className="rank">
            {st.top.map((r, i) => (
              <li key={r.name + i}>
                <span className="pos">{i + 1}</span>
                <span className="nm">{r.name}</span>
                <span className="q">{r.qty}<small>{inr0(r.revenue)}</small></span>
                <span className="bar" style={{ width: (r.qty / maxQ) * 100 + "%" }}
                  title={Object.entries(r.qtyBySource).map(([k, n]) => `${SOURCES[k]} ${n}`).join(" · ")}>
                  {Object.entries(r.qtyBySource).map(([k, n]) => <span key={k} className={srcClass(k)} style={{ width: (n / r.qty) * 100 + "%" }} />)}
                </span>
              </li>
            ))}
          </ol>
        </section>
        <div className="side">
          {source === "ALL" && (
            <section className="panel">
              <div className="panel-h"><h2>Top 3 on each channel</h2></div>
              <div className="src-top">
                {Object.keys(SOURCES).map((k) => {
                  const list = st.top.filter((r) => r.qtyBySource[k] > 0).sort((a, b) => b.qtyBySource[k] - a.qtyBySource[k]).slice(0, 3);
                  return (
                    <div key={k}>
                      <h4><span className={"chip " + srcClass(k)}>{SOURCES[k]}</span><span className="label">{st.bySource[k].orders} orders</span></h4>
                      {list.length ? <ol>{list.map((r) => <li key={r.name}><span>{r.name}</span> — {r.qtyBySource[k]}</li>)}</ol> : <div className="muted">No sales</div>}
                    </div>
                  );
                })}
              </div>
            </section>
          )}
          <section className="panel">
            <div className="panel-h"><h2>Busy hours</h2><span className="hint">orders per hour</span></div>
            <div className="hours"><Hours hours={st.hours} /></div>
          </section>
        </div>
      </div>
    </>
  );
}

function Hours({ hours }) {
  let lo = 6, hi = 23;
  hours.forEach((n, h) => { if (n) { lo = Math.min(lo, h); hi = Math.max(hi, h); } });
  const hs = [];
  for (let h = lo; h <= hi; h++) hs.push(h);
  const W = 360, H = 130, padB = 18, padT = 14, bw = W / hs.length;
  const max = Math.max(1, ...hs.map((h) => hours[h]));
  const lab = (h) => (h % 12 || 12) + (h < 12 ? "a" : "p");
  return (
    <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Orders by hour of day">
      <line x1="0" x2={W} y1={H - padB} y2={H - padB} stroke="var(--line)" />
      {hs.map((h, i) => {
        const v = hours[h], bh = (v / max) * (H - padB - padT), x = i * bw + 2, y = H - padB - bh, cx = x + (bw - 4) / 2;
        return (
          <g key={h}>
            <rect x={x} y={y} width={bw - 4} height={v ? Math.max(bh, 2) : 0} rx="2" fill="var(--accent)" opacity={v === max ? 1 : 0.55}>
              <title>{`${lab(h)}: ${v} orders`}</title>
            </rect>
            {v === max && v > 0 && <text x={cx} y={y - 3} textAnchor="middle">{v}</text>}
            {i % 3 === 0 && <text x={cx} y={H - 4} textAnchor="middle">{lab(h)}</text>}
          </g>
        );
      })}
    </svg>
  );
}
