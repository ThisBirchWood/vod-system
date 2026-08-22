import { useCallback, useEffect, useState } from "react";
import type { ReactNode } from "react";
import type { User } from "../utils/types.ts";
import { login as apiLogin, logout as apiLogout, getUser } from "../utils/api/users.ts";
import { AuthContext } from "./AuthContext.ts";

/**
 * Owns the single source of truth for the authenticated user. Everything that
 * needs to know who is logged in — the layout, the top bar, protected pages —
 * reads it through the `useAuth` hook rather than fetching the user itself.
 */
const AuthProvider = ({ children }: { children: ReactNode }) => {
    const [user, setUser] = useState<User | null>(null);
    const [loading, setLoading] = useState(true);

    const refresh = useCallback(async () => {
        try {
            setUser(await getUser());
        } catch (error) {
            console.error("Failed to fetch user:", error);
            setUser(null);
        }
    }, []);

    useEffect(() => {
        refresh().finally(() => setLoading(false));
    }, [refresh]);

    const login = useCallback(async (googleCredential: string) => {
        await apiLogin(googleCredential);
        await refresh();
    }, [refresh]);

    const logout = useCallback(async () => {
        await apiLogout();
        setUser(null);
    }, []);

    return (
        <AuthContext.Provider value={{ user, loading, login, logout, refresh }}>
            {children}
        </AuthContext.Provider>
    );
};

export default AuthProvider;
