import { useCallback, useEffect, useState } from "react";
import { getJob } from "../utils/api/jobs.ts";
import type { JobResponse } from "../utils/types.ts";

type TrackedJob = {
    uuid: string;
    label: string;
    progress: number;
    state: JobResponse['state'];
    errorOutput: string | null;
};

const isSettled = (job: TrackedJob) => job.state === 'SUCCEEDED' || job.state === 'FAILED';

/** Tracks background media jobs (clips, saves), polling each until it settles. Newest first. */
const useJobTracker = () => {
    const [jobs, setJobs] = useState<TrackedJob[]>([]);

    useEffect(() => {
        const pending = jobs.filter((j) => !isSettled(j));
        if (pending.length === 0) return;

        const interval = setInterval(() => {
            pending.forEach((job) => {
                getJob(job.uuid)
                    .then((result) => {
                        setJobs((prev) => prev.map((j) => j.uuid === job.uuid
                            ? { ...j, progress: result.progress, state: result.state, errorOutput: result.errorOutput }
                            : j));
                    })
                    .catch(() => {});
            });
        }, 1000);

        return () => clearInterval(interval);
    }, [jobs]);

    const addJob = useCallback((uuid: string, label: string) => {
        setJobs((prev) => [{ uuid, label, progress: 0, state: 'READY', errorOutput: null }, ...prev]);
    }, []);

    const succeededCount = jobs.filter((j) => j.state === 'SUCCEEDED').length;

    return { jobs, addJob, succeededCount };
};

export default useJobTracker;
export type { TrackedJob };
