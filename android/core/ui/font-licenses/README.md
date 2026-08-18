# Bundled font licenses

The three families in `src/main/res/font/` ship under the SIL Open Font
License 1.1, whose full text must travel with them:

| License | Covers |
|---|---|
| `OFL-BarlowCondensed.txt` | `barlow_condensed_semibold.ttf` — Logbook's `display` and `section` roles |
| `OFL-Inter.txt` | `inter_variable.ttf`, `inter_italic_variable.ttf` — the `name` and `body` roles |
| `OFL-IBMPlexMono.txt` | `ibm_plex_mono_{regular,medium,italic}.ttf` — every numeral (`data`, `meta`, `eyebrow`, `tableHeader`) |

They live here rather than in `res/font/` because that directory may contain
only font resources — aapt2 fails the build on anything else in it.
