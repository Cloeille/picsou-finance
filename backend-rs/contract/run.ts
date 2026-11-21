#!/usr/bin/env bun
/**
 * Differential contract harness: replays scenarios against the Java and the Rust backends, each on
 * a fresh database, and diffs the normalized responses. See README.md.
 */
import { mkdir, readdir } from "node:fs/promises";
import { join, resolve } from "node:path";
import { diffRuns, type Difference, formatDifferences } from "./lib/diff.ts";
import { parse, stringify, type Json } from "./lib/json.ts";
import { type BackendKind, launch, type LaunchConfig, stopAll } from "./lib/launcher.ts";
import { type RunResult, runScenario } from "./lib/runner.ts";
import type { Scenario } from "./lib/scenario.ts";
import { startStub, type StubServer } from "./lib/stub.ts";

const HERE = import.meta.dir;
const GOLDEN_DIR = join(HERE, "golden");
const SCENARIO_DIR = join(HERE, "scenarios");

const USAGE = `usage: bun run backend-rs/contract/run.ts <record|check|diff|list> (<scenario-or-prefix>... | --all)
  record   run on Java, write golden/<name>.json
  check    run on Rust (or --backend java), diff against the golden
  diff     run two backends live and diff them (--left java --right rust by default)
options:
  --left <java|rust> --right <java|rust>   backends for diff
  --backend <java|rust> backend for check (default rust)
  --jobs <n>            scenarios run concurrently (default 2)
  --java-jar <path>     default: backend/target/picsou-backend-*.jar
  --java-home <path>    default: JAVA_HOME, then /usr/libexec/java_home -v 21
  --rust-bin <path>     default: backend-rs/target/release/picsou
  --pg <host:port>      default 127.0.0.1:55432 (or CONTRACT_PG); --pg-user default picsou
  --keep-logs           keep each instance's work directory and log
  --verbose             print stub hits and log paths`;

interface Options {
  mode: "record" | "check" | "diff" | "list";
  selectors: string[];
  all: boolean;
  left: BackendKind;
  right: BackendKind;
  backend: BackendKind;
  jobs: number;
  verbose: boolean;
  launch: LaunchConfig;
}

function parseArgs(argv: string[]): Options {
  const [mode, ...rest] = argv;
  if (mode !== "record" && mode !== "check" && mode !== "diff" && mode !== "list") {
    console.error(USAGE);
    process.exit(2);
  }
  const pg = process.env.CONTRACT_PG ?? "127.0.0.1:55432";
  const opts: Options = {
    mode,
    selectors: [],
    all: false,
    left: "java",
    right: "rust",
    backend: "rust",
    jobs: 2,
    verbose: false,
    launch: {
      repoRoot: resolve(HERE, "..", ".."),
      pgHost: pg.split(":")[0]!,
      pgPort: Number(pg.split(":")[1] ?? 5432),
      pgUser: process.env.CONTRACT_PG_USER ?? "picsou",
      javaJar: process.env.CONTRACT_JAVA_JAR,
      javaHome: process.env.CONTRACT_JAVA_HOME,
      rustBin: process.env.CONTRACT_RUST_BIN,
      readyTimeoutMs: 180_000,
      keepLogs: false,
    },
  };
  for (let i = 0; i < rest.length; i++) {
    const a = rest[i]!;
    const value = () => {
      const v = rest[++i];
      if (v === undefined) throw new Error(`${a} needs a value`);
      return v;
    };
    const backend = (v: string): BackendKind => {
      if (v !== "java" && v !== "rust") throw new Error(`unknown backend ${v}`);
      return v;
    };
    if (a === "--all") opts.all = true;
    else if (a === "--left") opts.left = backend(value());
    else if (a === "--right") opts.right = backend(value());
    else if (a === "--backend") opts.backend = backend(value());
    else if (a === "--jobs") opts.jobs = Math.max(1, Number(value()));
    else if (a === "--java-jar") opts.launch.javaJar = value();
    else if (a === "--java-home") opts.launch.javaHome = value();
    else if (a === "--rust-bin") opts.launch.rustBin = value();
    else if (a === "--pg") {
      const [h, p] = value().split(":");
      opts.launch.pgHost = h!;
      opts.launch.pgPort = Number(p);
    } else if (a === "--pg-user") opts.launch.pgUser = value();
    else if (a === "--keep-logs") opts.launch.keepLogs = true;
    else if (a === "--verbose") opts.verbose = true;
    else if (a.startsWith("--")) throw new Error(`unknown option ${a}\n${USAGE}`);
    else opts.selectors.push(a);
  }
  if (opts.mode !== "list" && !opts.all && opts.selectors.length === 0) throw new Error(USAGE);
  return opts;
}

