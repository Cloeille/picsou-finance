import { bootstrap } from "../lib/flows.ts";
import { day, post, sql, type Scenario, type Vars } from "../lib/scenario.ts";

/** A cell: a string (inline), a number written verbatim as the stored value, or a formula. */
type Cell = string | { n: string } | { f: string; v: string } | null;

const esc = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

function sheetXml(rows: Cell[][]): string {
  const body = rows
    .map((cells, r) => {
      const xml = cells
        .map((c, i) => {
          const ref = String.fromCharCode(65 + i) + (r + 1);
          if (c === null) return "";
          if (typeof c === "string") return '<c r="' + ref + '" t="inlineStr"><is><t>' + esc(c) + "</t></is></c>";
          if ("f" in c) return '<c r="' + ref + '"><f>' + esc(c.f) + "</f><v>" + c.v + "</v></c>";
          return '<c r="' + ref + '"><v>' + c.n + "</v></c>";
        })
        .join("");
      return '<row r="' + (r + 1) + '">' + xml + "</row>";
    })
    .join("");
  return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>' + body + "</sheetData></worksheet>";
}

/** An uncompressed (stored) ZIP of the given entries. */
function zip(entries: [string, string][]): Uint8Array {
  const enc = new TextEncoder();
  const local: Uint8Array[] = [];
  const central: Uint8Array[] = [];
  let offset = 0;
  for (const [name, text] of entries) {
    const data = enc.encode(text);
    const fname = enc.encode(name);
    const crc = Bun.hash.crc32(data) >>> 0;
    const head = new DataView(new ArrayBuffer(30));
    head.setUint32(0, 0x04034b50, true);
    head.setUint16(4, 20, true);
    head.setUint32(14, crc, true);
    head.setUint32(18, data.length, true);
    head.setUint32(22, data.length, true);
    head.setUint16(26, fname.length, true);
    const dir = new DataView(new ArrayBuffer(46));
    dir.setUint32(0, 0x02014b50, true);
    dir.setUint16(4, 20, true);
    dir.setUint16(6, 20, true);
    dir.setUint32(16, crc, true);
    dir.setUint32(20, data.length, true);
    dir.setUint32(24, data.length, true);
    dir.setUint16(28, fname.length, true);
    dir.setUint32(42, offset, true);
    local.push(new Uint8Array(head.buffer), fname, data);
    central.push(new Uint8Array(dir.buffer), fname);
    offset += 30 + fname.length + data.length;
  }
  const size = central.reduce((n, b) => n + b.length, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true);
  end.setUint16(8, entries.length, true);
  end.setUint16(10, entries.length, true);
  end.setUint32(12, size, true);
  end.setUint32(16, offset, true);
  const parts = [...local, ...central, new Uint8Array(end.buffer)];
  const out = new Uint8Array(parts.reduce((n, b) => n + b.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}

/** A minimal XLSX workbook with one worksheet per entry, in order. */
function xlsx(sheets: [string, Cell[][]][]): Uint8Array {
  const ns = "http://schemas.openxmlformats.org";
  const types = sheets
    .map((_, i) => '<Override PartName="/xl/worksheets/sheet' + (i + 1) + '.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>')
    .join("");
  return zip([
    ["[Content_Types].xml", '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="' + ns + '/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' + types + "</Types>"],
    ["_rels/.rels", '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="' + ns + '/package/2006/relationships"><Relationship Id="rId1" Type="' + ns + '/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>'],
    ["xl/workbook.xml", '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="' + ns + '/spreadsheetml/2006/main" xmlns:r="' + ns + '/officeDocument/2006/relationships"><sheets>' + sheets.map(([name], i) => '<sheet name="' + esc(name) + '" sheetId="' + (i + 1) + '" r:id="rId' + (i + 1) + '"/>').join("") + "</sheets></workbook>"],
    ["xl/_rels/workbook.xml.rels", '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="' + ns + '/package/2006/relationships">' + sheets.map((_, i) => '<Relationship Id="rId' + (i + 1) + '" Type="' + ns + '/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet' + (i + 1) + '.xml"/>').join("") + "</Relationships>"],
    ...sheets.map(([, rows], i): [string, string] => ["xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(rows)]),
  ]);
}

const HEADER: Cell[] = ["Name", "Institution", "Balance", "Currency"];
const TX_HEADER: Cell[] = ["Category", "Date", "Name", "Amount", "Type", "Account", "Institution", "Currency"];

/** A Finary export: three account sheets (one named in lower case), edge-case rows, and transactions. */
function finaryExport(): Uint8Array {
  return xlsx([
    ["Checkings", [HEADER, ["Compte courant", "Bank", { n: "1250.5" }, "EUR"], ["Joint", "Bank", { n: "-42" }, null], ["Formula", "Bank", { f: "1+1", v: "2" }, "EUR"], ["  ", "Bank", { n: "5" }, "EUR"]]],
    ["savings", [HEADER, ["Livret A", " Boursorama ", { n: "12345678" }, "EUR"], [{ n: "2024.9" }, "Bank", { n: "0.1" }, "EUR"], ["Tiny", "Bank", { n: "0.0001" }, "EUR"]]],
    ["Cryptos", [HEADER, ["Ledger", "Ledger", { n: "1234.5600000000001" }, "EUR"]]],
    ["Notes", [HEADER, ["Ignored", "x", { n: "1" }, "EUR"]]],
    ["Transactions", [
      TX_HEADER,
      ["income", day(-1), "Salaire", { n: "2000" }, "credit", "COMPTE COURANT", "Bank", "EUR"],
      ["food", day(-1), "Courses", { n: "-50.25" }, "debit", "Compte courant", "Bank", "EUR"],
      ["rent", day(-5), "Loyer", { n: "-800" }, "debit", "compte courant", "Bank", null],
      [null, day(-2), null, { n: "100" }, null, "Livret A", null, "EUR"],
      ["bad", "2024/01/01", "Bad date", { n: "1" }, "debit", "Compte courant", "Bank", "EUR"],
      ["numeric", { n: "45000" }, "Numeric date", { n: "1" }, "debit", "Compte courant", "Bank", "EUR"],
      ["none", day(-3), "No amount", "12", "debit", "Compte courant", "Bank", "EUR"],
      ["orphan", day(-4), "Orphan", { n: "3" }, "debit", "Unknown", "Bank", "EUR"],
    ]],
  ]);
}

const file = (content: Uint8Array | string, filename = "finary.xlsx") => ({
  file: { filename, contentType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content },
});

type Preview = { fileToken: string; suggestedMappings: { finaryId: string; finaryName: string; finaryCategory: string; action: string; targetAccountId?: number }[] };

const ADMIN_MEMBER = "(SELECT member_id FROM app_user WHERE username = 'admin')";

/** The mapping the wizard sends: the backend's suggestions, with chosen rows created or skipped. */
function mappings(v: Vars) {
  const p = v.get("preview") as Preview;
  return p.suggestedMappings.map((m) => {
    if (m.finaryName === "Livret A") {
      return { ...m, action: "CREATE_NEW", newAccount: { name: "Livret A", type: "SAVINGS", provider: "Boursorama", currency: "EUR" } };
    }
    if (m.finaryName === "Ledger") {
      return { ...m, action: "CREATE_NEW", newAccount: { name: "Ledger", type: "CRYPTO", currency: "EUR", color: "#123456" } };
    }
    if (m.action === "MAP_EXISTING") return m;
    return { ...m, action: "SKIP" };
  });
}

const importFlow: Scenario = {
  name: "transfer.finary.import",
  description: "Finary XLSX preview, then an import that maps, creates and skips accounts and rebuilds their history",
  steps: [
    ...bootstrap(),
    sql("a manual checking account the preview matches by name and type", () =>
      "INSERT INTO account (member_id, name, type, currency, current_balance, is_manual, color) VALUES (" + ADMIN_MEMBER + ", 'Compte courant', 'CHECKING', 'EUR', 100, true, '#6366f1')"),
    sql("a hand-entered statement two days ago, which the rebuild keeps", () =>
      "INSERT INTO balance_snapshot (account_id, date, balance, invested_amount, source, native_balance) SELECT id, '" + day(-2) + "', 777, 777, 'MANUAL', 777 FROM account WHERE name = 'Compte courant'"),
    post("/api/finary/preview", undefined, {
      multipart: () => file(finaryExport()),
      expect: 200,
      capture: { preview: (r) => r.json },
    }),
    post("/api/finary/import", (v) => ({ fileToken: (v.get("preview") as Preview).fileToken, mappings: mappings(v) }), { expect: 200 }),
    post("/api/finary/import", (v) => ({ fileToken: (v.get("preview") as Preview).fileToken, mappings: mappings(v) }), {
      expect: 400,
      note: "a consumed preview cannot be imported twice",
    }),
    post("/api/finary/preview", undefined, {
      multipart: () => file(finaryExport()),
      expect: 200,
      note: "the created accounts are now found by their external id",
    }),
  ],
};

const errors: Scenario = {
  name: "transfer.finary.errors",
  description: "Finary import failures: bad uploads, unknown tokens, shared, foreign or unknown targets",
  steps: [
    ...bootstrap(),
    sql("another member with an account of their own", () =>
      "WITH m AS (INSERT INTO family_member (display_name) VALUES ('Other') RETURNING id) INSERT INTO account (member_id, name, type, currency, current_balance) SELECT id, 'Foreign', 'CHECKING', 'EUR', 5 FROM m"),
    post("/api/finary/preview", undefined, { multipart: () => file("not a spreadsheet", "finary.txt"), expect: 400 }),
    post("/api/finary/preview", undefined, { multipart: () => file(new Uint8Array(0)), expect: 400 }),
    post("/api/finary/preview", undefined, { multipart: { other: "x" }, expect: 400 }),
    post("/api/finary/import", { fileToken: "00000000-0000-0000-0000-000000000000", mappings: [] }, { expect: 400 }),
    post("/api/finary/preview", undefined, {
      multipart: () => file(xlsx([["Checkings", [HEADER, ["Joint", "Bank", { n: "10" }, "EUR"], ["Perso", "Bank", { n: "20" }, "EUR"]]]])),
      expect: 200,
      capture: { preview: (r) => r.json },
    }),
    post("/api/finary/import", (v) => {
      const p = v.get("preview") as Preview;
      return { fileToken: p.fileToken, mappings: p.suggestedMappings.map((m) => ({ ...m, action: "MAP_EXISTING", targetAccountId: 1 })) };
    }, { expect: 400, note: "two rows onto one account" }),
    post("/api/finary/import", (v) => {
      const p = v.get("preview") as Preview;
      return { fileToken: p.fileToken, mappings: [{ ...p.suggestedMappings[1], action: "MAP_EXISTING", targetAccountId: 1 }] };
    }, { expect: 400, note: "an account of another member" }),
    post("/api/finary/import", (v) => {
      const p = v.get("preview") as Preview;
      return { fileToken: p.fileToken, mappings: [{ ...p.suggestedMappings[0], action: "MAP_EXISTING", targetAccountId: 999999 }] };
    }, { expect: 400, note: "an unknown target" }),
    post("/api/finary/import", (v) => {
      const p = v.get("preview") as Preview;
      return { fileToken: p.fileToken, mappings: [{ ...p.suggestedMappings[1], action: "CREATE_NEW", newAccount: { name: "Perso", type: "CHECKING", currency: "EUR" } }] };
    }, { expect: 200, note: "the failed import left the preview usable" }),
  ],
};

export const scenarios: Scenario[] = [importFlow, errors];
