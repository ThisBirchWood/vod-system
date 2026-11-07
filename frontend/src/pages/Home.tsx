import { useEffect, useState, type ReactNode } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ArrowRight, Film, LayoutGrid, Play, Plus, Radio, Scissors, SlidersHorizontal, Upload, Video } from "lucide-react";
import { useAuth } from "../auth/useAuth.ts";
import useCurrentStream from "../hooks/useCurrentStream.ts";
import useJobTracker from "../hooks/useJobTracker.ts";
import Box from "../components/Box.tsx";
import Greeting from "../components/home/Greeting.tsx";
import QuickActions from "../components/home/QuickActions.tsx";
import { LivePanel, OfflinePanel } from "../components/home/StreamPanel.tsx";
import VideoCard from "../components/video/VideoCard.tsx";
import { deleteClip, getClips } from "../utils/api/clips.ts";
import { getVods } from "../utils/api/vods.ts";
import type { Clip, StreamHistoryItem, User, Vod } from "../utils/types.ts";
import { formatDuration, stringToDate } from "../utils/utils.ts";

const RECENT_CLIPS = 8;
const RECENT_VODS = 4;

const labelClass = "font-data text-[11px] text-muted uppercase tracking-[0.12em]";
const gridClass = "grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4";

const byNewest = <T extends { createdAt: string }>(items: T[]) =>
    [...items].sort((a, b) => stringToDate(b.createdAt).getTime() - stringToDate(a.createdAt).getTime());

const startOfToday = () => {
    const d = new Date();
    return new Date(d.getFullYear(), d.getMonth(), d.getDate());
};

const daysAgo = (n: number) => {
    const d = startOfToday();
    d.setDate(d.getDate() - n);
    return d;
};

/* ── Shared bits ────────────────────────────────────────────────────────── */

const SectionHeader = ({ title, to, linkLabel }: { title: string, to?: string, linkLabel?: string }) => (
    <div className="flex items-end justify-between gap-4 mb-3">
        <h2 className="text-2xl font-heading text-text-primary">{title}</h2>
        {to && (
            <Link to={to} className="flex items-center gap-1 text-sm text-text-secondary hover:text-text-strong transition-colors">
                {linkLabel ?? "View all"}
                <ArrowRight size={14} />
            </Link>
        )}
    </div>
);

const SkeletonGrid = ({ count }: { count: number }) => (
    <div className={gridClass} aria-hidden>
        {Array.from({ length: count }, (_, i) => (
            <div key={i} className="flex flex-col gap-2">
                <div className="w-full aspect-video rounded-lg bg-fields motion-safe:animate-pulse" />
                <div className="h-3 w-3/4 rounded bg-fields motion-safe:animate-pulse" />
                <div className="h-2.5 w-1/3 rounded bg-fields motion-safe:animate-pulse" />
            </div>
        ))}
    </div>
);

const EmptyState = ({ icon: Icon, title, children }: { icon: typeof Film, title: string, children: ReactNode }) => (
    <Box className="border border-dashed border-ring p-10 flex flex-col items-center text-center gap-2">
        <Icon size={26} className="text-inactive-strong mb-1" />
        <p className="text-base text-text-strong">{title}</p>
        <div className="text-sm text-muted">{children}</div>
    </Box>
);

const Stat = ({ label, value }: { label: string, value: string }) => (
    <div className="flex flex-col gap-0.5 min-w-0">
        <span className={labelClass}>{label}</span>
        <span className="text-2xl font-heading text-text-primary tabular-nums truncate">{value}</span>
    </div>
);

/* ── Signed-in dashboard ────────────────────────────────────────────────── */

