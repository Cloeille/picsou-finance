import { parse, type Json } from "./json.ts";

/** A browser-like cookie store: name and path matching, Max-Age=0 deletes. */
export class CookieJar {
  private readonly cookies = new Map<string, { value: string; path: string }>();

  absorb(setCookies: string[]) {
    for (const line of setCookies) {
      const [pair, ...attrs] = line.split(";").map((p) => p.trim());
      const eq = pair!.indexOf("=");
      const name = pair!.slice(0, eq);
      const value = pair!.slice(eq + 1);
      let path = "/";
      let expired = false;
      for (const a of attrs) {
        const [k, v = ""] = a.split("=");
        const key = k!.toLowerCase();
        if (key === "path") path = v;
        if (key === "max-age" && Number(v) <= 0) expired = true;
        if (key === "expires" && Date.parse(v) < Date.now()) expired = true;
      }
      if (expired) this.cookies.delete(name);
      else this.cookies.set(name, { value, path });
    }
  }

  header(requestPath: string): string | undefined {
    const parts = [...this.cookies]
      .filter(([, c]) => requestPath === c.path || requestPath.startsWith(c.path.endsWith("/") ? c.path : c.path + "/"))
      .map(([name, c]) => `${name}=${c.value}`);
    return parts.length ? parts.join("; ") : undefined;
  }
}

export interface RawResponse {
  status: number;
  headers: Headers;
  setCookies: string[];
  bodyKind: "empty" | "json" | "text" | "binary";
  json?: Json;
  text: string;
  bytes: number;
}

export interface RequestSpec {
  method: string;
  path: string;
  headers: Record<string, string>;
  body?: string | FormData;
}

/** Headers axios sends for the frontend's `api` client, plus a fixed agent for the sessions list. */
const DEFAULT_HEADERS: Record<string, string> = {
  accept: "application/json, text/plain, */*",
  "accept-encoding": "identity",
  "user-agent": "picsou-contract/1",
};

export async function send(baseUrl: string, jar: CookieJar, spec: RequestSpec): Promise<RawResponse> {
  const headers: Record<string, string> = { ...DEFAULT_HEADERS, ...spec.headers };
  const cookie = jar.header(spec.path.split("?")[0]!);
  if (cookie) headers.cookie = cookie;
  const res = await fetch(baseUrl + spec.path, {
    method: spec.method,
    headers,
    body: spec.body,
    redirect: "manual",
  });
  const setCookies = res.headers.getSetCookie();
  jar.absorb(setCookies);
  const buf = new Uint8Array(await res.arrayBuffer());
  const type = res.headers.get("content-type") ?? "";
  const text = new TextDecoder().decode(buf);
  const base = { status: res.status, headers: res.headers, setCookies, text, bytes: buf.length };
  if (buf.length === 0) return { ...base, bodyKind: "empty" };
  if (/\bjson\b/i.test(type)) {
    try {
      return { ...base, bodyKind: "json", json: parse(text) };
    } catch {
      return { ...base, bodyKind: "text" };
    }
  }
  if (/^text\/|^application\/([\w.-]+\+)?xml\b/i.test(type)) return { ...base, bodyKind: "text" };
  return { ...base, bodyKind: "binary" };
}
