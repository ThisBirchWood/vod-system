import { createContext } from "react";
import type { User } from "../utils/types.ts";

type AuthContextValue = {
    /** The currently authenticated user, or null when logged out. */
    user: User | null;
    /** True until the initial "who am I" request has resolved. */
    loading: boolean;
    /** Exchange a Google credential for a session, then load the user. */
    login: (googleCredential: string) => Promise<void>;
    /** End the session and clear the user. */
    logout: () => Promise<void>;
    /** Re-fetch the current user (e.g. after a profile change). */
    refresh: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export { AuthContext };
export type { AuthContextValue };
