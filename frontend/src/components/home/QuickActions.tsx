import type { ElementType } from "react";
import { Link } from "react-router-dom";
import { Plus, Film, Video, Radio, ArrowRight } from "lucide-react";
import clsx from "clsx";

type Action = {
    to: string;
    icon: ElementType;
    label: string;
    hint: string;
    primary?: boolean;
};

type Props = {
    clipCount: number | null;
    vodCount: number | null;
    isStreaming: boolean;
};

const plural = (n: number, noun: string) => `${n} ${noun}${n === 1 ? "" : "s"}`;

const QuickActions = ({ clipCount, vodCount, isStreaming }: Props) => {
    const actions: Action[] = [
        { to: "/create", icon: Plus, label: "Create a clip", hint: "Upload, trim & export", primary: true },
        { to: "/my-clips", icon: Film, label: "My clips", hint: clipCount === null ? "Your library" : plural(clipCount, "clip") },
        { to: "/my-vods", icon: Video, label: "My VoDs", hint: vodCount === null ? "Stream recordings" : plural(vodCount, "recording") },
        { to: "/stream", icon: Radio, label: "Stream control", hint: isStreaming ? "You're live now" : "Markers & live clipping" },
    ];

    return (
        <nav aria-label="Quick actions" className="grid grid-cols-2 lg:grid-cols-4 gap-3">
            {actions.map(({ to, icon: Icon, label, hint, primary }) => (
                <Link
                    key={to}
                    to={to}
                    className={clsx(
                        "group flex items-center gap-3 rounded-xl p-4 shadow-sm transition-colors duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-terracotta",
                        primary
                            ? "bg-terracotta text-on-accent hover:bg-terracotta-hover"
                            : "bg-card border border-hairline hover:bg-hover"
                    )}
                >
                    <span className={clsx(
                        "w-9 h-9 rounded-lg flex items-center justify-center flex-shrink-0",
                        primary ? "bg-on-accent/15" : "bg-terracotta/10"
                    )}>
                        <Icon size={18} className={primary ? "text-on-accent" : "text-terracotta"} />
                    </span>
                    <span className="min-w-0 flex-1">
                        <span className={clsx("block text-sm font-medium truncate", primary ? "text-on-accent" : "text-text-strong")}>{label}</span>
                        <span className={clsx("block text-xs truncate", primary ? "text-on-accent/80" : "text-muted")}>
                            {to === "/stream" && isStreaming && (
                                <span className="inline-block h-1.5 w-1.5 rounded-full bg-live mr-1.5 align-middle animate-pulse" />
                            )}
                            {hint}
                        </span>
                    </span>
                    <ArrowRight
                        size={15}
                        className={clsx(
                            "flex-shrink-0 transition-transform duration-150 group-hover:translate-x-0.5 hidden sm:block",
                            primary ? "text-on-accent/80" : "text-inactive-strong"
                        )}
                    />
                </Link>
            ))}
        </nav>
    );
};

export default QuickActions;
