package com.ddf.vodsystem.marker;

import com.ddf.vodsystem.common.dto.APIResponse;
import com.ddf.vodsystem.user.User;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
     * @return {@code 200 OK} wrapping the user's markers as {@link MarkerResponseDTO} DTOs
     */
    @GetMapping("")
    public ResponseEntity<APIResponse<List<MarkerResponseDTO>>> getMarkers(@AuthenticationPrincipal User user) {
        List<MarkerResponseDTO> markers = markerService.getUserMarkers(user).stream()
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
     * @return {@code 200 OK} wrapping the marker as a {@link MarkerResponseDTO} DTO
     */
    @GetMapping("/{id}")
    public ResponseEntity<APIResponse<MarkerResponseDTO>> getMarkerById(@PathVariable Long id,
                                                                   @AuthenticationPrincipal User user) {
        Marker marker = markerService.getMarkerById(id, user);

        return ResponseEntity.ok(
                new APIResponse<>(SUCCESS, "Marker retrieved successfully", convertToDTO(marker))
        );
    }

    /**
     * Creates a marker at the current moment on the user's active stream.
     *
     * @param request the marker payload carrying the marker message
     * @return {@code 200 OK} wrapping the created marker as a {@link MarkerResponseDTO} DTO
     */
    @PostMapping("")
    public ResponseEntity<APIResponse<MarkerResponseDTO>> createMarker(@Valid @RequestBody MarkerCreateRequestDTO request,
                                                                  @AuthenticationPrincipal User user) {
        Marker marker = markerService.create(request.message(), user);

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
    public ResponseEntity<APIResponse<String>> deleteMarker(@PathVariable Long id,
                                                            @AuthenticationPrincipal User user) {
        markerService.deleteMarker(id, user);

        return ResponseEntity.ok(
                new APIResponse<>(SUCCESS, "Marker deleted successfully", "Marker " + id + " deleted.")
        );
    }

    private MarkerResponseDTO convertToDTO(Marker marker) {
        return new MarkerResponseDTO(
                marker.getId(),
                marker.getUser().getId(),
                marker.getStream().getId(),
                marker.getMessage(),
                marker.getTimestamp()
        );
    }
}
