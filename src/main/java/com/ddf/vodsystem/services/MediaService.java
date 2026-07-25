package com.ddf.vodsystem.services;

import com.ddf.vodsystem.dto.ClipOptions;
import com.ddf.vodsystem.dto.Job;
import com.ddf.vodsystem.dto.JobState;
import com.ddf.vodsystem.entities.Marker;
import com.ddf.vodsystem.entities.User;
import com.ddf.vodsystem.exceptions.MarkerNotFound;
import com.ddf.vodsystem.exceptions.NotAuthenticated;
import com.ddf.vodsystem.services.media.CompressionService;
import com.ddf.vodsystem.services.media.StreamActionsService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class MediaService {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MediaService.class);
    private final JobRegistryService jobRegistryService;
    private final DirectoryService directoryService;
    private final CompressionService compressionService;
    private final UserService userService;
    private final ClipService clipService;

    private static final float CLIP_MAX_LENGTH = 180;
    private final StreamActionsService streamActionsService;
    private final VodService vodService;
    private final MarkerService markerService;

    public MediaService(JobRegistryService jobRegistryService,
                        DirectoryService directoryService,
                        CompressionService compressionService,
                        UserService userService,
                        ClipService clipService, StreamActionsService streamActionsService, VodService vodService, MarkerService markerService) {
        this.jobRegistryService = jobRegistryService;
        this.directoryService = directoryService;
        this.compressionService = compressionService;
        this.userService = userService;
        this.clipService = clipService;
        this.streamActionsService = streamActionsService;
        this.vodService = vodService;
        this.markerService = markerService;
    }

    /**
     * Submits an asynchronous compression job for the uploaded video file.
     * <p>
     * Saves the upload to a temporary input path, then runs the compression pipeline
     * in the background according to {@code clipOptions}. If a user session exists at
     * call time, the output is also persisted as a clip when the job succeeds.
     *
     * @param file        the uploaded video file to compress
     * @param clipOptions trim window, resolution, fps, and metadata for the output clip
     * @return the {@link Job} tracking the compression; state transitions to
     *         {@link JobState#SUCCEEDED} or {@link JobState#FAILED} asynchronously
     * @throws IOException          if saving the upload to the temporary directory fails
     */
    public Job compress(MultipartFile file, ClipOptions clipOptions) throws IOException {
        Job job = jobRegistryService.generateJob();

        Optional<User> user = userService.getLoggedInUser();
        String filename = job.getUuid() + ".mp4";

        Path tempInputFile = directoryService.getTempInputDir().resolve(filename);
        Path tempOutputFile = directoryService.getTempOutputDir().resolve(filename);
        directoryService.saveMultipartFile(tempInputFile, file);

        job.setState(JobState.PROCESSING);
        compressionService.compress(
                tempInputFile,
                tempOutputFile,
                clipOptions,
                job.getProgressTracker()
        ).thenRun(() -> {
            job.setDownload(tempOutputFile);
            job.setState(JobState.SUCCEEDED);
            logger.info("Compression job {} succeeded", job.getUuid());

            // save clip if user context
            user.ifPresent(userVal ->
                clipService.persistClip(
                        clipOptions.getTitle(),
                        clipOptions.getDescription(),
                        userVal,
                        tempOutputFile,
                        filename
                )
            );
        }).exceptionally(ex -> {
            job.setState(JobState.FAILED);
            job.setErrorOutput("Compression job failed");
            logger.error("Compression job with UUID {} failed due to: {}", job.getUuid(), ex.getMessage());
            return null;
        });

        return job;
    }

    /**
     * Saves a section of the currently authenticated user's live stream between two absolute timestamps.
     * <p>
     * Locates the recorded {@code .ts} HLS segments that overlap {@code [startTime, endTime)}, computes
     * the trim offset into the first partially-overlapping segment, then delegates extraction and muxing
     * to {@link StreamActionsService#saveSection}. On success, the output is persisted as a VoD via
     * {@link VodService#persist}.
     *
     * @param startTime   inclusive start of the section to save; must be before {@code endTime}
     * @param endTime     exclusive end of the section to save
     * @param title       title for the saved VoD; defaults to the current timestamp if {@code null}
     * @param description description for the saved VoD; defaults to an empty string if {@code null}
     * @return the {@link Job} tracking the save; state transitions to
     *         {@link JobState#SUCCEEDED} or {@link JobState#FAILED} asynchronously
     * @throws IllegalArgumentException if {@code startTime} is not before {@code endTime},
     *                                  or if no stream segments exist in the given range
     * @throws NotAuthenticated         if no user is currently authenticated
     * @throws IOException              if reading the stream directory or its segments fails
     */
    public Job saveSection(
            Instant startTime,
            Instant endTime,
            String title,
            String description) throws IOException {
        if (startTime.isAfter(endTime)) {
            throw new IllegalArgumentException("Start time must be before end time");
        }

        User user = userService.getLoggedInUser()
                .orElseThrow(() -> new NotAuthenticated("User is not authenticated"));

        Job job = jobRegistryService.generateJob();
        SegmentContext ctx = resolveSegments(user, startTime, endTime);
        float duration = (endTime.toEpochMilli() - startTime.toEpochMilli()) / 1000f;
        Path outputFile = directoryService.getVodsDir(user.getId()).resolve(UUID.randomUUID() + ".mp4");

        return dispatchSaveJob(job, ctx, duration, outputFile, () ->
            vodService.persist(
                title == null ? Instant.now().toString() : title,
                description == null ? "" : description,
                user,
                outputFile,
                outputFile.getFileName().toString()
            )
        );
    }

    /**
     * Saves a section of the currently authenticated user's live stream between two markers.
     * <p>
     * Resolves {@code startMarkerId} and {@code endMarkerId} to their marker timestamps and
     * delegates to {@link #saveSection(Instant, Instant, String, String)}.
     *
     * @param startMarkerId id of the marker whose timestamp begins the section to save
     * @param endMarkerId   id of the marker whose timestamp ends the section to save
     * @param title         title for the saved VoD; defaults to the current timestamp if {@code null}
     * @param description   description for the saved VoD; defaults to an empty string if {@code null}
     * @return the {@link Job} tracking the save; state transitions to
     *         {@link JobState#SUCCEEDED} or {@link JobState#FAILED} asynchronously
     * @throws MarkerNotFound           if either marker id does not exist
     * @throws NotAuthenticated         if no user is currently authenticated, or the caller does not
     *                                  own one of the referenced markers
     * @throws IllegalArgumentException if the start marker's timestamp is not before the end
     *                                  marker's, or if no stream segments exist in the given range
     * @throws IOException              if reading the stream directory or its segments fails
     */
    public Job saveSection(
            Long startMarkerId,
            Long endMarkerId,
            String title,
            String description
    ) throws IOException {
        Marker start = markerService.getMarkerById(startMarkerId);
        Marker end = markerService.getMarkerById(endMarkerId);

        return saveSection(start.getTimestamp(), end.getTimestamp(), title, description);
    }

    /**
     * Saves the last {@code duration} seconds of the currently authenticated user's live stream as a clip.
     * <p>
     * Computes a window of {@code [now - duration, now)} with millisecond precision, locates the
     * overlapping HLS {@code .ts} segments, and delegates extraction and muxing to
     * {@link StreamActionsService#saveSection}. On success, the output is persisted as a clip via
     * {@link ClipService#persistClip}.
     *
     * @param duration    length of the clip in seconds measured back from now; must be in
     *                    {@code (0, CLIP_MAX_LENGTH]}. Fractional values are supported.
     * @param title       title for the saved clip; defaults to the current timestamp if {@code null}
     * @param description description for the saved clip; defaults to an empty string if {@code null}
     * @return the {@link Job} tracking the save; state transitions to
     *         {@link JobState#SUCCEEDED} or {@link JobState#FAILED} asynchronously
     * @throws IllegalArgumentException if {@code duration} is not in {@code (0, CLIP_MAX_LENGTH]},
     *                                  or if no stream segments exist in the computed window
     * @throws NotAuthenticated         if no user is currently authenticated
     * @throws IOException              if reading the stream directory or its segments fails
     */
    public Job clip(float duration, String title, String description) throws IOException {
        if (duration <= 0 || duration > CLIP_MAX_LENGTH) {
            throw new IllegalArgumentException("Clip length must be between 0 and " + CLIP_MAX_LENGTH + " seconds");
        }

        Instant endTime = Instant.now();
        // minusSeconds() only does integer seconds, not float
        Instant startTime = endTime.minus(Duration.ofMillis((long) (duration * 1000)));

        User user = userService.getLoggedInUser()
                .orElseThrow(() -> new NotAuthenticated("User is not authenticated"));

        Job job = jobRegistryService.generateJob();
        SegmentContext ctx = resolveSegments(user, startTime, endTime);
        Path outputFile = directoryService.getTempOutputDir().resolve(UUID.randomUUID() + ".mp4");

        return dispatchSaveJob(job, ctx, duration, outputFile, () ->
            clipService.persistClip(
                title == null ? Instant.now().toString() : title,
                description == null ? "" : description,
                user,
                outputFile,
                outputFile.getFileName().toString()
            )
        );
    }

    private record SegmentContext(List<Path> segments, float trimOffset) {}

    private SegmentContext resolveSegments(User user, Instant startTime, Instant endTime) throws IOException {
        Path streamDir = directoryService.getStreamDir(user.getStreamKey());
        List<Path> segments = streamActionsService.getSegmentsInRange(streamDir, startTime, endTime);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("No stream segments found in the given time range");
        }
        long firstMs = streamActionsService.parseTimestampMs(segments.getFirst());
        float trimOffset = Math.max(0f, (startTime.toEpochMilli() - firstMs) / 1000f);
        return new SegmentContext(segments, trimOffset);
    }

    private Job dispatchSaveJob(Job job, SegmentContext ctx, float duration, Path outputFile, Runnable onSuccess) {
        job.setState(JobState.PROCESSING);
        streamActionsService.saveSection(ctx.segments(), ctx.trimOffset(), duration, outputFile, job.getProgressTracker())
            .thenRun(() -> {
                job.setDownload(outputFile);
                job.setState(JobState.SUCCEEDED);
                logger.info("Stream save job {} succeeded", job.getUuid());
                onSuccess.run();
            }).exceptionally(ex -> {
                job.setState(JobState.FAILED);
                job.setErrorOutput(ex.getMessage());
                logger.error("Stream save job {} failed: {}", job.getUuid(), ex.getMessage());
                return null;
            });
        return job;
    }


}
