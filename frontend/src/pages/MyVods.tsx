import VideoCard from "../components/video/VideoCard";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { getVods, deleteVod } from "../utils/api/vods";
import type { Vod } from "../utils/types";

const MyVods = () => {
    const [vods, setVods] = useState<Vod[]>([]);
    const [error, setError] = useState<null | string>(null);
    const navigate = useNavigate();

    useEffect(() => {
        getVods()
            .then((data) => setVods(data.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())))
            .catch((err) => setError(err instanceof Error ? err.message : "Failed to load vods"));
    }, []);

    const handleDelete = async (id: number) => {
        try {
            await deleteVod(id);
            setVods(prev => prev.filter(v => v.id !== id));
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to delete vod");
        }
    };

    return (
        <div className="p-6">
            <h1 className="text-4xl font-heading text-text-primary mb-6">My VoDs</h1>

            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
                {vods.map((vod) => (
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
                ))}
            </div>

            {vods.length === 0 && !error && (
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
