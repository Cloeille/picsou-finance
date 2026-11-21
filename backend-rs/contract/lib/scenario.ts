import type { Json } from "./json.ts";
import { currentStep, totpAt, TOTP_PERIOD_SECONDS } from "./totp.ts";

export type Method = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

/** What a capture or a body builder sees of a response. */
export interface Response {
  status: number;
  /** Body parsed with JSON.parse (numbers lossy), or null when the body is not JSON. */
  json: any;
  text: string;
  headers: Headers;
}

export type Lazy<T> = T | ((v: Vars) => T | Promise<T>);

export interface FilePart {
  filename: string;
  contentType: string;
  content: string | Uint8Array;
}

export interface HttpStep {
  kind: "http";
  /** Cookie jar the request uses. Every name gets its own jar; the first use creates it. */
  as: string;
  method: Method;
  /** Path with {var} placeholders filled from captures. The template, not the filled path, is what goldens record. */
  path: string;
  json?: Lazy<unknown>;
  multipart?: Lazy<Record<string, string | FilePart>>;
  /** A body sent verbatim, for malformed-input cases. */
  raw?: { contentType: string; body: Lazy<string> };
  headers?: Lazy<Record<string, string>>;
  /** Status the scenario expects. `record` refuses to write a golden that misses it. */
  expect?: number;
  capture?: Record<string, (r: Response) => unknown>;
  /** Default true. False keeps a step's response out of the comparison (setup noise only). */
  compare?: boolean;
  /**
   * Reduces a JSON body before it is normalized, for responses whose size depends on the time
   * of day (intraday series): keep the shape, drop what the clock decides.
   */
  keep?: (body: Json) => Json;
  note?: string;
}

/** Fixture written straight into the scenario's database, for state no HTTP endpoint creates. */
export interface SqlStep {
  kind: "sql";
  note: string;
  sql: Lazy<string>;
}

export type Step = HttpStep | SqlStep;

export interface Scenario {
  name: string;
  description: string;
  steps: Step[];
}

type HttpOpts = Omit<HttpStep, "kind" | "method" | "path" | "as" | "json"> & { as?: string };

const http = (method: Method, path: string, json: Lazy<unknown> | undefined, opts: HttpOpts = {}): HttpStep => ({
  kind: "http",
  method,
  path,
  ...(json === undefined ? {} : { json }),
  as: "admin",
  ...opts,
});

export const get = (path: string, opts?: HttpOpts) => http("GET", path, undefined, opts);
export const del = (path: string, opts?: HttpOpts) => http("DELETE", path, undefined, opts);
export const post = (path: string, json?: Lazy<unknown>, opts?: HttpOpts) => http("POST", path, json, opts);
export const put = (path: string, json?: Lazy<unknown>, opts?: HttpOpts) => http("PUT", path, json, opts);
export const patch = (path: string, json?: Lazy<unknown>, opts?: HttpOpts) => http("PATCH", path, json, opts);
export const sql = (note: string, query: Lazy<string>): SqlStep => ({ kind: "sql", note, sql: query });

/** Captured values plus the helpers body builders need. */
export class Vars {
  private readonly values = new Map<string, unknown>();
  private readonly usedTotpSteps = new Map<string, number>();

  set(name: string, value: unknown) {
    this.values.set(name, value);
  }

  has(name: string) {
    return this.values.has(name);
  }

  get(name: string): unknown {
    if (!this.values.has(name)) throw new Error(`no captured value named '${name}'`);
    return this.values.get(name);
  }

  id(name: string): number {
    const v = Number(this.get(name));
    if (!Number.isInteger(v)) throw new Error(`captured '${name}' is not an integer id`);
    return v;
  }

  str(name: string): string {
    return String(this.get(name));
  }

  /**
   * A TOTP code for the base32 secret captured under `name`, from a time step no earlier call
   * used. MfaService refuses a step it already accepted, so this waits for the next window
   * rather than replay one.
   */
  async totp(name: string): Promise<string> {
    const secret = this.str(name);
    const last = this.usedTotpSteps.get(secret);
    let step = currentStep();
    if (last !== undefined && step <= last) {
      const waitMs = (last + 1) * TOTP_PERIOD_SECONDS * 1000 - Date.now() + 250;
      await Bun.sleep(Math.max(waitMs, 0));
      step = currentStep();
    }
    this.usedTotpSteps.set(secret, step);
    return totpAt(secret, step);
  }

  fill(template: string): string {
    return template.replace(/\{(\w+)\}/g, (_, name: string) => encodeURIComponent(String(this.get(name))));
  }
}

export async function resolve<T>(value: Lazy<T>, vars: Vars): Promise<T> {
  return typeof value === "function" ? await (value as (v: Vars) => T | Promise<T>)(vars) : value;
}

const sentDates = new Map<string, string>();

/**
 * The first or last day of the month `offsetMonths` from this one. Normalizes to a month anchor
 * (`<month+18:first>`), so a golden stays valid whatever day it is checked on.
 */
export function monthEdge(offsetMonths: number, edge: "first" | "last", zone = "Europe/Paris"): string {
  const [y, m] = day(0, zone).split("-").map(Number) as [number, number];
  const first = new Date(Date.UTC(y, m - 1 + offsetMonths, 1));
  const date = edge === "first" ? first : new Date(Date.UTC(first.getUTCFullYear(), first.getUTCMonth() + 1, 0));
  const iso = date.toISOString().slice(0, 10);
  sentDates.set(iso, `<month${offsetMonths === 0 ? "" : offsetMonths > 0 ? `+${offsetMonths}` : offsetMonths}:${edge}>`);
  return iso;
}

/**
 * The label a date the scenario itself wrote normalizes to: `day(-1)` reads `<today-1>` every
 * day, even on the day it is also the first of the month.
 */
export function sentDateLabel(date: string): string | undefined {
  return sentDates.get(date);
}

/** Today in the instance's zone (APP_TIMEZONE), shifted by `offsetDays`, as YYYY-MM-DD. */
export function day(offsetDays = 0, zone = "Europe/Paris"): string {
  const today = new Intl.DateTimeFormat("en-CA", {
    timeZone: zone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
  if (offsetDays === 0) return today;
  const [y, m, d] = today.split("-").map(Number) as [number, number, number];
  // Calendar-day arithmetic in UTC so crossing a DST boundary late at night never shifts by 1 day.
  const date = new Date(Date.UTC(y, m - 1, d + offsetDays)).toISOString().slice(0, 10);
  sentDates.set(date, `<today${offsetDays > 0 ? "+" : ""}${offsetDays}>`);
  return date;
}
