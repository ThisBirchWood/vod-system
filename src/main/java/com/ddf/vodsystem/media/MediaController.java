package com.ddf.vodsystem.media;

import com.ddf.vodsystem.clip.ClipSectionRequestDTO;
import com.ddf.vodsystem.job.UUIDResponseDTO;
import com.ddf.vodsystem.job.Job;
import com.ddf.vodsystem.common.dto.APIResponse;
import com.ddf.vodsystem.user.User;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/media")
public class MediaController {
    private final MediaService mediaService;
    private static final String SUCCESS = "success";

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    /**
     * Starts an asynchronous job to compress an uploaded video file.
     *
     * @param file    the uploaded video to compress
     * @param spec    trim window, resolution, fps, and target size for the output
     * @param user    the authenticated user, or {@code null} for an anonymous request
     * @return {@code 200 OK} wrapping the new job's UUID for polling
     * @throws IOException if saving the upload to temporary storage fails
     */
    @PostMapping(value = "/compress", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<APIResponse<UUIDResponseDTO>> compress(
            @RequestParam("file")MultipartFile file,
            @Valid @ModelAttribute CompressionSpec spec,
            @AuthenticationPrincipal User user
            ) throws IOException {
        Job job = mediaService.compress(file, spec, user);
        return ResponseEntity.ok(new APIResponse<>(
                SUCCESS,
                "Compression successfully started",
                new UUIDResponseDTO(job.getUuid())
        ));
    }


    /**
     * Starts an asynchronous job to save a section of the user's stream between two timestamps as a VoD.
     *
     * @param saveSectionRequest the start/end timestamps and optional title/description
     * @return {@code 200 OK} wrapping the new job's UUID for polling
     * @throws IOException if reading the stream segments fails
     */
    @PostMapping("/save")
    public ResponseEntity<APIResponse<UUIDResponseDTO>> save(
            @Valid @RequestBody SaveSectionRequestDTO saveSectionRequest,
            @AuthenticationPrincipal User user) throws IOException {
        Job job = mediaService.saveSection(
                saveSectionRequest.startTime(),
                saveSectionRequest.endTime(),
                saveSectionRequest.title(),
                saveSectionRequest.description(),
                user);
        return ResponseEntity.ok(new APIResponse<>(
                SUCCESS,
                "Section saving successfully started",
                new UUIDResponseDTO(job.getUuid())
        ));
    }

    /**
     * Starts an asynchronous job to save the section of the user's stream between two markers as a VoD.
     *
     * @param saveSectionByMarkerRequest the start/end marker IDs and optional title/description
     * @return {@code 200 OK} wrapping the new job's UUID for polling
     * @throws IOException if reading the stream segments fails
     */
    @PostMapping("/save/markers")
    public ResponseEntity<APIResponse<UUIDResponseDTO>> saveByMarkers(
            @Valid @RequestBody SaveSectionByMarkerRequestDTO saveSectionByMarkerRequest,
            @AuthenticationPrincipal User user) throws IOException {
        Job job = mediaService.saveSection(
                saveSectionByMarkerRequest.startMarkerId(),
                saveSectionByMarkerRequest.endMarkerId(),
                saveSectionByMarkerRequest.title(),
                saveSectionByMarkerRequest.description(),
                user);
        return ResponseEntity.ok(new APIResponse<>(
                SUCCESS,
                "Section saving successfully started",
                new UUIDResponseDTO(job.getUuid())
        ));
    }

    /**
     * Starts an asynchronous job to save the last few seconds of the user's stream as a clip.
     *
     * @param clipSectionRequest the clip duration in seconds and optional title/description
     * @return {@code 200 OK} wrapping the new job's UUID for polling
     * @throws IOException if reading the stream segments fails
     */
    @PostMapping("/clip")
    public ResponseEntity<APIResponse<UUIDResponseDTO>> clip(
            @Valid @RequestBody ClipSectionRequestDTO clipSectionRequest,
            @AuthenticationPrincipal User user) throws IOException {
        Job job = mediaService.clip(
                clipSectionRequest.duration(),
                clipSectionRequest.title(),
                clipSectionRequest.description(),
                user
        );
        return ResponseEntity.ok(new APIResponse<>(
                SUCCESS,
                "Clipping successfully started",
                new UUIDResponseDTO(job.getUuid())
        ));
    }
}