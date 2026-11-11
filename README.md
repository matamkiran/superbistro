# Super Bistro — restaurant order desk

Take **offline**, **Zomato** and **Swiggy** orders in one place, run them through the kitchen,
see what sells best, and back everything up to Google Drive every night.

- **New order** — tap dishes (with section pictures from the menu), pick Offline (dine-in / takeaway /
  home delivery) or Zomato / Swiggy. Tapping a dish opens the **item popup**: Qty, price per unit,
  discount (% or ₹) and a kitchen note ("Less spicy", "No onion", …) with a large number pad for touch
  screens. Untick *Details on tap* for one-tap adding; tap any line on the ticket to edit it later.
- **Orders** — live kitchen board (New → Preparing → Ready → Done) on every screen, refreshes every
  5 seconds, shows kitchen notes, highlights orders waiting 20+ minutes, collects "pay later" bills.
- **Top selling** — best sellers with an Offline / Zomato / Swiggy split, sales, average bill, busy hours.
- **Menu** (admin page) — edit name, price, section and veg in place, search, rename a section, mark a
  section sold out, import from Excel/CSV, export to CSV.
- **Bill** — every order has a **Bill** button: restaurant name, address, phone, GSTIN, bill number,
  customer name and **mobile number**, items, discounts, GST (CGST/SGST), total, payment. Prints on an
  80 mm receipt printer, or **Send on WhatsApp** straight to the customer's mobile.
- **Store** (owner, manager) — three tabs:
  *In stock* (what you have: record Used / Waste / Count / Add, history per item),
  *To buy* (everything below its minimum with a suggested quantity; copy the list for WhatsApp),
  *To bring in* (saved lists; press **Receive goods** with actual qty and cost — stock goes up and the
  payment is written to the day book).
- **Day book** (owner, manager) — per day: sales by Cash / UPI / Card / Zomato-Swiggy / Pay later,
  expenses and money added, net for the day, and the **cash drawer check** (opening + cash sales + cash
  added − cash paid out = should be in drawer; enter the counted cash to see short/extra). Last 14 days table.
- **Settings** (owner) — restaurant name, GST %, bill details, staff logins (Owner / Manager / Staff).
- **System** (main admin) — nightly backups, Google Drive, backup history, download a backup,
  **restore** a backup (one click, or upload a backup file from Drive / another PC).

Made for one restaurant, Super Bistro: no sign-up page; the owner adds staff logins in Settings.

**Roles:** *Owner* everything · *Manager* orders, store, purchases, day book (not prices, staff or settings) ·
*Staff* orders and the kitchen board.

**On phones and tablets:** open the address in Chrome (Android) or Safari (iPhone) → menu →
**Add to Home screen / Install app**. It opens full-screen with the Super Bistro icon. Phones must be on the
same Wi-Fi as the billing PC, or use the hosted (https) address from anywhere. Over plain `http://<PC-IP>`
Android adds a shortcut rather than a full app; with https (hosted) it installs as a full app.

**Stack:** Spring Boot 4.1 (Java 17+) · PostgreSQL (built in, or your own PostgreSQL 15) · Flyway · Spring Security + Spring Session ·
React 19 (Vite). The React app is built into the Spring Boot jar.

---

## Install on a new computer (Windows)

Nothing else needs to be installed: Java and a private PostgreSQL database are inside Super Bistro.

