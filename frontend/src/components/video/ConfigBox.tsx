import { type Dispatch, type SetStateAction } from "react";
import type { VideoMetadata } from "../../utils/types.ts";
import Selector from "../Selector.tsx";
import clsx from "clsx";

type Props = {
    setMetadata: Dispatch<SetStateAction<VideoMetadata>>;
    className?: string;
};

const inputClass = "border border-hairline bg-card rounded-md w-full p-2 text-sm focus:outline-none focus:ring-2 focus:ring-muted transition-colors";

export default function ConfigBox({ setMetadata, className }: Props) {
    const updateRes = (e: React.ChangeEvent<HTMLSelectElement>) => {
        const [width, height] = e.target.value.split(",").map(Number);
        setMetadata(prev => ({ ...prev, width, height }));
    };

    const updateFps = (e: React.ChangeEvent<HTMLSelectElement>) => {
        setMetadata(prev => ({ ...prev, fps: parseInt(e.target.value) }));
    };

    const updateFileSize = (e: React.ChangeEvent<HTMLInputElement>) => {
        setMetadata(prev => ({ ...prev, fileSize: parseInt(e.target.value) * 1000 }));
    };

    return (
        <div className={clsx("flex flex-col gap-3 p-6", className)}>
            <h2 className="text-sm text-text-primary font-semibold mb-1">Export Settings</h2>

            <Selector label="Resolution">
                <select id="resolution" name="resolution" defaultValue="1280,720"
                        onChange={updateRes} className={inputClass}>
                    <option value="3840,2160">2160p</option>
                    <option value="2560,1440">1440p</option>
                    <option value="1920,1080">1080p</option>
                    <option value="1280,720">720p</option>
                    <option value="854,480">480p</option>
                    <option value="640,360">360p</option>
                </select>
            </Selector>

            <Selector label="FPS">
                <select id="fps" name="fps" defaultValue="30"
                        onChange={updateFps} className={inputClass}>
                    <option value="60">60</option>
                    <option value="30">30</option>
                    <option value="15">15</option>
                </select>
            </Selector>

            <Selector label="File Size Limit (MB)">
                <input type="number" min="1" defaultValue="10"
                       onChange={updateFileSize} className={inputClass}
                />
            </Selector>
        </div>
    );
}
