# VITALITY.SYS — App Digest (for the GitHub Pages landing page)

Sourced directly from the codebase on `claude/github-pages-landing-fsa0j5`
(branched from `main`) on 2026-09-10. Every claim below is cited to the file
it came from — nothing here is inferred or assumed. This exists so the
landing page's copy can be checked against it later, per the style guide's
"read the actual codebase before writing any copy" rule.

## What it is

**VITALITY.SYS** (`android:label` in both manifests) is a two-part,
self-hosted Android system for tracking activities of daily living —
meals, hydration, medication doses, and hygiene/self-care tasks — framed
as a video-game vitals system instead of a checklist. There is no
"VitalitySys" branding in-app; the product name that actually appears to
the user is **VITALITY.SYS**, styled with the period.

- **VITALITYMOBILE** — the phone companion. Full dashboard, schedule
  editor, Health Connect bridge, pain/symptom logging, and PDF report
  export. Package: `com.snakesan.vitalitysys`.
- **VITALITYDECK** — a standalone Wear OS watch app (`uses-feature
  android.hardware.type.watch`, `wearable.standalone = true`, so it keeps
  working even if the phone is out of range). This is the primary
  interaction surface: rotary-bezel or edge-tap navigation between five
  "decks" (one per protocol, plus a pain-logging deck), each showing one
  piece of live status and one action button. Package:
  `com.snakesan.vitalitysys` (Wear module).

The two talk to each other over the **Wear Data Layer API**
(`MessageClient`/`DataClient`, paths like `/vitality_status`,
`/vitality_config`, `/sys/telemetry`, `/sys/alert_phone`) — Bluetooth only,
phone-and-watch-in-pocket, no server in the loop.
*(`VITALITYMOBILE/.../MainActivity.kt`, `VITALITYDECK/.../MainActivity.kt`)*

## The core mechanic: HP, not checkboxes

The system tracks a single **HP value (0–100)**, computed fresh every
tick by `VitalityMath.calculateSystemStatus()` from four independent
"protocols," each of which can bleed damage over time the longer it's
neglected:

| Protocol | Tracks | Damage model |
|---|---|---|
| **NUTRIENT** (cyan) | Meals eaten today vs. up to 5 user-scheduled meal times | 30 min grace, then 5 dmg + 1 per 5 min late, per missed meal |
| **CHEMISTRY** (pink) | Any number of medications, each with any number of daily doses | 30 min grace, then 10 dmg + 1 per 3 min late, per missed dose (steepest curve — "meds are critical") |
| **HYDRATION** (green) | mL logged (250 mL per log) vs. a target spread across a user-defined "active window" | 500 mL grace buffer, then 5 dmg + 1 per 100 mL behind pace |
| **MAINTENANCE** (amber) | Any number of user-defined hygiene/self-care tasks | 60 min grace, then 5 dmg + 1 per 10 min late, per missed task |
*(`VITALITYMOBILE/.../VitalityMath.kt`)*

Two mechanics layer on top of the base math:
- **Bio-Drift combo penalty** — if more than one protocol is failing at
  once, total damage increases by an extra `(failingProtocols − 1) / 2`
  multiplier, so letting things slide across categories compounds rather
  than just adding up.
- **Overcharge** — holding 100 HP continuously builds a second "shield"
  meter (0–50, capping after ~60 minutes at 100), visualized as a second
  bar under the phone's EKG monitor. It resets instantly the moment HP
  drops below 100 ("Glass Cannon" in the source comments).
*(`VITALITYMOBILE/.../VitalityMath.kt`, `MainActivity.kt` lines 267–279)*

All of this is user-configurable, not fixed: meal count/times (1–5),
any number of named medications each with any number of dose times,
any number of named hygiene tasks, hydration target and active-hours
window all live in an editable `SysConfig` that syncs phone↔watch.
*(`VITALITYMOBILE/.../SysConfig.kt`)*

## What logging an action actually does

Each protocol has one clear "I did the thing" action, on both phone and
watch:

- **NUTRIENT** → opens a macro-entry screen (calories, protein, fat,
  fiber, sugar sliders) framed as a "macronutrient ingestion scan," then
  writes the meal to **Android Health Connect** as a `NutritionRecord`
  and increments today's meal count.
- **HYDRATION** → logs a fixed 250 mL and writes a `HydrationRecord` to
  Health Connect.
- **CHEMISTRY / MAINTENANCE** → marks that specific dose/task complete
  for today (each dose and each hygiene task has its own stable key, so
  "took the 8am dose" and "took the 6pm dose" of the same medication are
  tracked separately).
*(`VITALITYMOBILE/.../NutritionOverlay.kt`, `HealthConnectManager.kt`,
`VitalityUI.kt`)*

The watch deck is deliberately **one-thing-at-a-time**: for Chemistry and
Maintenance it surfaces only the single soonest-due pending item, not a
scrollable list, "so the watch face stays a quick glance instead of
another list to page through."
*(`VITALITYDECK/.../WatchComponents.kt`, `StandardProtocolInterface`)*

## Interrupt capture (context-switch handling)

