import { SQL } from "bun";
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
  const headers: Record<string, string> = { ...(step.headers ?? {}) };
  if (step.raw) {
    headers["content-type"] = step.raw.contentType;
    return { body: step.raw.body, headers };
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

export async function runScenario(scenario: Scenario, instance: Instance): Promise<RunResult> {
  const vars = new Vars();
  const norm = new Normalizer(day(0));
  const jars = new Map<string, CookieJar>();
  const steps: StepRecord[] = [];
  const unmet: string[] = [];
  let db: SQL | undefined;
  try {
    for (const [i, step] of scenario.steps.entries()) {
      if (step.kind === "sql") {
        db ??= new SQL(instance.databaseUrl);
        await db.unsafe(await resolve(step.sql, vars));
        continue;
      }
      const label = stepLabel(i, step, norm);
      let jar = jars.get(step.as);
      if (!jar) {
        jar = new CookieJar();
        jars.set(step.as, jar);
      }
      const { body, headers } = await buildBody(step, vars);
      const path = vars.fill(step.path);
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
  }
  return { scenario: scenario.name, steps, unmet };
}
