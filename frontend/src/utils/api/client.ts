import {config} from "../../config.ts";
import type { APIResponse } from "../types.ts";

const API_URL = config.apiUrl;

class AuthError extends Error {
    constructor() { super("Not authenticated"); this.name = "AuthError"; }
}

let onUnauthorized: (() => void) | null = null;
let onSessionRefreshed: ((accessTokenExpiresIn: number) => void) | null = null;
let refreshInFlight: Promise<boolean> | null = null;

export function registerUnauthorizedHandler(handler: () => void) {
    onUnauthorized = handler;
}

/** Called with the new access token lifetime (in seconds) whenever the session is refreshed. */
export function registerSessionRefreshedHandler(handler: (accessTokenExpiresIn: number) => void) {
    onSessionRefreshed = handler;
}

/**
 * Exchanges the refresh token cookie for new access and refresh token cookies.
 * Concurrent callers share a single request, so a burst of 401s only rotates
 * the refresh token once.
 *
 * @returns true if the session was refreshed, false if it has ended (no valid
 * refresh token). Throws on network errors so a dropped connection isn't
 * mistaken for being logged out.
 */
function refreshSession(): Promise<boolean> {
    refreshInFlight ??= requestRefresh().finally(() => { refreshInFlight = null; });
    return refreshInFlight;
}

async function requestRefresh(): Promise<boolean> {
    const response = await fetch(API_URL + '/api/v1/users/refresh', {
        method: 'POST',
        credentials: 'include',
    });

    if (!response.ok) return false;

    const result: APIResponse = await response.json();
    onSessionRefreshed?.(result.data.accessTokenExpiresIn);
    return true;
}

/**
 * Performs a request against the API, sending session cookies. An expired
 * access token is refreshed and the request retried once, so callers only see
 * an AuthError when the session has really ended — at which point the
 * registered unauthorized handler drops the UI back to a logged out state.
 * Returns the raw response so callers that need something other than JSON
 * (blobs, streams) can read it themselves.
 */
async function apiFetchRaw(url: string, options: RequestInit = {}): Promise<Response> {
    const send = () => fetch(url, { ...options, credentials: 'include' });

    let response = await send();

    if (response.status == 401 && await refreshSession()) {
        response = await send();
    }

    if (response.status == 401) {
        onUnauthorized?.();
        throw new AuthError();
    }

    // Logged in, but not allowed to access this resource: don't end the session.
    if (response.status == 403) {
        throw new AuthError();
    }

    if (!response.ok) {
        const text = await response.text();
        let message = text;
        try { message = JSON.parse(text).message ?? text; } catch { /* not JSON, use raw text */ }
        throw new Error(message || `Request failed: ${response.status}`);
    }

    return response;
}

async function apiFetch<T>(url: string, options: RequestInit = {}): Promise<T> {
    const response = await apiFetchRaw(url, options);
    const text = await response.text();
    return text ? JSON.parse(text) : (undefined as T);
}

export { API_URL, AuthError, apiFetch, apiFetchRaw, refreshSession };
