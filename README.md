# HaniyaX

**English** | [简体中文](README.zh-CN.md)

A rootless Linux workbench for developers on Android. HaniyaX installs alongside the original Termux (package `dev.haniya.x`) and runs the terminal, coding agents (Claude Code, Codex, Antigravity, OpenCode), DeepSeek Harness and background services in one shared Debian environment. The interface uses the Haniya Relief UI design system.

The app language follows the system by default (Chinese on Chinese systems, English otherwise) and can be changed in **Settings → Language**.

## Features

- **Continue working** – a bottom bar switches between Home, Projects, Agents, Services and Settings, and the round key opens the terminal. Home shows tasks, recent projects and your cards; any project or task can be the default entry when the app opens.
- **Terminal** – multiple tabs, CJK input method support, a compose bar for long text, paste preview, selection copy, scrollback search, customisable extra keys and fullscreen. Leaving a page, closing a tab and ending a task are separate actions; tasks killed by Android are shown as such and can be resumed.
- **Agents on demand** – install, update, start and resume Claude Code, Codex, Antigravity and OpenCode in any project. Updates are your call; settings, sign-in and history are kept.
- **Providers** – per-agent API endpoint, key and model management based on CC Switch's key-field model, with automatic snapshots and one-tap restore.
- **DeepSeek Harness** – installed on demand and embedded in the app with Relief colours.
- **Home cards** – agents can build home-screen cards with the `hx` command. Cards are declarative JSON rendered natively; any card that runs commands must be approved in the app first.
- **Files** – an in-app file manager, a system document provider ("HaniyaX" in the file picker) and optional access to phone storage for projects.

## Install

Download the APK from [Releases](https://github.com/TodayYueC/HaniyaX/releases) (arm64). HaniyaX targets SDK 28 so it can run programs from its own data directory, which means it is distributed by sideloading rather than through Google Play.

On first launch the app unpacks the bundled Debian system (about 250 MB once set up) and installs base packages from Debian's mirrors.

## Build

```bash
./tools/fetch-assets.sh                 # proot (Termux repo, RUNPATH patched to $ORIGIN) + Debian trixie arm64 rootfs
ARCH=x86_64 ./tools/fetch-assets.sh     # optional: x86_64 runtime for the debug build (emulator)
./gradlew :app:assembleRelease          # release build (arm64)
./gradlew :app:testDebugUnitTest        # unit tests
```

Requires JDK 17, the Android SDK (compileSdk 37.2, NDK 28.2) and podman (to export the rootfs).
Release signing reads `keystore/haniyax-release.jks` and `keystore/keystore.properties` (not in git); without them the debug key is used.

## Layout

| Path | Contents |
|---|---|
| `app/` | The app (Kotlin + Compose). `runtime/` proot workspace, tasks and foreground service; `terminal/` terminal; `home/` home and projects; `agents/` agents; `providers/` providers (CC Switch key-field logic ported); `dsh/` embedded DeepSeek Harness; `plugins/` home cards; `control/` local API used by `hx`; `files/` file manager and document provider |
| `app/src/main/assets/sdk/` | Installed into the workspace at `/opt/haniyax`: the `hx` command, docs, card templates, agent and DSH install scripts |
| `app/src/main/assets/i18n/` | UI translations: `source.txt` (Chinese source strings) and line-aligned `en.txt` |
| `relief/` | Haniya Relief UI design system (Compose) and the runtime translation layer |
| `terminal-emulator/`, `terminal-view/` | Termux terminal modules (Apache-2.0); local changes in `UPSTREAM.md` |

### Adding or fixing translations

UI strings are written in Chinese in the source and translated at runtime. Each line of `en.txt` translates the same line of `source.txt`; placeholders `{0}`, `{1}` must be kept. `I18nCatalogTest` checks alignment and placeholders.

## Workspace

- proot + Debian trixie (glibc), running as root with `HOME=/root`; projects live in `~/projects` by default.
- **Android host bridge**: enable in Settings to run `haniyax host` commands using the Android app UID or verified Root. [Usage and authorization](app/src/main/assets/sdk/docs/android-host.md)
- Processes are owned by a foreground service, so they keep running when you leave a page or lock the screen. If Android reclaims the app, tasks are marked "ended by the system" and can be resumed or restarted.

## Known limitations

- **Sandboxes**: proot has no bubblewrap / Landlock, so the OS sandboxes of Codex and DeepSeek Harness are unavailable. The launch sheet and DSH menu offer an explicit full-access option with an explanation.
- **Antigravity sign-in** uses a Google account through the browser, or a Gemini API key (`modelProvider: "gemini"` in `~/.gemini/antigravity-cli/settings.json` plus `GEMINI_API_KEY`).
- **DSH front end**: the built-in DSH web UI with Relief colours injected; a separate mobile front end is not implemented yet.
- **Background survival**: Android 12+ child-process limits and vendor battery policies may end background processes. Settings links to the relevant switches; the app cannot bypass them.
- **Performance**: heavy file I/O (git, npm) is slower under proot than natively; phone storage (`/sdcard`) does not support symlinks or executable permissions.

## License

[Apache License 2.0](LICENSE). Third-party components and their licenses are listed in [NOTICE](NOTICE) and [UPSTREAM.md](UPSTREAM.md).
