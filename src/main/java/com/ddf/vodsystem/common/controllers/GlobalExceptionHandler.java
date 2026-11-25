package com.ddf.vodsystem.common.controllers;

import com.ddf.vodsystem.auth.ForbiddenException;
import com.ddf.vodsystem.auth.NotAuthenticatedException;
import com.ddf.vodsystem.clip.ClipMediaUnavailableException;
import com.ddf.vodsystem.clip.ClipNotFoundException;
import com.ddf.vodsystem.common.exceptions.StorageException;
import com.ddf.vodsystem.common.dto.APIResponse;
import com.ddf.vodsystem.job.JobNotFinishedException;
import com.ddf.vodsystem.job.JobNotFoundException;
import com.ddf.vodsystem.job.KeyNotFoundException;
import com.ddf.vodsystem.marker.MarkerNotFoundException;
import com.ddf.vodsystem.media.command.FFMPEGException;
import com.ddf.vodsystem.stream.AlreadyStreamingException;
import com.ddf.vodsystem.stream.NotStreamingException;
import com.ddf.vodsystem.vod.VodMediaUnavailableException;
import com.ddf.vodsystem.vod.VodNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String ERROR = "error";

    /**
     * Handles malformed multipart requests, returning {@code 400 Bad Request}.
     *
     * @param ex the thrown exception
     * @return an error {@link APIResponse}
     */
    @ExceptionHandler({ MultipartException.class })
    public ResponseEntity<APIResponse<Void>> handleMultipartException(MultipartException ex) {
        logger.error("MultipartException: {}", ex.getMessage());
        APIResponse<Void> response = new APIResponse<>(ERROR, "Multipart request error: " + ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles requests missing a required file part, returning {@code 400 Bad Request}.
     *
     * @param ex the thrown exception
     * @return an error {@link APIResponse}
     */
    @ExceptionHandler({ MissingServletRequestPartException.class })
    public ResponseEntity<APIResponse<Void>> handleMissingPart(MissingServletRequestPartException ex) {
        logger.error("MissingServletRequestPartException: {}", ex.getMessage());
        APIResponse<Void> response = new APIResponse<>(ERROR, "Required file part is missing.", null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles unsupported request content types, returning {@code 415 Unsupported Media Type}.
     *
     * @param ex the thrown exception
     * @return an error {@link APIResponse}
     */
    @ExceptionHandler({ HttpMediaTypeNotSupportedException.class })
    public ResponseEntity<APIResponse<Void>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        logger.error("HttpMediaTypeNotSupportedException: {}", ex.getMessage());
        APIResponse<Void> response = new APIResponse<>(ERROR, "Unsupported media type: expected multipart/form-data.", null);
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(response);
    }

    /**
     * Handles invalid arguments, returning {@code 400 Bad Request} with the exception message.
     *
     * @param ex the thrown exception
     * @return an error {@link APIResponse}
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<APIResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        logger.error("IllegalArgumentException: {}", ex.getMessage());
        APIResponse<Void> response = new APIResponse<>(ERROR, ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles lookups for a non-existent job, returning {@code 404 Not Found}.
     *
     * @param ex the thrown exception
     * @return an error {@link APIResponse}
     */
    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<APIResponse<Void>> handleFileNotFound(JobNotFoundException ex) {
        logger.error("JobNotFound: {}", ex.getMessage());
        APIResponse<Void> response = new APIResponse<>(ERROR, ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    /**
     * Handles requests for a job that is still running, returning {@code 202 Accepted}.
     *
     * @param ex the thrown exception
     * @return an error {@link APIResponse}
     */
    @ExceptionHandler(JobNotFinishedException.class)
    public ResponseEntity<APIResponse<Void>> handleJobNotFinished(JobNotFinishedException ex) {
        logger.error("JobNotFinished: {}", ex.getMessage());
        APIResponse<Void> response = new APIResponse<>(ERROR, ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * Handles ffmpeg processing failures, returning {@code 500 Internal Server Error}.
     *
     * @param ex the thrown exception
     * @return an error {@link APIResponse}
     */
    @ExceptionHandler(FFMPEGException.class)
    public ResponseEntity<APIResponse<Void>> handleFFMPEGException(FFMPEGException ex) {
        logger.error("FFMPEGException: {}", ex.getMessage());
        APIResponse<Void> response = new APIResponse<>(ERROR, "FFMPEG Error: Please upload a valid file", null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * Handles unauthenticated access, returning {@code 401 Unauthorized}.
     *
     * @param ex the thrown exception
     * @return an error {@link APIResponse}
     */
    @ExceptionHandler(NotAuthenticatedException.class)
    public ResponseEntity<APIResponse<Void>> handleNotAuthenticated(NotAuthenticatedException ex) {
        logger.error("NotAuthenticated: {}", ex.getMessage());
        APIResponse<Void> response = new APIResponse<>(ERROR, ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    /**
     * Handles an authenticated user accessing a resource they do not own, returning {@code 403 Forbidden}.
     *
     * @param ex the thrown exception
     * @return an error {@link APIResponse}
     */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<APIResponse<Void>> handleForbidden(ForbiddenException ex) {
        logger.error("Forbidden: {}", ex.getMessage());
        APIResponse<Void> response = new APIResponse<>(ERROR, ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    /**
     * Handles an unrecognised stream key, returning {@code 404 Not Found}.
     *
     * @param ex the thrown exception
     * @return an error {@link APIResponse}
     */
    @ExceptionHandler(KeyNotFoundException.class)
    public ResponseEntity<APIResponse<Void>> handleKeyNotFound(KeyNotFoundException ex) {
        logger.error("KeyNotFound: {}", ex.getMessage());
        APIResponse<Void> response = new APIResponse<>(ERROR, ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    /**
     * Handles an attempt to start a stream while one is already active, returning {@code 409 Conflict}.
     *
     * @param ex the thrown exception
     * @return an error {@link APIResponse}
     */
    @ExceptionHandler(AlreadyStreamingException.class)
    public ResponseEntity<APIResponse<Void>> handleAlreadyStreaming(AlreadyStreamingException ex) {
        logger.error("AlreadyStreaming: {}", ex.getMessage());
        APIResponse<Void> response = new APIResponse<>(ERROR, ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /**
     * Handles file storage failures, returning {@code 500 Internal Server Error}.
     *
     * @param e the thrown exception
     * @return an error {@link APIResponse}
     */
    @ExceptionHandler(StorageException.class)
    public ResponseEntity<APIResponse<Void>> handleStorage(StorageException e) {
        logger.error("Storage operation failed", e);
        APIResponse<Void> response = new APIResponse<>(ERROR, "Could not process file", null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * Handles lookups for a non-existent clip, returning {@code 404 Not Found}.
     *
     * @param ex the thrown exception
     * @return an error {@link APIResponse}
     */
    @ExceptionHandler(ClipNotFoundException.class)
    public ResponseEntity<APIResponse<Void>> handleClipNotFound(ClipNotFoundException ex) {
        logger.error("ClipNotFound: {}", ex.getMessage());
        APIResponse<Void> response = new APIResponse<>(ERROR, ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    /**
     * Handles a clip whose media file exists but is not readable, returning {@code 503 Service Unavailable}.
     *
     * @param ex the thrown exception
     * @return an error {@link APIResponse}
     */
    @ExceptionHandler(ClipMediaUnavailableException.class)
    public ResponseEntity<APIResponse<Void>> handleClipMediaUnavailable(ClipMediaUnavailableException ex) {
        logger.error("ClipMediaUnavailable (clip {}): {}", ex.getClipId(), ex.getMessage());
        APIResponse<Void> response = new APIResponse<>(ERROR, "Clip media is currently unavailable", null);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    /**
     * Handles lookups for a non-existent VoD, returning {@code 404 Not Found}.
     *
     * @param ex the thrown exception
     * @return an error {@link APIResponse}
     */
    @ExceptionHandler(VodNotFoundException.class)
    public ResponseEntity<APIResponse<Void>> handleVodNotFound(VodNotFoundException ex) {
        logger.error("VodNotFound: {}", ex.getMessage());
        APIResponse<Void> response = new APIResponse<>(ERROR, ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    /**
     * Handles a VoD whose media file exists but is not readable, returning {@code 503 Service Unavailable}.
     *
     * @param ex the thrown exception
     * @return an error {@link APIResponse}
     */
    @ExceptionHandler(VodMediaUnavailableException.class)
    public ResponseEntity<APIResponse<Void>> handleVodMediaUnavailable(VodMediaUnavailableException ex) {
        logger.error("VodMediaUnavailable (vod {}): {}", ex.getVodId(), ex.getMessage());
        APIResponse<Void> response = new APIResponse<>(ERROR, "Vod media is currently unavailable", null);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    /**
     * Handles actions that require an active stream when none exists, returning {@code 409 Conflict}.
     *
     * @param ex the thrown exception
     * @return an error {@link APIResponse}
     */
    @ExceptionHandler(NotStreamingException.class)
    public ResponseEntity<APIResponse<Void>> handleNotStreaming(NotStreamingException ex) {
        logger.error("NotStreaming: {}", ex.getMessage());
        APIResponse<Void> response = new APIResponse<>(ERROR, ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /**
     * Handles lookups for a non-existent marker, returning {@code 404 Not Found}.
     *
     * @param ex the thrown exception
     * @return an error {@link APIResponse}
     */
    @ExceptionHandler(MarkerNotFoundException.class)
    public ResponseEntity<APIResponse<Void>> handleMarkerNotFound(MarkerNotFoundException ex) {
        logger.error("MarkerNotFound: {}", ex.getMessage());
        APIResponse<Void> response = new APIResponse<>(ERROR, ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }
}