// dsh-phone-approval — host plugin that lets an Android app answer
// agent permission prompts ("restricted command needs approval") from the phone.
//
// Mechanism: registers a host-side `approval/request` waterfall listener at
// startup (before any browser connects, so it is outermost), races the phone's
// answer against the rest of the waterfall (the Web UI), and serves a tiny
// token-authenticated HTTP API that the Android app long-polls.
//
// No dependencies. Node builtins only.

import { createServer } from "node:http";
import { randomBytes, timingSafeEqual } from "node:crypto";
import { readFileSync, writeFileSync, mkdirSync, chmodSync } from "node:fs";
import { join } from "node:path";
import { homedir } from "node:os";

const DEFAULT_PORT = 8790;
const DEFAULT_BIND = "0.0.0.0";
const MAX_WAIT_MS = 60_000;
const PHONE_STALE_MS = Number(process.env.DSH_PHONE_APPROVAL_STALE_MS) || 60_000; // phone considered offline if silent this long

function dshHome() {
  return process.env.DSH_HOME || join(homedir(), ".dsh");
}

function loadOrCreateConfig(dir, log) {
  const file = join(dir, "config.json");
  try {
    const cfg = JSON.parse(readFileSync(file, "utf8"));
    if (typeof cfg.token !== "string" || cfg.token.length < 16) throw new Error("token missing/short");
    return { cfg, file, created: false };
  } catch (error) {
    if (error.code !== "ENOENT") log(`phone-approval: config unreadable (${error.message}), regenerating`);
    const cfg = {
      port: Number(process.env.DSH_PHONE_APPROVAL_PORT) || DEFAULT_PORT,
      bind: process.env.DSH_PHONE_APPROVAL_BIND || DEFAULT_BIND,
      token: randomBytes(24).toString("hex"),
    };
    mkdirSync(dir, { recursive: true, mode: 0o700 });
    writeFileSync(file, JSON.stringify(cfg, null, 2) + "\n", { mode: 0o600 });
    try { chmodSync(file, 0o600); } catch {}
    return { cfg, file, created: true };
  }
}

/** Flatten a reason that may be a plain string or a localized {zh,en} object. */
function flattenReason(reason) {
  if (reason == null) return null;
  if (typeof reason === "string") return reason;
  if (typeof reason === "object") {
    for (const key of ["en", "en-US", "zh", "zh-CN"]) {
      const value = reason[key];
      if (typeof value === "string") return value;
    }
    for (const value of Object.values(reason)) {
      if (typeof value === "string") return value;
    }
  }
  try {
    return JSON.stringify(reason).slice(0, 500);
  } catch {
    return String(reason);
  }
}

function safeEqual(a, b) {
  const ba = Buffer.from(a);
  const bb = Buffer.from(b);
  return ba.length === bb.length && timingSafeEqual(ba, bb);
}

