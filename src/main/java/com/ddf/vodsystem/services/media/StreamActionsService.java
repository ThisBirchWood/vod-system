package com.ddf.vodsystem.services.media;

import com.ddf.vodsystem.dto.CommandOutput;
import com.ddf.vodsystem.dto.ProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

/**
 * Extracts and saves a trimmed section of a stream from its recorded HLS
 * segments.
 * <p>
 * The usual flow is {@link #selectSegments} to resolve the segments covering a
 * time range into a {@link SegmentSelection}, then {@link #saveSection} to mux
 * that selection to a file. Both are backed by {@code ffmpeg} and run on a
 * dedicated executor so long-running encodes never block request threads.
 */
@Service
public class StreamActionsService {
    private static final Logger logger = LoggerFactory.getLogger(StreamActionsService.class);
    private final CommandRunner commandRunner;
    private final MetadataService metadataService;

    public StreamActionsService(CommandRunner commandRunner, MetadataService metadataService) {
        this.commandRunner = commandRunner;
        this.metadataService = metadataService;
    }

    /**
     * A resolved section of a stream, ready to be muxed by {@link #saveSection}.
     *
     * @param segments   the segments to concatenate, in playback order; never empty
     * @param trimOffset seconds to trim from the start of {@code segments.getFirst()}
     *                   so playback begins at the requested moment, or {@code 0}
     *                   when the first segment already starts there
     * @param duration   seconds of footage to keep, measured from the trim point.
     *                   When the requested start fell in a recording gap and the
     *                   leading segment was dropped, this is measured from the
     *                   later real start so the section still ends where requested
     */
    public record SegmentSelection(List<Path> segments, float trimOffset, float duration) {}

    /**
     * Resolves the recorded {@code .ts} segments covering {@code [startTime, endTime)}
     * into a {@link SegmentSelection} ready for {@link #saveSection}.
     * <p>
     * The trim offset into the first segment is derived from {@code startTime}. If
     * that start falls in a recording gap after the earliest overlapping segment
     * (so the segment holds no footage for it), the segment is dropped and the
     * selection instead begins at the next recorded footage.
     *
     * @param streamDirectory directory holding the stream's {@code .ts} segments
     * @param startTime       inclusive start of the requested section
     * @param endTime         exclusive end of the requested section; must be after {@code startTime}
     * @return the segments, trim offset, and duration describing the section
     * @throws IllegalArgumentException if {@code startTime} is not before {@code endTime},
     *                                  or no segment holds footage for the range
     * @throws IOException              if listing the directory or probing a segment fails
     */
    public SegmentSelection selectSegments(Path streamDirectory, Instant startTime, Instant endTime) throws IOException {
        List<Path> segments = findOverlappingSegments(streamDirectory, startTime, endTime);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("No stream segments found in the given time range");
        }

        long firstMs = parseTimestampMs(segments.getFirst());
        float trimOffset = Math.max(0f, (startTime.toEpochMilli() - firstMs) / 1000f);

        // A first segment shorter than the trim offset means the requested start
        // lands in a gap after it; drop it and begin at the next recorded footage.
        if (trimOffset > 0 && probeDurationSeconds(segments.getFirst()) < trimOffset) {
            segments.removeFirst();
            if (segments.isEmpty()) {
                throw new IllegalArgumentException("No recorded footage at the requested start time");
            }
            firstMs = parseTimestampMs(segments.getFirst());
            trimOffset = 0f;
        }

        // Measure duration from where playback actually begins so a dropped
        // leading segment shortens the section rather than overrunning endTime.
        long effectiveStartMs = firstMs + (long) (trimOffset * 1000);
        float duration = (endTime.toEpochMilli() - effectiveStartMs) / 1000f;

