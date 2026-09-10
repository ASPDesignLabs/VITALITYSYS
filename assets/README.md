# Landing page assets

Drop real screenshots and GIFs of VITALITY.SYS here — for `index.html` at
the repo root.

- `screenshots/` — static screenshots. Phone shots should be phone
  aspect ratio (roughly 9:19.5), watch shots should be roughly square, to
  match the lightbox's frame.
- `gifs/` — short recordings (e.g. the interrupt-capture flow, the watch
  deck's rotary navigation).
- `social/` — the Open Graph / X card and its editable source.

Dropping a file in here doesn't wire it up by itself — `index.html` still
needs `data-shot-src="assets/screenshots/<filename>"` set on that item's
`.shot-thumb` button.

| Slot | Expected filename | Status |
|---|---|---|
| Phone dashboard (BioFlux monitor + protocol cards) | `screenshots/dashboard.png` | placeholder |
| Interrupt capture flow | `screenshots/interrupt-capture.png` | placeholder |
| Nutrition macro scan | `screenshots/nutrition-scan.png` | placeholder |
| Watch deck — protocol view | `screenshots/watch-deck.png` | placeholder |
| Watch deck — pain logging | `screenshots/watch-pain.png` | placeholder |
| Diagnostics / PDF export panel | `screenshots/diagnostics-export.png` | placeholder |
| Sample exported PDF report | `screenshots/pdf-report.png` | placeholder |
| Watch rotary navigation (GIF) | `gifs/watch-rotary-nav.gif` | placeholder |
| Social/OG card | `social/og-card.png` + `social/og-card.source.html` | placeholder |
