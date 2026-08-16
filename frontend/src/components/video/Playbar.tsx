import { useEffect, useState } from "react";
import { Volume1, Volume2, VolumeX, Play, Pause } from 'lucide-react';
import clsx from 'clsx';
import type { VideoMetadata } from "../../utils/types.ts";
import { formatTime } from "../../utils/utils.ts";

type Props = {
    video: HTMLVideoElement | null;
    videoMetadata: VideoMetadata;
    className?: string;
};

export default function Playbar({ video, videoMetadata, className }: Props) {
    const [isPlaying, setIsPlaying] = useState(false);
    const [volume, setVolume] = useState(100);

    const Icon = volume === 0 ? VolumeX : volume < 50 ? Volume1 : Volume2;

    const togglePlay = () => {
        if (!video) return;
        if (video.paused) {
            video.play();
            setIsPlaying(true);
        } else {
            video.pause();
            setIsPlaying(false);
        }
    };

    const updateVolume = (e: React.ChangeEvent<HTMLInputElement>) => {
        if (!video) return;
        const newVolume = parseInt(e.target.value);
        video.volume = newVolume / 100;
        setVolume(newVolume);
    };

    // Sync isPlaying when the video is controlled outside this component (e.g. keyboard shortcuts)
    useEffect(() => {
        if (!video) return;
        const handlePlay = () => setIsPlaying(true);
        const handlePause = () => setIsPlaying(false);
        video.addEventListener("play", handlePlay);
        video.addEventListener("pause", handlePause);
        return () => {
            video.removeEventListener("play", handlePlay);
            video.removeEventListener("pause", handlePause);
        };
    }, [video]);

    return (
        <div className={clsx("flex justify-between items-center mb-2", className)}>
            <div className="flex gap-1">
                <Icon size={24} />
                <input
                    type="range"
                    min={0}
                    max={100}
                    value={volume}
                    onChange={updateVolume}
                    className="w-20"
                />
            </div>
            <button
                onClick={togglePlay}
                className="hover:bg-hover rounded-full p-1.5 transition-colors duration-150 text-text-body"
            >
                {isPlaying ? <Pause size={24} /> : <Play size={24} />}
            </button>
            <span className="font-data text-sm text-text-secondary tabular-nums">
                {formatTime(video?.currentTime ?? 0)} / {formatTime(videoMetadata.duration ?? 0)}
            </span>
        </div>
    );
}
