import { useEffect, useState } from "react";
import { Radio, Check, Bookmark, Scissors, Film, Activity, Loader2, X } from "lucide-react";
import clsx from "clsx";
import Box from "../components/Box.tsx";
import PrimaryButton from "../components/buttons/PrimaryButton.tsx";
import { getUser } from "../utils/api/users.ts";
import { getCurrentStream, getStreamHistory } from "../utils/api/stream.ts";
import { getMarkers, createMarker, deleteMarker } from "../utils/api/markers.ts";
import { saveSectionByMarkers, clipSection } from "../utils/api/media.ts";
import { getJob } from "../utils/api/jobs.ts";
import type { User, StreamStatus, StreamHistoryItem, Marker, JobResponse } from "../utils/types.ts";
import { formatTime, formatLocalDate, stringToDate } from "../utils/utils.ts";

const inputClass = "border border-hairline bg-fields rounded-md w-full p-2 text-sm focus:outline-none focus:ring-2 focus:ring-muted transition-colors";
const labelClass = "font-data text-[11px] text-muted uppercase tracking-[0.12em]";

type TrackedJob = {
    uuid: string;
    label: string;
    progress: number;
    state: JobResponse['state'];
    errorOutput: string | null;
};

const CardHeader = ({ icon: Icon, title, accent = "primary" }: { icon: React.ElementType; title: string; accent?: "primary" | "accent" }) => (
    <div className="flex items-center gap-2.5">
        <div className={clsx("w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0", accent === "primary" ? "bg-terracotta/10" : "bg-olive/10")}>
            <Icon size={16} className={accent === "primary" ? "text-terracotta" : "text-olive"} />
        </div>
        <h2 className="text-xl font-heading text-text-primary">{title}</h2>
    </div>
);

const JobStatus = ({ job }: { job: TrackedJob }) => {
    if (job.state === 'FAILED') {
        return (
            <div className="flex items-start gap-2 text-sm">
                <X size={15} className="text-error mt-0.5 flex-shrink-0" />
                <p className="text-error">{job.label} failed: {job.errorOutput ?? "Unknown error"}</p>
            </div>
        );
    }
    if (job.state === 'SUCCEEDED') {
        return (
            <div className="flex items-center gap-2 text-sm">
                <Check size={15} className="text-olive flex-shrink-0" />
                <p className="text-text-body">{job.label} saved</p>
            </div>
        );
    }
    return (
        <div className="flex items-center gap-2">
            <Loader2 size={15} className="text-terracotta animate-spin flex-shrink-0" />
            <div className="flex-1">
                <p className="text-sm text-text-secondary mb-1">{job.label}…</p>
                <progress value={job.progress} className="w-full h-1.5 rounded bg-fields" />
            </div>
        </div>
    );
};

