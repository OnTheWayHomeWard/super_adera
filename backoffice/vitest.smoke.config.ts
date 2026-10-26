import { defineConfig } from "vitest/config";

// The live smoke test: runs the dashboard's real API client against the running dev stack (npm run smoke).
export default defineConfig({
  test: {
    environment: "node",
    include: ["tests/live/**/*.live.test.ts"],
    testTimeout: 180_000,
    hookTimeout: 180_000,
    fileParallelism: false,
    sequence: { concurrent: false },
  },
});