async function loadScenarios(): Promise<Scenario[]> {
  const files = (await readdir(SCENARIO_DIR)).filter((f) => f.endsWith(".ts")).sort();
  const all: Scenario[] = [];
  for (const f of files) {
    const mod = (await import(join(SCENARIO_DIR, f))) as { scenarios?: Scenario[] };
    if (!Array.isArray(mod.scenarios)) throw new Error(`${f} must export \`scenarios: Scenario[]\``);
    all.push(...mod.scenarios);
  }
  const seen = new Set<string>();
  for (const s of all) {
    if (seen.has(s.name)) throw new Error(`duplicate scenario name ${s.name}`);
    seen.add(s.name);
  }
  return all;
}

function select(all: Scenario[], opts: Options): Scenario[] {
  if (opts.all) return all;
  const picked = all.filter((s) => opts.selectors.some((sel) => s.name === sel || s.name.startsWith(`${sel}.`)));
  const unknown = opts.selectors.filter((sel) => !all.some((s) => s.name === sel || s.name.startsWith(`${sel}.`)));
  if (unknown.length) throw new Error(`no scenario matches: ${unknown.join(", ")}`);
  return picked;
}

async function execute(kind: BackendKind, scenario: Scenario, opts: Options, stub: StubServer): Promise<RunResult> {
  const instance = await launch(kind, scenario.name, opts.launch, stub);
  try {
    if (opts.verbose) console.log(`  [${kind}] ${scenario.name}: ${instance.baseUrl} log ${instance.logPath}`);
    return await runScenario(scenario, instance);
  } finally {
    await instance.stop();
  }
}

const goldenPath = (name: string) => join(GOLDEN_DIR, `${name}.json`);

async function readGolden(name: string): Promise<RunResult> {
  const file = Bun.file(goldenPath(name));
  if (!(await file.exists())) throw new Error(`no golden for ${name}: run record first`);
  const g = parse(await file.text()) as unknown as RunResult;
  // The lossless reader keeps every number as text; only body numbers need that.
  const steps = g.steps.map((s) => ({ ...s, status: Number(String((s.status as unknown as { raw: string }).raw)) }));
  return { scenario: g.scenario, steps, unmet: [] };
}

interface Outcome {
  name: string;
  steps: number;
  diffs: Difference[];
  errors: string[];
}

