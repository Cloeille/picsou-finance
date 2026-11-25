import { JNum, type Json } from "./json.ts";
import { sentDateLabel } from "./scenario.ts";

/**
 * Turns one run's raw responses into the comparable form. Stateful: ids and tokens get their
 * placeholder number by first appearance within the scenario, so the same id reads the same in
 * every step and two runs that create things in the same order read identically.
 */

/** Response headers that are part of the contract. Everything else (Date, Vary, length...) is ignored. */
export const COMPARED_HEADERS = [
  "content-type",
  "content-disposition",
  "cache-control",
  "pragma",
  "expires",
  "location",
  "www-authenticate",
  "retry-after",
  "x-content-type-options",
  "x-frame-options",
  "content-security-policy",
  "referrer-policy",
  "permissions-policy",
  "strict-transport-security",
  "access-control-allow-origin",
  "access-control-allow-credentials",
] as const;

/** Keys whose string values are secrets even when they look ordinary (8-digit recovery codes). */
const SECRET_KEYS = /^(secret|recoveryCodes|.*[tT]oken|password|.*Hash)$/;
const ID_KEY = /^(id|.*[a-z]Id)$/;
const IDS_KEY = /^.*[a-z]Ids$/;

const JWT = /\beyJ[\w-]+\.[\w-]+\.[\w-]+/g;
const UUID = /\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b/g;
const DATA_URI = /^data:([\w.+-]+\/[\w.+-]+)(;[^,]*)?,.*$/s;
const PEM = /-----BEGIN ([A-Z ]+)-----[\s\S]*?-----END \1-----/g;
const DATETIME = /(?<!\d)\d{4}-\d\d-\d\d([T ])\d\d:\d\d(:\d\d(?:\.\d+)?)?(Z|[+-]\d\d(:?)\d\d)?(?![\d:.])/g;
const COMPACT_DATETIME = /(?<!\d)\d{8}[-T_]?\d{6}(?!\d)/g;
const DATE = /(?<!\d)(\d{4})-(\d\d)-(\d\d)(?!\d)/g;
const YEAR_MONTH = /(?<!\d)(\d{4})-(\d\d)(?!-?\d)/g;
const TOKENISH = /[A-Za-z0-9_+=-]{20,}/g;
/** A link's last path segment is a token whatever it looks like (activation and reset links). */
const LINK_KEY = /^.*(Link|Url)$/;
const HTTP_DATE = /\b(Mon|Tue|Wed|Thu|Fri|Sat|Sun), \d\d [A-Z][a-z]{2} \d{4} \d\d:\d\d:\d\d GMT\b/g;

const DAY_MS = 86_400_000;

export interface NormalizedCookie {
  name: string;
  value: "<set>" | "<empty>";
  attributes: Record<string, string | true>;
}

export class Normalizer {
  private readonly ids = new Map<string, string>();
  private readonly tokens = new Map<string, string>();
  private readonly sent = new Set<string>();
  private readonly todayUtc: number;

  constructor(today: string) {
    this.todayUtc = Date.parse(`${today}T00:00:00Z`);
  }

  /** Records ISO dates sent by this scenario so `day(n)` / `monthEdge(k, edge)` labels never leak across scenarios. */
  observeSent(text: string): void {
    for (const m of text.matchAll(DATE)) {
      this.sent.add(m[0]);
    }
  }

  /** Registers an id seen outside a JSON body (a capture), so later paths and bodies map it. */
  id(raw: string | number): string {
    const key = String(raw);
    let p = this.ids.get(key);
    if (!p) {
      p = `<id:${this.ids.size + 1}>`;
      this.ids.set(key, p);
    }
    return p;
  }

  private token(raw: string, kind = "token"): string {
    let p = this.tokens.get(raw);
    if (!p) {
      p = `<${kind}:${this.tokens.size + 1}>`;
      this.tokens.set(raw, p);
    }
    return p;
  }

