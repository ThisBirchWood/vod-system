export const config = {
    apiUrl: window._env_?.VITE_API_URL ?? import.meta.env.VITE_API_URL ?? '',
    frontendUrl: window._env_?.VITE_FRONTEND_URL ?? import.meta.env.VITE_FRONTEND_URL ?? '',
    googleClientId: window._env_?.VITE_GOOGLE_CLIENT_ID ?? import.meta.env.VITE_GOOGLE_CLIENT_ID ?? '',
};