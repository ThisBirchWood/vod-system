import type { APIResponse, User } from "../types.ts";
import { API_URL } from "./client.ts";

// These deliberately use fetch directly rather than apiFetch: they run when
// there may be no session at all, and apiFetch's refresh-and-retry on 401
// would either loop or log the user out from under the auth provider.

/**
 * Exchanges a Google credential for a session. The tokens are set as HttpOnly
 * cookies; only the access token lifetime is returned.
 *
 * @returns seconds until the access token expires
 */
const login = async (googleToken: string): Promise<number> => {
    const response = await fetch(API_URL + '/api/v1/users/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token: googleToken }),
        credentials: 'include',
    });

    if (!response.ok) throw new Error(`Login failed: ${response.status}`);

    const result: APIResponse = await response.json();
    if (result.status === 'error') throw new Error(`Login failed: ${result.message}`);

    return result.data.accessTokenExpiresIn;
};

/** Revokes the session and clears the auth cookies. */
const logout = async (): Promise<void> => {
    const response = await fetch(API_URL + '/api/v1/users/logout', {
        method: 'POST',
        credentials: 'include',
    });

    if (!response.ok) throw new Error(`Logout failed: ${response.status}`);
};

const getUser = async (): Promise<User | null> => {
    const response = await fetch(API_URL + '/api/v1/users/me', { credentials: 'include' });
    if (!response.ok) return null;

    const result: APIResponse = await response.json();
    if (result.status === 'error') return null;

    return result.data;
};

export { login, logout, getUser };
