# Fire Plan (火情预案)

Home fire-drill app for PICO OS 6. Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage (Full Space,
passthrough), package `io.embodit.fireplan`. You mark the fire spot, extinguishers, exits and smoke
alarms in your own home, walk the escape route once, then run a drill: a small stylised fire starts
by the stove, the alarm sounds, and you either fetch the extinguisher and do PASS (pull, aim, squeeze,
sweep) or get out along your own green route; the result page tells you whether two minutes was enough.

Design package (binding): `../spatial-app-factory/apps/fire-plan/design-package.json` (archetype D,
container STAGE_MIXED, window model single_panel_with_popup, 26 states S00 - S23 + M1 / M2, copy,
rules). Mockup: `../spatial-app-factory/apps/fire-plan/sample.html`. Reference project (same skeleton):
`../spatial-ruler` (its `AGENTS.md` holds the SDK findings this app relies on).

Built by the factory's build station on 2026-10-25 (v0.1.0, debug) and run on the emulator
(emulator-5562, AVD Pico_Emulator_6_1_c): every designed state captured through the QA hooks, no crash.
Not yet run on a headset.

## v0.1.1 (2026-10-26, pc1): the room box, placement anywhere, route without walking

The user tried v0.1.0 in an office: no DOOR / CEILING planes were reported, so the smoke alarm and the
exit could not be placed at all. v0.1.1 follows factory taste profile D7 (never stuck scanning: a
virtual room) and B17 (as little walking as possible):

- **Room box** (`domain/model/RoomBox`, `ui/fire/scene/RoomView`): a box (default 5 x 6 x 2.7 m, the
  viewer 1.5 m from its back wall, facing its front wall) whose six planes (`room_floor`, `room_ceiling`,
  four `room_*` walls) are what every tap hits. Plane detection is only asked for two numbers:
  the floor height and the ceiling height (`PlaneTracker.realFloorY / realCeilingY`). Walls, doors and
  furniture are not needed; detected TABLE / CABINET tops are still offered (fire spot on the stove
  counter). Detected walls / doors / other planes are ignored.
- **Scan timeout**: the room is created as soon as a floor is detected, else after 5 s on the Stage
  ground (y = 0) as a *virtual* room (snackbar "No floor found · using a virtual room" + [Adjust]). A
  floor / ceiling detected later moves the room's floor / ceiling (a height the user set wins).
- **Room card** (main panel, state name `ROOM`; the cube key on home and on the tray): width / length
  +-0.5 m, height +-0.1 m, turn +-5 degrees, slide left / right and push away / pull closer 0.25 m
  (relative to where the user looks), "Around me" re-centres it. Size is kept in the settings; the
  position is re-made around the user at every launch (Stage coordinates do not survive a relaunch).
  Rendered as 12 white edges at 45 %, a 7 % ceiling fill, and a 7 % floor fill only while virtual.
- **Placement never refuses** (`PlaceMarkerUseCase`): whatever room surface is pinched, the marker goes
  where its kind belongs. Detector: ceiling above the point (a wall pinched within 0.6 m of the ceiling =
  wall-mounted; lower on a wall = ceiling 0.6 m in from it). Exit / extinguisher / fire spot: floor below
  the point, or 0.3 m in front of the wall that was pinched (fire spot on a detected table top stays on
  it). A pinch outside the box (backstop) is projected along head -> tap point onto the room's floor /
  ceiling (up to 12 m), so a real room bigger than the box still works.
- **Route without walking**: the route card's "Draw it from here" makes a straight route fire spot ->
  exit (0.4 m dots); after any route is saved a bright run travels along it from the fire spot to the
  exit (route preview, `FireScene.previewFrame`). Walking the route stays available. The drill itself
  still involves walking (fetch the extinguisher / reach the exit).
- UI audit fixes (2026-10-26, emulator-5562): the extinguisher prop moved from 20 / 28 degrees to 36 / 42 degrees
  right / down (`Follower.PROP_OFFSET`) because it covered the flame while aiming; the marker strip and detector
  card of a ceiling detector hang at eye height straight below it (`FireScene.placeStrips`, taste D10) instead of
  up at the ceiling; the `seed` extinguisher moved to 35 degrees right so its plate no longer sits behind the fire
  spot's. Audit list and shots: `../spatial-app-factory/apps/fire-plan/build/ui-audit.md`.
