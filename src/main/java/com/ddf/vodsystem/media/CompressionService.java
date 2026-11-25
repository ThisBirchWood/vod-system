package com.ddf.vodsystem.media;

import com.ddf.vodsystem.job.ProgressTracker;
import com.ddf.vodsystem.media.command.CommandOutput;
import com.ddf.vodsystem.media.command.CommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class CompressionService {
    private static final Logger logger = LoggerFactory.getLogger(CompressionService.class);
    private final CommandRunner commandRunner;

    private static final float AUDIO_RATIO = 0.15f;
    private static final float MAX_AUDIO_BITRATE = 128f;
    private static final float BITRATE_MULTIPLIER = 0.9f;

    public CompressionService(CommandRunner commandRunner) {
        this.commandRunner = commandRunner;
    }

    /**
     * Compresses (and optionally trims/rescales) a video with ffmpeg on a background executor.
     * <p>
     * Runs asynchronously; failures complete the returned future exceptionally rather than throwing.
     *
     * @param inputFile   the source video file
     * @param outputFile  the destination for the compressed output
     * @param spec        the trim window, target resolution/fps, and size driving the ffmpeg command
     * @param progress    the tracker updated as encoding progresses
     * @return a future completing with the ffmpeg output, or exceptionally on failure
     */
    @Async("ffmpegTaskExecutor")
    public CompletableFuture<CommandOutput> compress(Path inputFile,
                                                     Path outputFile,
                                                     CompressionSpec spec,
                                                     ProgressTracker progress
    ) {
        logger.info("Compressing video from {} to {}", inputFile.toAbsolutePath(), outputFile.toAbsolutePath());

        try {
            List<String> command = buildCommand(
                    inputFile,
                    outputFile,
                    spec
            );
            CommandOutput result = commandRunner.run(command, line -> commandRunner.setProgress(line, progress, spec.duration()));
            progress.markComplete();

            return CompletableFuture.completedFuture(result);
        } catch (IOException e) {
            logger.error("IO error on compress call: {}", e.toString());
            return CompletableFuture.failedFuture(e);
        } catch (InterruptedException e) {
            logger.error("Thread error on compress call: {}", e.toString());
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        }
    }

    private List<String> buildFilters(Float fps, Integer width, Integer height) {
        List<String> command = new ArrayList<>();
        command.add("-vf");

        List<String> filters = new ArrayList<>();

        if (fps != null) {
            logger.info("Frame rate set to {}", fps);
            filters.add("fps=" + fps);
        }

        if (!(width == null && height == null)) {
            logger.info("Scaling video to width: {}, height: {}", width, height);
            filters.add("scale=" + scaleDimension(width) + ":" + scaleDimension(height));
        } else {
            // Keep native size, but libx264 still rejects odd dimensions
            filters.add("scale=trunc(iw/2)*2:trunc(ih/2)*2");
        }

        logger.info("Adding video filters");
        command.add(String.join(",", filters));
        return command;
    }

    // libx264 requires even dimensions
    private static String scaleDimension(Integer value) {
        if (value == null) {
            return "-2";
        }
        return Integer.toString(Math.max(2, value - (value % 2)));
    }

    private List<String> buildBitrate(Float length, Long fileSize) {
        List<String> command = new ArrayList<>();

        float bitrate = ((fileSize * 8) / length) * BITRATE_MULTIPLIER;
        float audioBitrate = bitrate * AUDIO_RATIO;
        float videoBitrate;

        if (audioBitrate > MAX_AUDIO_BITRATE) {
            audioBitrate = MAX_AUDIO_BITRATE;
            videoBitrate = bitrate - MAX_AUDIO_BITRATE;
        } else {
            videoBitrate = bitrate * (1 - AUDIO_RATIO);
        }

        command.add("-b:v");
        command.add(videoBitrate + "k");
        command.add("-b:a");
        command.add(audioBitrate + "k");

        return command;
    }

    private List<String> buildInputs(Path inputFile, Float startPoint, Float length) {
        List<String> command = new ArrayList<>();

        command.add("-ss");
        command.add(startPoint.toString());

        command.add("-i");
        command.add(inputFile.toAbsolutePath().toString());

        command.add("-t");
        command.add(Float.toString(length));

        return command;
    }

    protected List<String> buildCommand(Path inputFile, Path outputFile, CompressionSpec spec) {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-progress");
        command.add("pipe:1");
        command.add("-y");

        command.addAll(buildInputs(inputFile, spec.startPoint(), spec.duration()));

        command.addAll(buildFilters(spec.fps(), spec.width(), spec.height()));

        if (spec.fileSize() != null) {
            command.addAll(buildBitrate(spec.duration(), spec.fileSize()));
        }

        // Output file
        command.add(outputFile.toAbsolutePath().toString());
        return command;
    }
}
