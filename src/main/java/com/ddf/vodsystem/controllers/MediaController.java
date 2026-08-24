package com.ddf.vodsystem.controllers;

import com.ddf.vodsystem.controllers.dto.ClipSectionRequest;
import com.ddf.vodsystem.controllers.dto.SaveSectionByMarkerRequest;
import com.ddf.vodsystem.controllers.dto.SaveSectionRequest;
import com.ddf.vodsystem.controllers.dto.UUIDResponse;
import com.ddf.vodsystem.dto.Job;
import com.ddf.vodsystem.dto.ClipOptions;
import com.ddf.vodsystem.dto.APIResponse;
import com.ddf.vodsystem.services.MediaService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
     * @param options trim window, resolution, fps, and target size for the output
     * @return {@code 200 OK} wrapping the new job's UUID for polling
     * @throws IOException if saving the upload to temporary storage fails
     */
    @PostMapping(value = "/compress", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<APIResponse<UUIDResponse>> compress(
            @RequestParam("file")MultipartFile file,
            @Valid @ModelAttribute ClipOptions options
            ) throws IOException {
        Job job = mediaService.compress(file, options);
        return ResponseEntity.ok(new APIResponse<>(
                SUCCESS,
                "Compression successfully started",
                new UUIDResponse(job.getUuid())
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
    public ResponseEntity<APIResponse<UUIDResponse>> save(
            @RequestBody SaveSectionRequest saveSectionRequest) throws IOException {
        Job job = mediaService.saveSection(
                saveSectionRequest.startTime(),
                saveSectionRequest.endTime(),
                saveSectionRequest.title(),
                saveSectionRequest.description());
        return ResponseEntity.ok(new APIResponse<>(
                SUCCESS,
                "Section saving successfully started",
                new UUIDResponse(job.getUuid())
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
    public ResponseEntity<APIResponse<UUIDResponse>> saveByMarkers(
            @Valid @RequestBody SaveSectionByMarkerRequest saveSectionByMarkerRequest) throws IOException {
        Job job = mediaService.saveSection(
                saveSectionByMarkerRequest.startMarkerId(),
                saveSectionByMarkerRequest.endMarkerId(),
                saveSectionByMarkerRequest.title(),
                saveSectionByMarkerRequest.description());
        return ResponseEntity.ok(new APIResponse<>(
                SUCCESS,
                "Section saving successfully started",
                new UUIDResponse(job.getUuid())
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
    public ResponseEntity<APIResponse<UUIDResponse>> clip(
            @RequestBody ClipSectionRequest clipSectionRequest) throws IOException {
        Job job = mediaService.clip(
                clipSectionRequest.duration(),
                clipSectionRequest.title(),
                clipSectionRequest.description()
        );
        return ResponseEntity.ok(new APIResponse<>(
                SUCCESS,
                "Clipping successfully started",
                new UUIDResponse(job.getUuid())
        ));
    }
}