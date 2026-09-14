# VITALITY.SYS

*Vitals, not checklists.*

VITALITY.SYS is a two-part, self-hosted Android system for tracking
activities of daily living — meals, hydration, medication doses, and
hygiene/self-care tasks — framed as a video-game vitals meter instead of a
to-do list. It's built to be dogfooded as an accessibility tool, so the
interrupt-response flow (see below) is a first-class feature, not an
afterthought.

- **`VITALITYMOBILE`** — the phone companion. Dashboard, schedule editor,
  Health Connect bridge, pain/symptom logging, compliance auditing, and PDF
  report export. Package: `com.snakesan.vitalitysys`.
- **`VITALITYDECK`** — a standalone Wear OS watch app. This is the primary
  day-to-day interaction surface: rotary-bezel or edge-tap navigation
  between five "decks" (one per protocol, plus a pain-logging deck), each
  showing one piece of live status and one action button. Keeps working
  even if the phone is out of Bluetooth range.

The two talk to each other entirely over the **Wear Data Layer API**
(`MessageClient`/`DataClient`) — no server, no internet permission in either
manifest, no accounts, no analytics SDKs. Everything lives in a local Room
database and `SharedPreferences` on the phone, and `SharedPreferences` on
the watch.

## The core mechanic: HP, not checkboxes

The system tracks a single **HP value (0–100)**, recomputed on every tick
from four independent "protocols," each of which bleeds damage the longer
it's neglected:

| Protocol | Tracks | Damage model |
|---|---|---|
| **NUTRIENT** (cyan) | Meals eaten today vs. up to 5 user-scheduled meal times | 30 min grace, then 5 dmg + 1/5 min late, per missed meal |
| **CHEMISTRY** (pink) | Any number of medications, each with any number of daily doses | 30 min grace, then 10 dmg + 1/3 min late, per missed dose — the steepest curve |
| **HYDRATION** (green) | mL logged (250 mL/log) vs. a target spread across a user-defined active window | 500 mL grace buffer, then 5 dmg + 1 per 100 mL behind pace |
| **MAINTENANCE** (amber) | Any number of user-defined hygiene/self-care tasks | 60 min grace, then 5 dmg + 1/10 min late, per missed task |

A **Bio-Drift** penalty multiplies damage when more than one protocol is
failing at once, so letting things slide across categories compounds
instead of just adding up. Holding 100 HP continuously builds a second
**Overcharge** meter (0–50 over ~60 minutes) that resets instantly the
moment HP drops — visualized as a second bar under the phone's EKG monitor.

Everything is user-configurable: meal count/times, any number of named
medications with any number of dose times, any number of named hygiene
tasks, hydration target, and the active-hours window all live in a
`SysConfig` that syncs phone ↔ watch.

## Context management (interrupt-response flow)

When a protocol comes due and you respond to it, the phone doesn't just log
the action — it treats the alert as an interruption of whatever you were
already doing, captures that, and hands it back to you afterward:

1. **Capture** — "STATE CURRENT VECTOR / What were you doing?" A free-text
   note, or a **photo** (camera capture, added alongside typing so a
   snapshot of whatever's in front of you can stand in for a rushed
   sentence).
2. **Action** — the actual protocol screen (log the dose, drink the water,
   etc.), with your captured note/photo still visible so it isn't just
   floating in short-term memory.
3. **Restore** — "SYSTEM OPTIMIZED — RESUMING VECTOR: `<what you typed>`"
   before handing you back to the dashboard.

Three pieces make this robust rather than just a nice screen flow:

- **Configurable escape difficulty.** A FOCUS settings card lets you pick
  how hard it is to back out of a capture/action screen without finishing
  it — **EASY** (back exits immediately, for when this is just a reminder),
  **STANDARD** (back asks you to confirm skipping first), or **FIRM** (back
  does nothing; finish it or explicitly abandon it). This is deliberately a
  dial, not a fixed behavior — steering your own follow-through is the
  point, not the app deciding for you.
