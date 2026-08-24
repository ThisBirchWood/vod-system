package com.ddf.vodsystem.services;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;

@Service
public class DirectoryService {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(DirectoryService.class);

    @Value("${media.users}")
    private String usersDir;

    @Value("${media.streams}")
    private String streamsDir;

    @Value("${media.temp.inputs}")
    private String tempInputsDir;

    @Value("${media.temp.outputs}")
    private String tempOutputsDir;

    private static final long TEMP_DIR_TIME_LIMIT = 3 * 60 * 60 * (long) 1000; // 3 hours
    private static final long TEMP_DIR_CLEANUP_RATE = 30 * 60 * (long) 1000; // 30 minutes

    private static final String VODS_DIR_NAME = "vods";
    private static final String CLIPS_DIR_NAME = "clips";
    private static final String THUMBNAIL_DIR_NAME = "thumbnails";

    /**
     * Returns the directory for temporary upload inputs.
     *
     * @return the configured temporary input directory path
     */
    public Path getTempInputDir() {
        return Path.of(tempInputsDir);
    }

    /**
     * Returns the directory for temporary processing outputs.
     *
     * @return the configured temporary output directory path
     */
    public Path getTempOutputDir() {
        return Path.of(tempOutputsDir);
    }

    /**
     * Returns the given user's root media directory, creating it if necessary.
     *
     * @param userId the ID of the user
     * @return the user's media directory path
     * @throws IOException if the directory cannot be created
     */
    public Path getUserDir(Long userId) throws IOException {
        Path userDir = Path.of(usersDir + File.separator + userId);
        Files.createDirectories(userDir);
        return userDir;
    }

    /**
     * Returns the given user's VoDs directory, creating it if necessary.
     *
     * @param userId the ID of the user
     * @return the user's VoDs directory path
     * @throws IOException if the directory cannot be created
     */
    public Path getVodsDir(Long userId) throws IOException {
        Path vodDir = getUserDir(userId).resolve(VODS_DIR_NAME);
        Files.createDirectories(vodDir);
        return vodDir;
    }

    /**
     * Returns the given user's clips directory, creating it if necessary.
     *
     * @param userId the ID of the user
     * @return the user's clips directory path
     * @throws IOException if the directory cannot be created
     */
    public Path getClipsDir(Long userId) throws IOException {
        Path clipsDir = getUserDir(userId).resolve(CLIPS_DIR_NAME);
        Files.createDirectories(clipsDir);
        return clipsDir;
    }

    /**
     * Returns the given user's thumbnails directory, creating it if necessary.
     *
     * @param userId the ID of the user
     * @return the user's thumbnails directory path
     * @throws IOException if the directory cannot be created
     */
    public Path getThumbnailsDir(Long userId) throws IOException {
        Path thumbnailsDir = getUserDir(userId).resolve(THUMBNAIL_DIR_NAME);
        Files.createDirectories(thumbnailsDir);
        return thumbnailsDir;
    }

    /**
     * Returns the directory holding a stream's recorded HLS segments, creating it if necessary.
     *
     * @param streamKey the stream key naming the segment directory
     * @return the stream's segment directory path
     * @throws IOException if the directory cannot be created
     */
    public Path getStreamDir(String streamKey) throws IOException {
        Path streamFolder = Path.of(streamsDir, streamKey);
        Files.createDirectories(streamFolder);
        return streamFolder;
    }

    /**
     * Converts an absolute path into one relative to the users media root, for storage in the database.
     *
     * @param path the absolute path to relativise
     * @return the path relative to the users media directory
     */
    public Path relativisePath(Path path) {
        Path media = Path.of(usersDir).toAbsolutePath();
        Path newPath = path.toAbsolutePath();
        return media.relativize(newPath);
    }

    /**
     * Resolves a stored relative path back into an absolute path under the users media root.
     *
     * @param path the relative path, as stored in the database
     * @return the absolute path on disk
     */
    public Path resolvePath(String path) {
        Path media = Path.of(usersDir).toAbsolutePath();
        Path newPath = Path.of(path);
        return media.resolve(newPath);
    }

    /**
     * Writes an uploaded file to the given path, creating parent directories and overwriting any existing file.
     *
     * @param path          the destination path
     * @param multipartFile the uploaded file to save
     * @throws IOException if writing the file fails
     */
    public void saveMultipartFile(Path path, MultipartFile multipartFile) throws IOException {
        Files.createDirectories(path.getParent());
        Files.copy(multipartFile.getInputStream(), path, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Copies a file, creating parent directories and overwriting any existing target.
     *
     * @param source the file to copy from
     * @param target the destination path
     * @throws IOException if the copy fails
     */
    public void copyFile(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        logger.info("Copied file from {} to {}", source, target);
    }

    private void cleanUpDirectory(String dir) throws IOException {
        Path dirPath = Path.of(dir);

        if (Files.notExists(dirPath)) {
            logger.warn("No files found in directory: {}", dir);
            return;
        }

        try (Stream<Path> files = Files.list(dirPath)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis()
                                    < System.currentTimeMillis() - TEMP_DIR_TIME_LIMIT;
                        } catch (IOException e) {
                            logger.warn("Could not read last modified time for {}", p);
                            return false;
                        }
                    })
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            logger.warn("Could not delete file {}", p);
                        }
                    });
        }
    }

    private void checkDirPermissions() {
        List<Path> dirs = List.of(
                Path.of(tempInputsDir),
                Path.of(tempOutputsDir),
                Path.of(usersDir)
        );

        List<Path> bad = dirs.stream()
                .filter(p -> !Files.isDirectory(p) || !Files.isReadable(p) || !Files.isWritable(p))
                .toList();

        if (!bad.isEmpty()) {
            throw new IllegalStateException("Unusable directories: " + bad);
        }
    }

    /**
     * Creates the temporary and users media directories on startup and verifies they are usable.
     *
     * @throws IOException           if a directory cannot be created
     * @throws IllegalStateException if any required directory is not a readable, writable directory
     */
    @PostConstruct
    public void createDirectoriesIfNotExist() throws IOException {
        Files.createDirectories(Path.of(tempInputsDir));
        Files.createDirectories(Path.of(tempOutputsDir));
        Files.createDirectories(Path.of(usersDir));

        checkDirPermissions();
    }

    /**
     * Scheduled cleanup that deletes stale files from the temporary input and output directories.
     * <p>
     * Runs every 30 minutes; files older than 3 hours are removed.
     *
     * @throws IOException if listing a temporary directory fails
     */
    @Scheduled(fixedRate = TEMP_DIR_CLEANUP_RATE)
    public void cleanTempDirectories() throws IOException {
        cleanUpDirectory(tempInputsDir);
        cleanUpDirectory(tempOutputsDir);
    }
}