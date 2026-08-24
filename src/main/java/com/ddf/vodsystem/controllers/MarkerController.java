package com.ddf.vodsystem.controllers;

import com.ddf.vodsystem.controllers.dto.MarkerCreateRequest;
import com.ddf.vodsystem.controllers.dto.MarkerResponse;
import com.ddf.vodsystem.dto.APIResponse;
import com.ddf.vodsystem.entities.Marker;
import com.ddf.vodsystem.services.MarkerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/markers")
public class MarkerController {
    private static final String SUCCESS = "success";

    private final MarkerService markerService;

    public MarkerController(MarkerService markerService) {
        this.markerService = markerService;
    }

    /**
     * Lists all markers belonging to the authenticated user.
     *
     * @return {@code 200 OK} wrapping the user's markers as {@link MarkerResponse} DTOs
     */
    @GetMapping("")
    public ResponseEntity<APIResponse<List<MarkerResponse>>> getMarkers() {
        List<MarkerResponse> markers = markerService.getUserMarkers().stream()
                .map(this::convertToDTO)
                .toList();

        return ResponseEntity.ok(
                new APIResponse<>(SUCCESS, "Markers retrieved successfully", markers)
        );
    }

    /**
     * Retrieves a single marker owned by the authenticated user.
     *
     * @param id the ID of the marker to retrieve
     * @return {@code 200 OK} wrapping the marker as a {@link MarkerResponse} DTO
     */
    @GetMapping("/{id}")
    public ResponseEntity<APIResponse<MarkerResponse>> getMarkerById(@PathVariable Long id) {
        Marker marker = markerService.getMarkerById(id);

        return ResponseEntity.ok(
                new APIResponse<>(SUCCESS, "Marker retrieved successfully", convertToDTO(marker))
        );
    }

    /**
     * Creates a marker at the current moment on the user's active stream.
     *
     * @param request the marker payload carrying the marker message
     * @return {@code 200 OK} wrapping the created marker as a {@link MarkerResponse} DTO
     */
    @PostMapping("")
    public ResponseEntity<APIResponse<MarkerResponse>> createMarker(@Valid @RequestBody MarkerCreateRequest request) {
        Marker marker = markerService.create(request.message());

        return ResponseEntity.ok(
                new APIResponse<>(SUCCESS, "Marker created successfully", convertToDTO(marker))
        );
    }

    /**
     * Deletes a marker owned by the authenticated user.
     *
     * @param id the ID of the marker to delete
     * @return {@code 200 OK} with a confirmation message
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<APIResponse<String>> deleteMarker(@PathVariable Long id) {
        markerService.deleteMarker(id);

        return ResponseEntity.ok(
                new APIResponse<>(SUCCESS, "Marker deleted successfully", "Marker " + id + " deleted.")
        );
    }

    private MarkerResponse convertToDTO(Marker marker) {
        return new MarkerResponse(
                marker.getId(),
                marker.getUser().getId(),
                marker.getStream().getId(),
                marker.getMessage(),
                marker.getTimestamp()
        );
    }
}
