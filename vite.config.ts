import { defineConfig } from 'vite';

export default defineConfig({
  base: './',
  root: './src',
  build: {
    outDir: '../dist',
    minify: false,
    emptyOutDir: true,
  },
});