  body(value: Json, key?: string): Json {
    if (value instanceof JNum) {
      return key && ID_KEY.test(key) && /^-?\d+$/.test(value.raw) ? this.id(value.raw) : value;
    }
    if (typeof value === "string") {
      // Distinct from a numeric id, so `"5"` against `5` stays a difference.
      if (key && ID_KEY.test(key) && /^\d+$/.test(value)) return `${this.id(value)}:string`;
      if (key && SECRET_KEYS.test(key) && value !== "") return this.token(value, "secret");
      if (key && LINK_KEY.test(key)) {
        const m = /^(.*\/)([^/?#]{16,})$/.exec(value);
        if (m) return this.text(m[1]!) + this.token(m[2]!);
      }
      return this.text(value);
    }
    if (Array.isArray(value)) {
      const elementKey = key && IDS_KEY.test(key) ? "id" : key && SECRET_KEYS.test(key) ? key : undefined;
      return value.map((v) => this.body(v, elementKey));
    }
    if (value && typeof value === "object") {
      const out: { [k: string]: Json } = {};
      // Sorted, so placeholder numbering does not depend on the order a serializer writes fields.
      for (const k of Object.keys(value).sort()) {
        const v = value[k]!;
        // Maps keyed by id (history's per-account points) carry the id in the key.
        const nk = /^\d+$/.test(k) ? this.id(k) : k;
        out[nk] = this.body(v, k);
      }
      return out;
    }
    return value;
  }

  /** Placeholders for everything run-dependent inside free text, in a fixed order. */
  text(s: string): string {
    const whole = DATA_URI.exec(s);
    if (whole) return `<data-uri:${whole[1]}>`;
    let out = s
      .replace(PEM, (_, type: string) => `<pem:${type.toLowerCase().replace(/ /g, "-")}>`)
      .replace(JWT, "<jwt>")
      .replace(UUID, (u) => this.token(u.toLowerCase(), "uuid"))
      .replace(HTTP_DATE, "<http-date>")
      .replace(DATETIME, (_m, sep: string, seconds: string | undefined, zone: string | undefined, colon: string | undefined) => {
        const base = zone === undefined ? "local-datetime" : zone === "Z" ? "instant" : "offset-datetime";
        const shape = [sep === " " ? "space" : "", seconds ? "" : "no-seconds", zone && zone !== "Z" && colon === "" ? "hhmm" : ""];
        return `<${[base, ...shape.filter(Boolean)].join(":")}>`;
      })
      .replace(COMPACT_DATETIME, "<compact-datetime>")
      .replace(DATE, (m, y: string, mo: string, d: string) => this.date(m, Number(y), Number(mo), Number(d)))
      .replace(YEAR_MONTH, (m, y: string, mo: string) => this.month(m, Number(y), Number(mo)))
      .replace(TOKENISH, (t) => (looksRandom(t) ? this.token(t) : t));
    if (out.startsWith("/") || /^https?:\/\//.test(out)) {
      out = out.replace(/\/(\d+)(?=[/?#]|$)/g, (m, n: string) => (this.ids.has(n) ? `/${this.ids.get(n)}` : m));
    }
    return out;
  }

  /**
   * A date near today becomes an offset; a month boundary becomes a month anchor, since server
   * dates like a cycle start move with the calendar, not with the day the golden was recorded.
   */
  private date(literal: string, y: number, m: number, d: number): string {
    const t = Date.UTC(y, m - 1, d);
    if (Number.isNaN(t) || m < 1 || m > 12 || d < 1 || d > 31) return literal;
    if (this.sent.has(literal)) {
      const sent = sentDateLabel(literal);
      if (sent) return sent;
    }
    const n = Math.round((t - this.todayUtc) / DAY_MS);
    if (n === 0) return "<today>";
    const k = this.monthDelta(y, m);
    const lastDay = new Date(Date.UTC(y, m, 0)).getUTCDate();
    if (Math.abs(k) <= 36 && d === 1) return `<month${k === 0 ? "" : signed(k)}:first>`;
    if (Math.abs(k) <= 36 && d === lastDay) return `<month${k === 0 ? "" : signed(k)}:last>`;
    if (Math.abs(n) <= 400) return `<today${signed(n)}>`;
    return literal;
  }

  private month(literal: string, y: number, m: number): string {
    if (m < 1 || m > 12) return literal;
    const k = this.monthDelta(y, m);
    return Math.abs(k) <= 36 ? `<month${k === 0 ? "" : signed(k)}>` : literal;
  }

  private monthDelta(y: number, m: number): number {
    const today = new Date(this.todayUtc);
    return (y - today.getUTCFullYear()) * 12 + (m - 1 - today.getUTCMonth());
  }

  headers(h: Headers): Record<string, string> {
    const out: Record<string, string> = {};
    for (const name of COMPARED_HEADERS) {
      const v = h.get(name);
      if (v !== null) out[name] = this.text(v);
    }
    return out;
  }

  cookies(setCookies: string[]): NormalizedCookie[] {
    const parsed = setCookies.map((line) => {
      const [pair, ...attrs] = line.split(";").map((p) => p.trim());
      const eq = pair!.indexOf("=");
      const name = pair!.slice(0, eq);
      const value = pair!.slice(eq + 1);
      const attributes: Record<string, string | true> = {};
      for (const a of attrs) {
        if (!a) continue;
        const i = a.indexOf("=");
        const k = (i < 0 ? a : a.slice(0, i)).toLowerCase();
        // SameSite's value is case-insensitive for browsers.
        attributes[k] = i < 0 ? true : k === "expires" ? "<http-date>" : k === "samesite" ? a.slice(i + 1).toLowerCase() : a.slice(i + 1);
      }
      return { name, value: value === "" || value === '""' ? "<empty>" : "<set>", attributes } as NormalizedCookie;
    });
    // Distinct cookies may come in any order; same-name lines keep theirs (the last one wins).
    return parsed.map((c, i) => ({ c, i })).sort((a, b) => a.c.name.localeCompare(b.c.name) || a.i - b.i).map((x) => x.c);
  }
}

function signed(n: number): string {
  return n >= 0 ? `+${n}` : String(n);
}

/**
 * Generated tokens mix cases and digits (base64) or are long hex / base32 runs; identifiers, enum
 * names, words and dashed file names are neither.
 */
function looksRandom(t: string): boolean {
  if (/[a-z]/.test(t) && /[A-Z]/.test(t) && /\d/.test(t)) return true;
  const digits = (t.match(/\d/g) ?? []).length;
  if (/^[0-9a-f]{26,}$/.test(t)) return digits >= 4;
  // Base32 has letters throughout; an IBAN is two letters and digits.
  return /^[0-9A-Z]{26,}$/.test(t) && digits >= 4 && (t.match(/[A-Z]/g) ?? []).length >= 8;
}
