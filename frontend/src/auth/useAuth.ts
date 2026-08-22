import { useContext } from "react";
import { AuthContext } from "./AuthContext.ts";
import type { AuthContextValue } from "./AuthContext.ts";

/** Access the centralised auth state. Must be used within an <AuthProvider>. */
const useAuth = (): AuthContextValue => {
    const context = useContext(AuthContext);
    if (!context) {
        throw new Error("useAuth must be used within an AuthProvider");
    }
    return context;
};

export { useAuth };
