/**
 * A tiny in-memory, stale-while-revalidate cache for API data.
 *
 * Pages read entries through `useCachedResource`: data cached from an earlier
 * visit renders straight away while a background request refreshes it, so
 * navigating back to a page doesn't flash an empty state. Concurrent requests
 * for the same key share one fetch. The cache lives only for the lifetime of
 * the tab and is cleared on logout so one account never sees another's data.
 */

type Entry<T> = {
    data?: T;
    error?: unknown;
    promise?: Promise<T>;
};

const entries = new Map<string, Entry<unknown>>();
const listeners = new Map<string, Set<() => void>>();

/** Bumped by `clearCache`, so requests started before a logout can't repopulate the cache. */
let generation = 0;

// Entries are replaced, never mutated, so a snapshot only changes identity when its contents change
// (which is what useSyncExternalStore needs).
function setEntry<T>(key: string, entry: Entry<T>) {
    entries.set(key, entry as Entry<unknown>);
    listeners.get(key)?.forEach((listener) => listener());
}

function getEntry<T>(key: string): Entry<T> | undefined {
    return entries.get(key) as Entry<T> | undefined;
}

function subscribe(key: string, listener: () => void): () => void {
    let set = listeners.get(key);
    if (!set) listeners.set(key, set = new Set());
    set.add(listener);

    return () => {
        set.delete(listener);
        if (set.size === 0) listeners.delete(key);
    };
}

/**
 * Fetches fresh data for `key` and stores it, keeping any cached data visible
 * while the request is in flight. Calls made while a request is already
 * running share it rather than starting another.
 */
function revalidate<T>(key: string, fetcher: () => Promise<T>): Promise<T> {
    const inFlight = getEntry<T>(key)?.promise;
    if (inFlight) return inFlight;

    const startedIn = generation;
    const promise = fetcher().then(
        (data) => {
            if (startedIn === generation) setEntry<T>(key, { data });
            return data;
        },
        (error) => {
            if (startedIn === generation) setEntry<T>(key, { data: getEntry<T>(key)?.data, error });
            throw error;
        },
    );

    setEntry<T>(key, { ...getEntry<T>(key), promise });
    return promise;
}

/**
 * Updates cached data in place, e.g. to drop an item straight after deleting it,
 * without waiting for a refetch. Does nothing if nothing is cached for `key`.
 */
function mutate<T>(key: string, update: (data: T) => T) {
    const entry = getEntry<T>(key);
    if (entry?.data === undefined) return;
    setEntry<T>(key, { ...entry, data: update(entry.data) });
}

/** Drops every cached entry, e.g. on logout. Requests already in flight are discarded when they land. */
function clearCache() {
    generation++;
    const keys = [...entries.keys()];
    entries.clear();
    keys.forEach((key) => listeners.get(key)?.forEach((listener) => listener()));
}

export { getEntry, subscribe, revalidate, mutate, clearCache };
export type { Entry };
