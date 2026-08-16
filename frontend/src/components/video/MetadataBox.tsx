import clsx from "clsx";
import { type Dispatch, type SetStateAction } from "react";
import type { VideoMetadata } from "../../utils/types.ts";

type Props = {
    setMetadata: Dispatch<SetStateAction<VideoMetadata>>;
    className?: string;
};

const MetadataBox = ({ setMetadata, className }: Props) => (
    <div className={clsx("flex flex-col content-between p-6 gap-2", className)}>
        <p className="w-full font-body text-text-strong font-bold text-sm">Title</p>
        <input
            type="text"
            placeholder="Enter title"
            onChange={(e) => setMetadata(prev => ({ ...prev, title: e.target.value }))}
            className="border border-hairline bg-fields rounded-md w-full p-2 text-sm focus:outline-none focus:ring-2 focus:ring-muted transition-colors"
        />
    </div>
);

export default MetadataBox;
