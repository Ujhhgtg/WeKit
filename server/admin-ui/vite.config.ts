import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
export default defineConfig({
  plugins: [react()],
  build: { outDir: "dist", emptyOutDir: true },
  server: {
    proxy: {
      "/admin/api": { target: "http://127.0.0.1:8789", changeOrigin: true },
    },
  },
});
