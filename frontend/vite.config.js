import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Build output goes straight into the Spring Boot jar's static folder.
// During development (`npm run dev`), API calls are proxied to Spring Boot on :8080.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "../target/classes/static",
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: { "/api": "http://localhost:8080" },
  },
});
