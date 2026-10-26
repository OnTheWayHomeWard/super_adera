/// <reference types="vitest/config" />
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// In dev the dashboard runs on :5173 and forwards API calls to the gateway, so the browser sees one origin (no CORS).
const gateway = process.env.GATEWAY_URL ?? "http://127.0.0.1:9080";

export default defineConfig({
  base: "/backoffice/",
  plugins: [react()],
  build: { chunkSizeWarningLimit: 2000 },
  server: {
    port: 5173,
    proxy: {
      "/admin": { target: gateway, changeOrigin: true },
      "/kratos": { target: gateway, changeOrigin: true },
      "/public": { target: gateway, changeOrigin: true },
      "/backoffice/api": { target: gateway, changeOrigin: true },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test-setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