const StreamControl = () => {
    const [user, setUser] = useState<User | null>(null);
    const [streamStatus, setStreamStatus] = useState<StreamStatus | null>(null);
    const [streamDetails, setStreamDetails] = useState<StreamHistoryItem | null>(null);
    const [now, setNow] = useState(Date.now());

    const [markers, setMarkers] = useState<Marker[]>([]);
    const [markerMessage, setMarkerMessage] = useState("");
    const [markerBusy, setMarkerBusy] = useState(false);
    const [markerError, setMarkerError] = useState<string | null>(null);

    const [clipDuration, setClipDuration] = useState(30);
    const [clipTitle, setClipTitle] = useState("");
    const [clipDescription, setClipDescription] = useState("");
    const [clipError, setClipError] = useState<string | null>(null);

    const [startMarkerId, setStartMarkerId] = useState<string>("");
    const [endMarkerId, setEndMarkerId] = useState<string>("");
    const [markerSaveTitle, setMarkerSaveTitle] = useState("");
    const [markerSaveDescription, setMarkerSaveDescription] = useState("");
    const [markerSaveError, setMarkerSaveError] = useState<string | null>(null);

    const [jobs, setJobs] = useState<TrackedJob[]>([]);

    const isStreaming = streamStatus?.isStreaming ?? false;

    useEffect(() => {
        getUser().then(setUser).catch(() => setUser(null));
    }, []);

    useEffect(() => {
        if (!user) return;
        const poll = () => getCurrentStream().then(setStreamStatus).catch(() => {});
        poll();
        const interval = setInterval(poll, 5000);
        return () => clearInterval(interval);
    }, [user]);

    useEffect(() => {
        if (!user || !streamStatus?.id) {
            setStreamDetails(null);
            return;
        }
        getStreamHistory(user.id)
            .then((history) => setStreamDetails(history.find((s) => s.id === streamStatus.id) ?? null))
            .catch(() => {});
    }, [user, streamStatus?.id]);

    useEffect(() => {
        if (!streamDetails?.startDate) return;
        const interval = setInterval(() => setNow(Date.now()), 1000);
        return () => clearInterval(interval);
    }, [streamDetails?.startDate]);

    const refreshMarkers = () => {
        getMarkers()
            .then((data) => setMarkers(data
                .sort((a, b) => stringToDate(a.timestamp).getTime() - stringToDate(b.timestamp).getTime())))
            .catch(() => {});
    };

    useEffect(() => {
        if (!isStreaming) {
            setMarkers([]);
            return;
        }
        refreshMarkers();
        const interval = setInterval(refreshMarkers, 5000);
        return () => clearInterval(interval);
    }, [isStreaming, streamStatus?.id]);

    useEffect(() => {
        const pending = jobs.filter((j) => j.state !== 'SUCCEEDED' && j.state !== 'FAILED');
        if (pending.length === 0) return;

        const interval = setInterval(() => {
            pending.forEach((job) => {
                getJob(job.uuid)
                    .then((result) => {
                        setJobs((prev) => prev.map((j) => j.uuid === job.uuid
                            ? { ...j, progress: result.progress, state: result.state, errorOutput: result.errorOutput }
                            : j));
                    })
                    .catch(() => {});
            });
        }, 1000);

        return () => clearInterval(interval);
    }, [jobs]);

    const addJob = (uuid: string, label: string) => {
        setJobs((prev) => [{ uuid, label, progress: 0, state: 'READY', errorOutput: null }, ...prev]);
    };

    const handleAddMarker = async () => {
        if (!markerMessage.trim() || markerBusy) return;
        setMarkerBusy(true);
        setMarkerError(null);
        try {
            await createMarker(markerMessage.trim());
            setMarkerMessage("");
            refreshMarkers();
        } catch (err) {
            setMarkerError(err instanceof Error ? err.message : "Failed to add marker");
        } finally {
            setMarkerBusy(false);
        }
    };

    const handleClip = async () => {
        setClipError(null);
        try {
            const uuid = await clipSection(clipDuration, clipTitle || undefined, clipDescription || undefined);
            addJob(uuid, `Clip (last ${clipDuration}s)`);
            setClipTitle("");
            setClipDescription("");
        } catch (err) {
            setClipError(err instanceof Error ? err.message : "Failed to clip section");
        }
    };

    const handleSaveByMarkers = async () => {
        setMarkerSaveError(null);
        if (!startMarkerId || !endMarkerId) {
            setMarkerSaveError("Tap two markers above to pick a start and end");
            return;
        }
        try {
            const uuid = await saveSectionByMarkers(
                Number(startMarkerId),
                Number(endMarkerId),
                markerSaveTitle || undefined,
                markerSaveDescription || undefined
            );
            addJob(uuid, "Save (by markers)");
            setStartMarkerId("");
            setEndMarkerId("");
            setMarkerSaveTitle("");
            setMarkerSaveDescription("");
        } catch (err) {
            setMarkerSaveError(err instanceof Error ? err.message : "Failed to save section");
        }
    };

    const handleDeleteMarker = async (id: number) => {
        try {
            await deleteMarker(id);
            if (startMarkerId === String(id)) setStartMarkerId("");
            if (endMarkerId === String(id)) setEndMarkerId("");
            refreshMarkers();
        } catch (err) {
            setMarkerError(err instanceof Error ? err.message : "Failed to delete marker");
        }
    };

    const handleChipClick = (id: number) => {
        const idStr = String(id);
        setMarkerSaveError(null);
        if (startMarkerId === idStr) { setStartMarkerId(""); return; }
        if (endMarkerId === idStr) { setEndMarkerId(""); return; }
        if (!startMarkerId) { setStartMarkerId(idStr); return; }
        if (!endMarkerId) { setEndMarkerId(idStr); return; }
        setEndMarkerId(idStr);
    };

    if (!user) {
        return (
            <div className="flex justify-center items-center h-full text-muted text-base">
                Please log in to control your stream.
            </div>
        );
    }

    const elapsed = streamDetails?.startDate
        ? formatTime((now - stringToDate(streamDetails.startDate).getTime()) / 1000)
        : null;

    const startMarker = markers.find((m) => String(m.id) === startMarkerId);
    const endMarker = markers.find((m) => String(m.id) === endMarkerId);

    return (
        <div className="px-8 py-10 max-w-5xl mx-auto flex flex-col gap-5">
            <h1 className="text-4xl font-heading text-text-primary">Stream Control</h1>

            {/* Hero status bar */}
            <Box className={clsx(
                "p-6 flex items-center justify-between gap-6 border-l-4",
                isStreaming ? "border-l-live" : "border-l-hairline"
            )}>
                <div className="flex items-center gap-3.5">
                    <span className="relative flex h-3.5 w-3.5">
                        {isStreaming && <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-live opacity-75" />}
                        <span className={clsx("relative inline-flex rounded-full h-3.5 w-3.5", isStreaming ? "bg-live" : "bg-inactive-strong")} />
                    </span>
                    <div>
                        <p className="text-2xl font-heading text-text-primary leading-tight">{isStreaming ? "Live" : "Offline"}</p>
                        {isStreaming && streamDetails?.startDate ? (
                            <p className="text-sm text-text-secondary">Started {formatLocalDate(streamDetails.startDate)}</p>
                        ) : !isStreaming ? (
                            <p className="text-sm text-text-secondary">Point OBS at your stream key (see Profile) to go live.</p>
                        ) : null}
                    </div>
                </div>

                {isStreaming && elapsed ? (
                    <div className="text-right">
                        <p className="text-2xl font-data text-text-primary tabular-nums">{elapsed}</p>
                        <p className={labelClass}>elapsed</p>
                    </div>
                ) : (
                    <Radio size={28} className="text-inactive" />
                )}
            </Box>

            {isStreaming && (
                <>
                    {/* Quick mark toolbar */}
                    <Box className="p-4 flex flex-col gap-3">
                        <div className="flex items-center gap-3">
                            <Bookmark size={18} className="text-olive flex-shrink-0" />
                            <input
                                type="text"
                                value={markerMessage}
                                onChange={(e) => setMarkerMessage(e.target.value)}
                                onKeyDown={(e) => e.key === "Enter" && handleAddMarker()}
                                placeholder="What just happened?"
                                className={inputClass}
                            />
                            <PrimaryButton onClick={handleAddMarker} disabled={markerBusy || !markerMessage.trim()} className="whitespace-nowrap">
                                Add Marker
                            </PrimaryButton>
                        </div>
                        {markerError && <p className="text-sm text-error">{markerError}</p>}

                        {markers.length > 0 && (
                            <div className="flex gap-2 overflow-x-auto pb-0.5 -mx-1 px-1 pt-1.5">
                                {markers.map((m) => {
                                    const isStart = startMarkerId === String(m.id);
                                    const isEnd = endMarkerId === String(m.id);
                                    return (
                                        <div key={m.id} className="group relative flex-shrink-0">
                                            <button
                                                onClick={() => handleChipClick(m.id)}
                                                className={clsx(
                                                    "flex flex-col items-start gap-0.5 rounded-lg border px-3 py-1.5 text-left transition-colors duration-150",
                                                    isStart && "border-terracotta bg-selected ring-1 ring-terracotta",
                                                    isEnd && "border-olive bg-selected ring-1 ring-olive",
                                                    !isStart && !isEnd && "border-hairline hover:border-ring hover:bg-hover"
                                                )}
                                            >
                                                <span className="text-xs font-medium text-text-strong max-w-40 truncate">{m.message}</span>
                                                <span className="font-data text-[11px] text-muted">
                                                    {formatLocalDate(m.timestamp, { hour: "2-digit", minute: "2-digit", second: "2-digit" })}
                                                    {isStart && <span className="text-terracotta font-medium"> · start</span>}
                                                    {isEnd && <span className="text-olive font-medium"> · end</span>}
                                                </span>
                                            </button>
                                            <button
                                                onClick={(e) => { e.stopPropagation(); handleDeleteMarker(m.id); }}
                                                title="Delete marker"
                                                className="hidden group-hover:flex items-center justify-center absolute -top-1.5 -right-1.5 w-4 h-4 rounded-full bg-muted hover:bg-error text-on-accent transition-colors duration-150"
                                            >
                                                <X size={10} />
                                            </button>
                                        </div>
                                    );
                                })}
                            </div>
                        )}
                    </Box>

                    {/* Forefront actions */}
                    <div className="grid grid-cols-1 lg:grid-cols-2 gap-5 items-stretch">
                        <Box className="p-6 flex flex-col gap-3">
                            <CardHeader icon={Scissors} title="Clip the last few seconds" />
                            <div className="flex flex-col gap-1.5">
                                <label className={labelClass}>Duration (seconds)</label>
                                <input
                                    type="number"
                                    min={1}
                                    value={clipDuration}
                                    onChange={(e) => setClipDuration(Number(e.target.value))}
                                    className={inputClass}
                                />
                            </div>
                            <input type="text" placeholder="Title" value={clipTitle} onChange={(e) => setClipTitle(e.target.value)} className={inputClass} />
                            <textarea placeholder="Description" value={clipDescription} onChange={(e) => setClipDescription(e.target.value)} rows={2} className={inputClass + " resize-none"} />
                            {clipError && <p className="text-sm text-error">{clipError}</p>}
                            <PrimaryButton onClick={handleClip} className="self-start mt-auto">Clip</PrimaryButton>
                        </Box>

                        <Box className="p-6 flex flex-col gap-3">
                            <CardHeader icon={Film} title="Save between two markers" accent="accent" />
                            {markers.length < 2 ? (
                                <p className="text-sm text-muted">Add at least two markers above to save a section between them.</p>
                            ) : (
                                <>
                                    <div className="flex items-center gap-2 flex-wrap">
                                        <span className={clsx(
                                            "text-xs font-medium px-2.5 py-1 rounded-full border",
                                            startMarker ? "border-terracotta text-terracotta bg-selected" : "border-dashed border-inactive text-muted"
                                        )}>
                                            {startMarker ? startMarker.message : "Tap a marker to set start"}
                                        </span>
                                        <span className="text-inactive-strong">→</span>
                                        <span className={clsx(
                                            "text-xs font-medium px-2.5 py-1 rounded-full border",
                                            endMarker ? "border-olive text-olive bg-selected" : "border-dashed border-inactive text-muted"
                                        )}>
                                            {endMarker ? endMarker.message : "Tap a marker to set end"}
                                        </span>
                                    </div>
                                    <input type="text" placeholder="Title" value={markerSaveTitle} onChange={(e) => setMarkerSaveTitle(e.target.value)} className={inputClass} />
                                    <textarea placeholder="Description" value={markerSaveDescription} onChange={(e) => setMarkerSaveDescription(e.target.value)} rows={2} className={inputClass + " resize-none"} />
                                    {markerSaveError && <p className="text-sm text-error">{markerSaveError}</p>}
                                </>
                            )}
                            <PrimaryButton onClick={handleSaveByMarkers} disabled={markers.length < 2} className="self-start mt-auto">Save</PrimaryButton>
                        </Box>
                    </div>

                    <Box className="p-5 flex flex-col gap-3">
                        <CardHeader icon={Activity} title="Recent actions" />
                        {jobs.length === 0 ? (
                            <p className="text-sm text-muted">Clip and save actions will show up here.</p>
                        ) : (
                            <div className="flex flex-col gap-3">
                                {jobs.map((job) => <JobStatus key={job.uuid} job={job} />)}
                            </div>
                        )}
                    </Box>
                </>
            )}
        </div>
    );
};

export default StreamControl;
