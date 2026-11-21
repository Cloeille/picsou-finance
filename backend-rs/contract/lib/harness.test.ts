import { expect, test } from "bun:test";
import { diffRuns } from "./diff.ts";
import { parse, stringify } from "./json.ts";
import { Normalizer } from "./normalize.ts";
import type { RunResult, StepRecord } from "./runner.ts";
import { monthEdge } from "./scenario.ts";
import { totpAt } from "./totp.ts";
import { CookieJar } from "./http.ts";

const TODAY = "2026-11-21";

function run(body: string): RunResult {
  const step: StepRecord = { step: "01 GET /x as admin", status: 200, headers: {}, cookies: [], bodyKind: "json", body: new Normalizer(TODAY).body(parse(body)) };
  return { scenario: "t", steps: [step], unmet: [] };
}

test("numbers keep their scale through a golden round trip", () => {
  expect(stringify(parse('{"a":1.50}'), 0)).toBe('{\n"a": 1.50\n}');
});

test("a scale-only difference is reported as such, a value difference is not", () => {
  const diffs = diffRuns(run('{"a":1.50,"b":2}'), run('{"a":1.5,"b":3}'));
  expect(diffs.map((d) => [d.where, d.kind])).toEqual([["body.a", "scale"], ["body.b", "body"]]);
});

test("key order is ignored, array order is not, an extra key is reported", () => {
  expect(diffRuns(run('{"a":1,"b":[1,2]}'), run('{"b":[1,2],"a":1}'))).toEqual([]);
  expect(diffRuns(run('{"b":[1,2]}'), run('{"b":[2,1]}')).length).toBe(2);
  expect(diffRuns(run('{"a":1}'), run('{"a":1,"x":null}'))[0]!.where).toBe("body.x");
});

test("ids map by first appearance, so two runs with shifted ids compare equal", () => {
  const a = new Normalizer(TODAY).body(parse('[{"id":7,"accountId":9},{"id":9}]'));
  const b = new Normalizer(TODAY).body(parse('[{"id":40,"accountId":41},{"id":41}]'));
  expect(a).toEqual(b);
  expect(stringify(a, 0)).toContain('"<id:2>"');
});

test("dates become offsets or month anchors, far dates stay literal", () => {
  const n = new Normalizer(TODAY);
  expect(n.text("2026-11-21")).toBe("<today>");
  expect(n.text("2026-11-19")).toBe("<today-2>");
  expect(n.text("2026-11-01")).toBe("<month:first>");
  expect(n.text("2028-05-31")).toBe("<month+18:last>");
  expect(n.text("2026-12-10")).toBe("<today+19>");
  expect(n.text("2019-06-30")).toBe("2019-06-30");
  expect(n.text("2026-11-21T10:15:30.123456Z")).toBe("<instant>");
  expect(n.text("2026-11-21T10:15:30")).toBe("<local-datetime>");
});

test("tokens and secrets are hidden but keep their identity", () => {
  const n = new Normalizer(TODAY);
  const body = n.body(parse('{"secret":"JBSWY3DPEHPK3PXP","link":"/activate/aB3dE5gH7jK9mN1pQ3sT5vX7","again":"/activate/aB3dE5gH7jK9mN1pQ3sT5vX7","recoveryCodes":["12345678"]}'));
  // Keys are walked sorted: again, link, recoveryCodes, secret.
  expect(body).toEqual({ again: "/activate/<token:1>", link: "/activate/<token:1>", recoveryCodes: ["<secret:2>"], secret: "<secret:3>" });
  expect(n.text("Amundi MSCI World CW8.PA")).toBe("Amundi MSCI World CW8.PA");
});

test("cookies keep name and attributes, never the value", () => {
  const c = new Normalizer(TODAY).cookies(["b=xyz; Path=/; Max-Age=0; HttpOnly", "a=secret; Path=/api; SameSite=Lax; Expires=Sat, 21 Nov 2026 13:00:00 GMT"]);
  expect(c).toEqual([
    { name: "a", value: "<set>", attributes: { path: "/api", samesite: "lax", expires: "<http-date>" } },
    { name: "b", value: "<set>", attributes: { path: "/", "max-age": "0", httponly: true } },
  ]);
});

test("RFC 6238 test vector (SHA-1, 6 digits)", () => {
  // Secret "12345678901234567890" in base32, T = 59 s.
  expect(totpAt("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 1)).toBe("287082");
});

test("monthEdge lands on a month boundary", () => {
  expect(monthEdge(0, "first").endsWith("-01")).toBe(true);
  const next = new Date(`${monthEdge(1, "last")}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + 1);
  expect(next.toISOString().slice(0, 10)).toBe(monthEdge(2, "first"));
});

test("placeholder numbering does not depend on field order, and string ids stay distinct", () => {
  const a = new Normalizer(TODAY).body(parse('{"id":7,"accountId":9}'));
  const b = new Normalizer(TODAY).body(parse('{"accountId":9,"id":7}'));
  expect(a).toEqual(b);
  expect(diffRuns(run('{"id":5}'), run('{"id":"5"}')).length).toBe(1);
});

test("dashed file names keep their shape, their timestamp is masked", () => {
  const n = new Normalizer(TODAY);
  expect(n.text('attachment; filename="picsou-comptes-20261121-142530.xlsx"')).toBe('attachment; filename="picsou-comptes-<compact-datetime>.xlsx"');
  expect(n.text("picsou-export-2026-11-21.zip")).toBe("picsou-export-<today>.zip");
  expect(n.text("FR7630006000011234567890189")).toBe("FR7630006000011234567890189");
  expect(n.text("2026-11-21T10:15+0100")).toBe("<offset-datetime:no-seconds:hhmm>");
});

test("a link's last segment is a token even without digits", () => {
  const n = new Normalizer(TODAY);
  expect(n.body(parse('{"resetLink":"/activate/abcdefghijklmnopqrstuv"}'))).toEqual({ resetLink: "/activate/<token:1>" });
});

test("duplicate keys are refused and __proto__ stays a member", () => {
  expect(() => parse('{"a":1,"a":2}')).toThrow();
  expect(Object.keys(parse('{"__proto__":{"x":1},"a":1}') as object)).toEqual(["__proto__", "a"]);
});

test("cookie jar scopes by path, defaults to the request directory, lets Max-Age win", () => {
  const jar = new CookieJar();
  jar.absorb(["rt=1; Path=/api/auth", "x=2", "keep=3; Max-Age=60; Expires=Thu, 01 Jan 1970 00:00:00 GMT"], "/api/auth/login");
  jar.absorb(["rt=; Path=/; Max-Age=0"], "/api/auth/logout");
  expect(jar.header("/api/auth/refresh")).toBe("rt=1; x=2; keep=3");
  expect(jar.header("/api/accounts")).toBeUndefined();
});
