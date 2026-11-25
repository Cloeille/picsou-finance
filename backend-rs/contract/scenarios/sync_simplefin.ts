import { bootstrap } from "../lib/flows.ts";
import { del, get, post, sql, type Scenario } from "../lib/scenario.ts";

// Setup tokens are Base64 claim URLs. None of these may reach a network: each is refused before a
// request, so the scenarios hold on both backends without the bridge.
const b64 = (url: string) => Buffer.from(url, "utf8").toString("base64");
const BRIDGE = "beta-bridge.simplefin.org";

const status: Scenario = {
  name: "sync.simplefin-status",
  description: "Status, sync and disconnect when the member never connected SimpleFIN",
  steps: [
    ...bootstrap(),
    get("/api/simplefin/status", { expect: 200 }),
    post("/api/simplefin/sync", undefined, { expect: 422 }),
    del("/api/simplefin/connection", { expect: 204 }),
    get("/api/simplefin/status", { expect: 200 }),
    get("/api/simplefin/status", { as: "anon", expect: 401 }),
  ],
};

const validation: Scenario = {
  name: "sync.simplefin-validation",
  description: "Connect request validation: blank, missing, oversized and unreadable tokens",
  steps: [
    ...bootstrap(),
    post("/api/simplefin/connect", { token: "   " }, { expect: 422 }),
    post("/api/simplefin/connect", {}, { expect: 422 }),
    post("/api/simplefin/connect", { token: "A".repeat(4097) }, { expect: 422 }),
    post("/api/simplefin/connect", undefined, { raw: { contentType: "application/json", body: "{\"token\":" }, expect: 400 }),
    post("/api/simplefin/connect", undefined, { raw: { contentType: "application/json", body: "" }, expect: 400 }),
  ],
};

const refused: Scenario = {
  name: "sync.simplefin-refused-hosts",
  description: "Setup tokens that are not an https claim URL on the bridge, then the shared per-IP limit",
  steps: [
    ...bootstrap(),
    post("/api/simplefin/connect", { token: b64("https://evil.example/simplefin/claim/abc") }, { expect: 422 }),
    post("/api/simplefin/connect", { token: b64(`http://${BRIDGE}/simplefin/claim/abc`) }, { expect: 422 }),
    post("/api/simplefin/connect", { token: b64(`https://${BRIDGE}:8443/simplefin/claim/abc`) }, { expect: 422 }),
    post("/api/simplefin/connect", { token: b64(`https://u:p@${BRIDGE}/simplefin/claim/abc`) }, { expect: 422 }),
    post("/api/simplefin/connect", { token: "not base64!!!" }, { expect: 422 }),
    post("/api/simplefin/connect", { token: `http://${BRIDGE}/simplefin/claim/abc` }, { expect: 422 }),
    post("/api/simplefin/sync", undefined, { expect: 429 }),
    post("/api/simplefin/connect", { token: b64("https://10.0.0.5/claim") }, { expect: 429 }),
    get("/api/simplefin/status", { expect: 200 }),
  ],
};

const undecryptable: Scenario = {
  name: "sync.simplefin-undecryptable",
  description: "A stored access the key cannot decrypt: ERROR status, a fixed mask, a 422 sync, then disconnect",
  steps: [
    ...bootstrap(),
    sql(
      "a connection whose access URL is not ciphertext of this instance's key",
      "INSERT INTO simplefin_connection (member_id, access_url) SELECT member_id, 'not-ciphertext' FROM app_user WHERE username = 'admin'",
    ),
    get("/api/simplefin/status", { expect: 200 }),
    post("/api/simplefin/sync", undefined, { expect: 422 }),
    get("/api/simplefin/status", { expect: 200 }),
    del("/api/simplefin/connection", { expect: 204 }),
    get("/api/simplefin/status", { expect: 200 }),
  ],
};

export const scenarios: Scenario[] = [status, validation, refused, undecryptable];
