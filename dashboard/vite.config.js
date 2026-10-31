import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// In development the API runs on :8080 and Vite proxies /api to it, so the session cookie stays
// same-origin exactly as in production, where the backend serves this build itself.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': { target: process.env.RESQMESH_API ?? 'http://localhost:8080', changeOrigin: true } },
  },
});
