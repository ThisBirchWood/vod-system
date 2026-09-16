import { useEffect, useState } from "react";
import { Radio, Check, Scissors, Film, Activity, Loader2, X } from "lucide-react";
import clsx from "clsx";
import Box from "../components/Box.tsx";
import CardHeader from "../components/CardHeader.tsx";
import MarkerList from "../components/stream/MarkerList.tsx";
import PrimaryButton from "../components/buttons/PrimaryButton.tsx";
import { useAuth } from "../auth/useAuth.ts";
import { getCurrentStream, getStreamHistory } from "../utils/api/stream.ts";
import { getMarkers, createMarker, deleteMarker } from "../utils/api/markers.ts";
import { saveSectionByMarkers, clipSection } from "../utils/api/media.ts";
import { getJob } from "../utils/api/jobs.ts";
import type { StreamStatus, StreamHistoryItem, Marker, JobResponse } from "../utils/types.ts";
import { formatLocalDate, stringToDate } from "../utils/utils.ts";

const inputClass = "border border-hairline bg-fields rounded-md w-full p-2 text-sm focus:outline-none focus:ring-2 focus:ring-muted transition-colors";
const labelClass = "font-data text-[11px] text-muted uppercase tracking-[0.12em]";

type TrackedJob = {
    uuid: string;
    label: string;
    progress: number;
    state: JobResponse['state'];
    errorOutput: string | null;
};

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
    const { user } = useAuth();
    const [streamStatus, setStreamStatus] = useState<StreamStatus | null>(null);
    const [streamDetails, setStreamDetails] = useState<StreamHistoryItem | null>(null);

    const [markers, setMarkers] = useState<Marker[]>([]);
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

    const refreshMarkers = () => {
        getMarkers()
            .then((data) => setMarkers(data
                .sort((a, b) => stringToDate(b.timestamp).getTime() - stringToDate(a.timestamp).getTime())))
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

    const handleAddMarker = async (message: string) => {
        setMarkerError(null);
        try {
            await createMarker(message);
            refreshMarkers();
            return true;
        } catch (err) {
            setMarkerError(err instanceof Error ? err.message : "Failed to add marker");
            return false;
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
            setMarkerSaveError("Pick a start and an end marker from the timeline");
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

    const handleSelectMarker = (id: number) => {
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

                <Radio size={28} className={isStreaming ? "text-live" : "text-inactive"} />
            </Box>

            {isStreaming && (
                <div className="grid grid-cols-1 lg:grid-cols-[minmax(0,1fr)_20rem] gap-5 items-start">
                    {/* Marker timeline. First on narrow screens, where marking
                        something as it happens beats the action forms for urgency. */}
                    <MarkerList
                        className="lg:order-last lg:sticky lg:top-6"
                        markers={markers}
                        startMarkerId={startMarkerId}
                        endMarkerId={endMarkerId}
                        error={markerError}
                        onAdd={handleAddMarker}
                        onSelect={handleSelectMarker}
                        onDelete={handleDeleteMarker}
                    />

                    {/* Forefront actions */}
                    <div className="flex flex-col gap-5">
                        <Box className="p-6 flex flex-col gap-3">
                            <CardHeader icon={Film} title="Save between two markers" accent="accent" />
                            {markers.length < 2 ? (
                                <p className="text-sm text-muted">Add at least two markers to save a section between them.</p>
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
                    </div>
                </div>
            )}
        </div>
    );
};

export default StreamControl;
