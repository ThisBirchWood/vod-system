package com.ddf.vodsystem.controllers;

import com.ddf.vodsystem.controllers.dto.ClipResponse;
import com.ddf.vodsystem.dto.APIResponse;
import com.ddf.vodsystem.controllers.dto.ClipUpdateRequest;
import com.ddf.vodsystem.entities.Clip;
import com.ddf.vodsystem.services.ClipService;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/clips")
public class ClipController {
    private final ClipService clipService;
    private static final String SUCCESS = "success";
    private static final String FILENAME_HEADER = "inline; filename=\"%s\"";

    public ClipController(ClipService clipService) {
        this.clipService = clipService;
    }

    /**
     * Lists all clips belonging to the authenticated user.
     *
     * @return {@code 200 OK} wrapping the user's clips as {@link ClipResponse} DTOs
     */
    @GetMapping("")
    public ResponseEntity<APIResponse<List<ClipResponse>>> getClips() {
        List<Clip> clips = clipService.getClipsByUser();
        List<ClipResponse> clipDTOs = clips.stream()
                .map(this::convertToDTO)
                .toList();

        return ResponseEntity.ok(
                new APIResponse<>(SUCCESS,
                        "Clips retrieved successfully",
                        clipDTOs
                )
        );
    }

    /**
     * Retrieves a single clip owned by the authenticated user.
     *
     * @param id the ID of the clip to retrieve
     * @return {@code 200 OK} wrapping the clip as a {@link ClipResponse} DTO
     */
    @GetMapping("/{id}")
    public ResponseEntity<APIResponse<ClipResponse>> getClipById(@PathVariable Long id) {
        Clip clip = clipService.getClipById(id);
        ClipResponse clipDTO = convertToDTO(clip);

        return ResponseEntity.ok(
                new APIResponse<>(SUCCESS,
                        "Clip retrieved successfully",
                        clipDTO
                )
        );
    }

    /**
     * Applies a partial update to a clip's metadata; null fields are left unchanged.
     *
     * @param id           the ID of the clip to update
     * @param updateFields the title and/or description to overwrite
     * @return {@code 200 OK} wrapping the updated clip as a {@link ClipResponse} DTO
     */
    @PatchMapping("/{id}")
    public ResponseEntity<APIResponse<ClipResponse>> updateClip(@PathVariable Long id,
                                                           @RequestBody ClipUpdateRequest updateFields) {
        Clip clip = clipService.updateClip(id, updateFields);
        ClipResponse clipDTO = convertToDTO(clip);

        return ResponseEntity.ok(
                new APIResponse<>(SUCCESS, "Clip updated successfully", clipDTO)
        );
    }

    /**
     * Deletes a clip and its associated files.
     *
     * @param id the ID of the clip to delete
     * @return {@code 200 OK} with a confirmation message
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<APIResponse<String>> deleteClip(@PathVariable Long id) {
        clipService.deleteClip(id);

        return ResponseEntity.ok(
                new APIResponse<>(
                        SUCCESS,
                        "Clip deleted successfully",
                        "Clip with ID " + id + " has been deleted"
                )
        );
    }

    /**
     * Streams the clip's video file inline, supporting HTTP range requests.
     *
     * @param id the ID of the clip to download
     * @return {@code 200 OK} with the video resource, or {@code 404 Not Found} if the file is missing
     * @throws IOException if the resource's content length cannot be read
     */
    @GetMapping(value = "/{id}/media", produces = MediaType.ALL_VALUE)
    public ResponseEntity<Resource> downloadClip(@PathVariable Long id) throws IOException {
        Resource resource = clipService.downloadClip(id);

        if (resource == null || !resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        String.format(FILENAME_HEADER, resource.getFilename()))
                .contentType(MediaTypeFactory.getMediaType(resource)
                        .orElse(MediaType.APPLICATION_OCTET_STREAM))
                .contentLength(resource.contentLength())
                .body(resource);
    }

    /**
     * Streams the clip's thumbnail image with a public cache policy.
     *
     * @param id the ID of the clip whose thumbnail to download
     * @return {@code 200 OK} with the thumbnail resource, or {@code 404 Not Found} if the file is missing
     * @throws IOException if the resource's last-modified time cannot be read
     */
    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<Resource> downloadThumbnail(@PathVariable Long id) throws IOException {
        Resource resource = clipService.downloadThumbnail(id);

        if (resource == null || !resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(2, TimeUnit.DAYS).cachePublic())
                .lastModified(resource.lastModified())
                .header(HttpHeaders.CONTENT_DISPOSITION, String.format(FILENAME_HEADER, resource.getFilename()))
                .contentType(MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM))
                .body(resource);
    }

    private ClipResponse convertToDTO(Clip clip) {
        return new ClipResponse(
                clip.getId(),
                clip.getUser().getId(),
                clip.getTitle(),
                clip.getDescription(),
                clip.getDuration(),
                clip.getCreatedAt()
        );
    }
}
