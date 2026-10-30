# Work log

This file preserves project milestones and requested work that is not captured
in the README or source comments. It is not a transcript of earlier chats.

## Repository history available

Previous chat transcripts were not available in the workspace or session
history when this file was created. The following summary is reconstructed from
the current README, tracked files, and visible Git commit subjects/statistics:

- `ff48a48` — “Main branch created.”
- `367138b` — “Basic ready.”
- `0561e07` — “initial changes”; updated the README and Android manifest and
  added the initial tracker screen and video-clip licensing notes.
- `7d159d9` — “training started”; expanded the app and README and added the
  experimental court-detection implementation.

Commit subjects are brief, so this log does not infer unavailable chat
decisions or claim details beyond the repository evidence.

## 2026-10-30

- Kept analysis focused on live-camera and uploaded-video workflows. Still
  images are not analysis inputs and are reserved for a future labeled-model
  training workflow; model training is not implemented in this prototype.
- Court estimates report visible corners and require center/net-line evidence
  before projecting corners outside the frame.
- Added a training-history treemap grouped by month. Tile area represents
  session duration and tile color represents tagged-hit count; the display uses
  the 20 most recent sessions.
- Added repository-specific Copilot coding instructions for Kotlin/Compose
  conventions, safe handling of experimental analysis claims, and build
  validation.
- Added this file to keep an explicit, future-facing record of repository
  milestones and substantial requested work.
