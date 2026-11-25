package com.ddf.vodsystem.clip;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;

import com.ddf.vodsystem.auth.ForbiddenException;
import com.ddf.vodsystem.auth.AuthGuard;
import com.ddf.vodsystem.common.exceptions.StorageException;
import com.ddf.vodsystem.media.command.FFMPEGException;
import com.ddf.vodsystem.common.services.DirectoryService;
import com.ddf.vodsystem.media.MetadataService;
import com.ddf.vodsystem.media.ThumbnailService;
import com.ddf.vodsystem.media.VideoMetadata;
import com.ddf.vodsystem.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class ClipService {
    private static final Logger logger = LoggerFactory.getLogger(ClipService.class);

    private final ClipRepository clipRepository;
    private final DirectoryService directoryService;
    private final MetadataService metadataService;
    private final ThumbnailService thumbnailService;

    public ClipService(ClipRepository clipRepository,
                       DirectoryService directoryService,
                       MetadataService metadataService,
                       ThumbnailService thumbnailService) {
        this.clipRepository = clipRepository;
        this.directoryService = directoryService;
        this.metadataService = metadataService;
        this.thumbnailService = thumbnailService;
    }

    /**
     * Returns all clips belonging to the given user.
     *
     * @param user the owner of the clips
     * @return list of clips owned by {@code user}, in repository order
     */
    public List<Clip> getClipsByUser(User user) {
        AuthGuard.requireUser(user);
        return clipRepository.findByUser(user);
    }

    /**
     * Returns the clip with the given ID, verifying ownership by {@code user}.
     *
     * @param id   the ID of the clip to retrieve
     * @param user the user requesting the clip
     * @return the matching {@link Clip} entity
     * @throws ClipNotFoundException     if no clip with {@code id} exists
     * @throws ForbiddenException        if {@code user} does not own the clip
     */
    public Clip getClipById(Long id, User user) {
        AuthGuard.requireUser(user);
        Clip clip = clipRepository.findById(id)
                .orElseThrow(() -> new ClipNotFoundException("Clip with id " + id + " not found."));

        if (!user.getId().equals(clip.getUser().getId())) {
            throw new ForbiddenException("You are not authorized to access clip: " + id);
        }

        return clip;
    }

    /**
     * Applies a partial update to an existing clip's metadata.
     * <p>
     * Only non-null fields in {@code newFields} are written; null fields are left unchanged.
     *
     * @param id        the ID of the clip to update
     * @param newFields fields to overwrite; any null field is ignored
     * @param user      the user requesting the update
     * @return the updated clip as persisted in the database
     * @throws ClipNotFoundException     if no clip with {@code id} exists
     * @throws ForbiddenException        if {@code user} does not own the clip
     */
    public Clip updateClip(Long id, ClipUpdateRequestDTO newFields, User user) {
        Clip clip = getClipById(id, user);

        if (newFields.title() != null) {
            clip.setTitle(newFields.title());
        }

        if (newFields.description() != null) {
            clip.setDescription(newFields.description());
        }

        return clipRepository.saveAndFlush(clip);
    }

    /**
     * Deletes a clip and its associated files from disk.
     *
     * @param id   the ID of the clip to delete
     * @param user the user requesting the deletion
     * @throws ClipNotFoundException     if no clip with {@code id} exists
     * @throws ForbiddenException        if {@code user} does not own the clip
     */
    public void deleteClip(Long id, User user) {
        Clip clip = getClipById(id, user);
        deleteClipFiles(clip);
        clipRepository.delete(clip);

        logger.info("Clip with ID {} deleted successfully", id);
    }

    /**
     * Returns a streamable resource for the clip's video file.
     *
     * @param id   the ID of the clip to download
     * @param user the user requesting the download
     * @return a {@link Resource} pointing to the clip's video file on disk
     * @throws ClipNotFoundException     if no clip with {@code id} exists, or the video file is missing on disk
     * @throws ForbiddenException        if {@code user} does not own the clip
     */
    public Resource downloadClip(Long id, User user) {
        Clip clip = getClipById(id, user);

        String path = clip.getVideoPath();
        Path file = directoryService.resolvePath(path);

        if (!Files.exists(file)) {
            throw new ClipNotFoundException("Clip file not found");
        }

        if (!Files.isReadable(file)) {
            throw new ClipMediaUnavailableException(id, "Clip file is not readable");
        }

        return new FileSystemResource(file);
    }

    /**
     * Returns a streamable resource for the clip's thumbnail image.
     *
     * @param id   the ID of the clip whose thumbnail to download
     * @param user the user requesting the thumbnail
     * @return a {@link Resource} pointing to the thumbnail file on disk
     * @throws ClipNotFoundException     if no clip with {@code id} exists, or the thumbnail file is missing on disk
     * @throws ForbiddenException        if {@code user} does not own the clip
     */
    public Resource downloadThumbnail(Long id, User user) {
        Clip clip = getClipById(id, user);

        String path = clip.getThumbnailPath();
        Path file = directoryService.resolvePath(path);

        if (!Files.exists(file)) {
            throw new ClipNotFoundException("Thumbnail file not found");
        }

        return new FileSystemResource(file);
    }

    /**
     * Copies a processed clip file into permanent storage, generates its thumbnail, and saves
     * the clip record to the database.
     * <p>
     * Thumbnail generation failure is non-fatal — the error is logged and the clip is saved
     * without a thumbnail rather than aborting.
     *
     * @param title       title to assign to the clip
     * @param description description to assign to the clip
     * @param user        owner of the clip
     * @param clipFile    path to the processed video file (typically in the temp output directory)
     * @param fileName    filename used for both the permanent video file and the thumbnail
     *                    (thumbnail is stored as {@code fileName + ".png"})
     * @throws StorageException  if copying the file to the clips directory fails
     * @throws FFMPEGException   if reading video metadata from the copied file fails
     */
    public void persistClip(String title,
                            String description,
                            User user,
                            Path clipFile,
                            String fileName) {
        Path newClipFile;
        Path thumbnailFile;

        // Move temp file from temp dir to output dir
        try {
            newClipFile = directoryService.getClipsDir(user.getId()).resolve(fileName);
            thumbnailFile = directoryService.getThumbnailsDir(user.getId()).resolve(fileName + ".png");
            directoryService.copyFile(clipFile, newClipFile);
        } catch (IOException e) {
            throw new StorageException("Failed to move clip from temporary directory to output directory", e);
        }

        VideoMetadata clipMetadata;
        try {
            clipMetadata = metadataService.getVideoMetadata(newClipFile).get();
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new FFMPEGException("Error retrieving video metadata for clip: " + e.getMessage());
        }

        // Thumbnail generation can fail with error propagation
        thumbnailService.createThumbnail(newClipFile, thumbnailFile, 0.0f)
                .exceptionally(ex -> {
                    logger.error("Thumbnail job for user {} failed: {}", user.getId(), ex.toString());
                    return null;
                });

        Clip clip = saveClip(clipMetadata, title, description, user, newClipFile, thumbnailFile);
        logger.info("Clip created successfully with ID: {}", clip.getId());
    }

    private Clip saveClip(VideoMetadata metadata,
                          String title,
                          String description,
                          User user,
                          Path videoPath,
                          Path thumbnailPath) {
        Clip clip = new Clip();
        clip.setUser(user);
        clip.setTitle(title != null ? title : "Untitled Clip");
        clip.setDescription(description != null ? description : "");
        clip.setCreatedAt(Instant.now());
        clip.setWidth(metadata.width());
        clip.setHeight(metadata.height());
        clip.setFps(metadata.fps());
        clip.setDuration(metadata.duration());
        clip.setFileSize(metadata.fileSizeBytes());
        clip.setVideoPath(directoryService.relativisePath(videoPath.toAbsolutePath()).toString());
        clip.setThumbnailPath(directoryService.relativisePath(thumbnailPath.toAbsolutePath()).toString());
        return clipRepository.save(clip);
    }

    private void deleteClipFiles(Clip clip) {
        // Stored paths are relative to the users media root, not the working directory
        Path clipFile = directoryService.resolvePath(clip.getVideoPath());
        Path thumbnailFile = directoryService.resolvePath(clip.getThumbnailPath());

        try {
            Files.deleteIfExists(clipFile);
            Files.deleteIfExists(thumbnailFile);
        } catch (IOException e) {
            logger.error("Could not delete clip files for clip ID: {}", clip.getId());
        }
    }
}
