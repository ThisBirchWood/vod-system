package com.ddf.vodsystem.controllers;

import com.ddf.vodsystem.controllers.dto.VodResponse;
import com.ddf.vodsystem.controllers.dto.VodUpdateRequest;
import com.ddf.vodsystem.dto.APIResponse;
import com.ddf.vodsystem.entities.Vod;
import com.ddf.vodsystem.services.VodService;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/vods")
public class VodController {
    private final VodService vodService;
    private static final String SUCCESS = "success";
    private static final String FILENAME_HEADER = "inline; filename=\"%s\"";

    public VodController(VodService vodService) {
        this.vodService = vodService;
    }

    /**
     * Lists all VoDs belonging to the authenticated user.
     *
     * @return {@code 200 OK} wrapping the user's VoDs as {@link VodResponse} DTOs
     */
    @GetMapping("")
    public ResponseEntity<APIResponse<List<VodResponse>>> getVods() {
        List<VodResponse> vods = vodService.getUserVods().stream()
                .map(this::convertToDTO)
                .toList();

        return ResponseEntity.ok(new APIResponse<>(SUCCESS, "Vods retrieved successfully", vods));
    }

    /**
     * Retrieves a single VoD owned by the authenticated user.
     *
     * @param id the ID of the VoD to retrieve
     * @return {@code 200 OK} wrapping the VoD as a {@link VodResponse} DTO
     */
    @GetMapping("/{id}")
    public ResponseEntity<APIResponse<VodResponse>> getVodById(@PathVariable Long id) {
        VodResponse vod = convertToDTO(vodService.getVodById(id));
        return ResponseEntity.ok(new APIResponse<>(SUCCESS, "Vod retrieved successfully", vod));
    }

    /**
     * Applies a partial update to a VoD's metadata; null fields are left unchanged.
     *
     * @param id           the ID of the VoD to update
     * @param updateFields the title and/or description to overwrite
     * @return {@code 200 OK} wrapping the updated VoD as a {@link VodResponse} DTO
     */
    @PatchMapping("/{id}")
    public ResponseEntity<APIResponse<VodResponse>> updateVod(@PathVariable Long id,
                                                              @RequestBody VodUpdateRequest updateFields) {
        VodResponse vod = convertToDTO(vodService.updateVod(id, updateFields));
        return ResponseEntity.ok(new APIResponse<>(SUCCESS, "Vod updated successfully", vod));
    }

    /**
     * Deletes a VoD and its associated files.
     *
     * @param id the ID of the VoD to delete
     * @return {@code 200 OK} with a confirmation message
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<APIResponse<String>> deleteVod(@PathVariable Long id) {
        vodService.deleteVod(id);
        return ResponseEntity.ok(new APIResponse<>(SUCCESS, "Vod deleted successfully",
                "Vod with ID " + id + " has been deleted"));
    }

    /**
     * Streams the VoD's video file inline, supporting HTTP range requests.
     *
     * @param id the ID of the VoD to download
     * @return {@code 200 OK} with the video resource, or {@code 404 Not Found} if the file is missing
     * @throws IOException if the resource's content length cannot be read
     */
    @GetMapping(value = "/{id}/media", produces = MediaType.ALL_VALUE)
    public ResponseEntity<Resource> downloadVod(@PathVariable Long id) throws IOException {
        Resource resource = vodService.downloadVod(id);

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
     * Streams the VoD's thumbnail image with a public cache policy.
     *
     * @param id the ID of the VoD whose thumbnail to download
     * @return {@code 200 OK} with the thumbnail resource, or {@code 404 Not Found} if the file is missing
     * @throws IOException if the resource's last-modified time cannot be read
     */
    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<Resource> downloadThumbnail(@PathVariable Long id) throws IOException {
        Resource resource = vodService.downloadThumbnail(id);

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

    private VodResponse convertToDTO(Vod vod) {
        return new VodResponse(
                vod.getId(),
                vod.getUser().getId(),
                vod.getTitle(),
                vod.getDescription(),
                vod.getDuration(),
                vod.getCreatedAt()
        );
    }
}
