import { SQL } from "bun";
import { mkdtemp, rm } from "node:fs/promises";
import { existsSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { randomBytes } from "node:crypto";
import type { StubServer } from "./stub.ts";

export type BackendKind = "java" | "rust";

export interface LaunchConfig {
  repoRoot: string;
  pgHost: string;
  pgPort: number;
  pgUser: string;
  javaJar?: string;
  javaHome?: string;
  rustBin?: string;
  sqlite?: boolean;
  readyTimeoutMs: number;
  keepLogs: boolean;
}

export interface Instance {
  kind: BackendKind;
  baseUrl: string;
  database: string;
  databaseUrl: string;
  sqlitePath?: string;
  logPath: string;
  stop(): Promise<void>;
}

const SIDECARS = [
  "TR_AUTH", "BOURSO_AUTH", "CAISSE_EPARGNE_AUTH", "BANQUE_POPULAIRE_AUTH", "LBP_AUTH", "REVOLUT_AUTH",
  "BOURSE_DIRECT_AUTH", "DEGIRO_AUTH", "AMUNDI_AUTH", "CMES_AUTH", "CIC_AUTH", "NATIXIS_AUTH", "BNP_AUTH",
  "AMEX_AUTH", "FORTUNEO_AUTH", "CORUM_AUTH", "SOFIDY_AUTH",
];

const live = new Set<Instance>();

/** Stops every running instance; the CLI calls it on SIGINT so no backend or database outlives a run. */
export async function stopAll() {
  await Promise.all([...live].map((i) => i.stop()));
}

export function adminDsn(cfg: LaunchConfig, database = "postgres"): string {
  return `postgres://${cfg.pgUser}@${cfg.pgHost}:${cfg.pgPort}/${database}`;
}

async function freePort(): Promise<number> {
  const s = Bun.listen({ hostname: "127.0.0.1", port: 0, socket: { data() {} } });
  const port = s.port;
  s.stop(true);
  return port;
}

/** What a throwaway instance needs: application.yml's variables, nothing inherited from the caller's shell. */
function environment(
  port: number,
  database: string,
  cfg: LaunchConfig,
  stub: StubServer,
  workDir: string,
  sqliteUrl?: string,
) {
  const env: Record<string, string> = {
    PATH: process.env.PATH ?? "/usr/bin:/bin",
    HOME: workDir,
    TMPDIR: workDir,
    TZ: "Europe/Paris",
    LANG: "C.UTF-8",
    LC_ALL: "C.UTF-8",
    SERVER_PORT: String(port),
    JWT_SECRET: randomBytes(48).toString("base64"),
    CRYPTO_ENCRYPTION_KEY: randomBytes(32).toString("base64"),
    SECURE_COOKIES: "false",
    APP_TIMEZONE: "Europe/Paris",
    APP_STARTUP_SYNC_ENABLED: "false",
    APP_UPDATE_CHECK_ENABLED: "false",
    INSTRUMENT_LOGOS_ENABLED: "false",
    VALUATION_ENABLED: "false",
    ADMIN_RECOVERY_ENABLED: "false",
    HSTS_ENABLED: "false",
    PUBLIC_BASE_URL: "http://localhost:5173",
    APP_VERSION: "contract",
    CEREMA_API_URL: `${stub.url}/cerema`,
    INSEE_API_URL: `${stub.url}/insee`,
    GEOCODING_API_URL: `${stub.url}/geocoding`,
    MERIA_BASE_URL: `${stub.url}/meria`,
    ENABLEBANKING_BASE_URL: `${stub.url}/enablebanking`,
    ENABLEBANKING_REDIRECT_URI: "http://localhost:5173/sync/callback",
    ENABLEBANKING_PRIVATE_KEY_PATH: join(workDir, "enablebanking-private.pem"),
    // Hosts no variable redirects (price feeds, logos) go through the stub as a proxy, which
    // refuses them: a scenario that reaches for the internet fails instead of passing by luck.
    HTTP_PROXY: stub.url,
    HTTPS_PROXY: stub.url,
    NO_PROXY: "127.0.0.1,localhost",
  };
  if (sqliteUrl) {
    env.DATABASE_URL = sqliteUrl;
  } else {
    env.SPRING_DATASOURCE_URL = `jdbc:postgresql://${cfg.pgHost}:${cfg.pgPort}/${database}`;
    env.SPRING_DATASOURCE_USERNAME = cfg.pgUser;
    env.SPRING_DATASOURCE_PASSWORD = "";
  }
  for (const s of SIDECARS) env[`${s}_URL`] = `${stub.url}/sidecar/${s.toLowerCase()}`;
  return env;
}

function findJar(cfg: LaunchConfig): string {
  if (cfg.javaJar) return cfg.javaJar;
  const dir = join(cfg.repoRoot, "backend", "target");
  const jar = existsSync(dir) ? readdirSync(dir).find((f) => /^picsou-backend-.*\.jar$/.test(f) && !f.includes("plain")) : undefined;
  if (!jar) throw new Error("no Java jar: build backend/ or pass --java-jar");
  return join(dir, jar);
}

function javaBinary(cfg: LaunchConfig): string {
  if (cfg.javaHome) return join(cfg.javaHome, "bin", "java");
  if (process.env.JAVA_HOME) return join(process.env.JAVA_HOME, "bin", "java");
  const probe = Bun.spawnSync(["/usr/libexec/java_home", "-v", "21"]);
  if (probe.exitCode === 0) return join(probe.stdout.toString().trim(), "bin", "java");
  return "java";
}

function command(kind: BackendKind, cfg: LaunchConfig, stub: StubServer): string[] {
  if (kind === "rust") {
    const bin = resolve(cfg.rustBin ?? join(cfg.repoRoot, "backend-rs", "target", "release", "picsou"));
    if (!existsSync(bin)) throw new Error(`no Rust binary at ${bin}: cargo build --release or pass --rust-bin`);
    return [bin];
  }
  return [
    javaBinary(cfg),
    "-Dhttp.proxyHost=127.0.0.1",
    `-Dhttp.proxyPort=${stub.port}`,
    "-Dhttps.proxyHost=127.0.0.1",
    `-Dhttps.proxyPort=${stub.port}`,
    "-Dhttp.nonProxyHosts=localhost|127.0.0.1",
    // The container's JVM runs in the POSIX locale; a developer Mac's would localize validation messages.
    "-Duser.language=en",
    "-Duser.country=US",
    "-XX:TieredStopAtLevel=1",
    "-jar",
    resolve(findJar(cfg)),
  ];
}

export async function launch(kind: BackendKind, label: string, cfg: LaunchConfig, stub: StubServer): Promise<Instance> {
  // Resolved first: a missing binary must fail before a database exists to leak.
  const argv = command(kind, cfg, stub);
  const useSqlite = Boolean(cfg.sqlite && kind === "rust");
  const suffix = randomBytes(4).toString("hex");
  const stem = `contract_${kind}_${label.replace(/[^a-z0-9]+/gi, "_").toLowerCase()}`.slice(0, 50);
  const database = `${stem}_${suffix}`;

  const workDir = await mkdtemp(join(tmpdir(), `picsou-contract-${kind}-`));
  let sqlitePath: string | undefined;
  let sqliteUrl: string | undefined;
  if (useSqlite) {
    sqlitePath = join(workDir, `contract-${label.replace(/[^a-z0-9._-]+/gi, "_")}-${suffix}.db`);
    sqliteUrl = `sqlite:///${sqlitePath.replace(/\\/g, "/").replace(/^\/+/, "")}`;
  } else {
    const admin = new SQL(adminDsn(cfg));
    await admin.unsafe(`CREATE DATABASE "${database}"`);
    await admin.close();
  }

  const logPath = join(workDir, "backend.log");
  const port = await freePort();
  const proc = Bun.spawn(argv, {
    cwd: workDir,
    env: environment(port, database, cfg, stub, workDir, sqliteUrl),
    stdout: Bun.file(logPath),
    stderr: Bun.file(join(workDir, "backend.err.log")),
  });

  let stopped = false;
  let keep = cfg.keepLogs;
  const instance: Instance = {
    kind,
    baseUrl: `http://127.0.0.1:${port}`,
    database: sqlitePath ?? database,
    databaseUrl: sqliteUrl ?? adminDsn(cfg, database),
    sqlitePath,
    logPath,
    async stop() {
      if (stopped) return;
      stopped = true;
      live.delete(instance);
      proc.kill("SIGTERM");
      const exited = await Promise.race([proc.exited.then(() => true), Bun.sleep(15_000).then(() => false)]);
      if (!exited) {
        proc.kill("SIGKILL");
        await proc.exited;
      }
      if (!useSqlite) {
        const sql = new SQL(adminDsn(cfg));
        await sql.unsafe(`DROP DATABASE IF EXISTS "${database}" WITH (FORCE)`);
        await sql.close();
      }
      if (!keep) await rm(workDir, { recursive: true, force: true });
    },
  };
  live.add(instance);

  const deadline = Date.now() + cfg.readyTimeoutMs;
  let exitCode: number | null = null;
  void proc.exited.then((c) => (exitCode = c));
  while (Date.now() < deadline && exitCode === null) {
    try {
      const r = await fetch(`${instance.baseUrl}/actuator/health/readiness`);
      if (r.status === 200) return instance;
    } catch {
      // not listening yet
    }
    await Bun.sleep(250);
  }
  const tail = (await Bun.file(logPath).text().catch(() => "")).slice(-4000);
  keep = true;
  await instance.stop();
  const why = exitCode === null ? "was not ready in time" : `exited with code ${exitCode}`;
  throw new Error(`${kind} backend ${why}; log kept at ${logPath}\n${tail}`);
}
