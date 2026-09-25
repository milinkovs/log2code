import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// The API (log2code-api, T23) runs on :8090; in dev every /api call is proxied there so the web
// app uses the same relative base URL ("/api") in dev and in the packaged build (T32).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8090',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    restoreMocks: true,
    unstubGlobals: true,
    // Whole-app tests with Radix menus take ~1.5 s alone and can pass 5 s when all files run in
    // parallel on a loaded machine; the default timeout made them flaky, not the logic.
    testTimeout: 15_000,
  },
});
