// Self-hosted web fonts — replaces the render-blocking Google Fonts <link> that used to sit in
// index.html. Bundled and served same-origin (hashed, immutable-cached) so the load path no longer
// depends on a third-party stylesheet round-trip. Latin subset only; the @font-face family names
// ('IBM Plex Sans', 'IBM Plex Mono', 'Newsreader') match the --font-* tokens in index.css @theme, so
// no CSS change is needed. Weights mirror the old Google Fonts request exactly.
import '@fontsource/ibm-plex-sans/latin-400.css';
import '@fontsource/ibm-plex-sans/latin-500.css';
import '@fontsource/ibm-plex-sans/latin-600.css';
import '@fontsource/ibm-plex-sans/latin-700.css';
import '@fontsource/ibm-plex-mono/latin-400.css';
import '@fontsource/ibm-plex-mono/latin-500.css';
import '@fontsource/ibm-plex-mono/latin-400-italic.css';
import '@fontsource/newsreader/latin-400.css';
import '@fontsource/newsreader/latin-500.css';
import '@fontsource/newsreader/latin-400-italic.css';
import '@fontsource/newsreader/latin-500-italic.css';