- QA hooks added: `noscan on|off` (on = ignore detection and drop the room: S01, then the virtual room after 5 s), `scan_timeout`, `room [w+|w-|l+|l-|h+|h-|turn+|turn-|left|right|away|closer|recenter|done]`,
  `state ROOM`, `tap x,y,z` (a pinch on the room surface at that Stage point), and `ui` controls
  `room`, `tray_room`, `room_*`, `route_auto`, `snackbar_room`. `dump` has a `room` object.
- Headset install note: v0.1.0 on the headset was signed on another machine (debug keystore differs),
  so `install -r` fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE. The station backed up
  `shared_prefs/fire_plan.xml` with `run-as`, uninstalled, installed v0.1.1 and pushed the file back
  (`MSYS_NO_PATHCONV=1` in Git Bash for `/data/local/tmp`). The world anchors survived the reinstall.
  First headset launch (office): floor 0.03 m and ceiling 4.11 m detected, room 5 x 6 x 4.1 m.

## Structure

```
app/src/main/java/io/embodit/fireplan/
  Main.kt                      DefaultStage { FirePlanTheme { FireScreen() } }
  platform/                    SpatialApplication (launch), LaunchActivity, FireGraph (wiring), FireLog
    sense/PlaneTracker         plane detection -> PlaneSurface (Stage metres)            [from Spatial Ruler]
    tracking/HeadTracker       HMD pose, lost-tracking sign, block reason                [from Spatial Ruler]
    anchor/WorldAnchorGateway  world anchors create / load / remove                      [from Spatial Ruler]
    input/ControllerInput      controller ray, trigger, thumbstick (ControllerTrackingProvider)
    input/SystemPointer        what is known about the gaze + pinch pointer              [from Spatial Ruler]
    audio/AlarmPlayer          T3 alarm tone in a static AudioTrack + SpatialAudioTrackExtension
  domain/model                 Vec3 / Quat4, PlaneSurface, Marker, Route, Settings, DrillRules (every number of the design)
  domain/usecase               PlaceMarkerUseCase (which surface takes which marker), DetectorCheckUseCase
                               (GB 50116 / NFPA 72 rows), FindingsUseCase (F1 - F7 / OK), HitSurface, ViewFrame,
                               FacePanel, RestBody                                        [last four from Spatial Ruler]
  data/                        FireRepository (SharedPreferences JSON: markers as anchors + offsets, routes in
                               their exit anchor's frame, last five drills, settings), AnchorGateway
  ui/theme                     FireColors (the only colour literals), FirePlanTheme (PicoTheme, 16 roles), typography
  ui/fire                      FireViewModel (the whole state machine, 100 ms drill clock), FireUiState / FireEvent, FireScreen
  ui/fire/components           FireStage (SpatialView + panel pool), MainPanel (home / tray / exit pick / route card),
                               DrillCard (+ PauseMenu), ResultPanel (+ top-down map), MarkerPanels (strip, detector card),
                               MoreMenu, Overlays (dialog, snackbar, coachmark), Common (keys, buttons, type roles)
  ui/fire/scene                FireScene (ECS: one frame System; taps, drags, head sampling, placement), PanelRig
                               (pooled attachment panels, Spatial Ruler findings), Follower (lazy follow), MarkerViews,
                               FlameView, ExtinguisherView, RouteView, PlaneColliders, SceneKit (materials, meshes)
  ui/fire/debug/FireProbe      seams for the debug receiver (no-ops in release)
app/src/debug/                 DebugCommandReceiver + DebugCommands (the QA hooks)
```

One direction of data: panel controls, scene callbacks (taps, head ten times a second, planes,
tracking) and debug hooks -> `FireViewModel.onEvent` -> `FireUiState` -> `FireScene.render`
(idempotent diff of entities) and the panels. Per-frame work (flame flicker, lazy follow, route pulse,
plate billboarding, spray cone, controller ray) lives in `FireScene.onFrame` only and never goes
through Compose.

