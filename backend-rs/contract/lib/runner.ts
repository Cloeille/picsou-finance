import { SQL } from "bun";
import { Database as SqliteDatabase } from "bun:sqlite";
import { CookieJar, send } from "./http.ts";
import { type Json, toPlain } from "./json.ts";
import { type NormalizedCookie, Normalizer } from "./normalize.ts";
import { day, resolve, type FilePart, type HttpStep, type Scenario, Vars } from "./scenario.ts";
import type { Instance } from "./launcher.ts";

export interface StepRecord {
  /** "03 POST /api/accounts/{account} as admin": the template, so goldens never hold raw ids. */
  step: string;
  status: number;
  headers: Record<string, string>;
  cookies: NormalizedCookie[];
  bodyKind: "empty" | "json" | "text" | "binary";
  body: Json;
}

export interface RunResult {
  scenario: string;
  steps: StepRecord[];
  /** Steps whose status missed the scenario's `expect`. */
  unmet: string[];
}

/** Dates a scenario writes into a path (`from=${day(-7)}`) are normalized too, so labels do not move daily. */
export function stepLabel(index: number, s: HttpStep, norm: Normalizer): string {
  return `${String(index + 1).padStart(2, "0")} ${s.method} ${norm.text(s.path)} as ${s.as}`;
}

async function buildBody(step: HttpStep, vars: Vars): Promise<{ body?: string | FormData; headers: Record<string, string> }> {
  const headers: Record<string, string> = { ...(step.headers === undefined ? {} : await resolve(step.headers, vars)) };
  if (step.raw) {
    headers["content-type"] = step.raw.contentType;
    return { body: await resolve(step.raw.body, vars), headers };
  }
  if (step.multipart !== undefined) {
    const parts = await resolve(step.multipart, vars);
    const form = new FormData();
    for (const [name, part] of Object.entries(parts)) {
      if (typeof part === "string") form.append(name, part);
      else {
        const f = part as FilePart;
        form.append(name, new Blob([f.content], { type: f.contentType }), f.filename);
      }
    }
    return { body: form, headers };
  }
  if (step.json !== undefined) {
    headers["content-type"] = "application/json";
    return { body: JSON.stringify(await resolve(step.json, vars)), headers };
  }
  return { headers };
}

/** Translates PostgreSQL fixture SQL from contract scenarios for bun:sqlite. */
function translateSqliteFixture(sql: string): string {
  return sql
    // TIMESTAMPTZ '2024-01-02 03:04:05+00' -> '2024-01-02T03:04:05Z'
    .replace(/\bTIMESTAMPTZ\s+'([^']+)'/gi, (_m, ts: string) => {
      const iso = ts.trim().replace(" ", "T").replace(/\+00(?::00)?$/, "Z");
      return `'${iso}'`;
    })
    // DATE 'YYYY-MM-DD' -> 'YYYY-MM-DD'
    .replace(/\bDATE\s+'([^']+)'/gi, "'$1'")
    // decode('deadbeef', 'hex') -> X'deadbeef'
    .replace(/\bdecode\(\s*'([0-9a-fA-F]*)'\s*,\s*'hex'\s*\)/gi, "X'$1'")
    // date_trunc('month', CURRENT_DATE)::date +/- N -> date('now', 'start of month', '+/-N days')
    .replace(
      /\bdate_trunc\(\s*'month'\s*,\s*CURRENT_DATE\s*\)(?:::\w+)?\s*([+-])\s*(\d+)\b/gi,
      (_m, sign: string, n: string) => `date('now', 'start of month', '${sign}${n} days')`,
    )
    .replace(
      /\bdate_trunc\(\s*'month'\s*,\s*CURRENT_DATE\s*\)(?:::\w+)?/gi,
      "date('now', 'start of month')",
    )
    // CURRENT_DATE +/- N -> date('now', '+/-N days')
    .replace(
      /\bCURRENT_DATE\s*([+-])\s*(\d+)\b/gi,
      (_m, sign: string, n: string) => `date('now', '${sign}${n} days')`,
    )
    // Quote bare `transaction` table name (reserved keyword in SQLite)
    .replace(/\b(FROM|JOIN|INTO|UPDATE|TABLE)\s+transaction\b/gi, '$1 "transaction"')
    // NOW() -> strftime UTC RFC3339
    .replace(/\bnow\(\s*\)/gi, "strftime('%Y-%m-%dT%H:%M:%fZ', 'now')")
    // Strip PostgreSQL ::type casts outside quotes
    .replace(/::(?:text|varchar|bigint|int|integer|numeric|decimal|date|timestamptz|timestamp|boolean|bool|jsonb|json|account_type|category_kind|rate_basis|recurring_cadence|recurring_status|requisition_status|savings_product|sharing_level)(?:\(\d+(?:,\s*\d+)?\))?(?:\[\])*/gi, "");
}

