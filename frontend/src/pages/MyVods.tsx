import VideoCard from "../components/video/VideoCard";
import TimeGroupedGrid from "../components/video/TimeGroupedGrid";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { deleteVod } from "../utils/api/vods";
import { useVods } from "../hooks/useLibrary";

const MyVods = () => {
    const { data: vods = [], error: loadError, loading, mutate } = useVods();
    const [deleteError, setDeleteError] = useState<null | string>(null);
    const navigate = useNavigate();

    const error = deleteError ?? (loadError ? (loadError instanceof Error ? loadError.message : "Failed to load vods") : null);

    const handleDelete = async (id: number) => {
        try {
            await deleteVod(id);
            mutate(prev => prev.filter(v => v.id !== id));
        } catch (err) {
            setDeleteError(err instanceof Error ? err.message : "Failed to delete vod");
        }
    };

    return (
        <div className="p-6">
            <h1 className="text-4xl font-heading text-text-primary mb-6">My VoDs</h1>

            <TimeGroupedGrid
                items={vods}
                getDate={(vod) => vod.createdAt}
                renderItem={(vod) => (
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
                        onDelete={() => handleDelete(vod.id)}
                    />
                )}
            />

            {vods.length === 0 && !error && !loading && (
                <div className="flex flex-col items-center justify-center py-16 text-muted">
                    <p className="text-base">No VoDs yet.</p>
                    <p className="text-sm mt-1">Your stream recordings will appear here.</p>
                </div>
            )}

            {error && <p className="text-sm text-error mt-4">{error}</p>}
        </div>
    );
};

export default MyVods;
