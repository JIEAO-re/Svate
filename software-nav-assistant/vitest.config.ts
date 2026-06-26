import { defineConfig } from "vitest/config";
import path from "path";

export default defineConfig({
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "src"),
    },
  },
  test: {
    include: ["src/**/__tests__/**/*.test.ts"],
    // v8 coverage instrumentation slows real-crypto (JWT) auth tests enough to
    // exceed the 5s default; a generous ceiling keeps `test:coverage` reliable
    // without affecting fast plain runs (it is a max, not a delay).
    testTimeout: 30_000,
    // Report-only coverage (no enforced threshold yet): `npm run test:coverage`.
    // Focused on the server agent logic under src/lib, excluding tests and schemas.
    coverage: {
      provider: "v8",
      reporter: ["text", "html"],
      include: ["src/lib/**/*.ts"],
      exclude: ["src/**/__tests__/**", "src/lib/schemas/**"],
    },
  },
});