- **A sticky status notification.** Once you open the app to respond to an
  alert, an ongoing notification tracks both *what alert you're answering*
  and *what you were doing when it interrupted you* — updated as you fill
  those in, and surviving the app being killed (tapping it resumes you
  exactly where you left off, photo included). It only clears when the
  protocol is actually completed; escaping or backing out never dismisses
  it, so you can't lose track of an interrupted task just by getting
  distracted mid-flow.
- **Phone/watch reconciliation.** Because either device can complete a
  protocol, the audit trail, the sticky notification, and an in-progress
  phone screen all reconcile against whichever device you actually used —
  a watch tap while the phone is mid-capture fast-forwards the phone screen
  instead of leaving a stale "confirm" button up, and a watch action taken
  while briefly disconnected is queued and retried rather than silently
  dropped.

## Pain / symptom logging

A separate flow (deck index 4 on the watch): log a 0–10 pain level with a
free-text "location / type / triggers" description (camera capture is
available here too). Logging above level 3 automatically schedules a
60-minute follow-up check-in unless it falls inside your configured sleep
window — which can itself be overridden with a "clinical override" setting
for round-the-clock monitoring.

## Compliance auditing + PDF export

Every alert fired is written to a `notification_audit` row (issued →
interacted → fulfilled timestamps, interaction type, and which device
responded). Each one is graded Success/Warning/Failure and rolled into a
per-protocol A–F compliance score, viewable in-app or exported as a
multi-page PDF ("Behavioral Audit" or a separate light-mode "Clinical
Report") — 24h/7d/30d alert volume and slippage-rate summaries, per-protocol
average response latency, a worst-hour-for-abandoned-alerts callout, and a
response-latency histogram — handed off to the OS share sheet.

## Background behavior

- **Phone:** a 15-minute `HeartbeatWorker` keeps HP flowing to the watch
  even while the app isn't open; a 5-second foreground UI tick while it is.
- **Watch:** a 15-minute `SentinelWorker` independently re-evaluates all
  four protocols, fires local notifications for whichever is most urgent
  per protocol, and flags an un-interacted-with notification "IGNORED"
  after 5 minutes.
- A broadcast contract to a sibling app (`com.snakesan.overseer`) shares
  live HP/status updates and accepts a kill-switch broadcast that cancels
  all background work — Vitality is one module in a larger personal
  "Overseer" system. See [OVERSEER](https://aspdesignlabs.github.io/OVERSEER).

## Privacy / offline posture

- No `INTERNET` permission in either manifest, no networking code anywhere
  in either module. The only inter-device channel is the Wear Data Layer
  over Bluetooth; Health Connect writes stay on-device.
- Local storage only: a Room database (`vitality_blackbox.db`) on the
  phone, `SharedPreferences` for config on both. No cloud sync, no
  accounts, no analytics SDKs in the dependency graph.
- A debug/admin panel (quick-purge, factory reset, fuzzy-data injection,
  test alerts) is gated two layers deep — absent entirely outside a debug
  build, and off by default even then until a long-press toggle flips it on.

## Building from source

Requires JDK 17+ and the Android SDK (`compileSdk 36` / `minSdk 31` for
`VITALITYMOBILE`, `compileSdk 34` / `minSdk 30` for `VITALITYDECK`).

Both modules sign debug and release builds with a shared
`debug.keystore` at the repo root, which is gitignored — generate your own
before building:

```sh
keytool -genkey -v -keystore debug.keystore -storetype PKCS12 \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass android -keypass android \
  -dname "CN=Android Debug,O=Android,C=US"
```

Then:

```sh
./gradlew :VITALITYMOBILE:assembleDebug
./gradlew :VITALITYDECK:assembleDebug
```

Install both to a paired phone + Wear OS watch (or emulators bridged as a
companion pair) — the two apps are useless independently since all
protocol config lives on the phone and syncs to the watch over Bluetooth.

## License

[GPL-3.0](LICENSE).
