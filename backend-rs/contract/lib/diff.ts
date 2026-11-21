import { decimalKey, JNum, type Json, stringify } from "./json.ts";
import type { RunResult, StepRecord } from "./runner.ts";

export type DiffKind = "status" | "header" | "cookie" | "body" | "scale" | "type" | "steps";

export interface Difference {
  step: string;
  where: string;
  kind: DiffKind;
  expected: string;
  actual: string;
}

const show = (v: Json | undefined) => (v === undefined ? "(absent)" : stringify(v, 0).replace(/\n/g, ""));

function typeOf(v: Json): string {
  if (v === null) return "null";
  if (v instanceof JNum) return "number";
  if (Array.isArray(v)) return "array";
  return typeof v;
}

function compareJson(step: string, where: string, a: Json | undefined, b: Json | undefined, out: Difference[]) {
  if (a === undefined || b === undefined) {
    if (a !== b) out.push({ step, where, kind: "body", expected: show(a), actual: show(b) });
    return;
  }
  const ta = typeOf(a);
  const tb = typeOf(b);
  if (ta !== tb) {
    out.push({ step, where, kind: "type", expected: `${ta} ${show(a)}`, actual: `${tb} ${show(b)}` });
    return;
  }
  if (a instanceof JNum && b instanceof JNum) {
    if (a.raw === b.raw) return;
    const kind = decimalKey(a.raw) === decimalKey(b.raw) ? "scale" : "body";
    out.push({ step, where, kind, expected: a.raw, actual: b.raw });
    return;
  }
  if (Array.isArray(a) && Array.isArray(b)) {
    if (a.length !== b.length) {
      out.push({ step, where: `${where}.length`, kind: "body", expected: String(a.length), actual: String(b.length) });
    }
    for (let i = 0; i < Math.min(a.length, b.length); i++) compareJson(step, `${where}[${i}]`, a[i], b[i], out);
    return;
  }
  if (ta === "object") {
    const oa = a as Record<string, Json>;
    const ob = b as Record<string, Json>;
    for (const k of [...new Set([...Object.keys(oa), ...Object.keys(ob)])].sort()) {
      compareJson(step, `${where}.${k}`, oa[k], ob[k], out);
    }
    return;
  }
  if (a !== b) out.push({ step, where, kind: "body", expected: show(a), actual: show(b) });
}

function compareStep(g: StepRecord, r: StepRecord, out: Difference[]) {
  const step = g.step;
  if (g.status !== r.status) out.push({ step, where: "status", kind: "status", expected: String(g.status), actual: String(r.status) });
  for (const h of [...new Set([...Object.keys(g.headers), ...Object.keys(r.headers)])].sort()) {
    if (g.headers[h] !== r.headers[h]) {
      out.push({ step, where: `header ${h}`, kind: "header", expected: g.headers[h] ?? "(absent)", actual: r.headers[h] ?? "(absent)" });
    }
  }
  const cookieText = (c: StepRecord["cookies"][number]) =>
    `${c.name}=${c.value}; ${Object.keys(c.attributes).sort().map((k) => (c.attributes[k] === true ? k : `${k}=${c.attributes[k]}`)).join("; ")}`;
  const gc = g.cookies.map(cookieText);
  const rc = r.cookies.map(cookieText);
  for (let i = 0; i < Math.max(gc.length, rc.length); i++) {
    if (gc[i] !== rc[i]) out.push({ step, where: `cookie[${i}]`, kind: "cookie", expected: gc[i] ?? "(absent)", actual: rc[i] ?? "(absent)" });
  }
  if (g.bodyKind !== r.bodyKind) {
    out.push({ step, where: "body kind", kind: "type", expected: g.bodyKind, actual: r.bodyKind });
    return;
  }
  compareJson(step, "body", g.body, r.body, out);
}

/** `expected` is the golden or the left backend, `actual` the backend under test. */
export function diffRuns(expected: RunResult, actual: RunResult): Difference[] {
  const out: Difference[] = [];
  const n = Math.max(expected.steps.length, actual.steps.length);
  for (let i = 0; i < n; i++) {
    const g = expected.steps[i];
    const r = actual.steps[i];
    if (!g || !r || g.step !== r.step) {
      out.push({ step: g?.step ?? r!.step, where: "step", kind: "steps", expected: g?.step ?? "(absent)", actual: r?.step ?? "(absent)" });
      if (!g || !r) continue;
      break;
    }
    compareStep(g, r, out);
  }
  return out;
}

export function formatDifferences(diffs: Difference[]): string {
  const lines: string[] = [];
  let current = "";
  for (const d of diffs) {
    if (d.step !== current) {
      current = d.step;
      lines.push(`  step ${d.step}`);
    }
    const tag = d.kind === "scale" ? "scale differs" : d.kind;
    lines.push(`    ${d.where} [${tag}]`, `      expected: ${d.expected}`, `      actual:   ${d.actual}`);
  }
  return lines.join("\n");
}
