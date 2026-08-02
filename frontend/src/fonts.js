// Self-hosted web fonts — replaces the render-blocking Google Fonts <link> that used to sit in
// index.html. Bundled and served same-origin (hashed, immutable-cached) so the load path no longer
// depends on a third-party stylesheet round-trip. Latin subset only; the @font-face family names
// ('IBM Plex Sans', 'IBM Plex Mono', 'Newsreader') match the --font-* tokens in index.css @theme, so
// no CSS change is needed. Weights mirror the old Google Fonts request, plus Newsreader 600 for the
// brand-lockup wordmark — without the real 600 the browser synthesises it from 500, which smears the
// serifs at 40px.
import '@fontsource/ibm-plex-sans/latin-400.css';
import '@fontsource/ibm-plex-sans/latin-500.css';
import '@fontsource/ibm-plex-sans/latin-600.css';
import '@fontsource/ibm-plex-sans/latin-700.css';
import '@fontsource/ibm-plex-mono/latin-400.css';
import '@fontsource/ibm-plex-mono/latin-500.css';
// 600 for the window-first design's mono kickers ("◎ BEST BET", "≈ TIDE", "TONIGHT"), which are
// 10px, uppercase and letter-spaced — the weight is what separates them from the data they label.
// Without the real face the browser synthesises it, and a smeared 10px kicker is exactly the
// surface where that shows.
import '@fontsource/ibm-plex-mono/latin-600.css';
import '@fontsource/ibm-plex-mono/latin-400-italic.css';
import '@fontsource/newsreader/latin-400.css';
import '@fontsource/newsreader/latin-500.css';
import '@fontsource/newsreader/latin-600.css';
import '@fontsource/newsreader/latin-400-italic.css';
import '@fontsource/newsreader/latin-500-italic.css';