## What the room contains

- Markers (`MarkerViews`): ground disc 0.30 m (type colour 40 % + solid edge), white pole 0.9 m, icon
  plate 0.12 m that faces the user (texture = type colour circle + white vector icon, exits numbered
  when there are two). Fire spot on a table: no pole, plate 0.25 m up. Detector: ceiling disc + 0.15 m
  stem + plate; wall fallback when no CEILING plane is reported. Tap target = `marker:<id>` on the plate.
- Route dots (`RouteView`): floor discs 6 cm, 0.4 m apart, green 60 %; while evacuating the six ahead
  light to 100 % and pulse forward every 0.6 s. Shown while setting up and while evacuating.
- Flame (`FlameView`): three low-poly teardrops (outer 85 % orange, mid, core), 2.5 Hz flicker, 4 degree
  sway; L1 0.30 x 0.22 m (the only level that can be fought), L2 0.60 x 0.40, L3 1.10 x 0.70; 0.4 s level
  transitions; 0.6 s shrink + grey puff when it goes out. Base ring (dashed, interaction yellow) with the
  colliders `fireBase` / `fireTop` (aimed at the base vs the flames). No particles, no light.
- Extinguisher prop (`ExtinguisherView`): red cylinder, black lever, yellow pin ring (`extPin`, tap),
  nozzle, gauge with a needle that follows the 12 s of spray. Lazy follow at the lower right of the
  view; fades out in 0.3 s when the evacuation starts. The spray cone runs from the nozzle to the aim.
- Alarm (`AlarmPlayer`): synthesised T3 pattern on a looping static AudioTrack, attached to the
  `alarmSource` entity at the nearest detector (else the fire) through `SpatialAudioTrackExtension`
  (object mode). 40 % once the extinguisher is in hand or the evacuation has started; paused with the
  drill; the alarm detector's plate blinks yellow at 1 Hz.

## Interaction

- Place: pick a tile on the tray, pinch on a surface. v0.1.1: every room surface takes every kind (see
  "v0.1.1" above); v0.1.0 needed FLOOR / DOOR / CEILING planes and said "can't place here" otherwise. A marker is its world anchor + offset;
  "not saved · retry" when the anchor fails. Tap a marker -> strip (delete / close); a detector also shows
  the check card. Tap empty space = deselect; with the menu open the first pinch only closes the menu.
- Route: tray -> "walk the escape route" (two exits: pick one first). Walk to the fire spot (0.8 m),
  then to the exit; a point every 0.4 m (3-sample smoothing); under 1.5 m or 4 points = "too short".
