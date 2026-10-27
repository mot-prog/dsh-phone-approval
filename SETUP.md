# Setup guide

Written so it can be handed to your **dsh agent** as a task
(*"Set up dsh-phone-approval, follow SETUP.md"*) — or followed by hand.
`~/.dsh` is the dsh home (profiles/logs); adjust if yours differs.

Prerequisites: a dsh install (the `@deepseek-ai/dsh-*` npx/npm setup), this
repo checked out somewhere, and a PC IP reachable from the phone (a Tailscale
IP is ideal).

## 1. Install the plugin

1. Copy `plugin/` → `~/.dsh/bundles/dsh-phone-approval/` (keep the internal
   layout: `package.json`, `cordis.patch.yml`, `lib/index.js`).
2. Wire it into the profile your dsh instance uses — usually
   `~/.dsh/profiles/web/package.json`:
   - add to `dependencies`:
     `"dsh-phone-approval": "link:/home/YOU/.dsh/bundles/dsh-phone-approval"`
     (absolute path to where you copied it)
   - append `"dsh-phone-approval"` to the `dsh.profile.bundles` array
     (last position is fine; see the note in `cordis.patch.yml`).
3. Install the link: run `pnpm install` in `~/.dsh/profiles/web/`. Without
   pnpm, a manual symlink works too:
   `ln -s ~/.dsh/bundles/dsh-phone-approval ~/.dsh/profiles/web/node_modules/dsh-phone-approval`.
4. **Restart the dsh instance.**

## 2. Verify

- Plugin self-test (no dsh needed):
  `node ~/.dsh/bundles/dsh-phone-approval/test/harness.mjs` → `ALL PASS`.
- After restart, grep the newest `~/.dsh/logs/startup-*.log` for
  `phone-approval` (listener/server line), and check that
  `~/.dsh/phone-approval/config.json` exists (created on first start,
  chmod 600) with `port` (8790), `bind` (`0.0.0.0`) and a random `token`.

## 3. Phone

1. Install `dsh-approvals.apk` from [Releases](../../releases) (or build it,
   see README).
2. Open the app and enter:
   - **Server:** `http://<PC-IP>:8790` — an IP your phone can reach the PC on.
   - **Token:** the `token` value from `~/.dsh/phone-approval/config.json`.
3. Status should show `online`.
4. **Xiaomi/MIUI caveat:** disable battery optimization for the app (it asks
   once) and lock it in recent apps, or background kills the long-poll.
5. **Test:** ask your agent to run something restricted — e.g. a `bash` call
   with `sandbox_permissions: "danger-full-access"` — a notification with the
   command appears; answer it on the phone and the PC proceeds.

## Security notes (read once)

- The **token is the only defense**. `bind` defaults to `0.0.0.0`, i.e. open
  on every interface. Prefer firewalling the port or setting `"bind"` in
  config.json to your Tailscale IP so only tailnet peers reach it.
- Regenerate the token: delete `~/.dsh/phone-approval/config.json`, restart
  dsh, paste the new token into the app.
- Plain HTTP for trusted LAN/tailnet only — never expose this port.
- "Always allow" follows dsh's own always-allow semantics (per tool+session).

## Troubleshooting

- **App says offline:** wrong IP/token, PC firewall, or the plugin is not
  mounted — check the startup log for the `phone-approval` line. If missing,
  verify the `link:` path, the `bundles` entry, and the `node_modules`
  symlink.
- **Mounts but approvals never arrive:** dsh internals (`approval/request`
  waterfall, `tools/pre-execute`) may have been renamed upstream — run the
  harness; if it fails the plugin needs updating.
- **Stale card remains in the PC web UI after the phone answers:** cosmetic
  upstream web-frontend behavior; the card is dead.

## Uninstall

Remove the `dsh-phone-approval` entries from the profile `package.json`
(`bundles` + `dependencies`), delete `~/.dsh/bundles/dsh-phone-approval/` and
`~/.dsh/phone-approval/`, restart dsh. Uninstall the APK.
