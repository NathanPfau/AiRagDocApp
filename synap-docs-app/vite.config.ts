import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tsconfigPaths from "vite-tsconfig-paths"

// https://vite.dev/config/
export default defineConfig(({ command }) => ({
  // Use '/static/' for production builds (served by Ktor), '/' for dev server
  base: command === 'build' ? '/static/' : '/',
  plugins: [react(), tsconfigPaths()],
}))