- Drill: countdown 3-2-1 (waits while you stand within 1.5 m of an exit), then by scenario: 70 % small
  (L1) / 30 % big (L2 = evacuate at once). Arrival = head foot point within 0.8 m for 0.3 s, coming from
  outside 1.0 m. Extinguisher reached (fire L1) -> pull the pin (tap the yellow ring or the card's button)
  -> aim at the base ring -> hold the pinch (or the controller trigger) = spray -> move sideways across the
  ring from one side (< -0.5 r) to the other (> +0.5 r) = one sweep; three sweeps put L1 out. 1.5 - 3.5 m
  from the fire counts (M1 otherwise: "too close / too far", sweeps not counted). The fire grows a level
  every 25 s without spraying; L2 or an empty extinguisher (12 s) = evacuate. Reaching an exit stops the
  clock -> result. Pause key, or going to the background, pauses (S19).
- System pointer (gaze + pinch, emulator mouse): taps on markers / pin / surfaces; a press-and-hold on
  the base ring is the squeeze, the hand's sideways travel (`dragAmount` px x 0.4 mm x depth factor)
  the sweep. Controller: `ControllerInput` samples the ray every frame; `FlameView.aimOf` tells base
  from flames (S14 ring / "aim at the base, not the flames"), the trigger squeezes, the ray's hit on the
  base plane sweeps. The thumbstick is read but has nothing to turn (see Deviations).

## Panels (all SpatialUI inside PicoTheme; no Material)

Pooled attachment panels, declared once with fixed sizes, shown at 0.75 (1 dp = 0.6 mm):
`mainPanel` 400 x 300 (home / tray / exit pick / route card), `drillCard` 280 x 120, `resultPanel` 520 x 440
(all three ride on the lazy follow 18 degrees left, 24 degrees down, 0.6 m, leaning back 12 degrees; slide
back after 0.5 s out of a 45 degree window), `markerStrip` 232 x 64 (12 cm above the plate), `detectorCard`
296 x 176 (below the plate), `pauseMenu` 380 x 128 (above the drill card), `moreMenu` 400 x 256 (above the
main panel), `coachPanel` 360 x 80 (beside the tray), `dialogPanel` 480 x 280 and `snackPanel` 560 x 72
(0.9 m ahead / head-locked 14 degrees up, at 1.125). Dialogs and the snackbar are self-built panels
(`AlertDialog` / `SnackbarHost` do nothing in a Stage). Copy: `res/values/strings.xml` (en) and
`res/values-zh`, read through `rs()` so the in-app language switch works without a restart. Fonts:
Noto Sans (Latin, four weights) + Noto Sans SC (variable, the full font, 17 MB) bundled under OFL.

## QA hooks (debug build only)

```
adb -s emulator-5562 shell "am broadcast -a io.embodit.fireplan.DEBUG -p io.embodit.fireplan --es cmd '<command>'"
```

| command | effect |
|---|---|
| `seed` | example setup laid out in front of the head (the emulator's view is only about ±25 degrees wide): fire spot on a table / cabinet top within 30 degrees and 5 m, else on the floor 2.8 m ahead; extinguisher 3.2 m ahead right; exit ① 4.6 m ahead; exit ② at the DOOR plane (else behind); two detectors on the ceiling (one 1 m from the stove = fails the 3 m row); a straight route to each exit; one past drill (1:56 small) |
| `state S00` … `state S23`, `state S15+M1`, `state M1`, `state M2` | straight to that designed state (seeds first when needed). Variants: `state S05 det` / `det2` / `exit` (which marker is selected), `state S08 short`, `state S11` (countdown held; `state S11 run` lets it run, `state S11 door` shows "step away from the exit"), `state S14 top`, `state S15+M1 far`, `state S18 bad` (with the distance finding) |
| `place fire|ext|exit|det [x,y,z]` | a marker at a Stage point (snapped to a fitting surface) or at a default spot |
| `drill small|big` | a drill with that scenario, countdown skipped |
| `arrive fire|ext|exit|exit2|<id>` | as if the user had walked there (route recording and drills) |
| `pass` / `aim ring|top|none` / `spray on|off` / `sweep <n>` / `distance near|far|ok` | the PASS steps (3 sweeps put the fire out) |
| `pause` / `resume` / `end` | the drill |
| `coach_reset`, `restore_timeout`, `tracking lost|ok` | S23, S10, M2 |
| `lang system|zh|en`, `hints on|off`, `scenario random|small|big`, `menu`, `clear`, `clear_now`, `exit` | settings and menus |
| `ui <control>` | presses a panel control by name (`DebugCommands.kt` lists them) |
| `dump` | `DUMP {json}` (also files/dump.json): state name, markers, drill, result, head |
| `pose`, `bounds`, `planes`, `park`, `fps on|off` | scene queries |

The build station's run (2026-10-25, emulator-5562) is in `../spatial-app-factory/apps/fire-plan/build/`:
`first-screen.png`, `launcher.png`, `contact-sheet.jpg` and `shots/` (S01 - S23, S15+M1, M2, the S02 / S05 / S07 /
S11 / S14 variants, `S09-real` / `S10-real` from a real relaunch, and the `inout-*` panel stress shots).

Screenshot list for QA (design `states[].qaCapture`): S00 (buttons disabled + spinner), S01 (snackbar
"look at the floor"), S02 ×2 (`state S02`; then `place fire` + `place ext` for "2 points, no exit"),
S03, S04, S05 ×3 (`state S05`, `state S05 det2`, `state S05 det`), S06, S07 ×2 (`state S07`, then `arrive fire`),
S08, S09, S10, S11 ×2 (`state S11`, `state S11 door`), S12, S13, S14 ×2 (`state S14 ring`, `state S14 top`),
S15, S15+M1, S16, S17, S18, S19, S20, S21, S22, S23, M2. Console screenshots: take two in a row
(the first often shows the previous frame).

Log tag `FirePlan`: `STATE <Sxx>`, `EVENT`, `PLANES`, `TAP`, `DRAG`, `MARKER add|remove|refused`,
`ANCHOR saved|failed|load`, `ROUTE start|done|short`, `DRILL start|fire|got|pin|spray|sweep|evacuate|out|finish|pause`,
`SNACK`, `COACH`, `FOLLOW`, `ALARM`, `CMD`, `DUMP`, `POSE`, `PANEL`, `FPS`.

## Build

```
# PowerShell (Git Bash fails on gradlew: CRLF)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
adb -s emulator-5562 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5562 shell monkey -p io.embodit.fireplan -c android.intent.category.LAUNCHER 1
```

Material gate: `grep -rEf ../spatial-app-factory/bin/material-grep.txt --include=*.kt --include=*.kts app/src` must be empty.

## Deviations from the design package (recorded in the factory's state.json)

- Factory default-interaction rule D1 (thumbstick rotation + snap frame): the markers are rotationally
  symmetric (discs, poles, billboarded plates) and the design package has nothing to turn, so the
  thumbstick turns nothing and the "turn with the stick" hint line is not shown; snapping is the
  design's own (exit on a DOOR plane lands in front of the door, markers sit on their surface).
- The marker strip keeps its 232 dp width by stacking name and distance in two lines.
- Noto Sans SC is bundled whole (17 MB) instead of a subset (no fontTools on the build machine).

## Emulator lessons (2026-10-25)

- (2026-10-26, v0.1.1) The emulator reports its floor at y = 0.40 (the headset: 0.03), so the scanned room stands
  at 0.40 there and the virtual one (`noscan on`) at 0. On a machine short of memory the emulator renders at 2 - 3
  fps: the scene build takes 13 - 15 s (1.2 s on the headset) and the PICO system UI may raise "isn't responding"
  dialogs that linger; check `dumpsys input | grep -c "Application Not Responding"` before every screenshot and press
  Wait / Close app with a slow click (`emu.sh click X Y -NoFast -HoldMs 1200`, coordinates x 0.84375 on a 2560 px AVD).

- Never send `KEYCODE_HOME` to the emulator: the system's "Back to Home" dialog cannot be dismissed over
  adb and the Stage's renderer stays black afterwards; only `adb emu kill` + a fresh start recovers it.
  The app icon is checked on that dialog or in the launcher by hand instead.
- The console screenshot (`adb exec-out screencap`) often returns a frame whose lower half is black (the
  Stage's lower region is not composited), and in some states it stays black however long you wait.
  `adb shell screenrecord --time-limit 3 --size 1620x1620 /sdcard/x.mp4` + the last frame (ffmpeg) always
  shows the whole stage; the factory station's `shot.sh` does that.
- At the emulator's idle frame rate the larger panels (result panel, more menu) take up to 10 s to draw
  their content after they appear: wait 10 - 12 s before a screenshot of S18 / S20, 4 s is enough elsewhere.
- World anchors do not survive an emulator kill: the next launch goes S09 -> S10 ("your last setup isn't
  here"), which is the designed path.
- `ControllerTrackingProvider` is `DEVICE_NOT_SUPPORTED` in the emulator's eye mode; the spray is driven
  by the system drag (or the `spray` / `sweep` hooks) there.

## Not built / not verified

Nothing has run on the emulator or a headset yet (the station built the APK only). Headset-only:
alarm direction, real walking smoothness, cross-room anchor drift, controller / hand sweep feel,
CEILING planes in a real home, permissions.
