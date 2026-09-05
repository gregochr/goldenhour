import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

// `loadEnv`, not bare `process.env`. Vite's .env files populate `import.meta.env` for CLIENT
// code; this config runs in Node, where `process.env` holds only what the shell exported. So a
// VITE_API_TARGET written into frontend/.env.local — the mechanism the CHANGELOG documents for
// pointing local dev at the backend — silently did nothing, the proxy fell back to the Docker
// port 8082 while application-local.yml listens on 8083, and every API call 502'd at the login
// screen. That is what "no local browser path past the login page" was.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const apiTarget = env.VITE_API_TARGET || 'http://localhost:8082';
  return {
  plugins: [
    react(),
    // Service worker that precaches the app shell so a refresh paints from disk instead of
    // waiting on the network. Deliberately scoped:
    //  - PRECACHE only the critical shell (html, css, fonts, entry + react chunks). The heavy
    //    optional chunks (recharts ~360K, ManageView ~251K, leaflet ~165K) are lazy-loaded by
    //    design, so precaching them would download ~800K on every deploy for a user who may
    //    never open the Map or Manage views. They are runtime-cached on first actual use.
    //  - NEVER cache /api. The stale-while-revalidate cache and ETags (#22/#23) already own
    //    that, and a cached API response would be a stale-data and cross-account hazard. With
    //    no matching handler, those requests (including the SSE streams, which must never be
    //    buffered) pass straight through to the network.
    //  - autoUpdate so a deploy can't strand a user on a stale shell.
    VitePWA({
      registerType: 'autoUpdate',
      // Web-app manifest so the app can be installed / added to a Home Screen. Icons are the
      // standard 192 and 512 squares generated from the logo; theme/background match the dark UI
      // (--color-plex-bg) so the splash and status bar don't flash white on launch. Both icons are
      // purpose "any" — a "maskable" variant would need safe-zone padding the current logo doesn't
      // have, and a wrongly-cropped adaptive icon is worse than none.
      // No `includeAssets`: favicon/apple-touch-icon are served on demand rather than precached.
      // The two manifest icons (~94 KB) ARE precached — the plugin injects manifest assets into the
      // precache and neither globIgnores nor omitting includeAssets overrides that. Accepted as the
      // price of installability: it's a one-off per deploy, and an installable PWA wants its icons.
      manifest: {
        name: 'PhotoCast — Golden hour, forecast and ranked by AI',
        short_name: 'PhotoCast',
        description: 'Sunrise, sunset, and aurora photography forecasts for your locations.',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        background_color: '#181210',
        theme_color: '#181210',
        icons: [
          { src: 'pwa-192x192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
          { src: 'pwa-512x512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
        ],
      },
      workbox: {
        globPatterns: [
          '**/*.{css,html,woff2}',
          'assets/index-*.js',
          'assets/react-*.js',
          'assets/rolldown-runtime-*.js',
        ],
        navigateFallback: '/index.html',
        // API and actuator requests must never be answered with the SPA shell.
        navigateFallbackDenylist: [/^\/api/, /^\/actuator/],
        cleanupOutdatedCaches: true,
        runtimeCaching: [
          {
            // Lazy route/vendor chunks and any other build asset. These filenames are
            // content-hashed, so a cache hit is always the right bytes — CacheFirst is safe and
            // makes the second visit to the Map/Manage views instant.
            urlPattern: ({ url, sameOrigin }) => sameOrigin && url.pathname.startsWith('/assets/'),
            handler: 'CacheFirst',
            options: {
              cacheName: 'photocast-assets',
              expiration: { maxEntries: 80, maxAgeSeconds: 60 * 60 * 24 * 30 },
            },
          },
        ],
      },
    }),
  ],
  build: {
    rollupOptions: {
      output: {
        // Split stable vendors into their own cacheable chunks so (a) they download in
        // parallel with app code and (b) an app-only deploy doesn't invalidate them.
        // Combined with the React.lazy boundaries in App.jsx, the leaflet + recharts chunks
        // are only fetched when the Map / Manage views actually open.
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined;
          if (id.includes('leaflet')) return 'leaflet'; // leaflet + react-leaflet
          // BEFORE the `d3-` catch below, and that order is the whole rule — appended after it this
          // never fires at all (measured at P0, which is why the plan calls it out).
          //
          // `d3-geo` is imported by the heat field kernel, whose only consumer is the Plan tab's
          // heat strip. Without this rule it falls into `recharts`, and `recharts` is reached today
          // only behind `ManageView`'s lazy() boundary — ADMIN-only. Measured both ways: with the
          // rule the strip's chunk statically imports `geo` at 24.14 KB / 9.19 KB gzip; without it,
          // it imports `recharts` at 396.61 KB / 115.40 KB gzip. So every reader opening Plan
          // would fetch the whole charting library for ~20 KB of projection code.
          //
          // `d3-array` rides along because `d3-geo` depends on it. Splitting them would put `geo`
          // downstream of `recharts` and reintroduce the same fetch by a longer route. The cost is
          // that recharts' own d3-array usage lands here too — measured at 3.43 KB raw / 1.33 KB
          // gzip off the recharts chunk, paid only by whoever loads either.
          if (id.includes('d3-geo') || id.includes('d3-array')) return 'geo';
          if (id.includes('recharts') || id.includes('d3-') || id.includes('victory-vendor')) return 'recharts';
          if (id.includes('react-dom') || id.includes('/react/') || id.includes('scheduler')) return 'react';
          return undefined;
        },
      },
    },
  },
  server: {
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
      '/actuator': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.js'],
    css: false,
    globals: true,
    exclude: ['**/node_modules/**', '**/e2e/**'],
    // ⚠️ Raised from Vitest's 5000 ms default because the suite's OWN async ceiling is 4000 ms, and
    // the two were incoherent. `setup.js` sets `asyncUtilTimeout: 4000` so a cold `React.lazy`
    // boundary has room; a Plan-shell test crosses two of them in sequence, which the default could
    // never hold — so the test died at 5000 ms before either `findBy*` reached its own ceiling or
    // could name the wait that was stuck. What that looked like: `Test timed out in 5000ms`,
    // pointing at the `it(` line and nothing else.
    //
    // Reproduced by running the full suite three times concurrently under a 16-process CPU load:
    // 3 of 3 runs failed, on the FIRST test of three different shell files, while those files
    // passed alone in six seconds. `src/test/warmPlanChunks.js` removes the largest part of that
    // cost — see its note for the measurements — and this covers what is left, which is
    // `React.lazy`'s own payload resolution: it happens on first RENDER, so no amount of module
    // warming can pay it ahead of time, and it lands in whichever test runs first.
    //
    // It is a CEILING, not a delay: nothing waits longer than it needs to, and a test that hangs
    // still fails — it now takes 20 s to say so rather than 5. That is the whole cost.
    testTimeout: 20000,
  },
  };
});
