import react from '@vitejs/plugin-react';
import type { Plugin } from 'vite';
import { defineConfig } from 'vitest/config';

/**
 * `@monaco-editor/loader` ships a default config that points at a CDN copy of Monaco. The app
 * never uses it (src/code/monacoSetup.ts passes the bundled instance to `loader.config`), but the
 * URL would still end up in the build, and the build must not reference a CDN (T28 AC4, ADR-030).
 * This swaps that one module for an empty config, and fails the build if the module is not found,
 * so a loader upgrade that moves the file cannot bring the URL back unnoticed.
 */
function monacoLoaderWithoutCdn(): Plugin {
  const target = /[\\/]@monaco-editor[\\/]loader[\\/]lib[\\/]es[\\/]config[\\/]index\.js$/;
  let replaced = false;
  return {
    name: 'log2code:monaco-loader-without-cdn',
    apply: 'build',
    enforce: 'pre',
    load(id) {
      if (!target.test(id.split('?')[0])) return null;
      replaced = true;
      return 'export default { paths: { vs: "" } };';
    },
    buildEnd(error) {
      if (!error && !replaced) {
        this.error('@monaco-editor/loader config module not found; update monacoLoaderWithoutCdn');
      }
    },
  };
}

// The API (log2code-api, T23) runs on :8090; in dev every /api call is proxied there so the web
// app uses the same relative base URL ("/api") in dev and in the packaged build (T32).
export default defineConfig({
  plugins: [react(), monacoLoaderWithoutCdn()],
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
