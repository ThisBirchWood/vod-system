package com.ddf.vodsystem.services;

import com.ddf.vodsystem.controllers.dto.VodUpdateRequest;
import com.ddf.vodsystem.dto.ClipOptions;
import com.ddf.vodsystem.entities.User;
import com.ddf.vodsystem.entities.Vod;
import com.ddf.vodsystem.exceptions.*;
import com.ddf.vodsystem.repositories.VodRepository;
import com.ddf.vodsystem.services.media.MetadataService;
import com.ddf.vodsystem.services.media.ThumbnailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

@Service
public class VodService {
    private static final Logger logger = LoggerFactory.getLogger(VodService.class);

    private final VodRepository vodRepository;
    private final DirectoryService directoryService;
    private final MetadataService metadataService;
    private final ThumbnailService thumbnailService;
    private final UserService userService;

    public VodService(VodRepository vodRepository,
                      DirectoryService directoryService,
                      MetadataService metadataService,
                      ThumbnailService thumbnailService,
                      UserService userService) {
        this.vodRepository = vodRepository;
        this.directoryService = directoryService;
        this.metadataService = metadataService;
        this.thumbnailService = thumbnailService;
        this.userService = userService;
    }

    /**
     * Returns the VoD with the given ID, verifying ownership by the current user.
     *
     * @param id the ID of the VoD to retrieve
     * @return the matching {@link Vod} entity
     * @throws VodNotFound      if no VoD with {@code id} exists
     * @throws NotAuthenticated if the current user does not own the VoD
     */
    public Vod getVodById(Long id) {
        Optional<Vod> vod = vodRepository.findById(id);

        if (vod.isEmpty()) {
            throw new VodNotFound("Vod ID " + id + " does not exist");
        }

        if (!isAuthenticatedForVod(vod.get())) {
            throw new NotAuthenticated("User not authenticated");
        }

        return vod.get();
    }

    /**
     * Returns all VoDs belonging to the currently authenticated user.
     *
     * @return list of VoDs owned by the current user, in repository order
     * @throws NotAuthenticated if no user session is present
     */
    public List<Vod> getUserVods() {
        Optional<User> user = userService.getLoggedInUser();

        if (user.isEmpty()) {
            throw new NotAuthenticated("User must be logged in to retrieve vods");
        }

        return vodRepository.findByUser(user.get());
    }

    /**
     * Applies a partial update to an existing VoD's metadata.
     * <p>
     * Only non-null fields in {@code newFields} are written; null fields are left unchanged.
     *
     * @param id        the ID of the VoD to update
     * @param newFields fields to overwrite; any null field is ignored
     * @return the updated VoD as persisted in the database
     * @throws VodNotFound      if no VoD with {@code id} exists
     * @throws NotAuthenticated if the current user does not own the VoD
     */
    public Vod updateVod(Long id, VodUpdateRequest newFields) {
        Vod vod = getVodById(id);

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
     * @param id the ID of the VoD to delete
     * @throws VodNotFound      if no VoD with {@code id} exists
     * @throws NotAuthenticated if the current user does not own the VoD
     */
    public void deleteVod(Long id) {
        Vod vod = getVodById(id);
        deleteVodFiles(vod);
        vodRepository.delete(vod);

        logger.info("Vod with ID {} deleted successfully", id);
    }

    /**
     * Returns a streamable resource for the VoD's video file.
     *
     * @param id the ID of the VoD to download
     * @return a {@link Resource} pointing to the VoD's video file on disk
     * @throws VodNotFound      if no VoD with {@code id} exists, or the video file is missing on disk
     * @throws NotAuthenticated if the current user does not own the VoD
     */
    public Resource downloadVod(Long id) {
        Vod vod = getVodById(id);
        Path file = directoryService.resolvePath(vod.getVideoPath());

        if (!Files.exists(file)) {
            throw new VodNotFound("Vod file not found for ID: " + id);
        }

        if (!Files.isReadable(file)) {
            throw new VodMediaUnavailable(id, "Vod file is not readable");
        }

        return new FileSystemResource(file);
    }

    /**
     * Returns a streamable resource for the VoD's thumbnail image.
     *
     * @param id the ID of the VoD whose thumbnail to download
     * @return a {@link Resource} pointing to the thumbnail file on disk
     * @throws VodNotFound      if no VoD with {@code id} exists, or the thumbnail file is missing on disk
     * @throws NotAuthenticated if the current user does not own the VoD
     */
    public Resource downloadThumbnail(Long id) {
        Vod vod = getVodById(id);
        Path file = directoryService.resolvePath(vod.getThumbnailPath());

        if (!Files.exists(file)) {
            throw new VodNotFound("Thumbnail file not found for vod ID: " + id);
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

        ClipOptions vodMetadata;
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

        vodMetadata.setTitle(title);
        vodMetadata.setDescription(description);

        Vod vod = saveToDb(vodMetadata, user, newVodFile, thumbnailFile);
        logger.info("Vod created successfully with ID: {}", vod.getId());
    }

    private Vod saveToDb(ClipOptions options,
                         User user,
                         Path videoPath,
                         Path thumbnailPath) {
        Vod vod = new Vod();
        vod.setUser(user);
        vod.setTitle(options.getTitle() != null ? options.getTitle() : "Untitled VoD");
        vod.setDescription(options.getDescription() != null ? options.getDescription() : "");
        vod.setCreatedAt(Instant.now());
        vod.setWidth(options.getWidth());
        vod.setHeight(options.getHeight());
        vod.setFps(options.getFps());
        vod.setDuration(options.getDuration());
        vod.setFileSize(options.getFileSize());
        vod.setVideoPath(directoryService.relativisePath(videoPath.toAbsolutePath()).toString());
        vod.setThumbnailPath(directoryService.relativisePath(thumbnailPath.toAbsolutePath()).toString());
        return vodRepository.save(vod);
    }

    private void deleteVodFiles(Vod vod) {
        File vodFile = new File(vod.getVideoPath());
        File thumbnailFile = new File(vod.getThumbnailPath());

        try {
            Files.deleteIfExists(vodFile.toPath());
            Files.deleteIfExists(thumbnailFile.toPath());
        } catch (IOException e) {
            logger.error("Could not delete vod files for vod ID: {}", vod.getId());
        }
    }

    private boolean isAuthenticatedForVod(Vod vod) {
        Optional<User> user = userService.getLoggedInUser();

        if (vod == null || user.isEmpty()) {
            return false;
        }

        return user.get().getId().equals(vod.getUser().getId());
    }
}
