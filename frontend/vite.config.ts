import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '..', 'OCC_FRONTEND_')
  const target = process.env.OCC_FRONTEND_EDGE_PUBLIC_URL ?? env.OCC_FRONTEND_EDGE_PUBLIC_URL ?? 'http://localhost:80'
  const port = Number(process.env.OCC_FRONTEND_DEV_PORT ?? env.OCC_FRONTEND_DEV_PORT ?? 5173)
  return {
    plugins: [react(), tailwindcss()],
    base: '/',
    build: {
      rolldownOptions: { input: {
        app: fileURLToPath(new URL('./index.html', import.meta.url)),
        notFound: fileURLToPath(new URL('./404.html', import.meta.url)),
      } },
    },
    resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
    server: {
      host: '127.0.0.1', port, strictPort: true,
      proxy: target ? { '/frontend-config': { target }, '/api/v1': { target } } : undefined,
    },
  }
})
