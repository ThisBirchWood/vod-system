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