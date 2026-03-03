import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
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
