/// <reference types="vite/client" />

interface Window {
    _env_?: {
        VITE_API_URL?: string;
        VITE_FRONTEND_URL?: string;
        VITE_GOOGLE_CLIENT_ID?: string;
    };
}