const Dashboard = ({ user }: { user: User }) => {
    const navigate = useNavigate();
    const { status, isStreaming, details, history } = useCurrentStream();
    const { jobs, addJob, succeededCount } = useJobTracker();

    const [clips, setClips] = useState<Clip[] | null>(null);
    const [vods, setVods] = useState<Vod[] | null>(null);
    const [error, setError] = useState<string | null>(null);

    // Re-run when a live clip finishes so it shows up in "Recent clips" straight away.
    useEffect(() => {
        getClips()
            .then((data) => setClips(byNewest(data)))
            .catch((err) => setError(err instanceof Error ? err.message : "Failed to load clips"));
    }, [succeededCount]);

    useEffect(() => {
        getVods()
            .then((data) => setVods(byNewest(data)))
            .catch(() => setVods([]));
    }, []);

    const handleDeleteClip = async (id: number) => {
        try {
            await deleteClip(id);
            setClips((prev) => prev?.filter((c) => c.id !== id) ?? prev);
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to delete clip");
        }
    };

    const firstName = user.name?.trim().split(/\s+/)[0] || user.username;
    const today = startOfToday();
    const weekAgo = daysAgo(6);
    const clipsToday = clips?.filter((c) => stringToDate(c.createdAt) >= today).length ?? 0;
    const clipsThisWeek = clips?.filter((c) => stringToDate(c.createdAt) >= weekAgo).length ?? 0;
    const totalFootage = clips?.reduce((sum, c) => sum + c.duration, 0) ?? 0;

    const lastStream: StreamHistoryItem | null = history
        .filter((s) => s.startDate !== null)
        .sort((a, b) => stringToDate(b.startDate!).getTime() - stringToDate(a.startDate!).getTime())[0] ?? null;

    return (
        <div className="px-6 sm:px-8 py-10 max-w-6xl mx-auto flex flex-col gap-8">
            {/* Welcome */}
            <header className="flex items-end justify-between gap-6 flex-wrap">
                <Greeting
                    ready={clips !== null || error !== null}
                    firstName={firstName}
                    isStreaming={isStreaming}
                    clipsToday={clipsToday}
                    clipsThisWeek={clipsThisWeek}
                    totalClips={clips?.length ?? 0}
                    latestClipTitle={clips?.[0]?.title || null}
                />
                {clips !== null && clips.length > 0 && (
                    <div className="flex gap-8">
                        <Stat label="Clips" value={String(clips.length)} />
                        <Stat label="Last 7 days" value={String(clipsThisWeek)} />
                        <Stat label="Footage" value={formatDuration(totalFootage)} />
                    </div>
                )}
            </header>

            {/* Stream: full controls while live, a compact status line otherwise.
                Held back until the first status poll lands so it doesn't flash "Offline". */}
            {status !== null && (isStreaming ? (
                <LivePanel
                    streamId={status.id}
                    startDate={details?.startDate ?? null}
                    jobs={jobs}
                    addJob={addJob}
                />
            ) : (
                <OfflinePanel streamKey={user.streamKey} lastStream={lastStream} />
            ))}

            <QuickActions clipCount={clips?.length ?? null} vodCount={vods?.length ?? null} isStreaming={isStreaming} />

            {/* Recent clips */}
            <section>
                <SectionHeader title="Recent clips" to={clips && clips.length > 0 ? "/my-clips" : undefined} />
                {error && <p className="text-sm text-error mb-3">{error}</p>}
                {clips === null && !error ? (
                    <SkeletonGrid count={RECENT_CLIPS} />
                ) : clips && clips.length === 0 ? (
                    <EmptyState icon={Film} title="No clips yet">
                        <p>
                            <Link to="/create" className="text-terracotta hover:underline">Upload a video</Link>
                            {" "}to make your first one, or clip straight from a live stream.
                        </p>
                    </EmptyState>
                ) : clips && (
                    <div className={gridClass}>
                        {clips.slice(0, RECENT_CLIPS).map((clip) => (
                            <VideoCard
                                key={clip.id}
                                id={clip.id}
                                title={clip.title}
                                duration={clip.duration}
                                createdAt={clip.createdAt}
                                onEdit={() => navigate(`/clips/${clip.id}/edit`)}
                                onDelete={() => handleDeleteClip(clip.id)}
                            />
                        ))}
                    </div>
                )}
            </section>

            {/* Recent VoDs — only once there are some, to keep a new account's page focused on clips. */}
            {vods && vods.length > 0 && (
                <section>
                    <SectionHeader title="Recent VoDs" to="/my-vods" />
                    <div className={gridClass}>
                        {vods.slice(0, RECENT_VODS).map((vod) => (
                            <VideoCard
                                key={vod.id}
                                id={vod.id}
                                title={vod.title}
                                duration={vod.duration}
                                createdAt={vod.createdAt}
                                mediaApiPath="/api/v1/vods"
                                playerPath="/vod"
                                itemLabel="vod"
                                onEdit={() => navigate(`/vods/${vod.id}/edit`)}
                            />
                        ))}
                    </div>
                </section>
            )}
        </div>
    );
};

