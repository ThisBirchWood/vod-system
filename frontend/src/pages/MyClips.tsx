import VideoCard from "../components/video/VideoCard";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getClips, deleteClip } from "../utils/api/clips";
import type { Clip } from "../utils/types";

const MyClips = () => {
    const [clips, setClips] = useState<Clip[]>([]);
    const [error, setError] = useState<null | string>(null);
    const navigate = useNavigate();

    useEffect(() => {
        getClips()
            .then((data) => setClips(data.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())))
            .catch((err) => setError(err));
    }, []);

    const handleDelete = async (id: number) => {
        try {
            await deleteClip(id);
            setClips(prev => prev.filter(c => c.id !== id));
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to delete clip");
        }
    };

    return (
        <div className="p-6">
            <h1 className="text-4xl font-heading text-text-primary mb-6">My Clips</h1>

            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
                {clips.map((clip) => (
                    <VideoCard
                        key={clip.id}
                        id={clip.id}
                        title={clip.title}
                        duration={clip.duration}
                        createdAt={clip.createdAt}
                        onEdit={() => navigate(`/clips/${clip.id}/edit`)}
                        onDelete={() => handleDelete(clip.id)}
                    />
                ))}
            </div>

            {clips.length === 0 && !error && (
                <div className="flex flex-col items-center justify-center py-16 text-muted">
                    <p className="text-base">No clips yet.</p>
                    <p className="text-sm mt-1">Upload your first clip to get started.</p>
                </div>
            )}

            {error && <p className="text-sm text-error mt-4">{error.toString()}</p>}
        </div>
    );
}

export default MyClips;
