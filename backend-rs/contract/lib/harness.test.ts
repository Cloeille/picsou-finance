import { expect, test } from "bun:test";
import { diffRuns } from "./diff.ts";
import { parse, stringify } from "./json.ts";
import { Normalizer } from "./normalize.ts";
import type { RunResult, StepRecord } from "./runner.ts";
import { monthEdge } from "./scenario.ts";
import { totpAt } from "./totp.ts";

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
  expect(body).toEqual({ secret: "<secret:1>", link: "/activate/<token:2>", again: "/activate/<token:2>", recoveryCodes: ["<secret:3>"] });
  expect(n.text("Amundi MSCI World CW8.PA")).toBe("Amundi MSCI World CW8.PA");
});

test("cookies keep name and attributes, never the value", () => {
  const c = new Normalizer(TODAY).cookies(["b=xyz; Path=/; Max-Age=0; HttpOnly", "a=secret; Path=/api; SameSite=Lax; Expires=Sat, 21 Nov 2026 13:00:00 GMT"]);
  expect(c).toEqual([
    { name: "a", value: "<set>", attributes: { path: "/api", samesite: "Lax", expires: "<http-date>" } },
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