1. **Install Super Bistro** — run `SuperBistro-1.0.0.exe`. If Windows says "Windows protected your PC",
   click **More info → Run anyway** (the installer isn't code-signed).
2. **Open Super Bistro** from the Start menu. A black console window opens (that's Super Bistro running,
   keep it open). There are no setup questions: the first start (up to a minute) creates the built-in
   database in `C:\ProgramData\SuperBistro\pgdata`, the restaurant **Super Bistro** with its full menu
   (116 dishes, GST 0), turns on *start with Windows*, and opens the login page.
3. **Sign in** with `admin` / `password` (shown on the login page on first start only), then choose your
   own password. The first-start password stops working once you've changed it.
4. **Settings**: address, GSTIN and bill footer; add staff logins (Manager / Staff).
5. **System → Google Drive**: connect Drive (below) so backups leave the building.

**Forgot the admin password?** On the billing PC: close the black Super Bistro window, then open
**Start → Super Bistro → Reset-Super-Bistro-Password**. Super Bistro starts with the admin password back to
`password` (you choose a new one at sign-in). All orders, menu and stock are kept. It only works on that
PC itself, not from phones or other computers. Without the Start-menu item: run `SuperBistro.exe --reset-admin`.

**Download:** always the newest version at
https://github.com/matamkiran/superbistro/releases/latest/download/SuperBistro-Setup.exe
(portable: `.../latest/download/SuperBistro-portable.zip`).

Close Super Bistro with the console window's **X** (or shut Windows down normally). If the PC loses
power, Super Bistro tidies up the database on the next start.

Kitchen tablets and phones on the same Wi-Fi open `http://<billing-PC-IP>:8080`.
Windows asks once to allow Java through the firewall — click **Allow**.
Every later update keeps the data: database changes are applied automatically on start.

### Build the installer

Without a Windows PC: push this folder to GitHub → **Actions → Windows installer → Run workflow** →
download `SuperBistro-windows` (contains the `.exe` and a portable zip).
On a Windows PC with JDK 21 (+ WiX 3.14 for the `.exe`): `packaging\windows\build-installer.bat`.

---

## Backups to Google Drive

Every day at the chosen time (default 23:30) Super Bistro saves a full backup (a `.zip` with all
orders, menu, store, day book, staff and settings) in `C:\ProgramData\SuperBistro\backups` (last 14 kept)
and uploads it to a **Super Bistro Backups** folder in Google Drive (last 30 kept). If the PC was off at
that time, the backup runs as soon as Super Bistro starts again. **System → Back up now** makes one
immediately. This works the same with the built-in database and with your own PostgreSQL.

**Connect Google Drive (one time, ~10 minutes):**
1. [Google Cloud Console](https://console.cloud.google.com) → create a project → enable **Google Drive API**.
2. **Google Auth Platform** → consent screen: *External*, app name "Super Bistro" → **Publish app**
   (while it says *Testing*, Google stops the connection after 7 days).
3. **Clients → Create client → Web application** → *Authorized redirect URI*: the address shown on the
   System page, normally `http://localhost:8080/api/system/google/callback`.
4. Paste the client ID and secret on the **System** page → **Connect Google Drive** → choose the Google
   account. Do this on the server PC itself (open `http://localhost:8080`).

Super Bistro uses the `drive.file` permission: it can only see files it uploaded, not the rest of the Drive.

**Restore:** **System → Restore a backup** — press **Restore** next to a backup in the list, or
**Choose backup file…** for a `.zip` downloaded from Google Drive or copied from another PC. It replaces
all current data with the backup (a safety backup of the current data is made first) and everyone logs
in again. The backup must come from the same Super Bistro version; to restore an older backup, install
that version, restore, then update.

**New computer:** install Super Bistro, finish setup, then **System → Restore a backup** with the latest
backup file from Google Drive.

Older `.dump` backups (made with pg_dump by the first version) restore with PostgreSQL's own tool,
with Super Bistro stopped:

```bat
pg_restore --clean --if-exists --no-owner -h localhost -U postgres -d superbistro superbistro_2026-11-10_2330.dump
```

---

## Other ways to run it

**Docker** (servers, cloud VMs — includes PostgreSQL 15):

```bash
cp .env.example .env      # set DB_PASSWORD (and GOOGLE_CLIENT_ID / SECRET if you like)
docker compose up -d --build
```

**Plain Java 17+:** `java -jar superbistro.jar` — with no database configured, the Windows/Mac build sets
itself up as above. To use your own PostgreSQL server or another restaurant name instead, start it once with
`SETUP=true` (or `-Dsuperbistro.setup=true`): that opens the setup page.
Or set `DB_URL`, `DB_USER`, `DB_PASSWORD` to skip setup. On Windows: `start.bat`.

**Free hosting** (demo only): Render (app, `render.yaml`) + Neon (PostgreSQL). Render's free plan sleeps
after 15 idle minutes and wakes in about a minute; Neon's free plan has 1 GB. Don't use Render's own free
PostgreSQL: it is deleted after 30 days. An always-on option is an Oracle Cloud "Always Free" VM with Docker.

---

## Configuration

Environment variables (or lines in `superbistro.properties`, written by setup):

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | from setup | PostgreSQL connection |
| `DATA_DIR` | `C:\ProgramData\SuperBistro` (Windows), `~/SuperBistro` | settings, backups |
| `PORT` | `8080` | web port |
| `TIME_ZONE` | `Asia/Kolkata` | order numbers restart at #1 each day; backup time |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | — | alternative to entering them on the System page |
| `OPEN_BROWSER` | `false` | open the browser on start (the installer sets it) |
| `EMBEDDED_DB` | from setup | `true`: built-in PostgreSQL (data in `DATA_DIR/pgdata`, port 54329, Windows and Mac builds) |

## Development

```bash
./mvnw test                                  # API tests (in-memory database)
./mvnw package                               # React + jar (downloads Node/Maven itself)
EMBEDDED_DB=true java -jar target/superbistro.jar
cd frontend && ./node/npm run dev            # React hot reload on :5173
```

Code: `src/main/java/com/superbistro/` — `setup` (first-run page, launcher), `auth`, `tenant`, `menu`,
`order`, `stats`, `backup` (zip backup/restore, Google Drive, System API), `config` (incl. the built-in database). Schema: `src/main/resources/db/migration`.
UI: `frontend/src/views/`. Section pictures: `frontend/public/icons/` (file name = section name, e.g. `sides-curry.jpg`).

## Not built yet

- Automatic Zomato / Swiggy order import (needs their partner API access, e.g. via UrbanPiper).
- Kitchen (KOT) slip printing; per-platform menu prices; deducting ingredients from stock automatically per dish sold.
- Pictures for the non-veg sections (send the non-veg menu photo as a file).
