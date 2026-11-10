// Reads a menu from CSV text or cells pasted from Excel / Google Sheets.
// Columns (header row, any order, case-insensitive): Name, Category, Price, Veg
//   Veg accepts: yes/no, veg/non-veg, nonveg, y/n, true/false, 1/0. Missing = veg.

function splitRows(text) {
  const sep = text.includes("\t") ? "\t" : ",";
  const rows = [];
  let row = [], cell = "", q = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (q) {
      if (c === '"' && text[i + 1] === '"') { cell += '"'; i++; }
      else if (c === '"') q = false;
      else cell += c;
    } else if (c === '"') q = true;
    else if (c === sep) { row.push(cell); cell = ""; }
    else if (c === "\n" || c === "\r") {
      if (c === "\r" && text[i + 1] === "\n") i++;
      row.push(cell); rows.push(row); row = []; cell = "";
    } else cell += c;
  }
  if (cell !== "" || row.length) { row.push(cell); rows.push(row); }
  return rows.map((r) => r.map((x) => x.trim())).filter((r) => r.some((x) => x));
}

const NON_VEG = /^(no|n|false|0|non[\s-]?veg|nv|egg)$/i;

export function parseMenu(text) {
  const rows = splitRows(text.replace(/^﻿/, ""));
  if (!rows.length) return { items: [], errors: ["The file is empty."] };
  const head = rows[0].map((h) => h.toLowerCase());
  const col = (...names) => head.findIndex((h) => names.some((n) => h.includes(n)));
  let iName = col("name", "dish", "item"), iCat = col("categ", "section", "type"),
      iPrice = col("price", "rate", "mrp", "amount", "₹"), iVeg = col("veg");
  let body = rows.slice(1);
  if (iName < 0 || iPrice < 0) {
    // No header row: assume Name, Category, Price, Veg
    [iName, iCat, iPrice, iVeg] = [0, 1, 2, 3];
    body = rows;
  }
  const items = [], errors = [];
  body.forEach((r, n) => {
    const line = n + (body === rows ? 1 : 2);
    const name = r[iName] || "";
    const price = parseFloat(String(r[iPrice] || "").replace(/[₹,\s]|rs\.?/gi, ""));
    if (!name) return;
    if (!(price >= 0)) { errors.push(`Row ${line} (${name}): price "${r[iPrice] || ""}" is not a number`); return; }
    const vegCell = iVeg >= 0 ? r[iVeg] || "" : "";
    items.push({ name: name.slice(0, 80), category: (iCat >= 0 && r[iCat]) || "Other", price, veg: !NON_VEG.test(vegCell) });
  });
  return { items, errors };
}

export const TEMPLATE = "Name,Category,Price,Veg\n";
