# DSH Phone Approval

Answer your [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh-host)
(dsh) agent's permission prompts **from your Android phone**.

When the agent asks to run a restricted command, your phone gets a
notification + in-app card showing the **exact command**, with
**Allow once / Always allow / Deny** buttons. The phone races the web UI:
wherever you answer first wins. If nobody answers, dsh falls back to its
normal prompt and eventually fails closed — nothing is ever auto-allowed.

![build-apk](https://github.com/mot-prog/dsh-phone-approval/actions/workflows/build-apk.yml/badge.svg)

## How it works

```
dsh agent ── approval waterfall ──> plugin (HTTP API :8790, token auth) <──long-poll── Android app
```

- **`plugin/`** — a dsh bundle (pure Node, zero dependencies) that registers
  as the outermost `approval/request` waterfall listener and serves a tiny
  token-authenticated HTTP API. It also hooks `tools/pre-execute` to capture
  tool arguments, so the phone shows the real bash command, not just the
  reason string.
- **`app/`** — plain Android Java (minSdk 26, no Gradle, no AndroidX):
  foreground service long-poll, BigText notifications with the command,
  system dark/light mode.

## Quick start

1. **Phone:** install the APK from the [latest
   release](../../releases/latest) (allow unknown apps).
2. **PC:** install the plugin into your dsh profile — see
   [SETUP.md](SETUP.md). It works best as a task for your own dsh agent:
   *"Set up dsh-phone-approval, follow SETUP.md"*.
3. Point the app at `http://<pc-ip>:8790` and paste the token from
   `~/.dsh/phone-approval/config.json`. Done — restricted commands now ping
   your phone.

Best over **Tailscale** (both machines on the tailnet, bind the port to the
Tailscale IP — see security notes in [SETUP.md](SETUP.md)).

## Building the APK

Every `v*` tag builds the APK in GitHub Actions and attaches it to the
release — no local Android SDK needed. For local builds, `bash app/build.sh`
(JDK 17 + Android build-tools 34.0.0; paths auto-detected, see the header
comment). For stable signing across releases, set the `KEYSTORE_BASE64` and
`KEYSTORE_PASSWORD` repo secrets.

## Compatibility

Built against `@deepseek-ai/dsh-*` as of October 2026. The plugin self-test
(`node plugin/test/harness.mjs`) verifies the dsh seams still line up.
MIT licensed.
