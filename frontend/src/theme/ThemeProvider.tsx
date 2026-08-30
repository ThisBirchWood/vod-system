import { useCallback, useEffect, useState } from "react";
import type { ReactNode } from "react";
import { ThemeContext } from "./ThemeContext.ts";
import type { ResolvedTheme, ThemePreference } from "./ThemeContext.ts";

/** Shared with the pre-paint script in index.html — keep the two in step. */
const STORAGE_KEY = "theme";
const DARK_QUERY = "(prefers-color-scheme: dark)";

const readPreference = (): ThemePreference => {
    try {
        const stored = localStorage.getItem(STORAGE_KEY);
        if (stored === "light" || stored === "dark" || stored === "system") {
            return stored;
        }
    } catch {
        // Storage blocked (private browsing, cookies disabled) — follow the OS.
    }
    return "system";
};

const readSystemTheme = (): ResolvedTheme =>
    window.matchMedia(DARK_QUERY).matches ? "dark" : "light";

/**
 * Owns the single source of truth for the active theme. Nothing reads colours
 * from here: the palette lives in index.css and switches on the `data-theme`
 * attribute this provider writes, so components keep using the semantic
 * utilities (`bg-card`, `text-text-primary`) and follow along for free.
 */
const ThemeProvider = ({ children }: { children: ReactNode }) => {
    const [preference, setPreferenceState] = useState<ThemePreference>(readPreference);
    const [systemTheme, setSystemTheme] = useState<ResolvedTheme>(readSystemTheme);

    // Keep following the OS while the preference is "system".
    useEffect(() => {
        const query = window.matchMedia(DARK_QUERY);
        const onChange = (event: MediaQueryListEvent) =>
            setSystemTheme(event.matches ? "dark" : "light");

        query.addEventListener("change", onChange);
        return () => query.removeEventListener("change", onChange);
    }, []);

    const theme: ResolvedTheme = preference === "system" ? systemTheme : preference;

    useEffect(() => {
        document.documentElement.dataset.theme = theme;
    }, [theme]);

    const setPreference = useCallback((next: ThemePreference) => {
        setPreferenceState(next);
        try {
            localStorage.setItem(STORAGE_KEY, next);
        } catch {
            // Storage blocked — the choice still applies for this session.
        }
    }, []);

    const toggleTheme = useCallback(() => {
        setPreference(theme === "dark" ? "light" : "dark");
    }, [theme, setPreference]);

    return (
        <ThemeContext.Provider value={{ preference, theme, setPreference, toggleTheme }}>
            {children}
        </ThemeContext.Provider>
    );
};

export default ThemeProvider;
