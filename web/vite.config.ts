import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  // GitHub Pages serves the site under /<repo>/ (set by the deploy workflow); Vercel/local use /.
  base: process.env.VITE_BASE ?? "/",
  plugins: [react()],
  define: { "process.env.IS_PREACT": JSON.stringify("false") },
  server: { fs: { allow: [".."] } },
  build: { chunkSizeWarningLimit: 6000 },
});
