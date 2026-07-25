import type { APIResponse, Clip } from "../types.ts";
import { API_URL, AuthError } from "./client.ts";

const getClips = async (): Promise<Clip[]> => {
    const response = await fetch(API_URL + '/api/v1/clips', { credentials: 'include' });

    if (!response.ok) {
        const errorResult: APIResponse = await response.json();
        throw new Error(`Failed to fetch clips: ${errorResult.message}`);
    }

    const result: APIResponse = await response.json();
    return result.data;
};

const getClipById = async (id: string): Promise<Clip | null> => {
    const response = await fetch(API_URL + `/api/v1/clips/${id}`, { credentials: 'include' });

    if (!response.ok) {
        if (response.status === 401 || response.status === 403) throw new AuthError();
        throw new Error(`Failed to fetch clip: ${response.status}`);
    }

    const result: APIResponse = await response.json();
    return result.data;
};

// URL for the raw media stream. The <video> element loads this directly so the
// browser can issue HTTP Range requests (progressive loading + seeking) instead
// of downloading the whole file up front.
const getClipMediaUrl = (id: string): string => API_URL + `/api/v1/clips/${id}/media`;

const patchClip = async (id: number, data: { title?: string; description?: string }): Promise<Clip> => {
    const response = await fetch(API_URL + `/api/v1/clips/${id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(data),
    });

    if (!response.ok) throw new Error(`Failed to update clip: ${response.status}`);

    const result: APIResponse = await response.json();
    if (result.status === 'error') throw new Error(`Failed to update clip: ${result.message}`);

    return result.data;
};

const deleteClip = async (id: number): Promise<void> => {
    const response = await fetch(API_URL + `/api/v1/clips/${id}`, {
        method: 'DELETE',
        credentials: 'include',
    });

    if (!response.ok) throw new Error(`Failed to delete clip: ${response.status}`);

    const result: APIResponse = await response.json();
    if (result.status === 'error') throw new Error(`Failed to delete clip: ${result.message}`);
};

export { getClips, getClipById, getClipMediaUrl, patchClip, deleteClip };
