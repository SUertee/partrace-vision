import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// The browser only calls same-origin /api/v1; the dev server forwards it so the
// backend needs no CORS change. PARTRACE_API_TARGET selects another backend.
const target = process.env.PARTRACE_API_TARGET ?? "http://127.0.0.1:8000";

export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    port: 5173,
    proxy: { "/api": { target, changeOrigin: false } },
  },
  preview: {
    host: true,
    proxy: { "/api": { target, changeOrigin: false } },
  },
  test: { environment: "node", include: ["src/**/*.test.ts"] },
});
