import { useCallback, useEffect, useSyncExternalStore } from "react";
import { getEntry, mutate, revalidate, subscribe } from "../utils/cache.ts";

/**
 * Reads `key` from the in-memory cache and refreshes it in the background on
 * mount. Cached data from an earlier visit is returned immediately, so the
 * page renders without a loading state on every visit but the first.
 *
 * `fetcher` should be a stable (module-level) function: it is only read when
 * a refresh starts, and a new identity does not trigger a refetch.
 */
function useCachedResource<T>(key: string, fetcher: () => Promise<T>) {
    const entry = useSyncExternalStore(
        useCallback((listener: () => void) => subscribe(key, listener), [key]),
        () => getEntry<T>(key),
    );

    useEffect(() => {
        // Failures are stored on the entry and surfaced through `error`.
        revalidate(key, fetcher).catch(() => {});
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [key]);

    return {
        /** Cached data, or undefined until the first request succeeds. */
        data: entry?.data,
        /** The error from the most recent failed request, cleared by the next success. */
        error: entry?.error,
        /** True only when there's nothing to show yet: no data and no error. */
        loading: entry?.data === undefined && entry?.error === undefined,
        refresh: useCallback(() => revalidate(key, fetcher).catch(() => {}), [key, fetcher]),
        mutate: useCallback((update: (data: T) => T) => mutate(key, update), [key]),
    };
}

export default useCachedResource;
