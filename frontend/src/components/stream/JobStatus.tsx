import { Check, Loader2, X } from "lucide-react";
import type { TrackedJob } from "../../hooks/useJobTracker.ts";

const JobStatus = ({ job }: { job: TrackedJob }) => {
    if (job.state === 'FAILED') {
        return (
            <div className="flex items-start gap-2 text-sm">
                <X size={15} className="text-error mt-0.5 flex-shrink-0" />
                <p className="text-error">{job.label} failed: {job.errorOutput ?? "Unknown error"}</p>
            </div>
        );
    }
    if (job.state === 'SUCCEEDED') {
        return (
            <div className="flex items-center gap-2 text-sm">
                <Check size={15} className="text-olive flex-shrink-0" />
                <p className="text-text-body">{job.label} saved</p>
            </div>
        );
    }
    return (
        <div className="flex items-center gap-2">
            <Loader2 size={15} className="text-terracotta animate-spin flex-shrink-0" />
            <div className="flex-1">
                <p className="text-sm text-text-secondary mb-1">{job.label}…</p>
                <progress value={job.progress} className="w-full h-1.5 rounded bg-fields" />
            </div>
        </div>
    );
};

export default JobStatus;
