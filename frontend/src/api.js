// Small fetch wrapper for the Spring Boot API: same-origin session cookie + CSRF header.

let onUnauthorized = () => {};
export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn;
}

function csrfToken() {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : null;
}

export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

export async function api(method, path, body) {
  const headers = {};
  const raw = body instanceof Blob; // a file: sent as it is (e.g. a backup to restore)
  if (body !== undefined) headers["Content-Type"] = raw ? "application/octet-stream" : "application/json";
  if (method !== "GET") {
    let token = csrfToken();
    if (!token) {
      // First write of the visit: any GET makes the server set the XSRF-TOKEN cookie.
      await fetch("/api/auth/config", { credentials: "same-origin" });
      token = csrfToken();
    }
    if (token) headers["X-XSRF-TOKEN"] = token;
  }

  let res;
  try {
    res = await fetch("/api/" + path, {
      method,
      headers,
      credentials: "same-origin",
      body: body === undefined ? undefined : raw ? body : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, "Can't reach the server. Check the connection.");
  }

  if (res.status === 204) return null;
  let data = null;
  try {
    data = await res.json();
  } catch {
    /* empty body */
  }
  if (res.status === 401 && !path.startsWith("auth/")) onUnauthorized();
  if (!res.ok) {
    const fallback = res.status === 403 ? "You don't have permission to do that" : `Request failed (${res.status})`;
    throw new ApiError(res.status, (data && data.error) || fallback);
  }
  return data;
}

// ---------- shared formatting + labels

export const SOURCES = { OFFLINE: "Offline", ZOMATO: "Zomato", SWIGGY: "Swiggy" };
export const TYPES = { DINE_IN: "Dine-in", TAKEAWAY: "Takeaway", DELIVERY: "Home delivery" };
export const PAYMENTS = { CASH: "Cash", UPI: "UPI", CARD: "Card", UNPAID: "Pay later", PLATFORM: "Paid online" };
export const STATUS_LABEL = { NEW: "New", PREPARING: "Preparing", READY: "Ready", COMPLETED: "Completed", CANCELLED: "Cancelled" };
export const NEXT_STATUS = { NEW: "PREPARING", PREPARING: "READY", READY: "COMPLETED" };
export const NEXT_LABEL = { NEW: "Start cooking", PREPARING: "Mark ready", READY: "Complete" };

export const DAY = 86400000;
export const startOfDay = (ms) => {
  const d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
};
export const inr = (n) => "₹" + Number(n || 0).toLocaleString("en-IN", { maximumFractionDigits: 2 });
export const inr0 = (n) => "₹" + Math.round(Number(n || 0)).toLocaleString("en-IN");
export const hhmm = (ms) => new Date(ms).toLocaleTimeString("en-IN", { hour: "numeric", minute: "2-digit" });
export const srcClass = (s) => (s || "").toLowerCase();

// Section pictures cropped from the restaurant's menu posters (frontend/public/icons).
// A section shows the picture whose file name matches its name, e.g. "Sides & Curry" -> sides-curry.jpg.
const ICONS = new Set(["beverages", "milkshakes", "fresh-juices", "maggi", "fries", "sandwiches", "bites", "bread",
  "main-plates", "sides-curry", "starters", "fried-rice", "noodles"]);
const ICON_ALIASES = { "non-veg-curries": "sides-curry", curries: "sides-curry", "non-veg-starters": "starters", biryani: "fried-rice" };
export function catIcon(category) {
  const slug = String(category || "").toLowerCase().replace(/&/g, " ").trim().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
  const name = ICONS.has(slug) ? slug : ICON_ALIASES[slug];
  return name ? `/icons/${name}.jpg` : null;
}
