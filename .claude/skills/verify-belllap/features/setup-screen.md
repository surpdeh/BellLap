# Setup screen

The app launches to a black camera-preview placeholder with a rounded status chip in the top-left showing the current mode, `SETUP`. The chip stays clear of system bars and display cutouts.

## Sub-features

- `SS-1` launch shows the setup screen root.
- `SS-2` the status overlay shows mode text `SETUP`.
- `SS-3` the camera preview placeholder fills the screen.
- `SS-4` the overlay respects safe-drawing insets.

## How to get to it (user POV)

- Launch the app from the launcher. It opens `MainActivity`, which shows `SetupScreen`.
- No other entry point exists yet.

## Driving it with Gradle and Compose tests

Preconditions: Doctor prints `DOCTOR_OK`.

- **Tier 1, assertions (SS-1..SS-3):** `./gradlew :app:testDebugUnitTest --tests 'net.belllap.ui.SetupScreenTest'`. Expect 2 tests, 0 failures in `app/build/test-results/testDebugUnitTest/TEST-net.belllap.ui.SetupScreenTest.xml`. Handles: `setup_screen`, `status_overlay`, `status_mode` (text `SETUP`), `camera_preview_placeholder`.
- **Tier 1, screenshot:** `./gradlew recordRoborazziDebug`, then Read `app/src/test/screenshots/setup_screen.png`. Expect a black field, a white rounded chip reading `SETUP` top-left, and the grey text `Camera preview` centred.
- **Tier 2, real launch (SS-1, SS-2, SS-4 on a real OS):** push the PR and read the CI evidence comment. `LaunchTest.launchesToSetupScreen` must pass, `evidence/01-setup-launch.png` shows the same screen, and the GIF shows the launch. You cannot run this in the container.
- **Full set with evidence:** `.claude/skills/verify-belllap/scripts/tier1.sh`.

## F10 additions

- Settings overlay (`settings_button`, `settings_overlay`), permission rationale (`permission_rationale`, `grant_button`, `open_settings_button`). Goldens: `setup_settings_overlay.png`, `setup_permission_rationale.png`.
- Tier 2 `LaunchTest` grants camera and mic, so the emulator screenshot shows the emulated camera preview.

## Gotchas

- `SS-4` (insets, cutouts) is not provable by the Robolectric screenshot, which has no cutout. It needs the tier 2 emulator or a phone.
- `recordRoborazziDebug` rewrites the tracked golden. A diff in `app/src/test/screenshots/` that you did not intend is a regression.