        return new SegmentSelection(segments, trimOffset, duration);
    }

    /**
     * Muxes a {@link SegmentSelection} to {@code outputFile}, trimming the leading
     * segment to {@code selection.trimOffset()} and keeping {@code selection.duration()}
     * seconds of footage.
     * <p>
     * Runs asynchronously; failures (process-launch errors, ffmpeg failures,
     * interruption) complete the returned future exceptionally rather than being
     * thrown, so callers should use {@link CompletableFuture#exceptionally} or
     * {@link CompletableFuture#handle} rather than try/catch.
     *
     * @param selection       the segments, trim offset, and duration to write
     *                        (see {@link #selectSegments})
     * @param outputFile      destination for the muxed section; overwritten if present
     * @param progressTracker receives progress updates as ffmpeg runs
     * @return a future completing with the ffmpeg output, or exceptionally on failure
     */
    @Async("ffmpegTaskExecutor")
    public CompletableFuture<CommandOutput> saveSection(
            SegmentSelection selection,
            Path outputFile,
            ProgressTracker progressTracker
    ) {
        List<Path> segments = selection.segments();
        float trimOffset = selection.trimOffset();
        float duration = selection.duration();

        if (segments.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Selection must contain at least one segment"));
        }
        if (trimOffset < 0 || duration <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "trimOffset must be >= 0 and duration must be > 0 (got " + trimOffset + ", " + duration + ")"));
        }

        Path headFragment = null;
        Path concatList = null;
        try {
            // Only the first segment is trimmed, so re-encode just that fragment and
            // stream-copy the rest. A zero offset means the first segment already
            // starts at the requested point, so re-encoding it would be wasted work.
            List<Path> fragments;
            if (trimOffset > 0) {
                headFragment = Files.createTempFile("reencode-section-", ".ts");
                encodeHead(segments.getFirst(), trimOffset, headFragment);

                fragments = new ArrayList<>();
                fragments.add(headFragment);
                fragments.addAll(segments.subList(1, segments.size()));
            } else {
                fragments = segments;
            }

            // The concat demuxer (unlike the concat: byte protocol) rebases each
            // fragment's timestamps to run continuously across the joins, absorbing
            // the discontinuity between the PTS-0 head and the copied fragments.
            concatList = writeConcatList(fragments);

            List<String> command = List.of(
                    "ffmpeg",
                    "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", concatList.toAbsolutePath().toString(),
                    "-t", String.valueOf(duration),
                    "-c", "copy",
                    outputFile.toAbsolutePath().toString()
            );

            logger.info("Saving section ({} segments) to '{}'", segments.size(), outputFile);
            CommandOutput output = commandRunner.run(command, line -> commandRunner.setProgress(line, progressTracker, duration));

            progressTracker.markComplete();
            return CompletableFuture.completedFuture(output);
        } catch (IOException e) {
            logger.error("IO error on saveSection call: {}", e.toString());
            return CompletableFuture.failedFuture(e);
        } catch (InterruptedException e) {
            logger.error("Thread error on saveSection call: {}", e.toString());
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        } catch (RuntimeException e) {
            logger.error("Failed to save section to '{}': {}", outputFile, e.toString());
            return CompletableFuture.failedFuture(e);
        } finally {
            deleteTempQuietly(headFragment);
            deleteTempQuietly(concatList);
        }
    }

    /**
     * Parses the start timestamp encoded in an HLS segment's filename (e.g.
     * {@code 1712345678.ts}). nginx names fragments in whole seconds, so
     * second-scale values are scaled up to milliseconds.
     *
     * @param path a {@code .ts} segment path
     * @return the segment's start time in epoch milliseconds
     * @throws IllegalArgumentException if {@code path} is not a {@code .ts} file
     * @throws NumberFormatException    if the filename stem is not numeric
     */
    public long parseTimestampMs(Path path) {
        String fullName = path.getFileName().toString();

        if (!fullName.endsWith(".ts")) {
            throw new IllegalArgumentException("File must end in ts");
        }

        String name = fullName.substring(0, fullName.length() - 3);
        long value = Long.parseLong(name);
        return value < 1_000_000_000_000L ? value * 1000L : value;
    }

    /**
     * Lists the {@code .ts} segments whose playback interval overlaps
     * {@code [startTime, endTime)}, in playback order. A segment spans from its
     * own timestamp up to the next segment's timestamp (or open-ended for the last).
     */
    private List<Path> findOverlappingSegments(Path streamDirectory, Instant startTime, Instant endTime) throws IOException {
        long startMs = startTime.toEpochMilli();
        long endMs = endTime.toEpochMilli();

        if (endMs <= startMs) {
            throw new IllegalArgumentException("Start time must be before end time");
        }

        List<Path> overlapping = new ArrayList<>();

        try (Stream<Path> files = Files.list(streamDirectory)) {
            List<Path> streamFiles = files
                    .filter(p -> p.getFileName().toString().endsWith(".ts"))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong(this::parseTimestampMs))
                    .toList();

            for (int i = 0; i < streamFiles.size(); i++) {
                Path streamFile = streamFiles.get(i);

                long segmentStart = parseTimestampMs(streamFile);
                long segmentEnd = (i + 1 < streamFiles.size())
                        ? parseTimestampMs(streamFiles.get(i + 1))
                        : Long.MAX_VALUE;

                if (segmentStart < endMs && segmentEnd > startMs) {
                    overlapping.add(streamFile);
                }
            }
        }

        return overlapping;
    }

    private float probeDurationSeconds(Path segment) throws IOException {
        try {
            return metadataService.getVideoMetadata(segment).get().getDuration();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while probing segment duration: " + segment, e);
        } catch (ExecutionException e) {
            throw new IOException("Failed to probe segment duration: " + segment, e);
        }
    }

    private void encodeHead(Path firstSegment, float trimOffset, Path head)
            throws IOException, InterruptedException {

        List<String> command = List.of(
                "ffmpeg",
                "-y",
                "-i", firstSegment.toAbsolutePath().toString(),
                "-ss", String.valueOf(trimOffset),
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "20",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-b:a", "160k",
                "-muxdelay", "0",
                "-muxpreload", "0",
                "-f", "mpegts",
                head.toAbsolutePath().toString()
        );

        logger.debug("Re-encoding head fragment '{}' from {}s", firstSegment, trimOffset);
        commandRunner.run(command, line -> { /* head pass is short; no progress reporting */ });

        if (!Files.exists(head) || Files.size(head) == 0) {
            throw new IOException("Head re-encode produced no output — trimOffset " + trimOffset
                    + "s may exceed the duration of " + firstSegment);
        }
    }

    private Path writeConcatList(List<Path> fragments) throws IOException {
        Path listFile = Files.createTempFile("concat-list-", ".txt");
        List<String> lines = fragments.stream()
                .map(p -> "file '" + p.toAbsolutePath().toString().replace("'", "'\\''") + "'")
                .toList();
        Files.write(listFile, lines);
        return listFile;
    }

    private void deleteTempQuietly(Path tempFile) {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                logger.warn("Failed to delete temp file '{}': {}", tempFile, e.toString());
            }
        }
    }
}
