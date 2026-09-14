# Landing page assets

Drop real screenshots and GIFs of VITALITY.SYS here — for `index.html` at
the repo root.

- `screenshots/` — static screenshots. Phone shots should be phone
  aspect ratio (roughly 9:19.5), watch shots should be roughly square, to
  match the lightbox's frame.
- `gifs/` — short recordings (e.g. the interrupt-capture flow, the watch
  deck's rotary navigation).
- `social/` — the Open Graph / X card (`og-card.png`) and its editable
  source (`og-card.source.html`). To regenerate after an edit: open the
  source file, screenshot the `#card` element at 1200x630 (2x/retina and
  downscale to exactly 1200x630 for crisp text — matches the
  `og:image:width`/`og:image:height` tags in `index.html`), overwrite
  `og-card.png`.
- `icons/` — favicon/apple-touch-icon set, downscaled from the real
  `ic_launcher-playstore.png` brand mark (identical in both Android
  modules) rather than a separate invented icon. `icon-512.png` (512x512),
  `apple-touch-icon.png` (180x180), `favicon-32.png`, `favicon-16.png`.
  Regenerate the same way if the launcher icon ever changes.

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
| Social/OG card | `social/og-card.png` + `social/og-card.source.html` | done |
