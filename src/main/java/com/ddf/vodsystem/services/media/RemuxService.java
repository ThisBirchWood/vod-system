package com.ddf.vodsystem.services.media;

import com.ddf.vodsystem.dto.CommandOutput;
import com.ddf.vodsystem.dto.ProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class RemuxService {
    private static final Logger logger = LoggerFactory.getLogger(RemuxService.class);
    private final CommandRunner commandRunner;

    public RemuxService(CommandRunner commandRunner) {
        this.commandRunner = commandRunner;
    }

    /**
     * Re-encodes a video to H.264/AAC in an MP4 container with ffmpeg on a background executor.
     * <p>
     * Runs asynchronously; failures complete the returned future exceptionally rather than throwing.
     *
     * @param inputFile     the source video file
     * @param outputFile    the destination for the remuxed MP4
     * @param remuxProgress the tracker updated as encoding progresses
     * @param length        the media length in seconds, used to compute progress
     * @return a future completing with the ffmpeg output, or exceptionally on failure
     */
    @Async("ffmpegTaskExecutor")
    public CompletableFuture<CommandOutput> remux(File inputFile,
                                                  File outputFile,
                                                  ProgressTracker remuxProgress,
                                                  float length
    ) {
        try {
            List<String> command = List.of(
                    "ffmpeg",
                    "-progress", "pipe:1",
                    "-y",
                    "-i", inputFile.getAbsolutePath(),
                    "-c:v", "h264",
                    "-c:a", "aac",
                    "-f", "mp4",
                    outputFile.getAbsolutePath()
            );

            return CompletableFuture.completedFuture(commandRunner.run(command, line ->
                    commandRunner.setProgress(line, remuxProgress, length)));
        } catch (IOException e) {
            logger.error("IO error on remux call: {}", e.toString());
            return CompletableFuture.failedFuture(e);
        } catch (InterruptedException e) {
            logger.error("Thread error on remux call: {}", e.toString());
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        }
    }
}
