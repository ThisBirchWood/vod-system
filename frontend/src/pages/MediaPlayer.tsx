import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, Lock, Maximize, Minimize, Pause, Play, Volume1, Volume2, VolumeX } from "lucide-react";
import { AuthError } from "../utils/api/client.ts";
import Box from "../components/Box.tsx";
import { dateToTimeAgo, formatTime, stringToDate } from "../utils/utils.ts";

// Both VoDs and clips share the same on-screen shape.
type MediaItem = {
    title: string,
    createdAt: string,
};

type MediaPlayerProps = {
    /** Human-readable name for the media, e.g. "VoD" or "clip". Used in messages. */
    noun: string,
    /** Builds the raw media stream URL for the given id. Loaded directly by the
     *  <video> element so the browser can issue HTTP Range requests. */
    getMediaUrl: (id: string) => string,
    /** Fetches the media metadata for the given id. */
    fetchDetails: (id: string) => Promise<MediaItem | null>,
};

const MediaPlayer = ({ noun, getMediaUrl, fetchDetails }: MediaPlayerProps) => {
    const { id } = useParams();
    const navigate = useNavigate();

    const videoRef = useRef<HTMLVideoElement>(null);
    const containerRef = useRef<HTMLDivElement>(null);
    const progressRef = useRef<HTMLDivElement>(null);
    const hideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    // Data
    const mediaUrl = id ? getMediaUrl(id) : undefined;
    const [mediaLoaded, setMediaLoaded] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [notAuthenticated, setNotAuthenticated] = useState(false);
    const [item, setItem] = useState<MediaItem | null>(null);
    const [timeAgo, setTimeAgo] = useState("");

    // Playback
    const [isPlaying, setIsPlaying] = useState(false);
    const [currentTime, setCurrentTime] = useState(0);
    const [duration, setDuration] = useState(0);
    const [bufferedPct, setBufferedPct] = useState(0);
    const [isWaiting, setIsWaiting] = useState(false);

    // Volume
    const [volume, setVolume] = useState(1);
    const [isMuted, setIsMuted] = useState(false);

    // UI
    const [isFullscreen, setIsFullscreen] = useState(false);
    const [controlsVisible, setControlsVisible] = useState(true);
    const [isDragging, setIsDragging] = useState(false);

    // ── Data loading ────────────────────────────────────────────────────────
    // The video stream itself is loaded by the <video> element from `mediaUrl`,
    // which lets the browser fetch it incrementally via Range requests. We only
    // need to fetch the metadata here; it shares the media endpoint's auth, so a
    // 401/403 here also stands in for the stream being inaccessible.
    useEffect(() => {
        if (!id) { setError(`${noun} ID is required.`); return; }

        fetchDetails(id)
            .then(setItem)
            .catch((e) => {
                if (e instanceof AuthError) setNotAuthenticated(true);
                else setError(`Failed to load ${noun.toLowerCase()} details.`);
            });
    }, [id, noun, fetchDetails]);

    useEffect(() => {
        if (!item?.createdAt) return;
        const update = () => setTimeAgo(dateToTimeAgo(stringToDate(item.createdAt)));
        update();
        const interval = setInterval(update, 1000);
        return () => clearInterval(interval);
    }, [item]);

    // ── Controls visibility ─────────────────────────────────────────────────
    const scheduleHide = useCallback(() => {
        if (hideTimerRef.current) clearTimeout(hideTimerRef.current);
        hideTimerRef.current = setTimeout(() => setControlsVisible(false), 3000);
    }, []);

    const showControls = useCallback(() => {
        setControlsVisible(true);
        scheduleHide();
    }, [scheduleHide]);

    // ── Fullscreen ──────────────────────────────────────────────────────────
    useEffect(() => {
        const onChange = () => setIsFullscreen(!!document.fullscreenElement);
        document.addEventListener('fullscreenchange', onChange);
        return () => document.removeEventListener('fullscreenchange', onChange);
    }, []);

    const toggleFullscreen = useCallback(() => {
        if (!containerRef.current) return;
        document.fullscreenElement
            ? document.exitFullscreen()
            : containerRef.current.requestFullscreen();
    }, []);

    // ── Keyboard shortcuts ──────────────────────────────────────────────────
    useEffect(() => {
        const onKey = (e: KeyboardEvent) => {
            if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;
            const video = videoRef.current;
            if (!video) return;

            switch (e.code) {
                case 'Space': case 'KeyK':
                    e.preventDefault();
                    video.paused ? video.play() : video.pause();
                    showControls();
                    break;
                case 'ArrowLeft':
                    e.preventDefault();
                    video.currentTime = Math.max(0, video.currentTime - 5);
                    showControls();
                    break;
                case 'ArrowRight':
                    e.preventDefault();
                    video.currentTime = Math.min(video.duration, video.currentTime + 5);
                    showControls();
                    break;
                case 'KeyM':
                    video.muted = !video.muted;
                    setIsMuted(video.muted);
                    break;
                case 'KeyF':
                    toggleFullscreen();
                    break;
            }
        };
        document.addEventListener('keydown', onKey);
        return () => document.removeEventListener('keydown', onKey);
    }, [showControls, toggleFullscreen]);

    // ── Progress bar seeking ────────────────────────────────────────────────
    const seekTo = useCallback((clientX: number) => {
        const rect = progressRef.current?.getBoundingClientRect();
        const video = videoRef.current;
        if (!rect || !video || !duration) return;
        const ratio = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
        video.currentTime = ratio * duration;
        setCurrentTime(ratio * duration);
    }, [duration]);

    useEffect(() => {
        if (!isDragging) return;
        const onMove = (e: MouseEvent) => seekTo(e.clientX);
        const onUp = () => setIsDragging(false);
        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', onUp);
        return () => {
            document.removeEventListener('mousemove', onMove);
            document.removeEventListener('mouseup', onUp);
        };
    }, [isDragging, seekTo]);

    // ── Video element event handlers ────────────────────────────────────────
    const handleTimeUpdate = () => {
        const video = videoRef.current;
        if (!video) return;
        setCurrentTime(video.currentTime);
        if (video.buffered.length > 0) {
            setBufferedPct((video.buffered.end(video.buffered.length - 1) / video.duration) * 100);
        }
    };

    const handleVolumeChange = (e: React.ChangeEvent<HTMLInputElement>) => {
        const v = parseFloat(e.target.value);
        setVolume(v);
        setIsMuted(v === 0);
        if (videoRef.current) {
            videoRef.current.volume = v;
            videoRef.current.muted = v === 0;
        }
    };

    const toggleMute = () => {
        const video = videoRef.current;
        if (!video) return;
        video.muted = !video.muted;
        setIsMuted(video.muted);
    };

    const togglePlay = () => {
        const video = videoRef.current;
        if (!video) return;
        video.paused ? video.play() : video.pause();
        showControls();
    };

    const progressPct = duration > 0 ? (currentTime / duration) * 100 : 0;
    const effectiveVolume = isMuted ? 0 : volume;
    const VolumeIcon = effectiveVolume === 0 ? VolumeX : effectiveVolume < 0.5 ? Volume1 : Volume2;

    if (notAuthenticated) {
        return (
            <div className="h-full flex flex-col items-center justify-center gap-4 text-center p-8">
                <div className="w-14 h-14 rounded-full bg-fields flex items-center justify-center">
                    <Lock size={24} className="text-muted" />
                </div>
                <div>
                    <p className="text-2xl font-heading text-text-primary">Not authenticated</p>
                    <p className="text-sm text-text-secondary mt-1">You don't have access to this {noun.toLowerCase()}.</p>
                </div>
                <button
                    onClick={() => navigate(-1)}
                    className="flex items-center gap-1.5 text-sm text-text-secondary hover:text-text-primary transition-colors duration-150"
                >
                    <ArrowLeft size={16} />
                    Go back
                </button>
            </div>
        );
    }

    return (
        <div className="h-full flex flex-col p-4 gap-3">
            <button
                onClick={() => navigate(-1)}
                className="flex items-center gap-1.5 text-sm text-text-secondary hover:text-text-primary flex-shrink-0 transition-colors duration-150"
            >
                <ArrowLeft size={16} />
                Back
            </button>

            {/* Player */}
            <div
                ref={containerRef}
                className="relative bg-media-backdrop rounded-xl overflow-hidden flex-1 min-h-0 select-none"
                onMouseMove={showControls}
                onMouseLeave={() => isPlaying && setControlsVisible(false)}
                onClick={togglePlay}
                style={{ cursor: controlsVisible ? 'default' : 'none' }}
            >
                <video
                    ref={videoRef}
                    className="w-full h-full object-contain"
                    src={mediaUrl}
                    // Send auth cookies with the cross-origin Range requests.
                    crossOrigin="use-credentials"
                    preload="metadata"
                    onPlay={() => { setIsPlaying(true); scheduleHide(); }}
                    onPause={() => {
                        setIsPlaying(false);
                        setControlsVisible(true);
                        if (hideTimerRef.current) clearTimeout(hideTimerRef.current);
                    }}
                    onTimeUpdate={handleTimeUpdate}
                    onLoadedMetadata={() => {
                        if (videoRef.current) setDuration(videoRef.current.duration);
                        setMediaLoaded(true);
                    }}
                    onWaiting={() => setIsWaiting(true)}
                    onCanPlay={() => setIsWaiting(false)}
                    onError={(e) => setError(e.currentTarget.error?.message || "An error occurred.")}
                />

                {/* Buffering spinner */}
                {isWaiting && mediaUrl && (
                    <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
                        <div className="w-11 h-11 rounded-full border-[3px] border-on-dark/20 border-t-on-dark animate-spin" />
                    </div>
                )}

                {/* Controls overlay — pointer-events-none so video clicks pass through */}
                <div className={`absolute inset-0 flex flex-col justify-end transition-opacity duration-300 pointer-events-none ${controlsVisible ? 'opacity-100' : 'opacity-0'}`}>
                    {/* Gradient */}
                    <div className="absolute inset-0 bg-gradient-to-t from-black/75 via-black/10 to-transparent" />

                    {/* Controls bar — pointer-events-auto only here */}
                    <div
                        className="relative px-4 pb-4 pt-10 pointer-events-auto"
                        onClick={(e) => e.stopPropagation()}
                    >
                        {/* Scrub bar */}
                        <div
                            ref={progressRef}
                            className="relative h-1 rounded-full bg-on-dark/25 cursor-pointer mb-3 group/prog hover:h-1.5 transition-[height] duration-150"
                            onMouseDown={(e) => { setIsDragging(true); seekTo(e.clientX); }}
                        >
                            <div className="absolute inset-y-0 left-0 bg-on-dark/35 rounded-full" style={{ width: `${bufferedPct}%` }} />
                            <div className="absolute inset-y-0 left-0 bg-terracotta-light rounded-full" style={{ width: `${progressPct}%` }} />
                            <div
                                className="absolute top-1/2 -translate-y-1/2 w-3 h-3 bg-on-dark rounded-full shadow opacity-0 group-hover/prog:opacity-100 transition-opacity"
                                style={{ left: `calc(${progressPct}% - 6px)` }}
                            />
                        </div>

                        {/* Buttons row */}
                        <div className="flex items-center gap-3 text-on-dark">
                            <button onClick={togglePlay} className="hover:text-on-dark/70 transition-colors">
                                {isPlaying
                                    ? <Pause size={20} fill="currentColor" />
                                    : <Play size={20} fill="currentColor" />}
                            </button>

                            {/* Volume — slider expands on hover */}
                            <div className="flex items-center gap-1.5 group/vol">
                                <button onClick={toggleMute} className="hover:text-on-dark/70 transition-colors">
                                    <VolumeIcon size={18} />
                                </button>
                                <div className="overflow-hidden w-0 group-hover/vol:w-16 transition-[width] duration-200">
                                    <input
                                        type="range"
                                        min={0} max={1} step={0.02}
                                        value={effectiveVolume}
                                        onChange={handleVolumeChange}
                                        className="w-16 accent-on-dark cursor-pointer"
                                    />
                                </div>
                            </div>

                            <span className="text-xs font-data tabular-nums text-on-dark/80">
                                {formatTime(currentTime)} / {formatTime(duration)}
                            </span>

                            <div className="flex-1" />

                            <button onClick={toggleFullscreen} className="hover:text-on-dark/70 transition-colors">
                                {isFullscreen ? <Minimize size={18} /> : <Maximize size={18} />}
                            </button>
                        </div>
                    </div>
                </div>
            </div>

            {error && <div className="text-error text-sm mt-3">{error}</div>}
            {!mediaLoaded && !error && (
                <div className="text-muted text-sm mt-3 flex items-center gap-2">
                    <div className="w-3 h-3 rounded-full border-2 border-inactive border-t-muted animate-spin" />
                    Loading video...
                </div>
            )}

            <Box className="p-4 flex-shrink-0">
                <p className="text-2xl font-heading text-text-primary">{item?.title || "(No Title)"}</p>
                <p className="text-sm text-text-secondary mt-1">{timeAgo}</p>
            </Box>
        </div>
    );
};

export default MediaPlayer;