async function runOne(scenario: Scenario, opts: Options, stub: StubServer): Promise<Outcome> {
  const outcome: Outcome = { name: scenario.name, steps: 0, diffs: [], errors: [] };
  try {
    if (opts.mode === "record") {
      const r = await execute("java", scenario, opts, stub);
      outcome.steps = r.steps.length;
      outcome.errors.push(...r.unmet);
      if (r.unmet.length === 0) {
        const doc = { scenario: r.scenario, description: scenario.description, steps: r.steps } as unknown as Json;
        await Bun.write(goldenPath(scenario.name), stringify(doc) + "\n");
      }
    } else if (opts.mode === "check") {
      const golden = await readGolden(scenario.name);
      const r = await execute(opts.backend, scenario, opts, stub);
      outcome.steps = r.steps.length;
      outcome.diffs = diffRuns(golden, r);
      outcome.errors.push(...r.unmet.map((u) => `[${opts.backend}] ${u}`));
    } else {
      // allSettled: when one side fails, the other still runs to its end and stops its instance.
      const [l, r] = await Promise.allSettled([execute(opts.left, scenario, opts, stub), execute(opts.right, scenario, opts, stub)]);
      if (l.status === "rejected") throw l.reason;
      if (r.status === "rejected") throw r.reason;
      outcome.steps = r.value.steps.length;
      outcome.diffs = diffRuns(l.value, r.value);
      outcome.errors.push(...l.value.unmet.map((u) => `[${opts.left}] ${u}`), ...r.value.unmet.map((u) => `[${opts.right}] ${u}`));
    }
  } catch (e) {
    outcome.errors.push((e as Error).message);
  }
  return outcome;
}

async function pool<T, R>(items: T[], n: number, fn: (item: T) => Promise<R>): Promise<R[]> {
  const results: R[] = new Array(items.length);
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(n, items.length) }, async () => {
      while (next < items.length) {
        const i = next++;
        results[i] = await fn(items[i]!);
      }
    }),
  );
  return results;
}

async function main() {
  const opts = parseArgs(Bun.argv.slice(2));
  const all = await loadScenarios();
  if (opts.mode === "list") {
    for (const s of all) console.log(`${s.name.padEnd(28)} ${s.description}`);
    return 0;
  }
  const scenarios = select(all, opts);
  await mkdir(GOLDEN_DIR, { recursive: true });
  const stub = startStub();
  const onSignal = async () => {
    await stopAll();
    process.exit(130);
  };
  process.on("SIGINT", onSignal);
  process.on("SIGTERM", onSignal);
  const label = opts.mode === "record" ? "java -> golden" : opts.mode === "check" ? `golden vs ${opts.backend}` : `${opts.left} vs ${opts.right}`;
  console.log(`contract ${opts.mode} (${label}): ${scenarios.length} scenario(s), ${opts.jobs} at a time`);
  const started = Date.now();
  const outcomes = await pool(scenarios, opts.jobs, async (s) => {
    const o = await runOne(s, opts, stub);
    const scale = o.diffs.filter((d) => d.kind === "scale").length;
    const state = o.errors.length ? "ERROR" : o.diffs.length ? "DIFF " : "ok   ";
    const extra = o.diffs.length ? ` ${o.diffs.length} difference(s)${scale ? `, ${scale} scale` : ""}` : "";
    console.log(`${state} ${o.name} (${o.steps} steps)${extra}`);
    for (const e of o.errors) console.log(`  error: ${e}`);
    if (o.diffs.length) console.log(formatDifferences(o.diffs));
    return o;
  });
  stub.stop();
  if (opts.verbose && stub.hits.length) console.log(`stub hits:\n  ${[...new Set(stub.hits)].join("\n  ")}`);
  const steps = outcomes.reduce((n, o) => n + o.steps, 0);
  const diffs = outcomes.reduce((n, o) => n + o.diffs.length, 0);
  const scale = outcomes.reduce((n, o) => n + o.diffs.filter((d) => d.kind === "scale").length, 0);
  const failed = outcomes.filter((o) => o.errors.length).length;
  const seconds = Math.round((Date.now() - started) / 1000);
  const verdict = diffs === 0 && failed === 0 ? "PASS" : "FAIL";
  console.log(
    `summary: ${verdict} ${opts.mode} ${outcomes.length} scenarios, ${steps} compared steps, ${diffs} differences (${scale} scale), ${failed} errored, ${seconds}s`,
  );
  return verdict === "PASS" ? 0 : 1;
}

main().then(
  async (code) => {
    await stopAll();
    process.exit(code);
  },
  async (e) => {
    console.error((e as Error).message);
    await stopAll();
    process.exit(2);
  },
);
