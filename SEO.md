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

## Still needs the project owner

These require an account this session doesn't have access to:

1. **Google Search Console** — verify the site (either drop their
   provided `<meta name="google-site-verification">` tag into
   `index.html`'s `<head>`, or add a DNS TXT record), then submit
   `sitemap.xml` from within Search Console.
2. **Bing Webmaster Tools** — same idea; Bing Webmaster can also import
   verification straight from an existing Search Console property.