/* ── Signed-out landing ─────────────────────────────────────────────────── */

const features = [
    { icon: Upload, title: "Upload & compress", description: "Drop in a video file, set your clip range and export settings, then compress." },
    { icon: Scissors, title: "Trim", description: "Set start and end points with a range slider. Preview as you go." },
    { icon: SlidersHorizontal, title: "Export settings", description: "Choose resolution, frame rate, and an optional file size cap." },
    { icon: Radio, title: "RTMP streaming", description: "Point OBS at your stream key and go live. Drop markers and clip as it happens." },
    { icon: LayoutGrid, title: "Clip library", description: "Browse all your clips. Watch, rename, edit the description, or delete." },
    { icon: Play, title: "Built-in player", description: "Scrub, adjust volume, go fullscreen. Runs in the browser." },
];

const Landing = () => (
    <div className="flex flex-col items-center px-8 py-12 min-h-full">
        <div className="flex flex-col items-center text-center max-w-2xl mb-12">
            {/* The logo PNG is used only as an alpha mask, so one asset
                renders solid black in light mode and white in dark mode. */}
            <div
                role="img"
                aria-label="VoD System Logo"
                className="h-24 aspect-square mb-6 bg-black dark:bg-white"
                style={{
                    maskImage: "url(/logo.png)",
                    maskSize: "contain",
                    maskRepeat: "no-repeat",
                    maskPosition: "center",
                }}
            />
            <h1 className="text-4xl font-heading text-text-primary">A home for your clips.</h1>
            <p className="text-text-secondary mb-6">Upload, trim, and export video clips. Stream live if you need to.</p>
            <div className="flex flex-col sm:flex-row items-center gap-3">
                <Link
                    to="/create"
                    className="inline-flex items-center gap-2 h-10 px-5 rounded-lg bg-terracotta text-on-accent text-sm hover:bg-terracotta-hover transition-colors duration-150"
                >
                    <Plus size={16} />
                    Create a clip
                </Link>
                <p className="text-sm text-muted">
                    or sign in (top right) to keep a library and stream
                </p>
            </div>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 w-full max-w-4xl mb-10">
            {features.map(({ icon: Icon, title, description }) => (
                <div key={title} className="bg-card border border-hairline rounded-xl p-5 flex flex-col gap-3 shadow-sm">
                    <div className="w-9 h-9 rounded-lg bg-terracotta/10 flex items-center justify-center shrink-0">
                        <Icon size={18} className="text-terracotta" />
                    </div>
                    <div>
                        <p className="font-body text-text-strong font-bold text-sm mb-1">{title}</p>
                        <p className="text-sm text-text-secondary leading-relaxed">{description}</p>
                    </div>
                </div>
            ))}
        </div>

        <p className="flex items-center gap-1.5 text-xs text-muted">
            <Video size={13} />
            Supports MP4, MOV, WebM, and OGG.
        </p>
    </div>
);

const Home = () => {
    const { user, loading } = useAuth();

    if (loading) return null;
    return user ? <Dashboard user={user} /> : <Landing />;
};

export default Home;
