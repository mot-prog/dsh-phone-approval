# DSH Phone Approval

Answer your DeepSeek Harness agent's permission prompts ("this command needs
approval") **from your Android phone**. The phone app races the web UI:
wherever you click first — phone notification, phone app, or browser — wins.

```
Android app  <—HTTP long-poll, Bearer token—  dsh-phone-approval plugin (port 8790)
                                                  │  approval/request waterfall listener (outermost)
                                                  ▼
                                        DSH agent turn (allow once / reject)
```

## How it works

- **Host side** (`bundles/dsh-phone-approval/lib/index.js`): a DSH plugin that
  registers an `approval/request` waterfall listener at startup. The web UI's
  host-side forwarding listener only registers when a browser connects, so this
  one is outermost and the phone always gets the prompt. The plugin serves a
  token-authenticated HTTP API that the phone long-polls.
- **Phone side** (`app/phone-approval/`, plain Java, no Gradle): a foreground
  service long-polls the PC, mirrors each pending approval as a heads-up
  notification with **Allow / Deny** actions, and keeps an in-app list.

### Resolution semantics

| Situation | Result |
|---|---|
| Phone answers first | tool allowed/rejected; web card clears via session events |
| Web UI answers first | phone notification disappears on next poll (≤25 s) |
| Nothing connected | after 60 s of phone silence the request fails closed (`unavailable`, exactly like stock DSH) |
| Agent turn cancelled | prompt dismissed on the phone automatically |

Answers are **allow-once** or **reject** only — there is no "allow always" on
purpose.

## PC setup (already done by the agent)

1. Plugin bundle: `~/.dsh/bundles/dsh-phone-approval/`
2. Registered in `~/.dsh/profiles/web/package.json` (`dsh.profile.bundles` +
   `link:` dependency; node_modules symlink created manually because pnpm's
   store was read-only for the agent).
3. Token/config: `~/.dsh/phone-approval/config.json` (chmod 600):
   ```json
   { "port": 8790, "bind": "0.0.0.0", "token": "<random 48-hex>" }
   ```
   Read the token with: `cat ~/.dsh/phone-approval/config.json`
4. **Restart `dsh web`** to load the plugin. Look in the log for:
   `phone-approval: listening on http://0.0.0.0:8790`

### Firewall (firewalld is active on this Arch box)

Either open the port on your LAN only:
```
sudo firewall-cmd --permanent --add-rich-rule='rule family=ipv4 source address=192.168.0.0/24 port port=8790 protocol=tcp accept'
sudo firewall-cmd --reload
```
…or skip the firewall change and use **Tailscale**: this host has
`tailscale0` (`100.70.50.40`) — install Tailscale on the phone and use
`http://100.70.50.40:8790`.

## Phone setup

1. Install `~/.dsh/out/dsh-approvals.apk` on the phone
   (`adb install -r dsh-approvals.apk`, or copy the file over and tap it —
   it's self-signed, accept the "unknown source" warning).
2. Open **DSH Approvals**, enter:
   - Server URL: `http://192.168.0.12:8790` (LAN) or `http://100.70.50.40:8790` (Tailscale)
   - Token: contents of `~/.dsh/phone-approval/config.json` → `"token"`
3. **Save & Connect**, allow notifications, and accept the battery-optimization
   exemption prompt (otherwise aggressive OEM power managers kill the poller).
4. Grant-on-boot is handled (the service restarts after reboot when configured).

## Verifying

```
curl -H "Authorization: Bearer $(node -e 'console.log(require(process.env.HOME+"/.dsh/phone-approval/config.json").token)')" \
  http://127.0.0.1:8790/api/health
```
→ `{"ok":true,"service":"dsh-phone-approval","version":0,"pending":[]}`

Then trigger any approval (e.g. ask the agent to run a sandbox-denied command)
and watch the phone notification.

## API (for tinkering)

- `GET /api/health` / `GET /api/poll?wait=25&since=<version>` /
  `POST /api/answer {"id":"pa-…","outcome":"allow-once"|"reject"}` — all
  require `Authorization: Bearer <token>`.

## Test & rebuild

- Protocol tests (stubbed ctx, all resolution paths):
  `node ~/.dsh/bundles/dsh-phone-approval/test/harness.mjs` → `ALL PASS`
- APK rebuild (toolchain in `~/.dsh/build-tools/`, no Gradle):
  `bash ~/.dsh/app/phone-approval/build.sh` → `~/.dsh/out/dsh-approvals.apk`
  (signing keystore: `~/.dsh/out/dsh-approvals.keystore`, pass `dshphone` —
  keep it to update the app without uninstalling).

## Security notes

- HTTP is plain (LAN/Tailscale only). Token is the gate; every endpoint 401s
  without it. Don't port-forward 8790 to the internet.
- Set `"bind": "127.0.0.1"` in the config if you only ever use Tailscale.
- Change the token by deleting the file and restarting dsh (it regenerates).

## Known limitations

- A web-UI approval card answered from the phone may linger until the next
  session event repaints the composer (clicking it afterwards is harmless).
- Terminal/ACP answerers also race the phone (first click wins everywhere).
