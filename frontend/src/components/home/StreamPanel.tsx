import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { ArrowRight, Bookmark, Check, Copy, History, Plus, Radio, Scissors } from "lucide-react";
import Box from "../Box.tsx";
import PrimaryButton from "../buttons/PrimaryButton.tsx";
import JobStatus from "../stream/JobStatus.tsx";
import type { TrackedJob } from "../../hooks/useJobTracker.ts";
import { createMarker, getMarkers } from "../../utils/api/markers.ts";
import { clipSection } from "../../utils/api/media.ts";
import type { Marker, StreamHistoryItem } from "../../utils/types.ts";
import { dateToTimeAgo, formatDuration, formatLocalTime, formatTime, stringToDate } from "../../utils/utils.ts";

const inputClass = "border border-hairline bg-fields rounded-md w-full p-2 text-sm focus:outline-none focus:ring-2 focus:ring-muted transition-colors";
const secondaryButtonClass = "inline-flex items-center gap-1.5 h-9 px-3 rounded-lg border border-ring bg-card text-sm text-text-strong hover:bg-hover transition-colors duration-150";

const CLIP_PRESETS: { seconds: number, label: string }[] = [
    { seconds: 30, label: "30s" },
    { seconds: 60, label: "1m" },
    { seconds: 120, label: "2m" },
    { seconds: 300, label: "5m" },
];

const isTyping = (target: EventTarget | null) =>
    target instanceof HTMLElement && (target.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName));

const sortNewestFirst = (markers: Marker[]) =>
    [...markers].sort((a, b) => stringToDate(b.timestamp).getTime() - stringToDate(a.timestamp).getTime());

type LiveProps = {
    streamId: number | null;
    startDate: string | null;
    jobs: TrackedJob[];
    addJob: (uuid: string, label: string) => void;
};

/** Condensed stream controls for the home page: drop a marker or clip the last few seconds without leaving. */
const LivePanel = ({ streamId, startDate, jobs, addJob }: LiveProps) => {
    const [now, setNow] = useState(() => Date.now());
    const [markers, setMarkers] = useState<Marker[]>([]);
    const [markerMessage, setMarkerMessage] = useState("");
    const [markerBusy, setMarkerBusy] = useState(false);
    const [markerError, setMarkerError] = useState<string | null>(null);
    const [clipTitle, setClipTitle] = useState("");
    const [clipBusy, setClipBusy] = useState<number | null>(null);
    const [clipError, setClipError] = useState<string | null>(null);
    const markerInputRef = useRef<HTMLInputElement>(null);

    useEffect(() => {
        const interval = setInterval(() => setNow(Date.now()), 1000);
        return () => clearInterval(interval);
    }, []);

    useEffect(() => {
        const refresh = () => getMarkers().then((m) => setMarkers(sortNewestFirst(m))).catch(() => {});
        refresh();
        const interval = setInterval(refresh, 10000);
        return () => clearInterval(interval);
    }, [streamId]);

    // "M" jumps to the marker box from anywhere on the page, so marking a
    // moment mid-stream is one keystroke away.
    useEffect(() => {
        const onKey = (e: KeyboardEvent) => {
            if (e.key.toLowerCase() !== "m" || e.metaKey || e.ctrlKey || e.altKey || isTyping(e.target)) return;
            e.preventDefault();
            markerInputRef.current?.focus();
        };
        document.addEventListener("keydown", onKey);
        return () => document.removeEventListener("keydown", onKey);
    }, []);

    const handleAddMarker = async () => {
        const message = markerMessage.trim();
        if (!message || markerBusy) return;
        setMarkerBusy(true);
        setMarkerError(null);
        try {
            const created = await createMarker(message);
            setMarkers((prev) => sortNewestFirst([created, ...prev.filter((m) => m.id !== created.id)]));
            setMarkerMessage("");
        } catch (err) {
            setMarkerError(err instanceof Error ? err.message : "Failed to add marker");
        } finally {
            setMarkerBusy(false);
        }
    };

    const handleClip = async (seconds: number, label: string) => {
        setClipBusy(seconds);
        setClipError(null);
        try {
            const uuid = await clipSection(seconds, clipTitle.trim() || undefined);
            addJob(uuid, clipTitle.trim() ? `“${clipTitle.trim()}”` : `Clip (last ${label})`);
            setClipTitle("");
        } catch (err) {
            setClipError(err instanceof Error ? err.message : "Failed to clip");
        } finally {
            setClipBusy(null);
        }
    };

    const uptime = startDate ? Math.max(0, Math.floor((now - stringToDate(startDate).getTime()) / 1000)) : null;

    return (
        <Box className="border-l-4 border-l-live p-6 flex flex-col gap-6">
            <div className="flex items-center justify-between gap-4 flex-wrap">
                <div className="flex items-center gap-3.5">
                    <span className="relative flex h-3.5 w-3.5">
                        <span className="motion-safe:animate-ping absolute inline-flex h-full w-full rounded-full bg-live opacity-75" />
                        <span className="relative inline-flex rounded-full h-3.5 w-3.5 bg-live" />
                    </span>
                    <div>
                        <p className="text-2xl font-heading text-text-primary leading-tight">You're live</p>
                        {uptime !== null && startDate && (
                            <p className="text-sm text-text-secondary">
                                <span className="font-data tabular-nums text-text-strong">{formatTime(uptime)}</span>
                                <span className="text-muted"> · since {formatLocalTime(startDate)}</span>
                            </p>
                        )}
                    </div>
                </div>
                <Link to="/stream" className="flex items-center gap-1.5 text-sm text-text-secondary hover:text-text-strong transition-colors">
                    Full stream control
                    <ArrowRight size={14} />
                </Link>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                {/* Markers */}
                <section className="flex flex-col gap-3">
                    <div className="flex items-center justify-between">
                        <h2 className="flex items-center gap-2 text-sm font-medium text-text-strong">
                            <Bookmark size={15} className="text-olive" />
                            Drop a marker
                        </h2>
                        <kbd className="font-data text-[10px] text-muted border border-ring rounded px-1.5 py-0.5" title="Press M to focus">M</kbd>
                    </div>
                    <div className="flex gap-2">
                        <input
                            ref={markerInputRef}
                            type="text"
                            value={markerMessage}
                            onChange={(e) => setMarkerMessage(e.target.value)}
                            onKeyDown={(e) => {
                                if (e.key === "Enter") handleAddMarker();
                                if (e.key === "Escape") e.currentTarget.blur();
                            }}
                            placeholder="What just happened?"
                            aria-label="Marker message"
                            className={inputClass}
                        />
                        <PrimaryButton
                            onClick={handleAddMarker}
                            disabled={markerBusy || !markerMessage.trim()}
                            className="flex items-center gap-1.5 px-3 whitespace-nowrap"
                        >
                            <Plus size={15} />
                            Mark
                        </PrimaryButton>
                    </div>
                    {markerError && <p className="text-sm text-error">{markerError}</p>}
                    {markers.length === 0 ? (
                        <p className="text-sm text-muted">No markers yet this stream.</p>
                    ) : (
                        <ul className="flex flex-col gap-1">
                            {markers.slice(0, 3).map((m) => (
                                <li key={m.id} className="flex items-baseline justify-between gap-3 text-sm">
                                    <span className="truncate text-text-body">{m.message}</span>
                                    <span className="font-data text-[11px] text-muted tabular-nums flex-shrink-0">{formatLocalTime(m.timestamp)}</span>
                                </li>
                            ))}
                            {markers.length > 3 && (
                                <li>
                                    <Link to="/stream" className="text-xs text-text-secondary hover:text-text-strong">
                                        +{markers.length - 3} more — save between markers in stream control
                                    </Link>
                                </li>
                            )}
                        </ul>
                    )}
                </section>

                {/* Instant clips */}
                <section className="flex flex-col gap-3">
                    <h2 className="flex items-center gap-2 text-sm font-medium text-text-strong">
                        <Scissors size={15} className="text-terracotta" />
                        Clip the last…
                    </h2>
                    <input
                        type="text"
                        value={clipTitle}
                        onChange={(e) => setClipTitle(e.target.value)}
                        placeholder="Title (optional)"
                        aria-label="Clip title"
                        className={inputClass}
                    />
                    <div className="grid grid-cols-4 gap-2">
                        {CLIP_PRESETS.map(({ seconds, label }) => (
                            <button
                                key={seconds}
                                onClick={() => handleClip(seconds, label)}
                                disabled={clipBusy !== null}
                                className="h-10 rounded-lg border border-terracotta/40 text-sm font-medium text-terracotta hover:bg-terracotta hover:text-on-accent disabled:opacity-50 disabled:hover:bg-transparent disabled:hover:text-terracotta transition-colors duration-150 tabular-nums"
                            >
                                {clipBusy === seconds ? "…" : label}
                            </button>
                        ))}
                    </div>
                    {clipError && <p className="text-sm text-error">{clipError}</p>}
                    {jobs.length > 0 && (
                        <div className="flex flex-col gap-2 pt-1">
                            {jobs.slice(0, 3).map((job) => <JobStatus key={job.uuid} job={job} />)}
                        </div>
                    )}
                </section>
            </div>
        </Box>
    );
};

