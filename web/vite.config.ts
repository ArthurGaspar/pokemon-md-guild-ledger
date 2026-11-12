import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";

// Default mode runs everything in the browser (GitHub Pages). `--mode api` talks to the Spring API instead;
// see .env.api.
export default defineConfig({
  base: "./",
  plugins: [react()],
  resolve: {
    alias: { "@shared": fileURLToPath(new URL("../shared", import.meta.url)) },
  },
  server: { fs: { allow: [".."] } },
});
