// Lets phones install Super Bistro as an app. It always uses the live server (no offline copy),
// so orders and prices are never stale.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (e) => e.waitUntil(self.clients.claim()));
self.addEventListener("fetch", () => {});