type OfflineProps = {
    streamKey: string;
    lastStream: StreamHistoryItem | null;
};

/** Shown when not live: when you last streamed, plus the stream key ready to paste into OBS. */
const OfflinePanel = ({ streamKey, lastStream }: OfflineProps) => {
    const [copied, setCopied] = useState(false);

    const handleCopy = async () => {
        try {
            await navigator.clipboard.writeText(streamKey);
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
        } catch {
            // Clipboard can be blocked (e.g. insecure context); the key is still on the Profile page.
        }
    };

    const lastLength = lastStream?.startDate && lastStream.endDate
        ? (stringToDate(lastStream.endDate).getTime() - stringToDate(lastStream.startDate).getTime()) / 1000
        : null;

    return (
        <Box className="border-l-4 border-l-hairline p-5 flex items-center justify-between gap-4 flex-wrap">
            <div className="flex items-center gap-3.5 min-w-0">
                <Radio size={22} className="text-inactive-strong flex-shrink-0" />
                <div className="min-w-0">
                    <p className="text-sm font-medium text-text-strong">Offline</p>
                    <p className="text-sm text-text-secondary">
                        {lastStream?.startDate
                            ? <>Last streamed {dateToTimeAgo(stringToDate(lastStream.startDate))}{lastLength !== null && <> for {formatDuration(lastLength)}</>}.</>
                            : "Point OBS at your stream key to go live."}
                    </p>
                </div>
            </div>
            <div className="flex items-center gap-2">
                {streamKey && (
                    <button onClick={handleCopy} className={secondaryButtonClass}>
                        {copied ? <Check size={14} className="text-olive" /> : <Copy size={14} />}
                        {copied ? "Copied" : "Copy stream key"}
                    </button>
                )}
                <Link to="/stream/history" className={secondaryButtonClass}>
                    <History size={14} />
                    History
                </Link>
            </div>
        </Box>
    );
};

export { LivePanel, OfflinePanel };