A distinct feature: when a protocol comes due and you tap into it on the
phone, the app first asks **"what were you doing?"** (free text, stored
as `userContext`) before you can mark the action done — then afterward
shows a "SYSTEM OPTIMIZED — RESUMING VECTOR: <what you typed>" screen
before returning you to the dashboard. It's explicitly designed to
capture the task you were pulled away from, and hand it back to you,
rather than just clearing a reminder.
*(`VITALITYMOBILE/.../VitalityUI.kt`, `InterruptionOverlay` /
`AppMode.INTERRUPT_CAPTURE` → `INTERRUPT_ACTION` → `INTERRUPT_RESTORE`)*

## Pain / symptom logging

A separate flow (deck index 4 on the watch, protocol id 99 on the phone):
log a 0–10 pain level with a text description of "location / type /
triggers." Logging above level 3 automatically schedules a **60-minute
follow-up check-in** ("STATUS CHECK: Update Pain Levels") unless it's
inside the user's configured sleep window (`activeStartHour`/
`activeEndHour`), and that sleep-window suppression itself can be
overridden with a "clinical override" setting for cases where round-the-
clock monitoring is wanted.
*(`VITALITYDECK/.../MainActivity.kt` `logPain`/`schedulePainCheck`,
`SentinelWorker.kt`)*

## Notification compliance auditing + PDF export

Every alert fired is written to a `notification_audit` row (issued →
interacted → fulfilled timestamps, interaction type: clicked/dismissed/
ignored). `VitalityMath` grades each one Success / Warning / Failure and
rolls them into a per-protocol A–F compliance score. The phone can
generate a **multi-page PDF** ("Behavioral Audit" or, in a separate
"clinical" light-mode theme, a "Vitality Clinical Report") covering:
raw pain/event log, 24h/7d/30d alert-volume and slippage-rate summaries,
per-protocol average response latency, a "worst hour of the day for
abandoned alerts" callout, and a response-latency histogram — then hands
it to the OS share sheet.
*(`VITALITYMOBILE/.../VitalityMath.kt` audit section, `PdfGenerator.kt`,
`data/VitalityDatabase.kt` `NotificationAudit` entity)*

## Background behavior

- Phone: a 15-minute `HeartbeatWorker` keeps HP flowing to the watch even
  if the app isn't open; a foreground 5-second UI tick while the app is
  open.
- Watch: a 15-minute `SentinelWorker` independently re-evaluates all four
  protocols, fires local notifications for whichever is most urgent per
  protocol, and pushes an `AuditCheckWorker`-style "IGNORED" grade if a
  notification sits un-interacted-with for 5+ minutes.
- A broadcast contract to a **third, sibling app** — a package literally
  named `com.snakesan.overseer` — receives live HP/hydration-status/meal-
  status/overcharge updates (`ACTION UPDATE_STATUS`) and can send the
  watch app a hard **kill-switch** broadcast
  (`com.snakesan.overseer.KILL_COMMAND`) that cancels all background work
  and force-quits it. This confirms Vitality is designed as one module in
  a larger personal "Overseer" system, not a fully standalone product —
  worth flagging to the project owner rather than assuming for the
  landing page. Do not promise "works standalone forever" without
  checking whether that's the intended framing.
*(`VITALITYMOBILE/.../HeartbeatWorker.kt`, `VITALITYDECK/.../
SentinelWorker.kt`, `MainActivity.kt` `killReceiver`)*

## Privacy / offline posture (verified, not assumed)

