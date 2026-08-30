import { createContext } from "react";

/** What the user chose. "system" defers to the OS setting. */
type ThemePreference = "light" | "dark" | "system";

/** The theme actually on screen, once "system" has been resolved. */
type ResolvedTheme = "light" | "dark";

type ThemeContextValue = {
    /** The stored choice, which may be "system". */
    preference: ThemePreference;
    /** The theme currently applied to the document. */
    theme: ResolvedTheme;
    /** Store a new preference and apply it immediately. */
    setPreference: (preference: ThemePreference) => void;
    /** Flip between light and dark, pinning the result as an explicit choice. */
    toggleTheme: () => void;
};

const ThemeContext = createContext<ThemeContextValue | null>(null);

export { ThemeContext };
export type { ThemeContextValue, ThemePreference, ResolvedTheme };
