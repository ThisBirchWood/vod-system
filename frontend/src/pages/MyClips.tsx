import VideoCard from "../components/video/VideoCard";
import TimeGroupedGrid from "../components/video/TimeGroupedGrid";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { deleteClip } from "../utils/api/clips";
import { useClips } from "../hooks/useLibrary";

const MyClips = () => {
    const { data: clips = [], error: loadError, loading, mutate } = useClips();
    const [deleteError, setDeleteError] = useState<null | string>(null);
    const navigate = useNavigate();

    const error = deleteError ?? (loadError ? (loadError instanceof Error ? loadError.message : "Failed to load clips") : null);

    const handleDelete = async (id: number) => {
        try {
            await deleteClip(id);
            mutate(prev => prev.filter(c => c.id !== id));
        } catch (err) {
            setDeleteError(err instanceof Error ? err.message : "Failed to delete clip");
        }
    };

    return (
        <div className="p-6">
            <h1 className="text-4xl font-heading text-text-primary mb-6">My Clips</h1>

            <TimeGroupedGrid
                items={clips}
                getDate={(clip) => clip.createdAt}
                renderItem={(clip) => (
                    <VideoCard
                        key={clip.id}
                        id={clip.id}
                        title={clip.title}
                        duration={clip.duration}
                        createdAt={clip.createdAt}
                        onEdit={() => navigate(`/clips/${clip.id}/edit`)}
                        onDelete={() => handleDelete(clip.id)}
                    />
                )}
            />

            {clips.length === 0 && !error && !loading && (
                <div className="flex flex-col items-center justify-center py-16 text-muted">
                    <p className="text-base">No clips yet.</p>
                    <p className="text-sm mt-1">Upload your first clip to get started.</p>
                </div>
            )}

            {error && <p className="text-sm text-error mt-4">{error}</p>}
        </div>
    );
}

export default MyClips;
