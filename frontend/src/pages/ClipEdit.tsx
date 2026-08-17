import { useLocation } from 'react-router-dom';
import { useEffect, useRef, useState } from "react";
import Playbar from "./../components/video/Playbar";
import PlaybackSlider from "./../components/video/PlaybackSlider";
import ClipRangeSlider from "./../components/video/ClipRangeSlider";
import ConfigBox from "../components/video/ConfigBox.tsx";
import ExportWidget from "../components/video/ExportWidget.tsx";
import { compress } from "../utils/api/media"
import { getJob } from "../utils/api/jobs"
import type { VideoMetadata } from "../utils/types.ts";
import Box from "../components/Box.tsx";
import MetadataBox from "../components/video/MetadataBox.tsx";
import {config} from "../config.ts";

const API_URL = config.apiUrl;

const ClipEdit = () => {
    const location = useLocation();
    const localFile: File = location.state?.file;

    const videoRef = useRef<HTMLVideoElement | null>(null);
    const [videoUrl, setVideoUrl] = useState<string>('');
    const [uploadedId, setUploadedId] = useState<string | null>(null);

    const [metadata, setMetadata] = useState<VideoMetadata | null>(null);
    const [playbackValue, setPlaybackValue] = useState(0);
    const [outputMetadata, setOutputMetadata] = useState<VideoMetadata>({
        title: "",
        description: "",
        startPoint: 0,
        duration: 5,
        width: 1280,
        height: 720,
        fps: 30,
        fileSize: 10000
    });
    const [progress, setProgress] = useState(0);
    const [downloadable, setDownloadable] = useState(false);
    const [uploading, setUploading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!localFile) return;
        const url = URL.createObjectURL(localFile);
        setVideoUrl(url);
        return () => URL.revokeObjectURL(url);
    }, [localFile]);

    const handleVideoMetadataLoaded = () => {
        const video = videoRef.current;
        if (!video) return;
        setMetadata({
            title: '',
            description: '',
            startPoint: 0,
            duration: video.duration,
            width: video.videoWidth,
            height: video.videoHeight,
            fps: 30,
            fileSize: localFile?.size ?? 0,
        });
    };

    const sendData = async () => {
        if (!localFile || uploading) return;

        setUploading(true);
        setError(null);

        let jobId: string;
        try {
            jobId = await compress(localFile, outputMetadata);
            setUploadedId(jobId);
        } catch (err: unknown) {
            setError(`Failed to start compression: ${err instanceof Error ? err.message : 'Unknown error'}`);
            setUploading(false);
            return;
        }

        const interval = setInterval(async () => await pollProgress(jobId, interval), 500);
    };

    const pollProgress = async (jobId: string, intervalId: ReturnType<typeof setInterval>) => {
        getJob(jobId)
            .then((job) => {
                setProgress(job.progress);

                if (job.state === 'FAILED') {
                    setError(`Compression failed: ${job.errorOutput ?? 'Unknown error'}`);
                    clearInterval(intervalId);
                    setUploading(false);
                } else if (job.isComplete) {
                    clearInterval(intervalId);
                    setDownloadable(true);
                    setUploading(false);
                }
            })
            .catch((err: Error) => {
                setError(`Failed to fetch progress: ${err.message}`);
                clearInterval(intervalId);
                setUploading(false);
            });
    };

    const handleDownload = async () => {
        if (!uploadedId) return;

        const response = await fetch(API_URL + `/api/v1/jobs/${uploadedId}/download`, { credentials: 'include' });

        if (!response.ok) {
            console.error('Download failed');
            return;
        }

        const blob = await response.blob();
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');

        a.href = url;
        a.download = `${uploadedId}.mp4`;
        document.body.appendChild(a);
        a.click();
        a.remove();
        window.URL.revokeObjectURL(url);
    };

    return (
        <div className={"grid grid-cols-[7fr_3fr] gap-4 p-4"}>
            <video
                ref={videoRef}
                src={videoUrl}
                className={"w-full rounded-lg shadow-sm bg-text-primary"}
                onLoadedMetadata={handleVideoMetadataLoaded}
            />

            <Box className={"w-4/5 h-full m-auto"}>
                <MetadataBox setMetadata={setOutputMetadata} />
                <ConfigBox setMetadata={setOutputMetadata} />
            </Box>

            {metadata &&
                <Box className={"mt-4 p-5"}>
                    <Playbar
                        video={videoRef.current}
                        videoMetadata={metadata}
                        className={"w-full accent-terracotta"}
                    />

                    <PlaybackSlider
                        videoRef={videoRef.current}
                        videoMetadata={metadata}
                        sliderValue={playbackValue}
                        setSliderValue={setPlaybackValue}
                        className={"w-full accent-terracotta"}
                    />

                    <ClipRangeSlider
                        videoRef={videoRef.current}
                        videoMetadata={metadata}
                        setSliderValue={setPlaybackValue}
                        setMetadata={setOutputMetadata}
                        className={"w-full mt-2"}
                    />

                    {error && (
                        <div className={"text-error text-center mt-2"}>
                            {error}
                        </div>
                    )}
                </Box>
            }

            <Box className={"flex flex-col gap-2 w-4/5 m-auto mt-4 p-5"}>
                <ExportWidget
                    dataSend={sendData}
                    handleDownload={handleDownload}
                    downloadable={downloadable}
                    progress={progress}
                    uploading={uploading}
                />
            </Box>
        </div>
    );
};

export default ClipEdit;
