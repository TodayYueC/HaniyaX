# Upstream sources

| Path | Upstream | Revision | License |
|---|---|---|---|
| terminal-emulator/, terminal-view/ | https://github.com/termux/termux-app | 8629e632fcb95da272221be327db653fb24befe9 | Apache-2.0 |
| relief/ , docs/design/ | https://github.com/TodayYueC/haniya-relief-ui | c531446e87ffa09565eaef1d6ad6b7cc68ad3f41 | "use freely" (see upstream README) |
| relief/src/main/res/font/inter_variable.ttf | https://github.com/google/fonts (ofl/inter) | main | SIL OFL 1.1 |
| assets: proot, libtalloc, libandroid-shmem | https://packages.termux.dev (termux/proot) | proot 5.1.107.102, talloc 2.5.0, shmem 0.7 | GPL-2.0 / LGPL-3.0 / BSD — source: https://github.com/termux/proot, https://talloc.samba.org |
| assets: debian rootfs | docker.io/library/debian:trixie-slim (arm64) | 2026-11-04 | Debian (various free licenses) |
| app/src/main/assets/fonts/JetBrainsMono.ttf | https://github.com/google/fonts (ofl/jetbrainsmono) | main | SIL OFL 1.1 |
| app/src/main/assets/fonts/NotoSansSymbols2.ttf | https://github.com/google/fonts (ofl/notosanssymbols2) | main | SIL OFL 1.1 |

## Local modifications (Apache-2.0 terminal modules)

- terminal-emulator `TerminalEmulator`: answers OSC 4/10/11 colour queries from the current scheme (TUIs detect light/dark).
- terminal-view `TerminalView`: IME input connection that lets Chinese/Japanese/Korean keyboards compose and commit text; composition (preedit) drawn at the cursor; scrollback search highlight and programmatic top-row control; selection mode tweaks.
- terminal-view `TerminalView.onKeyDown`: Back is no longer swallowed when the view has no live session (ended tasks).
- terminal-view `TerminalRenderer`: selection / search highlight painted with theme colours instead of inverting; dim text blended toward the background; preedit rendering; glyphs narrower than their cells (CJK) are centred instead of stretched horizontally.
- terminal-view `TextSelectionCursorController`, `strings.xml`: Chinese labels and selection behaviour.
