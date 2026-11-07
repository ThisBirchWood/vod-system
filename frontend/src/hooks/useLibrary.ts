import useCachedResource from "./useCachedResource.ts";
import { getClips } from "../utils/api/clips.ts";
import { getVods } from "../utils/api/vods.ts";
import { clearCache, mutate, revalidate } from "../utils/cache.ts";
import { resetWarmedThumbnails, thumbnailUrl, warmThumbnails } from "../utils/thumbnails.ts";
import { stringToDate } from "../utils/utils.ts";
import type { Clip, Vod } from "../utils/types.ts";

/** How many of the newest thumbnails of each kind to warm after login: roughly the first screen of a grid. */
const WARM_THUMBNAIL_COUNT = 20;

const CLIPS_KEY = "clips";
const VODS_KEY = "vods";

const byNewest = <T extends { createdAt: string }>(items: T[]) =>
    [...items].sort((a, b) => stringToDate(b.createdAt).getTime() - stringToDate(a.createdAt).getTime());

const fetchClips = async () => byNewest(await getClips());
const fetchVods = async () => byNewest(await getVods());

/** The current user's clips, newest first, served from cache and refreshed in the background. */
const useClips = () => useCachedResource<Clip[]>(CLIPS_KEY, fetchClips);

/** The current user's VoDs, newest first, served from cache and refreshed in the background. */
const useVods = () => useCachedResource<Vod[]>(VODS_KEY, fetchVods);

/**
 * Loads both libraries and warms their newest thumbnails in the background,
 * so the first visit to My Clips / My VoDs is as quick as later ones.
 */
function prefetchLibrary() {
    revalidate(CLIPS_KEY, fetchClips)
        .then((clips) => warmThumbnails(clips.slice(0, WARM_THUMBNAIL_COUNT).map((c) => thumbnailUrl("/api/v1/clips", c.id))))
        .catch(() => {});
    revalidate(VODS_KEY, fetchVods)
        .then((vods) => warmThumbnails(vods.slice(0, WARM_THUMBNAIL_COUNT).map((v) => thumbnailUrl("/api/v1/vods", v.id))))
        .catch(() => {});
}

const replaceById = <T extends { id: number }>(updated: T) => (items: T[]) =>
    items.map((item) => item.id === updated.id ? updated : item);

/** Writes an edited clip into the cache so the grid shows the change without waiting for a refetch. */
const updateCachedClip = (clip: Clip) => mutate<Clip[]>(CLIPS_KEY, replaceById(clip));

/** Writes an edited VoD into the cache so the grid shows the change without waiting for a refetch. */
const updateCachedVod = (vod: Vod) => mutate<Vod[]>(VODS_KEY, replaceById(vod));

/** Forgets everything cached for the current user. */
function clearLibrary() {
    clearCache();
    resetWarmedThumbnails();
}

export { useClips, useVods, prefetchLibrary, clearLibrary, updateCachedClip, updateCachedVod };
