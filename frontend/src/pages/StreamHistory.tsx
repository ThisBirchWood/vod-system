import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ArrowLeft, Clock, History } from "lucide-react";
import clsx from "clsx";
import Box from "../components/Box.tsx";
import CardHeader from "../components/CardHeader.tsx";
import { useAuth } from "../auth/useAuth.ts";
import { getStreamHistory } from "../utils/api/stream.ts";
import type { StreamHistoryItem } from "../utils/types.ts";
import { formatLocalDate, formatLocalTime, groupByTimeRange, stringToDate } from "../utils/utils.ts";

const labelClass = "font-data text-[11px] text-muted uppercase tracking-[0.12em]";

/** A stream that has a start date; streams without one can't be placed on the timeline. */
type DatedStream = StreamHistoryItem & { startDate: string };

const isDated = (stream: StreamHistoryItem): stream is DatedStream => stream.startDate !== null;

/** Length of the stream in seconds, measured up to now while it is still live. */
const streamSeconds = (stream: DatedStream, now: number): number => {
    const start = stringToDate(stream.startDate).getTime();
    const end = stream.endDate ? stringToDate(stream.endDate).getTime() : now;
    return Math.max(0, Math.floor((end - start) / 1000));
};

// Coarser than formatTime: a stream history reads better as "2h 14m" than "2:14:07".
const formatDuration = (seconds: number): string => {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    if (h > 0) return `${h}h ${m}m`;
    if (m > 0) return `${m}m`;
    return `${seconds}s`;
};

const Stat = ({ label, value }: { label: string, value: string }) => (
    <div className="flex flex-col gap-1">
        <span className={labelClass}>{label}</span>
        <span className="text-2xl font-heading text-text-primary tabular-nums">{value}</span>
    </div>
);

const StreamRow = ({ stream, now }: { stream: DatedStream, now: number }) => {
    const isLive = stream.endDate === null;

    return (
        <li className={clsx(
            "flex items-center justify-between gap-4 px-5 py-4 border-l-4",
            isLive ? "border-l-live" : "border-l-transparent"
        )}>
            <div className="min-w-0">
                <p className="text-sm font-medium text-text-strong">
                    {formatLocalDate(stream.startDate, { weekday: "short", year: "numeric", month: "short", day: "numeric" })}
                </p>
                <p className="font-data text-[11px] text-muted tabular-nums">
                    {formatLocalTime(stream.startDate)}
                    {" – "}
                    {stream.endDate ? formatLocalTime(stream.endDate) : "now"}
                </p>
            </div>

            <div className="flex items-center gap-3 flex-shrink-0">
                {isLive && (
                    <span className="flex items-center gap-1.5 text-xs font-medium text-live">
                        <span className="h-2 w-2 rounded-full bg-live animate-pulse" />
                        Live
                    </span>
                )}
                <span className="flex items-center gap-1.5 text-sm text-text-secondary tabular-nums">
                    <Clock size={14} className="text-muted" />
                    {formatDuration(streamSeconds(stream, now))}
                </span>
            </div>
        </li>
    );
};

const StreamHistory = () => {
    const { user } = useAuth();
    const [streams, setStreams] = useState<DatedStream[]>([]);
    const [error, setError] = useState<string | null>(null);
    const [loading, setLoading] = useState(true);
    const [now, setNow] = useState(() => Date.now());

    useEffect(() => {
        if (!user) return;
        getStreamHistory(user.id)
            .then((data) => setStreams(data
                .filter(isDated)
                .sort((a, b) => stringToDate(b.startDate).getTime() - stringToDate(a.startDate).getTime())))
            .catch((err) => setError(err instanceof Error ? err.message : "Failed to load stream history"))
            .finally(() => setLoading(false));
    }, [user]);

    // Keep a live stream's running duration ticking over.
    const hasLive = streams.some((s) => s.endDate === null);
    useEffect(() => {
        if (!hasLive) return;
        const interval = setInterval(() => setNow(Date.now()), 30_000);
        return () => clearInterval(interval);
    }, [hasLive]);

    const durations = streams.map((s) => streamSeconds(s, now));
    const totalSeconds = durations.reduce((sum, d) => sum + d, 0);
    const longestSeconds = durations.reduce((max, d) => Math.max(max, d), 0);

    return (
        <div className="px-8 py-10 max-w-5xl mx-auto flex flex-col gap-5">
            <div className="flex items-end justify-between gap-4">
                <h1 className="text-4xl font-heading text-text-primary">Stream History</h1>
                <Link to="/stream" className="flex items-center gap-1.5 text-sm text-text-secondary hover:text-text-strong transition-colors">
                    <ArrowLeft size={15} />
                    Stream Control
                </Link>
            </div>

            {streams.length > 0 && (
                <Box className="p-6 grid grid-cols-3 gap-6">
                    <Stat label="Streams" value={String(streams.length)} />
                    <Stat label="Time live" value={formatDuration(totalSeconds)} />
                    <Stat label="Longest" value={formatDuration(longestSeconds)} />
                </Box>
            )}

            {groupByTimeRange(streams, (s) => s.startDate).map((group) => (
                <Box key={group.label} className="overflow-hidden">
                    <div className="px-5 pt-5 pb-3">
                        <CardHeader icon={History} title={group.label} />
                    </div>
                    <ul className="divide-y divide-hairline border-t border-hairline">
                        {group.items.map((stream) => <StreamRow key={stream.id} stream={stream} now={now} />)}
                    </ul>
                </Box>
            ))}

            {streams.length === 0 && !error && !loading && (
                <div className="flex flex-col items-center justify-center py-16 text-muted">
                    <p className="text-base">No streams yet.</p>
                    <p className="text-sm mt-1">Your past broadcasts will appear here once you go live.</p>
                </div>
            )}

            {error && <p className="text-sm text-error">{error}</p>}
        </div>
    );
};

export default StreamHistory;
