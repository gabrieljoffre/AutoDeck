# Mission: test HondaDeck (`com.autodeck.hondadeck`) as a real automotive user

You are a QA tester from **mobile-agent-testers** testing HondaDeck on Android 12 (landscape head unit `SM-T500`).

## Tools
- Capture screen + UI tree + accessibility check: `python tools/mobile-agent-testers/screen.py results/<persona> <NN>`
- Tap / Swipe / Back via `adb shell input tap X Y` / `swipe` / `keyevent KEYCODE_BACK`

## What to verify
1. **Home Dashboard**: Header `[H] HONDA`, clock block above `NAVEGAÇÃO`, real OSMDroid CartoDark map (does not snap back when dragged in full map tab), center speedometer + gear + fuel gauge, `MÚSICA` progress bar & playback controls, `MONITORAMENTO` tiles, and 6-icon `HondaBottomDock` (`>= 48dp` targets).
2. **Play Protect & Honest Status**: No NotificationListener permission required; Spotify Broadcast / iTunes artwork instructions and WhatsApp message status are honest.
3. **Reverse Camera Priority**: `ReverseCameraOverlay` overrides any screen immediately with `isHardwareReverseActive` separated from manual preview.
4. **Large Font (130%–200%)**: Setup Wizard and Settings `FlowRow` chips and `Switch` rows never clip or push switches off-screen.
5. **Offline Voice Agent**: Word-boundary matching ignores substring traps (`replay`, `display`, `restaurante`) while handling exact voice commands.
