import { useCallback, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import type { User } from "../utils/types.ts";
import { login as apiLogin, logout as apiLogout, getUser } from "../utils/api/users.ts";
import { AuthContext } from "./AuthContext.ts";
import {
    refreshSession,
    registerSessionRefreshedHandler,
    registerUnauthorizedHandler,
} from "../utils/api/client.ts";

/** Refresh this long before the access token expires, so in-flight requests never see it lapse. */
const REFRESH_MARGIN_MS = 60_000;
/** How soon to try again when a scheduled refresh fails because the network is down. */
const RETRY_DELAY_MS = 30_000;

/**
 * Owns the single source of truth for the authenticated user. Everything that
 * needs to know who is logged in — the layout, the top bar, protected pages —
 * reads it through the `useAuth` hook rather than fetching the user itself.
 *
 * It also keeps the session alive: the access token is refreshed shortly
 * before it expires, rather than waiting for a request to fail. That matters
 * for <video> elements, which load media straight from the API and can't
 * retry on a 401 the way apiFetch does.
 */
const AuthProvider = ({ children }: { children: ReactNode }) => {
    const [user, setUser] = useState<User | null>(null);
    const [loading, setLoading] = useState(true);

    const refreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    /** When the next refresh is due (epoch ms), or null when there's no session to keep alive. */
    const refreshDueRef = useRef<number | null>(null);

    const cancelScheduledRefresh = useCallback(() => {
        if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current);
        refreshTimerRef.current = null;
        refreshDueRef.current = null;
    }, []);

    const endSession = useCallback(() => {
        cancelScheduledRefresh();
        setUser(null);
    }, [cancelScheduledRefresh]);

    const scheduleRefreshIn = useCallback((delayMs: number) => {
        if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current);
        refreshDueRef.current = Date.now() + delayMs;
        refreshTimerRef.current = setTimeout(() => {
            refreshSession()
                .then((refreshed) => { if (!refreshed) endSession(); })
                .catch(() => scheduleRefreshIn(RETRY_DELAY_MS));
        }, delayMs);
    }, [endSession]);

    // Every successful refresh — scheduled, or triggered by apiFetch on a 401 —
    // reports the new access token lifetime here, so the timer always tracks
    // the live cookie.
    const scheduleRefreshBeforeExpiry = useCallback((accessTokenExpiresIn: number) => {
        const lifetimeMs = accessTokenExpiresIn * 1000;
        scheduleRefreshIn(Math.max(lifetimeMs - REFRESH_MARGIN_MS, lifetimeMs / 2));
    }, [scheduleRefreshIn]);

    const refresh = useCallback(async () => {
        try {
            setUser(await getUser());
        } catch (error) {
            console.error("Failed to fetch user:", error);
            setUser(null);
        }
    }, []);

    useEffect(() => {
        registerUnauthorizedHandler(endSession);
        registerSessionRefreshedHandler(scheduleRefreshBeforeExpiry);
    }, [endSession, scheduleRefreshBeforeExpiry]);

    // Restore the session on page load. Refreshing first means a returning
    // user whose access token expired while they were away is logged straight
    // back in, and gives us the expiry to schedule the next refresh against.
    useEffect(() => {
        refreshSession()
            .catch((error) => console.error("Failed to refresh session:", error))
            .then(refresh)
            .finally(() => setLoading(false));
    }, [refresh]);

    // Timers are throttled in background tabs and paused while the machine
    // sleeps, so catch up as soon as the user comes back.
    useEffect(() => {
        const refreshIfDue = () => {
            if (document.visibilityState !== "visible") return;
            const due = refreshDueRef.current;
            if (due !== null && Date.now() >= due) scheduleRefreshIn(0);
        };

        document.addEventListener("visibilitychange", refreshIfDue);
        window.addEventListener("online", refreshIfDue);
        return () => {
            document.removeEventListener("visibilitychange", refreshIfDue);
            window.removeEventListener("online", refreshIfDue);
        };
    }, [scheduleRefreshIn]);

    useEffect(() => cancelScheduledRefresh, [cancelScheduledRefresh]);

    const login = useCallback(async (googleCredential: string) => {
        scheduleRefreshBeforeExpiry(await apiLogin(googleCredential));
        await refresh();
    }, [refresh, scheduleRefreshBeforeExpiry]);

    const logout = useCallback(async () => {
        // Drop the local session even if the server call fails, so the user
        // is never left looking logged in after clicking log out.
        endSession();
        await apiLogout();
    }, [endSession]);

    return (
        <AuthContext.Provider value={{ user, loading, login, logout, refresh }}>
            {children}
        </AuthContext.Provider>
    );
};

export default AuthProvider;
