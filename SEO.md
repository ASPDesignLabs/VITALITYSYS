# SEO / indexing setup

What's wired up for `index.html` (a single-page site), and what's left for
the project owner to do by hand because it needs an account this session
doesn't have.

## Done

- `robots.txt`, `sitemap.xml` at the repo root (served at the site root by
  GitHub Pages) — allow-all, one URL, since it's a one-page site.
- `<link rel="canonical">`, `<meta name="theme-color">`, favicon/
  apple-touch-icon set (`assets/icons/`, see `assets/README.md`).
- Open Graph / Twitter card, backed by a real image (`assets/social/`)
  instead of the missing pending asset.
- `SoftwareApplication` JSON-LD structured data in `index.html`'s `<head>`
  (name, description, license, offer, download/repo URLs) — every field is
  something already stated elsewhere on the page, nothing invented for
  SEO's sake.
- An [IndexNow](https://www.indexnow.org/) key file at the repo root
  (`<32-hex-char-key>.txt`, containing just that same key). IndexNow is
  Bing/Yandex's no-account-needed "I changed this page" ping — Google
  doesn't participate, that still goes through Search Console below.
  To use it after a content change goes live, once the branch is merged
  and Pages has redeployed:
  ```
  curl "https://api.indexnow.org/indexnow?url=https://aspdesignlabs.github.io/VITALITYSYS/&key=<the-key>"
  ```
- **Google Search Console** and **Bing Webmaster Tools** verification
  meta tags (`google-site-verification`, `msvalidate.01`) are in
  `index.html`'s `<head>`, from the project owner's own accounts. Still
  worth doing from inside each dashboard once this deploys: submit
  `sitemap.xml` (both), and Bing Webmaster can import verification
  straight from an existing Search Console property if that's easier.
- X/Twitter card hardening: the OG image URL now carries a `?v=2`
  cache-buster (X caches a card by the exact image URL, sometimes
  indefinitely after a first failed fetch — bump this number any time
  `og-card.png`/`.jpg` actually changes), added `og:image:type` +
  `og:image:secure_url`, and X specifically gets a flattened
  `og-card.jpg` (no alpha channel) via `twitter:image` instead of the
  PNG, since X has a documented history of being flakier with
  alpha-channel PNGs even when they're fully opaque. `twitter:site` /
  `twitter:creator` are set to `@Snakesan`.

## Still needs the project owner

1. **Force a re-crawl on platforms that cached a stale/broken card
   before `og-card.png` existed** — Facebook's Sharing Debugger and
   LinkedIn's Post Inspector both need to be run interactively (they're
   the closest thing either platform has to X's now-retired Card
   Validator). X itself has no equivalent button anymore; the `?v=2`
   cache-buster above plus sharing a fresh URL is the workaround.
2. **No `robots.txt`/`sitemap.xml` at the true host root.** Crawlers
   that check `https://aspdesignlabs.github.io/robots.txt` (the actual
   spec-defined location) get GitHub's "there isn't a Pages site here"
   404 — this project's own `robots.txt`/`sitemap.xml` only exist at
   `.../VITALITYSYS/...`, one path level down, which most crawlers never
   look at. A 404 is usually read as "allow everything" so it's not
   necessarily blocking anything today, but there's no sitemap being
   found at the host root either way. Fixing it for real means an
   `ASPDesignLabs.github.io` user/org Pages repo — a separate site, and
   a bigger structural decision than this repo's own landing page, so
   left for the project owner to decide rather than done unasked.
