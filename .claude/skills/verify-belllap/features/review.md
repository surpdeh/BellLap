# Review screen

Status: **not implemented yet** (`:review` is an empty module; the screen arrives in M1). Defines what to prove then.

After the finish, the commissaire sees the recorded video and the finishing order, with each finish-line crossing marked, and can step through frames.

## Sub-features

- `RV-1` the review screen opens automatically from REVIEW.
- `RV-2` each crossing is listed in order with its frame time.
- `RV-3` selecting a crossing seeks the player to that frame.
- `RV-4` a crossing mark can be corrected or removed.

## How to get to it (user POV)

- Finish a session (flag, crossings, stop). The app moves to REVIEW and opens this screen.
- Reopen a past session from the session list, once it exists.

## Driving it with Gradle and Compose tests

Preconditions: Doctor prints `DOCTOR_OK`.

- **Tier 1 (RV-2, RV-4):** Compose tests with a fake review ViewModel. Handles will be declared in `TestTags.kt`. Capture a Roborazzi screenshot per state and Read it.
- **Tier 2 (RV-1, RV-3):** the replay e2e must end on the review screen, and the player must show the crossing frame. Needs the Media3 player, so it cannot run in the container.
- **Today:** report as **inconclusive: not implemented**.

## Gotchas

- Robolectric cannot play video. Do not claim RV-3 from a tier 1 test.
