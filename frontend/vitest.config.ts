import { defineConfig } from 'vitest/config'
import path from 'path'

export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify('test-version'),
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: [],
    // Unit tests live under src/. The e2e/ specs are Playwright tests (run via
    // `bun run test:e2e`) and share the .spec.ts extension, so scope vitest to
    // src/ to keep the two runners from picking up each other's files.
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    // Vitest blanks CSS modules by default; theme-contrast.test.ts reads the theme tokens raw.
    css: { include: [/\/src\/index\.css/] },
  },
})