- **`VITALITYMOBILE/AndroidManifest.xml`** requests: `VIBRATE`,
  `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `USE_FULL_SCREEN_INTENT`,
  `POST_NOTIFICATIONS`, `health.WRITE_HYDRATION`, `health.WRITE_NUTRITION`.
  **`VITALITYDECK/AndroidManifest.xml`** requests only `VIBRATE`.
  **Neither manifest requests `INTERNET`.** There is no networking code
  anywhere in either module — the only inter-device channel is the Wear
  Data Layer (Bluetooth), and Health Connect writes stay on-device.
- The in-app Health Connect rationale screen states this in the user's
  own words: *"VITALITY.SYS operates on a strict self-sovereign data
  matrix... telemetry regarding hydration and macromolecular ingestion is
  syndicated strictly locally to your device's core repository. We do not
  broadcast your physiological status to megacorps."*
  *(`PrivacyRationaleActivity.kt`)* — this is real, shippable in-app copy
  in the app's own voice; rule 11 says don't rewrite it, but it can be
  quoted directly on the landing page as evidence of the offline claim.
- Local storage only: a Room database (`vitality_blackbox.db`) for logs/
  audits/daily stats, and `SharedPreferences` for config. No cloud sync,
  no accounts, no analytics SDKs anywhere in the dependency list
  (`build.gradle.kts` for both modules only pull AndroidX/Compose/
  Wearable/Health-Connect/Room/WorkManager — no Firebase, no crash/
  analytics reporting).
- A debug/admin panel exists (quick-purge, factory-reset, fuzzy-data-
  injection, "artificial system stress test" alerts), gated two layers
  deep: on the phone it doesn't render at all unless the APK is built as
  a debug variant (`BuildConfig.DEBUG`), and even in a debug build it
  stays off until a long-press toggle flips a `DebugFlags` SharedPreferences
  switch. Not reachable in a normal/release install.
  *(`VITALITYMOBILE/build.gradle.kts`, `debug/DebugFlags.kt`,
  `debug/DebugTools.kt`)*

## Visual identity (source of the landing page's own design tokens)

Both apps share one launcher icon: a **neon pink/red EKG heartbeat trace
over a black grid** (`ic_launcher-playstore.png`, identical in both
modules) — this is the app's actual brand mark, not a generic health
icon, and should be the landing page's hero motif rather than an invented
one.

Design tokens actually used in-app (`Theme.kt`, identical values in both
`VITALITYMOBILE` and `VITALITYDECK`):

```
--void        #050505   -- page/window background ("NeonBg"/"VitalityBg")
--graphite    #121212   -- panel background
--cyan        #00F3FF   -- NUTRIENT protocol color, primary accent
--pink        #FF0055   -- CHEMISTRY protocol color, critical/error state
--green       #00FF41   -- HYDRATION protocol color, "optimal" state
--amber       #FF9900   -- MAINTENANCE protocol color, "warning" state
```

Both apps also explicitly reuse a **sibling app's ("ACK") shape recipe** —
a `CutCornerShape` motif (`VitalityShape`: 10dp/2dp/2dp/10dp corners;
`VitalityHeroShape`: 20dp/20dp opposite corners for primary buttons) — and
its own note says this is deliberate, "so both apps' panels/cards/
buttons/dialogs read as the same design language." Typography is
monospace everywhere (`FontFamily.Monospace` swapped across the entire
Material typography scale), all display text is written in normal case
in source and rendered uppercase via `.uppercase()` calls at the
composable level (not literal caps in strings) — already compliant with
the style guide's rule 10 without any change needed.
*(`VITALITYMOBILE/.../Theme.kt`, `PhoneComponents.kt`)*

**Contrast check** (WCAG formula from the style guide) against `--void`
(`#050505`):

| Color | On `--void` | Verdict |
|---|---|---|
| `#00F3FF` cyan | 16.9:1 | passes for text |
| `#FF0055` pink | 4.9:1 | passes body text (≥4.5:1), borderline — keep off small/thin text if possible |
| `#00FF41` green | 16.4:1 | passes for text |
| `#FF9900` amber | 9.5:1 | passes for text |
| `#a8a8a8`-class gray (in-app "Color.Gray" labels) | ~9.1:1 (`Color.Gray` = `#888888`) | passes for text |

Pink is the one to watch — it clears AA body text by a comfortable margin
but isn't as generous as the others, so on the landing page treat it the
way the style guide treats a borderline brand color: fine for body copy
at normal sizes, but don't lean on it for anything already thin/small
(e.g. fine print) without re-checking that specific pairing.

## Copy voice (verified in-app strings — do not rewrite, only select from)

Actual in-app strings, useful as direct quotes or tone reference:
- "VITALITY.SYS" / "CLINICAL CONTROLLER" (dashboard header)
- "SYSTEM OPTIMAL // VITALS STABLE" / "WARNING: HOMEOSTASIS DEGRADING" /
  "CRITICAL ERROR: INTEGRITY FAILING" / "EMERGENCY: SYSTEM SHUTDOWN
  IMMINENT" / "MAX OVERCHARGE // EDGERUNNER" (HP status ladder)
- "Fuel cells empty. Intake imminent." / "Running on fumes? Eat." (meal
  nag flavor text)
- "Hydration critical. You're drifting, Samurai." (hydration nag)
- "ADMIN // DATA_GOVERNANCE_PROTOCOL" (privacy screen header)
- Pain scale labels: RESOLVED / NEGLIGIBLE / NOTICEABLE / DISTRACTING /
  IMPAIRING / SEVERE / CRITICAL

Voice is consistently cyberpunk/"command console" — terse, all-caps
(rendered, not literal), diagnostic/systems-engineering framing of bodily
needs ("protocols," "vectors," "telemetry," "drift," "compliance
grade"). This is a strong, specific identity already — the landing page
should carry it rather than softening it into generic wellness-app copy.

## Decisions from the project owner (2026-09-10)

1. **License** — GPL-3.0. A `LICENSE` file (standard GPLv3 text) now
   exists at the repo root; the landing page footer links to it.
2. **Real screenshots/GIFs** — none exist yet. Ship the placeholder
   pattern (striped "coming soon" tile in the lightbox thumbnail) now;
   wire up real captures later by dropping files into `assets/` per its
   own README.
3. **"Overseer" relationship** — mention it. Overseer is a real sibling
   project with its own GitHub Pages site at
   `https://aspdesignlabs.github.io/OVERSEER`; the landing page links to
   it rather than staying silent about the integration.
4. **Distribution** — CTA links to the GitHub repo. The project owner is
   building a set of release APKs in parallel with this page; until
   those exist as GitHub Releases, "View on GitHub" / build-from-source
   is the honest call to action.
