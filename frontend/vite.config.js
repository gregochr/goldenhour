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
      // No web-app manifest: the existing icons aren't the sizes an installable PWA needs, and
      // this change is about load speed, not installability. Add one with proper 192/512 icons
      // if "Add to Home Screen" is ever wanted.
      manifest: false,
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
