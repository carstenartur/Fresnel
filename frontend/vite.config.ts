import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Vite dev-server configuration. The backend is expected to run on :8080;
// during development we proxy /api so the frontend can issue same-origin requests.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
  },
});
