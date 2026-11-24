import { parse, type Json } from "./json.ts";

/** A browser-like cookie store (RFC 6265): keyed by name and path, Max-Age before Expires. */
export class CookieJar {
  private readonly cookies = new Map<string, { name: string; value: string; path: string }>();

  absorb(setCookies: string[], requestPath: string) {
    for (const line of setCookies) {
      const [pair, ...attrs] = line.split(";").map((p) => p.trim());
      const eq = pair!.indexOf("=");
      const name = pair!.slice(0, eq);
      const value = pair!.slice(eq + 1);
      // Default path: the request path's directory.
      let path = requestPath.lastIndexOf("/") > 0 ? requestPath.slice(0, requestPath.lastIndexOf("/")) : "/";
      let maxAge: number | undefined;
      let expires: number | undefined;
      for (const a of attrs) {
        const i = a.indexOf("=");
        const key = (i < 0 ? a : a.slice(0, i)).toLowerCase();
        const v = i < 0 ? "" : a.slice(i + 1);
        if (key === "path" && v.startsWith("/")) path = v;
        if (key === "max-age") maxAge = Number(v);
        if (key === "expires") expires = Date.parse(v);
      }
      const expired = maxAge !== undefined ? maxAge <= 0 : expires !== undefined && expires < Date.now();
      const id = `${name}\u0000${path}`;
      if (expired) this.cookies.delete(id);
      else this.cookies.set(id, { name, value, path });
    }
  }

  header(requestPath: string): string | undefined {
    const parts = [...this.cookies.values()]
      .filter((c) => requestPath === c.path || requestPath.startsWith(c.path.endsWith("/") ? c.path : c.path + "/"))
      // Longer paths first, as browsers send them.
      .sort((a, b) => b.path.length - a.path.length)
      .map((c) => `${c.name}=${c.value}`);
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
  const pathOnly = spec.path.split("?")[0]!;
  const cookie = jar.header(pathOnly);
  if (cookie) headers.cookie = cookie;
  const res = await fetch(baseUrl + spec.path, {
    method: spec.method,
    headers,
    body: spec.body,
    redirect: "manual",
  });
  const setCookies = res.headers.getSetCookie();
  jar.absorb(setCookies, pathOnly);
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
