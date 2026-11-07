import { useEffect, useState } from "react";
import { useAuth } from "../auth/useAuth.ts";
import { getCurrentStream, getStreamHistory } from "../utils/api/stream.ts";
import type { StreamHistoryItem, StreamStatus } from "../utils/types.ts";

/**
 * Polls the signed-in user's live stream status, and keeps their stream
 * history alongside it so callers can show when the current (or last) stream
 * started without a second round of requests.
 */
const useCurrentStream = (pollMs = 5000) => {
    const { user } = useAuth();
    const [status, setStatus] = useState<StreamStatus | null>(null);
    const [history, setHistory] = useState<StreamHistoryItem[]>([]);

    useEffect(() => {
        if (!user) return;
        const poll = () => getCurrentStream().then(setStatus).catch(() => {});
        poll();
        const interval = setInterval(poll, pollMs);
        return () => clearInterval(interval);
    }, [user, pollMs]);

    // Re-fetched whenever a stream starts or ends, so a new stream's start date
    // shows up and a finished one gets its end date.
    const streamId = status?.id ?? null;
    useEffect(() => {
        if (!user) {
            setHistory([]);
            return;
        }
        getStreamHistory(user.id).then(setHistory).catch(() => {});
    }, [user, streamId]);

    const isStreaming = status?.isStreaming ?? false;
    const details = streamId === null ? null : history.find((s) => s.id === streamId) ?? null;

    return { status, isStreaming, details, history };
};

export default useCurrentStream;
