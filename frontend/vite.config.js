import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
  plugins: [
    react(),
    // Service worker that precaches the app shell so a refresh paints from disk instead of
    // waiting on the network. Deliberately scoped:
    //  - PRECACHE only the critical shell (html, css, fonts, entry + react chunks). The heavy
    //    optional chunks (recharts ~360K, ManageView ~251K, leaflet ~196K) are lazy-loaded by
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
        name: 'PhotoCast — AI sunrise, sunset, and aurora forecasting',
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
          if (id.includes('leaflet')) return 'leaflet'; // leaflet, react-leaflet(-cluster), markercluster
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
        target: process.env.VITE_API_TARGET || 'http://localhost:8082',
        changeOrigin: true,
      },
      '/actuator': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8082',
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
  },
});
