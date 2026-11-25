package com.ddf.vodsystem.vod;

import com.ddf.vodsystem.auth.ForbiddenException;
import com.ddf.vodsystem.auth.AuthGuard;
import com.ddf.vodsystem.media.VideoMetadata;
import com.ddf.vodsystem.common.exceptions.StorageException;
import com.ddf.vodsystem.media.command.FFMPEGException;
import com.ddf.vodsystem.common.services.DirectoryService;
import com.ddf.vodsystem.user.User;
import com.ddf.vodsystem.media.MetadataService;
import com.ddf.vodsystem.media.ThumbnailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;

@Service
public class VodService {
    private static final Logger logger = LoggerFactory.getLogger(VodService.class);

    private final VodRepository vodRepository;
    private final DirectoryService directoryService;
    private final MetadataService metadataService;
    private final ThumbnailService thumbnailService;

    public VodService(VodRepository vodRepository,
                      DirectoryService directoryService,
                      MetadataService metadataService,
                      ThumbnailService thumbnailService) {
        this.vodRepository = vodRepository;
        this.directoryService = directoryService;
        this.metadataService = metadataService;
        this.thumbnailService = thumbnailService;
    }

    /**
     * Returns the VoD with the given ID, verifying ownership by {@code user}.
     *
     * @param id   the ID of the VoD to retrieve
     * @param user the user requesting the VoD
     * @return the matching {@link Vod} entity
     * @throws VodNotFoundException      if no VoD with {@code id} exists
     * @throws ForbiddenException        if {@code user} does not own the VoD
     */
    public Vod getVodById(Long id, User user) {
        AuthGuard.requireUser(user);
        Vod vod = vodRepository.findById(id)
                .orElseThrow(() -> new VodNotFoundException("Vod ID " + id + " does not exist"));

        if (!user.getId().equals(vod.getUser().getId())) {
            throw new ForbiddenException("You are not authorized to access this VoD");
        }

        return vod;
    }

    /**
     * Returns all VoDs belonging to the given user.
     *
     * @param user the owner of the VoDs
     * @return list of VoDs owned by {@code user}, in repository order
     */
    public List<Vod> getUserVods(User user) {
        AuthGuard.requireUser(user);
        return vodRepository.findByUser(user);
    }

    /**
     * Applies a partial update to an existing VoD's metadata.
     * <p>
     * Only non-null fields in {@code newFields} are written; null fields are left unchanged.
     *
     * @param id        the ID of the VoD to update
     * @param newFields fields to overwrite; any null field is ignored
     * @param user      the user requesting the update
     * @return the updated VoD as persisted in the database
     * @throws VodNotFoundException      if no VoD with {@code id} exists
     * @throws ForbiddenException        if {@code user} does not own the VoD
     */
    public Vod updateVod(Long id, VodUpdateRequestDTO newFields, User user) {
        Vod vod = getVodById(id, user);

        if (newFields.title() != null) {
            vod.setTitle(newFields.title());
        }

        if (newFields.description() != null) {
            vod.setDescription(newFields.description());
        }

        return vodRepository.saveAndFlush(vod);
    }

    /**
     * Deletes a VoD and its associated files from disk.
     *
     * @param id   the ID of the VoD to delete
     * @param user the user requesting the deletion
     * @throws VodNotFoundException      if no VoD with {@code id} exists
     * @throws ForbiddenException        if {@code user} does not own the VoD
     */
    public void deleteVod(Long id, User user) {
        Vod vod = getVodById(id, user);
        deleteVodFiles(vod);
        vodRepository.delete(vod);

        logger.info("Vod with ID {} deleted successfully", id);
    }

    /**
     * Returns a streamable resource for the VoD's video file.
     *
     * @param id   the ID of the VoD to download
     * @param user the user requesting the download
     * @return a {@link Resource} pointing to the VoD's video file on disk
     * @throws VodNotFoundException      if no VoD with {@code id} exists, or the video file is missing on disk
     * @throws ForbiddenException        if {@code user} does not own the VoD
     */
    public Resource downloadVod(Long id, User user) {
        Vod vod = getVodById(id, user);
        Path file = directoryService.resolvePath(vod.getVideoPath());

        if (!Files.exists(file)) {
            throw new VodNotFoundException("Vod file not found for ID: " + id);
        }

        if (!Files.isReadable(file)) {
            throw new VodMediaUnavailableException(id, "Vod file is not readable");
        }

        return new FileSystemResource(file);
    }