function execSqliteFixture(sqlite: SqliteDatabase, rawSql: string) {
  const translated = translateSqliteFixture(rawSql).trim();
  // Support PG data-modifying CTEs used by contract fixtures:
  // `WITH m AS (INSERT INTO ... RETURNING id) INSERT INTO ... FROM m`
  const cteMatch = /^WITH\s+(\w+)\s+AS\s*\(\s*(INSERT\s+INTO\s+[\s\S]+?\s+RETURNING\s+(\w+))\s*\)\s*([\s\S]+)$/i.exec(
    translated,
  );
  if (cteMatch) {
    const [, alias, innerInsert, retCol, outerStmt] = cteMatch;
    const row = sqlite.query(innerInsert!).get() as Record<string, unknown> | null;
    if (!row || row[retCol!] === undefined) {
      throw new Error(`SQLite CTE fixture returned no ${retCol}: ${innerInsert}`);
    }
    const idVal = row[retCol!];
    const rewritten = outerStmt!.replace(
      new RegExp(`\\bFROM\\s+${alias}\\b`, "gi"),
      `FROM (SELECT ${typeof idVal === "number" ? idVal : `'${String(idVal).replace(/'/g, "''")}'`} AS ${retCol})`,
    );
    sqlite.exec(rewritten);
  } else {
    sqlite.exec(translated);
  }
}

export async function runScenario(scenario: Scenario, instance: Instance): Promise<RunResult> {
  const vars = new Vars();
  const norm = new Normalizer(day(0));
  const jars = new Map<string, CookieJar>();
  const steps: StepRecord[] = [];
  const unmet: string[] = [];
  let db: SQL | undefined;
  let sqlite: SqliteDatabase | undefined;
  try {
    for (const [i, step] of scenario.steps.entries()) {
      if (step.kind === "sql") {
        const query = await resolve(step.sql, vars);
        norm.observeSent(query);
        if (instance.sqlitePath) {
          if (!sqlite) {
            sqlite = new SqliteDatabase(instance.sqlitePath);
            // bun:sqlite does not register Rust's dec_cmp() UDF used in CHECK constraints; the
            // Picsou backend connection itself registers dec_cmp() and enforces all CHECKs.
            sqlite.exec("PRAGMA busy_timeout = 5000; PRAGMA foreign_keys = ON; PRAGMA ignore_check_constraints = ON;");
          }
          execSqliteFixture(sqlite, query);
        } else {
          db ??= new SQL(instance.databaseUrl);
          await db.unsafe(query);
        }
        continue;
      }
      let jar = jars.get(step.as);
      if (!jar) {
        jar = new CookieJar();
        jars.set(step.as, jar);
      }
      const { body, headers } = await buildBody(step, vars);
      const path = vars.fill(step.path);
      norm.observeSent(path);
      for (const v of Object.values(headers)) norm.observeSent(v);
      if (typeof body === "string") norm.observeSent(body);
      else if (body instanceof FormData) {
        for (const v of body.values()) {
          if (typeof v === "string") norm.observeSent(v);
        }
      }
      const label = stepLabel(i, step, norm);
      const res = await send(instance.baseUrl, jar, { method: step.method, path, headers, body });
      if (step.expect !== undefined && res.status !== step.expect) {
        unmet.push(`${label}: expected ${step.expect}, got ${res.status} ${res.text.slice(0, 300)}`);
      }
      const plain = res.json === undefined ? null : toPlain(res.json);
      for (const [name, pick] of Object.entries(step.capture ?? {})) {
        let value: unknown;
        try {
          value = pick({ status: res.status, json: plain, text: res.text, headers: res.headers });
        } catch (e) {
          throw new Error(`${label}: capture '${name}' failed: ${(e as Error).message}`);
        }
        if (value === undefined || value === null) throw new Error(`${label}: capture '${name}' found nothing in ${res.text.slice(0, 300)}`);
        vars.set(name, value);
      }
      if (step.compare === false) continue;
      // Body first: it registers the ids a Location header may then refer to.
      const normalizedBody =
        res.bodyKind === "json"
          ? norm.body(step.keep ? step.keep(res.json!) : res.json!)
          : res.bodyKind === "text"
            ? norm.text(res.text)
            : res.bodyKind === "binary"
              ? "<binary>"
              : null;
      steps.push({
        step: label,
        status: res.status,
        headers: norm.headers(res.headers),
        cookies: norm.cookies(res.setCookies),
        bodyKind: res.bodyKind,
        body: normalizedBody,
      });
    }
  } finally {
    await db?.close();
    sqlite?.close();
  }
  return { scenario: scenario.name, steps, unmet };
}
