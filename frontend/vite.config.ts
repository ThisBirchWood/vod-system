import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig(({ mode }) => {  // change to function form
  const env = loadEnv(mode, process.cwd(), '')  // load all env vars

  return {
    plugins: [react(), tailwindcss()],
    preview: {
      port: 5173,
      strictPort: true,
    },
    server: {
      port: 5173,
      strictPort: true,
      host: true,
      origin: env.VITE_FRONTEND_URL || "http://0.0.0.0:5173",  // use it here
      allowedHosts: true,
      proxy: {
        '/api/v1': {
          target: 'http://localhost:8080',
          changeOrigin: true,
          secure: false,
        }
      }
    }
  }
})
