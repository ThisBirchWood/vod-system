import { useContext } from "react";
import { ThemeContext } from "./ThemeContext.ts";
import type { ThemeContextValue } from "./ThemeContext.ts";

/** Access the centralised theme state. Must be used within a <ThemeProvider>. */
const useTheme = (): ThemeContextValue => {
    const context = useContext(ThemeContext);
    if (!context) {
        throw new Error("useTheme must be used within a ThemeProvider");
    }
    return context;
};

export { useTheme };
