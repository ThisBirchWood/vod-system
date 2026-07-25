package com.ddf.vodsystem.services;

import com.ddf.vodsystem.dto.ClipOptions;
import com.ddf.vodsystem.controllers.dto.ClipUpdateRequest;
import com.ddf.vodsystem.entities.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import com.ddf.vodsystem.exceptions.*;
import com.ddf.vodsystem.repositories.ClipRepository;
import com.ddf.vodsystem.services.media.MetadataService;
import com.ddf.vodsystem.services.media.ThumbnailService;
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
    private final UserService userService;

    public ClipService(ClipRepository clipRepository,
                       DirectoryService directoryService,
                       MetadataService metadataService,
                       ThumbnailService thumbnailService,
                       UserService userService) {
        this.clipRepository = clipRepository;
        this.directoryService = directoryService;
        this.metadataService = metadataService;
        this.thumbnailService = thumbnailService;
        this.userService = userService;
    }

    /**
     * Returns all clips belonging to the currently authenticated user.
     *
     * @return list of clips owned by the current user, in repository order
     * @throws NotAuthenticated if no user session is present
     */
    public List<Clip> getClipsByUser() {
        Optional<User> user = userService.getLoggedInUser();

        if (user.isEmpty()) {
            throw new NotAuthenticated("User is not authenticated");
        }

        return clipRepository.findByUser(user.get());
    }

    /**
     * Returns the clip with the given ID, verifying ownership by the current user.
     *
     * @param id the ID of the clip to retrieve
     * @return the matching {@link Clip} entity
     * @throws ClipNotFound     if no clip with {@code id} exists
     * @throws NotAuthenticated if the current user does not own the clip
     */
    public Clip getClipById(Long id) {
        Optional<Clip> clip = clipRepository.findById(id);

        if (clip.isEmpty()) {
            throw new ClipNotFound("Clip with id " + id + " not found.");
        }

        if (!isAuthenticatedForClip(clip.get())) {
            throw new NotAuthenticated("You are not authorized to access clip: " + id);
        }

        return clip.get();
    }

    /**
     * Applies a partial update to an existing clip's metadata.
     * <p>
     * Only non-null fields in {@code newFields} are written; null fields are left unchanged.
     *
     * @param id        the ID of the clip to update
     * @param newFields fields to overwrite; any null field is ignored
     * @return the updated clip as persisted in the database
     * @throws ClipNotFound     if no clip with {@code id} exists
     * @throws NotAuthenticated if the current user does not own the clip
     */
    public Clip updateClip(Long id, ClipUpdateRequest newFields) {
        Clip clip = getClipById(id);

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
     * @param id the ID of the clip to delete
     * @throws ClipNotFound     if no clip with {@code id} exists
     * @throws NotAuthenticated if the current user does not own the clip
     */
    public void deleteClip(Long id) {
        Clip clip = getClipById(id);
        deleteClipFiles(clip);
        clipRepository.delete(clip);

        logger.info("Clip with ID {} deleted successfully", id);
    }

    /**
     * Returns whether the current user owns the given clip.
     *
     * @param clip the clip to check ownership of
     * @return {@code true} if the current user's ID matches the clip's owner; {@code false} if
     *         {@code clip} is null or no user session exists
     */
    public boolean isAuthenticatedForClip(Clip clip) {
        Optional<User> user = userService.getLoggedInUser();
        if (user.isEmpty() || clip == null) {
            return false;
        }
        return user.get().getId().equals(clip.getUser().getId());
    }

    /**
     * Returns a streamable resource for the clip's video file.
     *
     * @param id the ID of the clip to download
     * @return a {@link Resource} pointing to the clip's video file on disk
     * @throws ClipNotFound     if no clip with {@code id} exists, or the video file is missing on disk
     * @throws NotAuthenticated if the current user does not own the clip
     */
    public Resource downloadClip(Long id) {
        Clip clip = getClipById(id);

        String path = clip.getVideoPath();
        Path file = directoryService.resolvePath(path);

        if (!Files.exists(file)) {
            throw new ClipNotFound("Clip file not found");
        }

        if (!Files.isReadable(file)) {
            throw new ClipMediaUnavailable(id, "Clip file is not readable");
        }

        return new FileSystemResource(file);
    }

    /**
     * Returns a streamable resource for the clip's thumbnail image.
     *
     * @param id the ID of the clip whose thumbnail to download
     * @return a {@link Resource} pointing to the thumbnail file on disk
     * @throws ClipNotFound     if no clip with {@code id} exists, or the thumbnail file is missing on disk
     * @throws NotAuthenticated if the current user does not own the clip
     */
    public Resource downloadThumbnail(Long id) {
        Clip clip = getClipById(id);

        String path = clip.getThumbnailPath();
        Path file = directoryService.resolvePath(path);

        if (!Files.exists(file)) {
            throw new ClipNotFound("Thumbnail file not found");
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

        ClipOptions clipMetadata;
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

        clipMetadata.setTitle(title);
        clipMetadata.setDescription(description);

        Clip clip = saveClip(clipMetadata, user, newClipFile, thumbnailFile);
        logger.info("Clip created successfully with ID: {}", clip.getId());
    }

    private Clip saveClip(ClipOptions metadata,
                          User user,
                          Path videoPath,
                          Path thumbnailPath) {
        Clip clip = new Clip();
        clip.setUser(user);
        clip.setTitle(metadata.getTitle() != null ? metadata.getTitle() : "Untitled Clip");
        clip.setDescription(metadata.getDescription() != null ? metadata.getDescription() : "");
        clip.setCreatedAt(Instant.now());
        clip.setWidth(metadata.getWidth());
        clip.setHeight(metadata.getHeight());
        clip.setFps(metadata.getFps());
        clip.setDuration(metadata.getDuration() - metadata.getStartPoint());
        clip.setFileSize(metadata.getFileSize());
        clip.setVideoPath(directoryService.relativisePath(videoPath.toAbsolutePath()).toString());
        clip.setThumbnailPath(directoryService.relativisePath(thumbnailPath.toAbsolutePath()).toString());
        return clipRepository.save(clip);
    }

    private void deleteClipFiles(Clip clip) {
        File clipFile = new File(clip.getVideoPath());
        File thumbnailFile = new File(clip.getThumbnailPath());

        try {
            Files.deleteIfExists(clipFile.toPath());
            Files.deleteIfExists(thumbnailFile.toPath());
        } catch (IOException e) {
            logger.error("Could not delete clip files for clip ID: {}", clip.getId());
        }
    }
}
