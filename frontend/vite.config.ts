import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// D3: the app calls only relative /api URLs; in development they go to the gateway.
const gateway = 'http://localhost:8080';

export default defineConfig({
    plugins: [react(), tailwindcss()],
    server: {
        proxy: {
            '/api': {target: gateway, changeOrigin: false},
        },
    },
    preview: {
        proxy: {
            '/api': {target: gateway, changeOrigin: false},
        },
    },
});
