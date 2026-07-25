package com.ddf.vodsystem.controllers;

import com.ddf.vodsystem.controllers.dto.ClipResponse;
import com.ddf.vodsystem.dto.APIResponse;
import com.ddf.vodsystem.controllers.dto.ClipUpdateRequest;
import com.ddf.vodsystem.entities.Clip;
import com.ddf.vodsystem.services.ClipService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/clips")
public class ClipController {
    private final ClipService clipService;
    private static final String SUCCESS = "success";
    private static final String FILENAME_HEADER = "inline; filename=\"%s\"";

    public ClipController(ClipService clipService) {
        this.clipService = clipService;
    }

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

    @PatchMapping("/{id}")
    public ResponseEntity<APIResponse<ClipResponse>> updateClip(@PathVariable Long id,
                                                           @RequestBody ClipUpdateRequest updateFields) {
        Clip clip = clipService.updateClip(id, updateFields);
        ClipResponse clipDTO = convertToDTO(clip);

        return ResponseEntity.ok(
                new APIResponse<>(SUCCESS, "Clip updated successfully", clipDTO)
        );
    }

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

    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<Resource> downloadThumbnail(@PathVariable Long id) {
        Resource resource = clipService.downloadThumbnail(id);

        if (resource == null || !resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
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
