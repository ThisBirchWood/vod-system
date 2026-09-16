import { useState } from "react";
import { Bookmark, Plus, X } from "lucide-react";
import clsx from "clsx";
import Box from "../Box.tsx";
import CardHeader from "../CardHeader.tsx";
import PrimaryButton from "../buttons/PrimaryButton.tsx";
import type { Marker } from "../../utils/types.ts";
import { formatLocalTime } from "../../utils/utils.ts";

const inputClass = "border border-hairline bg-fields rounded-md w-full p-2 text-sm focus:outline-none focus:ring-2 focus:ring-muted transition-colors";

type RailSegment = "none" | "plain" | "lit";

const railClass = (segment: RailSegment) => clsx(
    "w-px",
    segment === "lit" && "bg-terracotta",
    segment === "plain" && "bg-inactive-strong/60",
    segment === "none" && "bg-transparent"
);

type MarkerRowProps = {
    marker: Marker;
    role: "start" | "end" | "between" | null;
    /** Rail segments above and below the dot — "lit" inside the chosen section. */
    railAbove: RailSegment;
    railBelow: RailSegment;
    onSelect: () => void;
    onDelete: () => void;
};

const MarkerRow = ({ marker, role, railAbove, railBelow, onSelect, onDelete }: MarkerRowProps) => (
    <li className="group relative flex gap-3">
        {/* Timeline rail: a dot per marker, with the segment between the chosen
            start and end lit so the selected section reads as one run. */}
        <div className="flex w-2.5 flex-col items-center flex-shrink-0">
            <span className={clsx("h-3.5 flex-shrink-0", railClass(railAbove))} />
            <span className={clsx(
                "h-2.5 w-2.5 rounded-full border-2 flex-shrink-0 transition-colors duration-150",
                role === "start" && "border-terracotta bg-terracotta",
                role === "end" && "border-olive bg-olive",
                role === "between" && "border-terracotta bg-card",
                role === null && "border-inactive-strong bg-card"
            )} />
            <span className={clsx("flex-1", railClass(railBelow))} />
        </div>

        {/* The rail spans the full row, so the breathing room goes on the button —
            the line stays continuous while neighbouring rings no longer touch. */}
        <button
            onClick={onSelect}
            className={clsx(
                "min-w-0 flex-1 rounded-md my-0.5 py-2 pl-2.5 pr-7 text-left transition-colors duration-150",
                role === "start" && "bg-selected ring-1 ring-terracotta",
                role === "end" && "bg-selected ring-1 ring-olive",
                (role === "between" || role === null) && "hover:bg-hover"
            )}
        >
            <p className="truncate text-sm font-medium text-text-strong">{marker.message}</p>
            <p className="font-data text-[11px] text-muted tabular-nums">
                {formatLocalTime(marker.timestamp)}
                {role === "start" && <span className="font-medium text-terracotta"> · start</span>}
                {role === "end" && <span className="font-medium text-olive"> · end</span>}
            </p>
        </button>

        <button
            onClick={onDelete}
            title="Delete marker"
            aria-label={`Delete marker ${marker.message}`}
            className="absolute right-1 top-2.5 flex h-5 w-5 items-center justify-center rounded-md text-muted opacity-0 transition duration-150 hover:bg-hover hover:text-error focus-visible:opacity-100 group-hover:opacity-100"
        >
            <X size={12} />
        </button>
    </li>
);

type Props = {
    /** Newest first — the list renders them in the order given. */
    markers: Marker[];
    startMarkerId: string;
    endMarkerId: string;
    error: string | null;
    /** Resolves true once the marker exists, so the input only clears on success. */
    onAdd: (message: string) => Promise<boolean>;
    onSelect: (id: number) => void;
    onDelete: (id: number) => void;
    className?: string;
};

const MarkerList = ({ markers, startMarkerId, endMarkerId, error, onAdd, onSelect, onDelete, className }: Props) => {
    const [message, setMessage] = useState("");
    const [busy, setBusy] = useState(false);

    const handleAdd = async () => {
        if (!message.trim() || busy) return;
        setBusy(true);
        if (await onAdd(message.trim())) setMessage("");
        setBusy(false);
    };

    // Either of the two may have been picked first, so the lit run is bounded by
    // whichever indices come first/last rather than by start/end.
    const startIndex = markers.findIndex((m) => String(m.id) === startMarkerId);
    const endIndex = markers.findIndex((m) => String(m.id) === endMarkerId);
    const hasRange = startIndex !== -1 && endIndex !== -1;
    const rangeFrom = hasRange ? Math.min(startIndex, endIndex) : -1;
    const rangeTo = hasRange ? Math.max(startIndex, endIndex) : -1;
    // The rail segment sitting between marker `i` and the one after it.
    const railSegment = (i: number): RailSegment => {
        if (i < 0 || i >= markers.length - 1) return "none";
        return hasRange && i >= rangeFrom && i < rangeTo ? "lit" : "plain";
    };

    return (
        <Box className={clsx("p-5 flex flex-col gap-4", className)}>
            <div className="flex items-center justify-between gap-3">
                <CardHeader icon={Bookmark} title="Markers" accent="accent" />
                {markers.length > 0 && (
                    <span className="font-data text-[11px] text-muted tabular-nums">{markers.length}</span>
                )}
            </div>

            <div className="flex flex-col gap-2">
                <div className="flex gap-2">
                    <input
                        type="text"
                        value={message}
                        onChange={(e) => setMessage(e.target.value)}
                        onKeyDown={(e) => e.key === "Enter" && handleAdd()}
                        placeholder="What just happened?"
                        className={inputClass}
                    />
                    <PrimaryButton
                        onClick={handleAdd}
                        disabled={busy || !message.trim()}
                        className="flex items-center gap-1.5 px-3 whitespace-nowrap"
                    >
                        <Plus size={15} />
                        Add
                    </PrimaryButton>
                </div>
                {error && <p className="text-sm text-error">{error}</p>}
            </div>

            {markers.length === 0 ? (
                <p className="text-sm text-muted">
                    Nothing marked yet. Markers stack up here, newest first — pick two to save the section between them.
                </p>
            ) : (
                /* py-0.5 keeps the scroll container from clipping the selection
                   ring on the first and last rows. */
                <ul className="-mr-1 flex max-h-[26rem] flex-col overflow-y-auto py-0.5 pr-1">
                    {markers.map((m, i) => (
                        <MarkerRow
                            key={m.id}
                            marker={m}
                            role={String(m.id) === startMarkerId ? "start"
                                : String(m.id) === endMarkerId ? "end"
                                : hasRange && i > rangeFrom && i < rangeTo ? "between"
                                : null}
                            railAbove={railSegment(i - 1)}
                            railBelow={railSegment(i)}
                            onSelect={() => onSelect(m.id)}
                            onDelete={() => onDelete(m.id)}
                        />
                    ))}
                </ul>
            )}
        </Box>
    );
};

export default MarkerList;
