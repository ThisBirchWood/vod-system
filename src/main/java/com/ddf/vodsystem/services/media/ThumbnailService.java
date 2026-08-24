package com.ddf.vodsystem.services.media;

import com.ddf.vodsystem.dto.CommandOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class ThumbnailService {
    private static final Logger logger = LoggerFactory.getLogger(ThumbnailService.class);
    private final CommandRunner commandRunner;

    public ThumbnailService(CommandRunner commandRunner) {
        this.commandRunner = commandRunner;
    }

    /**
     * Extracts a single frame from a video as a thumbnail image with ffmpeg on a background executor.
     * <p>
     * Runs asynchronously; failures complete the returned future exceptionally rather than throwing.
     *
     * @param inputFile   the source video file
     * @param outputFile  the destination for the thumbnail image
     * @param timeInVideo the timestamp in seconds at which to capture the frame
     * @return a future completing with the ffmpeg output, or exceptionally on failure
     */
    @Async("ffmpegTaskExecutor")
    public CompletableFuture<CommandOutput> createThumbnail(Path inputFile, Path outputFile, Float timeInVideo) {
        logger.info("Creating thumbnail at {} seconds", timeInVideo);

        try {
            List<String> command = List.of(
                    "ffmpeg",
                    "-ss", timeInVideo.toString(),
                    "-i", inputFile.toAbsolutePath().toString(),
                    "-frames:v", "1",
                    outputFile.toAbsolutePath().toString()
            );

            CommandOutput output = commandRunner.run(command);
            return CompletableFuture.completedFuture(output);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        }
    }
}
