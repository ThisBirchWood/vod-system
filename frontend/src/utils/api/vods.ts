import type { APIResponse, Vod } from "../types.ts";
import { API_URL, AuthError } from "./client.ts";

const getVods = async (): Promise<Vod[]> => {
    const response = await fetch(API_URL + '/api/v1/vods', { credentials: 'include' });

    if (!response.ok) {
        const errorResult: APIResponse = await response.json();
        throw new Error(`Failed to fetch vods: ${errorResult.message}`);
    }

    const result: APIResponse = await response.json();
    return result.data;
};

const getVodById = async (id: string): Promise<Vod | null> => {
    const response = await fetch(API_URL + `/api/v1/vods/${id}`, { credentials: 'include' });

    if (!response.ok) {
        if (response.status === 401 || response.status === 403) throw new AuthError();
        throw new Error(`Failed to fetch vod: ${response.status}`);
    }

    const result: APIResponse = await response.json();
    return result.data;
};

// URL for the raw media stream. The <video> element loads this directly so the
// browser can issue HTTP Range requests (progressive loading + seeking) instead
// of downloading the whole file up front.
const getVodMediaUrl = (id: string): string => API_URL + `/api/v1/vods/${id}/media`;

const patchVod = async (id: number, data: { title?: string; description?: string }): Promise<Vod> => {
    const response = await fetch(API_URL + `/api/v1/vods/${id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(data),
    });

    if (!response.ok) throw new Error(`Failed to update vod: ${response.status}`);

    const result: APIResponse = await response.json();
    if (result.status === 'error') throw new Error(`Failed to update vod: ${result.message}`);

    return result.data;
};

const deleteVod = async (id: number): Promise<void> => {
    const response = await fetch(API_URL + `/api/v1/vods/${id}`, {
        method: 'DELETE',
        credentials: 'include',
    });

    if (!response.ok) throw new Error(`Failed to delete vod: ${response.status}`);

    const result: APIResponse = await response.json();
    if (result.status === 'error') throw new Error(`Failed to delete vod: ${result.message}`);
};

export { getVods, getVodById, getVodMediaUrl, patchVod, deleteVod };
