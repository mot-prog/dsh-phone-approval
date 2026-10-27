// Standalone harness: stub ctx, exercise the approval waterfall + HTTP API.
import { tmpdir } from "node:os";
import { join } from "node:path";
// Portable scratch home — never a hardcoded dev path (CI runners can't mkdir
// under someone's /home, which crashed the plugin's token-file bootstrap).
process.env.DSH_HOME = join(tmpdir(), "dsh-phone-approval-test-home");
process.env.DSH_PHONE_APPROVAL_PORT = "18790";
process.env.DSH_PHONE_APPROVAL_BIND = "127.0.0.1";
process.env.DSH_PHONE_APPROVAL_STALE_MS = "3000";

import { readFileSync } from "node:fs";

const { apply } = await import("../lib/index.js");

const listeners = [];
const preExec = [];
const disposers = [];
const baseCtx = {
  on(event, fn) {
    if (event === "approval/request") listeners.push(fn);
    if (event === "tools/pre-execute") preExec.push(fn);
    return () => {};
  },
  effect(fn, label) {
    const d = fn();
    if (typeof d === "function") disposers.push(d);
    return d;
  },
  logger: { info: (m) => console.log("[info]", m), warn: (m) => console.warn("[warn]", m) },
};
// Simulate cordis's ctx proxy: any property access not explicitly provided
// throws — catches "cannot get property \"signal\" without inject"-class bugs
// in the harness instead of at startup mid-apply.
const ctx = new Proxy(baseCtx, {
  get(t, prop) {
    if (typeof prop === "symbol" || Object.hasOwn(t, prop)) return Reflect.get(t, prop);
    throw new Error(`cannot get property "${String(prop)}" without inject`);
  },
});
apply(ctx);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
await sleep(400); // let listen() settle

const cfg = JSON.parse(readFileSync(join(process.env.DSH_HOME, "phone-approval/config.json"), "utf8"));
const BASE = "http://127.0.0.1:18790";
const auth = { authorization: `Bearer ${cfg.token}` };

let failures = 0;
const check = (name, cond, extra = "") => {
  console.log(`${cond ? "PASS" : "FAIL"} ${name}${extra ? " — " + extra : ""}`);
  if (!cond) failures++;
};

// 0. auth
const bad = await fetch(`${BASE}/api/health`, { headers: { authorization: "Bearer wrong" } });
check("bad token -> 401", bad.status === 401);
const good = await fetch(`${BASE}/api/health`, { headers: auth });
check("good token health -> 200", good.status === 200);

// 1. phone answers when downstream is 'unavailable'
check("pre-execute listener registered", preExec.length === 1);
check(
  "pre-execute passes through",
  (await preExec[0](
    { callId: "c1", name: "bash", arguments: { command: "rm -rf ./tmp", description: "cleanup" } },
    async () => "allow",
  )) === "allow",
);
const ac1 = new AbortController();
const f1 = listeners[0](
  { toolName: "bash", callId: "c1", reason: { en: "Run `rm -rf ./tmp`?", zh: "执行 rm？" }, agent: { id: "sess-1" }, signal: ac1.signal },
  async () => "unavailable",
);
await sleep(50);
let poll = await (await fetch(`${BASE}/api/poll?since=-1&wait=1`, { headers: auth })).json();
check("poll sees pending", poll.pending.length === 1, JSON.stringify(poll.pending[0]));
const id1 = poll.pending[0]?.id;
check("reason flattened", poll.pending[0]?.reason === "Run `rm -rf ./tmp`?");
check("command attached from pre-execute args", poll.pending[0]?.command === "rm -rf ./tmp");
const ans = await fetch(`${BASE}/api/answer`, {
  method: "POST",
  headers: { ...auth, "content-type": "application/json" },
  body: JSON.stringify({ id: id1, outcome: "allow-once" }),
});
check("answer accepted", ans.status === 200);
check("waterfall resolves allowed-once", (await f1) === "allowed-once");

// 2. downstream (web) answers first -> phone prompt cleared
const ac2 = new AbortController();
const f2 = listeners[0](
  { toolName: "write", reason: "overwrite file", agent: { id: "sess-1" }, signal: ac2.signal },
  async () => {
    await sleep(150);
    return "allowed-once";
  },
);
check("waterfall resolves downstream result", (await f2) === "allowed-once");
poll = await (await fetch(`${BASE}/api/poll?since=-1&wait=1`, { headers: auth })).json();
check("pending cleared after web answer", poll.pending.length === 0);

// 3. signal abort -> cancelled
const ac3 = new AbortController();
const f3 = listeners[0](
  { toolName: "bash", reason: "long", agent: { id: "sess-1" }, signal: ac3.signal },
  async () => "unavailable",
);
await sleep(50);
ac3.abort();
check("abort -> cancelled", (await f3) === "cancelled");

// 4. phone stale -> fail closed 'unavailable'
const ac4 = new AbortController();
const t0 = Date.now();
const f4 = listeners[0](
  { toolName: "bash", reason: "stale test", agent: { id: "sess-1" }, signal: ac4.signal },
  async () => "unavailable",
);
const r4 = await f4;
check("stale -> unavailable", r4 === "unavailable", `after ${Date.now() - t0}ms`);

// 5. long-poll wakes on new approval (no since => waits)
const ac5 = new AbortController();
const f5p = listeners[0]({ toolName: "bash", reason: "wake", agent: { id: "s" }, signal: ac5.signal }, async () => {
  await sleep(200);
  return "rejected";
});
const pollP = fetch(`${BASE}/api/poll?wait=5`, { headers: auth }).then((r) => r.json());
await sleep(300); // approval created BEFORE poll started, but bump already fired; this poll just times out fast enough? (wait=5) — just assert it eventually returns
const pr = await pollP;
check("long-poll returns object", typeof pr.version === "number");
await f5p;

console.log(failures === 0 ? "ALL PASS" : `${failures} FAILURES`);
for (const d of disposers) d(); // fiber-teardown simulation: closes server, cancels pending
check("effect disposer registered", disposers.length >= 1);
process.exit(failures === 0 ? 0 : 1);
