import { get, post, type Step } from "./scenario.ts";

export const ADMIN = { username: "admin", password: "contract-admin-pass-1" } as const;
export const MEMBER_PASSWORD = "contract-member-pass-1";

/** The wizard's calls in the wizard's order, then the first login. Not compared unless `compare` is set. */
export function bootstrap(opts: { compare?: boolean } = {}): Step[] {
  const compare = opts.compare ?? false;
  return [
    post("/api/setup/admin", { username: ADMIN.username, password: ADMIN.password, displayName: "Admin" }, { as: "anon", expect: 200, compare }),
    post("/api/setup/complete", undefined, { as: "anon", expect: 204, compare }),
    ...login("admin", ADMIN.username, ADMIN.password, { compare }),
  ];
}

export function login(as: string, username: string, password: string, opts: { compare?: boolean; rememberMe?: boolean } = {}): Step[] {
  return [
    post("/api/auth/login", { username, password, rememberMe: opts.rememberMe ?? false }, { as, expect: 200, compare: opts.compare ?? false }),
  ];
}

/**
 * The admin invites a member, who activates the link and signs in as client `as`. Captures the
 * member's id as `<as>Id`.
 */
export function inviteMember(as: string, displayName: string, opts: { compare?: boolean } = {}): Step[] {
  const compare = opts.compare ?? false;
  return [
    post("/api/family/members", { displayName, avatarColor: "#16a34a" }, {
      expect: 201,
      compare,
      capture: { [`${as}Id`]: (r) => r.json.id },
    }),
    post(`/api/family/members/{${as}Id}/activate`, undefined, {
      expect: 200,
      compare,
      capture: { [`${as}Token`]: (r) => String(r.json.activationLink).split("/").pop() },
    }),
    get("/api/family/members", {
      expect: 200,
      compare,
      capture: { [`${as}Login`]: (r) => r.json.find((m: { displayName: string }) => m.displayName === displayName)?.loginName },
    }),
    post(`/api/auth/activate/{${as}Token}`, { password: MEMBER_PASSWORD, acknowledgedWarning: true }, { as, expect: 200, compare }),
    post("/api/auth/login", (v) => ({ username: v.str(`${as}Login`), password: MEMBER_PASSWORD, rememberMe: false }), {
      as,
      expect: 200,
      compare,
    }),
  ];
}

export function manualAccount(capture: string, body: Record<string, unknown>, opts: { compare?: boolean; as?: string } = {}): Step {
  return post(
    "/api/accounts",
    { type: "CHECKING", currency: "EUR", isManual: true, currentBalance: 0, ...body },
    { as: opts.as ?? "admin", expect: 201, compare: opts.compare ?? true, capture: { [capture]: (r) => r.json.id } },
  );
}
