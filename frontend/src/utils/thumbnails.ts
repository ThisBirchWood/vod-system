import { config } from "../config.ts";

/** Thumbnail URL for a clip or VoD. VideoCard and the prefetcher must agree on this so warmed images are reused. */
const thumbnailUrl = (mediaApiPath: string, id: number) => `${config.apiUrl}${mediaApiPath}/${id}/thumbnail`;

const warmed = new Set<string>();

/**
 * Downloads and decodes thumbnails in the background so they're in the
 * browser's image cache before a grid that shows them is opened. Requests go
 * out at low priority so they don't hold up anything the user is waiting on.
 */
function warmThumbnails(urls: string[]) {
    for (const url of urls) {
        if (warmed.has(url)) continue;
        warmed.add(url);

        const img = new Image();
        img.decoding = "async";
        img.fetchPriority = "low";
        img.src = url;
        img.decode().catch(() => warmed.delete(url));
    }
}

/** Forgets which thumbnails were warmed, e.g. on logout. */
function resetWarmedThumbnails() {
    warmed.clear();
}

export { thumbnailUrl, warmThumbnails, resetWarmedThumbnails };
