# files.horse access primer (for JB production bots/sessions)

Tue 2026-10-12. How to reach the Jupiter Broadcasting Nextcloud from any
session on the ThinkPad. No credentials needed beyond what's already here.

## Transport

- Binary: rclone 1.75.1 in the nix store (no install needed):
  `/nix/store/xz71r0m7r6l2gflj8c6b402big8skwd6-rclone-1.75.1/bin/rclone`
- Config: `~/.config/rclone/rclone.conf`, remote name `files.horse`
  (Nextcloud WebDAV, auth already working — verified 2026-10-12).
- For NixOS packaging: `pkgs.rclone` (same binary class).

## Layout (remote root = per-show dirs: LUP, TWL, TWiB, Coder, ...)

- Episode transcripts (Auphonic-style): `files.horse:LUP/archive/lup-NNNN.json`
  + `lup-NNNN.vtt` (top level of archive/, ~100–850 KB each).
- Episode video clips: `files.horse:LUP/archive/lup-NNNN-<slug>/` (numbered
  `NN-Title CLIP [timestamp].mp4`).
- Stems/masters: loose `LUP-NNNN_<Host>.flac` (`_Chris/_Wes/_Brent/_Guest`,
  `_Master`, `_SoundBoard`) + `Linux Unplugged NNN (Ads/Premium).flac`.
- Clip collections: `members-segments-since-621/` and similar.

## Conventions

- Everything is PER-EPISODE. Fetch when working an episode, not every run.
- Local working dir: `~/Downloads/workdir/` (+ `done/` subdir when finished).
- Download, don't mount: `rclone copy files.horse:LUP/archive/lup-0688.vtt ~/Downloads/workdir/`
- Never write to the remote except an explicitly authorized destination
  (e.g. `Team/stuff4joe/raw/...` for staged uploads). Default direction is DOWN.
- Restricted-shell quirks do NOT apply here (that's rsync.net); rclone handles
  auth/retry/checksums itself.
