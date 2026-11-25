package com.ddf.vodsystem.job;

import lombok.Data;

import java.nio.file.Path;
import java.time.Instant;

@Data
public class Job {
    private String uuid;
    private ProgressTracker progressTracker = new ProgressTracker();
    private JobState state = JobState.READY;
    private String errorOutput;

    private Path download;
    private Instant createdAt = Instant.now();

    public Job(String uuid) {
        this.uuid = uuid;
    }
}
