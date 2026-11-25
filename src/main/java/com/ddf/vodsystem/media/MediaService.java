package com.ddf.vodsystem.media;

import com.ddf.vodsystem.clip.ClipService;
import com.ddf.vodsystem.job.Job;
import com.ddf.vodsystem.job.JobState;
import com.ddf.vodsystem.marker.Marker;
import com.ddf.vodsystem.common.services.DirectoryService;
import com.ddf.vodsystem.job.JobRegistryService;
import com.ddf.vodsystem.user.User;
import com.ddf.vodsystem.auth.AuthGuard;
import com.ddf.vodsystem.marker.MarkerNotFoundException;
import com.ddf.vodsystem.marker.MarkerService;
import com.ddf.vodsystem.vod.VodService;
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
    private final ClipService clipService;

    private static final float CLIP_MAX_LENGTH = 180;
    private final StreamActionsService streamActionsService;
    private final VodService vodService;
    private final MarkerService markerService;

    public MediaService(JobRegistryService jobRegistryService,
                        DirectoryService directoryService,
                        CompressionService compressionService,
                        ClipService clipService, StreamActionsService streamActionsService, VodService vodService, MarkerService markerService) {
        this.jobRegistryService = jobRegistryService;
        this.directoryService = directoryService;
        this.compressionService = compressionService;
        this.clipService = clipService;
        this.streamActionsService = streamActionsService;
        this.vodService = vodService;
        this.markerService = markerService;
    }

    /**
     * Submits an asynchronous compression job for the uploaded video file.
     * <p>
     * Saves the upload to a temporary input path, then runs the compression pipeline
     * in the background according to {@code spec}. If {@code user} is non-null,
     * the output is also persisted as a clip owned by them when the job succeeds.
     *
     * @param file        the uploaded video file to compress
     * @param spec        trim window, resolution, fps, and metadata for the output clip
     * @param user        the requesting user, or {@code null} for an anonymous request
     * @return the {@link Job} tracking the compression; state transitions to
     *         {@link JobState#SUCCEEDED} or {@link JobState#FAILED} asynchronously
     * @throws IOException          if saving the upload to the temporary directory fails
     */
    public Job compress(MultipartFile file, CompressionSpec spec, User user) throws IOException {
        Job job = jobRegistryService.generateJob();

        String filename = job.getUuid() + ".mp4";

        Path tempInputFile = directoryService.getTempInputDir().resolve(filename);
        Path tempOutputFile = directoryService.getTempOutputDir().resolve(filename);
        directoryService.saveMultipartFile(tempInputFile, file);

        job.setState(JobState.PROCESSING);
        compressionService.compress(
                tempInputFile,
                tempOutputFile,
                spec,
                job.getProgressTracker()
        ).thenRun(() -> {
            if (user != null) {
                clipService.persistClip(
                        spec.title(),
                        spec.description(),
                        user,
                        tempOutputFile,
                        filename
                );
            }

            job.setDownload(tempOutputFile);
            job.setState(JobState.SUCCEEDED);
            logger.info("Compression job {} succeeded", job.getUuid());
        }).exceptionally(ex -> {
            job.setState(JobState.FAILED);
            job.setErrorOutput("Compression job failed");
            logger.error("Compression job with UUID {} failed due to: {}", job.getUuid(), ex.getMessage());
            return null;
        });

        return job;
    }

    /**
     * Saves a section of the given user's live stream between two absolute timestamps.
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
     * @param user        the user whose stream to save from, and the owner of the resulting VoD
     * @return the {@link Job} tracking the save; state transitions to
     *         {@link JobState#SUCCEEDED} or {@link JobState#FAILED} asynchronously
     * @throws IllegalArgumentException if {@code startTime} is not before {@code endTime},
     *                                  or if no stream segments exist in the given range
     * @throws IOException              if reading the stream directory or its segments fails
     */
    public Job saveSection(
            Instant startTime,
            Instant endTime,
            String title,
            String description,
            User user) throws IOException {
        AuthGuard.requireUser(user);
        if (startTime.isAfter(endTime)) {
            throw new IllegalArgumentException("Start time must be before end time");
        }

        StreamActionsService.SegmentSelection ctx = resolveSegments(user, startTime, endTime);
        Path outputFile = directoryService.getVodsDir(user.getId()).resolve(UUID.randomUUID() + ".mp4");

        Job job = jobRegistryService.generateJob();

        return dispatchSaveJob(job, ctx, outputFile, () ->
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
     * Saves a section of the given user's live stream between two markers.
     * <p>
     * Resolves {@code startMarkerId} and {@code endMarkerId} to their marker timestamps and
     * delegates to {@link #saveSection(Instant, Instant, String, String, User)}.
     *
     * @param startMarkerId id of the marker whose timestamp begins the section to save
     * @param endMarkerId   id of the marker whose timestamp ends the section to save
     * @param title         title for the saved VoD; defaults to the current timestamp if {@code null}
     * @param description   description for the saved VoD; defaults to an empty string if {@code null}
     * @param user          the user whose stream to save from; must own both markers
     * @return the {@link Job} tracking the save; state transitions to
     *         {@link JobState#SUCCEEDED} or {@link JobState#FAILED} asynchronously
     * @throws MarkerNotFoundException           if either marker id does not exist
     * @throws com.ddf.vodsystem.auth.ForbiddenException if {@code user} does not own one of
     *                                  the referenced markers
     * @throws IllegalArgumentException if the start marker's timestamp is not before the end
     *                                  marker's, or if no stream segments exist in the given range
     * @throws IOException              if reading the stream directory or its segments fails
     */
    public Job saveSection(
            Long startMarkerId,
            Long endMarkerId,
            String title,
            String description,
            User user
    ) throws IOException {
        AuthGuard.requireUser(user);
        Marker start = markerService.getMarkerById(startMarkerId, user);
        Marker end = markerService.getMarkerById(endMarkerId, user);

        return saveSection(start.getTimestamp(), end.getTimestamp(), title, description, user);
    }

    /**
     * Saves the last {@code duration} seconds of the given user's live stream as a clip.
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
     * @param user        the user whose stream to clip, and the owner of the resulting clip
     * @return the {@link Job} tracking the save; state transitions to
     *         {@link JobState#SUCCEEDED} or {@link JobState#FAILED} asynchronously
     * @throws IllegalArgumentException if {@code duration} is not in {@code (0, CLIP_MAX_LENGTH]},
     *                                  or if no stream segments exist in the computed window
     * @throws IOException              if reading the stream directory or its segments fails
     */
    public Job clip(float duration, String title, String description, User user) throws IOException {
        AuthGuard.requireUser(user);
        if (duration <= 0 || duration > CLIP_MAX_LENGTH) {
            throw new IllegalArgumentException("Clip length must be between 0 and " + CLIP_MAX_LENGTH + " seconds");
        }

        Instant endTime = Instant.now();
        // minusSeconds() only does integer seconds, not float
        Instant startTime = endTime.minus(Duration.ofMillis((long) (duration * 1000)));

        StreamActionsService.SegmentSelection ctx = resolveSegments(user, startTime, endTime);
        Path outputFile = directoryService.getTempOutputDir().resolve(UUID.randomUUID() + ".mp4");

        Job job = jobRegistryService.generateJob();

        return dispatchSaveJob(job, ctx, outputFile, () ->
            clipService.persistClip(
                title == null ? Instant.now().toString() : title,
                description == null ? "" : description,
                user,
                outputFile,
                outputFile.getFileName().toString()
            )
        );
    }

    private StreamActionsService.SegmentSelection resolveSegments(User user, Instant startTime, Instant endTime) throws IOException {
        Path streamDir = directoryService.getStreamDir(user.getStreamKey());
        return streamActionsService.selectSegments(streamDir, startTime, endTime);
    }

    private Job dispatchSaveJob(Job job, StreamActionsService.SegmentSelection ctx, Path outputFile, Runnable onSuccess) {
        job.setState(JobState.PROCESSING);
        streamActionsService.saveSection(ctx, outputFile, job.getProgressTracker())
            .thenRun(() -> {
                onSuccess.run();
                job.setDownload(outputFile);
                job.setState(JobState.SUCCEEDED);
                logger.info("Stream save job {} succeeded", job.getUuid());
            }).exceptionally(ex -> {
                job.setState(JobState.FAILED);
                job.setErrorOutput("FFMPEG failed to save stream segments");
                logger.error("Stream save job {} failed: {}", job.getUuid(), ex.getMessage());
                return null;
            });
        return job;
    }


}
