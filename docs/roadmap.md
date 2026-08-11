# Roadmap

Ideas agreed for later versions, in rough priority order.

## In-app marking of average-speed section gantries (planned)

**Problem:** section entry/exit points in `pipeline/data/sections.yaml` are
curated from geometry and government rows, and the ~20 candidate zones can't
be verified without someone physically there. Riding every road in Taiwan to
check them is not realistic.

**Feature:** while detection is running, a big "mark" button (usable with
gloves, one tap) records the current GPS fix + bearing + timestamp as a
section **start** or **end** gantry — tap once passing the entry portal/sign,
once at the exit. Marks are reviewable afterwards on the map screen.

**Data flow:**

- Marks are stored locally and exportable as a ready-to-paste
  `sections.yaml` fragment (or a pre-filled GitHub issue), so a ride
  becomes a pipeline PR with zero transcription.
- Optionally let friends' installs collect marks too. Multiple marks of the
  same gantry get averaged (weighted by GPS accuracy) so positions become
  more precise as passes accumulate; the pipeline can then attach a
  confidence per endpoint.
- **Privacy constraint:** the app promises location never leaves the device
  and the only network traffic is the db update. Crowd-collection must not
  break that: sharing stays explicit and manual (user-triggered export /
  share-sheet of the marks file only — individual gantry points, never
  tracks), opt-in per export, nothing automatic in the background.

**Why this beats more geometry work:** a mark made at the physical gantry is
ground truth; it both verifies the shipped zones (瑪陵 exit bearing, 蘇花改
portal offsets, 向上路 中興路 end) and unlocks the whole candidates list at
the bottom of `sections.yaml`.

## Municipal dataset parsers (v1.1)

Taichung's 科技執法 dataset (170673) alone has ~77 enforcement points absent
from the national data, and red-light cameras exist **only** in municipal
datasets. Add per-city parsers behind the existing dedupe, starting with
Taichung, then Taipei (130111, 135957). Expect swapped lat/lon columns and
text in coordinate fields — route everything through `normalize_coords` and
the unresolved report.

## Smaller items

- Recognize 「A至B」-style rows in dataset 7320 as section hints instead of
  point cameras (suppress the redundant point alert near a curated section
  exit).
- Auto-start detection on Bluetooth connect; auto-stop when stationary for
  N minutes.
- Verify the remaining `sections.yaml` candidates as second anchors turn up
  (each is a ~10-minute job with `scratchpad` tooling — see the comment
  block in that file for what each zone still needs).