function readBody(req, limit = 64 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error("body too large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

export function apply(ctx) {
  const log = (msg) => {
    if (ctx && ctx.logger && typeof ctx.logger.info === "function") ctx.logger.info(msg);
    else console.log(`[phone-approval] ${msg}`);
  };
  const warn = (msg) => {
    if (ctx && ctx.logger && typeof ctx.logger.warn === "function") ctx.logger.warn(msg);
    else console.warn(`[phone-approval] ${msg}`);
  };

  const dir = join(dshHome(), "phone-approval");
  let cfg;
  try {
    ({ cfg } = loadOrCreateConfig(dir, log));
  } catch (error) {
    warn(`phone-approval: cannot create config under ${dir}: ${error.message}; plugin disabled`);
    return;
  }

  /** @type {Map<string, object>} pending approvals keyed by relay id */
  const pending = new Map();
  let version = 0;
  const waiters = new Set(); // long-poll resolvers woken when `pending` changes
  let lastActivityAt = 0; // last time the phone talked to us

  const bump = () => {
    version += 1;
    for (const wake of waiters) wake();
  };

  // ------------------------------------------------------------ tool arguments
  // The approval payload carries only toolName/reason/callId. tools/pre-execute
  // fires BEFORE the ask gate (dsh-tools: gate resolved after pre-execute), so
  // we remember exec.arguments keyed by callId and enrich the phone view with
  // the actual command (exec.arguments — verified against dsh-tools internals).
  const recentArgs = new Map(); // String(callId) -> { at, command }
  const ARGS_TTL_MS = 5 * 60 * 1000;
  const ARGS_CAP = 64;
  let lastArgsPrune = 0;

  const summarizeArgs = (args) => {
    if (!args || typeof args !== "object") return "";
    let s;
    if (typeof args.command === "string") s = args.command;
    else if (typeof args.file_path === "string") s = args.file_path;
    else if (typeof args.url === "string") s = args.url;
    else {
      try { s = JSON.stringify(args); } catch { s = ""; }
    }
    s = String(s ?? "");
    return s.length > 900 ? s.slice(0, 900) + " …" : s;
  };

  const noteArgs = (callId, args) => {
    const now = Date.now();
    if (now - lastArgsPrune > 30_000) {
      lastArgsPrune = now;
      for (const [k, v] of recentArgs) if (now - v.at > ARGS_TTL_MS) recentArgs.delete(k);
    }
    recentArgs.set(String(callId), { at: now, command: summarizeArgs(args) });
    if (recentArgs.size > ARGS_CAP) recentArgs.delete(recentArgs.keys().next().value);
  };

  // Pass-through observer: must never interfere with the waterfall decision.
  ctx.on("tools/pre-execute", (exec, next) => {
    try {
      if (exec && exec.callId != null) noteArgs(exec.callId, exec.arguments);
    } catch { /* observation only */ }
    return next();
  });

  const view = (record) => ({
    id: record.id,
    ts: record.ts,
    toolName: record.toolName,
    callId: record.callId ?? null,
    reason: record.reason,
    command: record.command ?? "",
    sessionId: record.sessionId,
  });

  const snapshot = () => ({ version, pending: [...pending.values()].map(view) });

  const removePending = (record, outcome, source) => {
    if (record.settled) return;
    record.settled = true;
    pending.delete(record.id);
    if (record.signal) record.signal.removeEventListener("abort", record.onAbort);
    if (record.phoneResolve) record.phoneResolve({ outcome, source });
    bump();
  };

  // ---------------------------------------------------------------- HTTP API
  const server = createServer(async (req, res) => {
    const url = new URL(req.url ?? "/", "http://localhost");
    const send = (status, obj) => {
      const body = JSON.stringify(obj);
      res.writeHead(status, { "content-type": "application/json", "content-length": Buffer.byteLength(body) });
      res.end(body);
    };

    if (url.pathname === "/api/health" && req.method === "GET") {
      // health check also requires the token so the port reveals nothing
      const auth = req.headers.authorization ?? "";
      if (!(auth === `Bearer ${cfg.token}` || safeEqual(auth.replace(/^Bearer /, ""), cfg.token))) {
        return send(401, { ok: false, error: "unauthorized" });
      }
      lastActivityAt = Date.now();
      return send(200, { ok: true, service: "dsh-phone-approval", ...snapshot() });
    }

    const auth = req.headers.authorization ?? "";
    if (!(auth === `Bearer ${cfg.token}` || safeEqual(auth.replace(/^Bearer /, ""), cfg.token))) {
      return send(401, { ok: false, error: "unauthorized" });
    }
    lastActivityAt = Date.now();

    if (url.pathname === "/api/poll" && req.method === "GET") {
      const since = Number(url.searchParams.get("since"));
      const waitMs = Math.min(Number(url.searchParams.get("wait")) || 20, MAX_WAIT_MS / 1000) * 1000;
      if (Number.isFinite(since) && since !== version) return send(200, snapshot());
      // one wake-up slot per poll connection; staleness is covered by `since`
      await new Promise((resolve) => {
        const timer = setTimeout(() => { waiters.delete(fn); resolve(false); }, waitMs);
        const fn = () => {
          clearTimeout(timer);
          waiters.delete(fn);
          resolve(true);
        };
        waiters.add(fn);
      });
      return send(200, snapshot());
    }

    if (url.pathname === "/api/answer" && req.method === "POST") {
      let parsed;
      try {
        parsed = JSON.parse(await readBody(req));
      } catch {
        return send(400, { ok: false, error: "invalid json" });
      }
      const record = typeof parsed.id === "string" ? pending.get(parsed.id) : undefined;
      if (!record) return send(404, { ok: false, error: "unknown or already answered" });
      const outcome = parsed.outcome;
      if (outcome !== "allow-once" && outcome !== "reject") {
        return send(400, { ok: false, error: "outcome must be allow-once or reject" });
      }
      removePending(record, outcome === "allow-once" ? "allowed-once" : "rejected", "phone");
      return send(200, { ok: true });
    }

    return send(404, { ok: false, error: "not found" });
  });

  let listening = false;
  server.on("error", (error) => {
    warn(`phone-approval: http server error: ${error.message}; phone channel disabled`);
    listening = false;
  });
  try {
    server.listen(cfg.port, cfg.bind, () => {
      listening = true;
      log(`phone-approval: listening on http://${cfg.bind}:${cfg.port} (config: ${dir}/config.json)`);
    });
  } catch (error) {
    warn(`phone-approval: failed to listen: ${error.message}`);
  }

  const stop = () => {
    for (const record of pending.values()) removePending(record, "cancelled", "shutdown");
    try { server.close(); } catch {}
  };
  // ctx.effect ties cleanup to the fiber lifetime (cordis pattern, same as
  // api-remotes). NOTE: `ctx.signal` is NOT a cordis service — the ctx proxy
  // threw "cannot get property \"signal\" without inject" and killed apply()
  // mid-mount, silently skipping everything registered after it.
  ctx.effect(() => () => stop(), "phone-approval: http shutdown + cancel pending");

  // ------------------------------------------------------- approval waterfall
  ctx.on("approval/request", (request, next) => {
    log("phone-approval: approval/request listener fired (phone channel " + (listening ? "up" : "down") + ")");
    if (!listening) return next();

    const id = "pa-" + randomBytes(6).toString("hex");
    const sessionId = request.agent?.id ?? request.sessionId ?? null;
    const knownArgs = request.callId != null ? recentArgs.get(String(request.callId)) : undefined;
    const record = {
      id,
      ts: Date.now(),
      toolName: String(request.toolName ?? "unknown"),
      callId: request.callId ?? null,
      reason: flattenReason(request.reason ?? request.displayReason ?? null),
      command: knownArgs?.command ?? "",
      sessionId,
      signal: request.signal,
      settled: false,
      phoneResolve: null,
      onAbort: null,
    };

    const phoneP = new Promise((resolve) => {
      record.phoneResolve = resolve;
    });

    if (record.signal) {
      record.onAbort = () => removePending(record, "cancelled", "signal");
      if (record.signal.aborted) record.onAbort();
      else record.signal.addEventListener("abort", record.onAbort, { once: true });
    }

    pending.set(id, record);
    bump();

    const downstream = Promise.resolve().then(next);

    return (async () => {
      const phoneWinner = Symbol("phone");
      const first = await Promise.race([
        phoneP.then((r) => ({ src: phoneWinner, r })),
        downstream.then((r) => ({ src: "downstream", r })),
      ]).catch((error) => ({ src: "downstream-error", error }));

      if (first.src === "downstream-error") {
        removePending(record, "cancelled", "downstream-error");
        throw first.error;
      }
      if (first.src === phoneWinner) {
        // phone answered first; the Web UI prompt (if any) clears via session events
        return first.r.outcome;
      }
      const result = first.r;
      if (result !== "unavailable") {
        // someone else (Web UI / terminal) answered — clear the phone prompt
        removePending(record, "cancelled", "answered-elsewhere");
        return result;
      }
      // No other answerer exists: hold the approval for the phone until it
      // answers, the request is withdrawn, or the phone goes silent.
      return await new Promise((resolve) => {
        const timer = setInterval(() => {
          if (record.settled) {
            clearInterval(timer);
            return;
          }
          if (Date.now() - lastActivityAt > PHONE_STALE_MS) {
            clearInterval(timer);
            removePending(record, "unavailable", "phone-stale");
            resolve("unavailable");
          }
        }, 5000);
        phoneP.then((r) => {
          clearInterval(timer);
          if (!record.settled) removePending(record, "cancelled", "double");
          resolve(r.outcome === "cancelled" ? "cancelled" : r.outcome);
        });
      });
    })();
  });

  log(`phone-approval: plugin mounted; token file: ${dir}/config.json`);
}

// Cordis plugin convention (same trio as dsh-pet / auto-review bundles).
// The ctx proxy throws on property access for non-injected services that are
// not mixin builtins. `logger`, `on`, `effect` are core/mixin and need no
// declaration (verified against the installed cordis + api-remotes); there is
// no "signal" service. Keep inject empty unless a real service is used.
export const name = "dsh-phone-approval";
export const inject = [];