    /**
     * Returns a streamable resource for the VoD's thumbnail image.
     *
     * @param id   the ID of the VoD whose thumbnail to download
     * @param user the user requesting the thumbnail
     * @return a {@link Resource} pointing to the thumbnail file on disk
     * @throws VodNotFoundException      if no VoD with {@code id} exists, or the thumbnail file is missing on disk
     * @throws ForbiddenException        if {@code user} does not own the VoD
     */
    public Resource downloadThumbnail(Long id, User user) {
        Vod vod = getVodById(id, user);
        Path file = directoryService.resolvePath(vod.getThumbnailPath());

        if (!Files.exists(file)) {
            throw new VodNotFoundException("Thumbnail file not found for vod ID: " + id);
        }

        return new FileSystemResource(file);
    }

    /**
     * Copies a processed VoD file into permanent storage, generates its thumbnail, and saves
     * the VoD record to the database.
     * <p>
     * Thumbnail generation failure is non-fatal — the error is logged and the VoD is saved
     * without a thumbnail rather than aborting.
     *
     * @param title       title to assign to the VoD
     * @param description description to assign to the VoD
     * @param user        owner of the VoD
     * @param vodFile     path to the processed video file to copy into permanent storage
     * @param fileName    filename used for both the permanent video file and the thumbnail
     *                    (thumbnail is stored as {@code fileName + ".png"})
     * @throws StorageException if copying the file to the VoDs directory fails
     * @throws FFMPEGException  if reading video metadata from the copied file fails
     */
    public void persist(String title,
                        String description,
                        User user,
                        Path vodFile,
                        String fileName) {
        Path newVodFile;
        Path thumbnailFile;

        try {
            newVodFile = directoryService.getVodsDir(user.getId()).resolve(fileName);
            thumbnailFile = directoryService.getThumbnailsDir(user.getId()).resolve(fileName + ".png");
            directoryService.copyFile(vodFile, newVodFile);
        } catch (IOException e) {
            throw new StorageException("Failed to move vod from temporary directory to output directory", e);
        }

        VideoMetadata vodMetadata;
        try {
            vodMetadata = metadataService.getVideoMetadata(newVodFile).get();
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new FFMPEGException("Error retrieving video metadata for vod: " + e.getMessage());
        }

        thumbnailService.createThumbnail(newVodFile, thumbnailFile, 0.0f)
                .exceptionally(ex -> {
                    logger.error("Thumbnail job for user {} failed: {}", user.getId(), ex.toString());
                    return null;
                });

        Vod vod = saveToDb(vodMetadata, title, description, user, newVodFile, thumbnailFile);
        logger.info("Vod created successfully with ID: {}", vod.getId());
    }

    private Vod saveToDb(VideoMetadata metadata,
                         String title,
                         String description,
                         User user,
                         Path videoPath,
                         Path thumbnailPath) {
        Vod vod = new Vod();
        vod.setUser(user);
        vod.setTitle(title != null ? title : "Untitled VoD");
        vod.setDescription(description != null ? description : "");
        vod.setCreatedAt(Instant.now());
        vod.setWidth(metadata.width());
        vod.setHeight(metadata.height());
        vod.setFps(metadata.fps());
        vod.setDuration(metadata.duration());
        vod.setFileSize(metadata.fileSizeBytes());
        vod.setVideoPath(directoryService.relativisePath(videoPath.toAbsolutePath()).toString());
        vod.setThumbnailPath(directoryService.relativisePath(thumbnailPath.toAbsolutePath()).toString());
        return vodRepository.save(vod);
    }

    private void deleteVodFiles(Vod vod) {
        // Stored paths are relative to the users media root, not the working directory
        Path vodFile = directoryService.resolvePath(vod.getVideoPath());
        Path thumbnailFile = directoryService.resolvePath(vod.getThumbnailPath());

        try {
            Files.deleteIfExists(vodFile);
            Files.deleteIfExists(thumbnailFile);
        } catch (IOException e) {
            logger.error("Could not delete vod files for vod ID: {}", vod.getId());
        }
    }
}
