package com.ddf.vodsystem.integration;

import com.ddf.vodsystem.clip.Clip;
import com.ddf.vodsystem.marker.Marker;
import com.ddf.vodsystem.stream.Stream;
import com.ddf.vodsystem.user.User;
import com.ddf.vodsystem.vod.Vod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StreamCaptureIT extends MediaTestSupport {

    @Nested
    @DisplayName("POST /media/save and /media/save/markers")
    class SaveSection {

        @Test
        void windowOnSegmentBoundariesProducesVodOfWindowLength() throws Exception {
            User user = createUser();
            Instant first = writeStreamSegments(user, 10);

            assertSucceeded(saveSection(user, first.plusSeconds(4), first.plusSeconds(10)));
            Vod vod = awaitSingleVod(user);

            assertThat((double) vod.getDuration()).isCloseTo(6, within(COPY_TOLERANCE));
            assertThat(TestMedia.probe(directoryService.resolvePath(vod.getVideoPath())).formatDuration())
                    .isCloseTo(6, within(COPY_TOLERANCE));
        }

        /** The leading segment is copied whole, so the lead-in is included. */
        @Test
        void midSegmentStartIncludesTrimOffsetLeadIn() throws Exception {
            User user = createUser();
            Instant first = writeStreamSegments(user, 10);

            assertSucceeded(saveSection(user, first.plusSeconds(5), first.plusSeconds(10)));
            Vod vod = awaitSingleVod(user);

            double window = 5;
            double trimOffset = 1;
            assertThat((double) vod.getDuration()).isCloseTo(window + trimOffset, within(COPY_TOLERANCE));
        }

        /** The first segment is 2 s long but the next is named 10 s later, so a start at 5 s skips it. */
        @Test
        void startPastFirstSegmentsRealDurationDropsIt() throws Exception {
            User user = createUser();
            Instant first = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
            List<Instant> starts = List.of(first, first.plusSeconds(10), first.plusSeconds(12), first.plusSeconds(14));
            TestMedia.writeSegments(streamDir(user), starts, SEGMENT_SECONDS);

            assertSucceeded(saveSection(user, first.plusSeconds(5), first.plusSeconds(14)));
            Vod vod = awaitSingleVod(user);

            // 6 s if the first segment weren't dropped
            assertThat((double) vod.getDuration()).isCloseTo(4, within(COPY_TOLERANCE));
        }

        @Test
        void windowWithNoSegmentsReturns400AndCreatesNoJob() throws Exception {
            User user = createUser();
            Instant first = writeStreamSegments(user, 5);

            mockMvc.perform(saveRequest(user, first.minusSeconds(100), first.minusSeconds(90)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is("error")));

            assertNoJobCreated();
            assertThat(vodRepository.count()).isZero();
        }

        @Test
        void emptyStreamDirectoryReturns400AndCreatesNoJob() throws Exception {
            User user = createUser();
            Instant now = Instant.now();

            mockMvc.perform(saveRequest(user, now.minusSeconds(10), now))
                    .andExpect(status().isBadRequest());

            assertNoJobCreated();
        }

        @Test
        void startAfterEndReturns400() throws Exception {
            User user = createUser();
            Instant first = writeStreamSegments(user, 5);

            mockMvc.perform(saveRequest(user, first.plusSeconds(8), first.plusSeconds(2)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is("error")));

            assertNoJobCreated();
            assertThat(vodRepository.count()).isZero();
        }

        @Test
        void markerSaveMatchesEquivalentTimestampSave() throws Exception {
            User user = createUser();
            Instant first = writeStreamSegments(user, 10);
            Stream stream = createStream(user, first);
            Marker start = createMarker(user, stream, first.plusSeconds(4));
            Marker end = createMarker(user, stream, first.plusSeconds(10));

            assertSucceeded(saveByMarkers(user, start.getId(), end.getId()));
            Vod byMarkers = awaitVodCount(user, 1).getFirst();

            assertSucceeded(saveSection(user, first.plusSeconds(4), first.plusSeconds(10)));
            List<Vod> vods = awaitVodCount(user, 2);
            Vod byTimestamps = vods.stream().filter(v -> !v.getId().equals(byMarkers.getId())).findFirst().orElseThrow();

            assertThat((double) byMarkers.getDuration()).isCloseTo(6, within(COPY_TOLERANCE));
            assertThat((double) byMarkers.getDuration()).isCloseTo(byTimestamps.getDuration(), within(ONE_FRAME));
            assertThat(byMarkers.getWidth()).isEqualTo(byTimestamps.getWidth());
            assertThat(byMarkers.getHeight()).isEqualTo(byTimestamps.getHeight());
        }

        @Test
        void markerOwnedByAnotherUserIsRejected() throws Exception {
            User owner = createUser();
            User caller = createUser();
            Instant first = writeStreamSegments(caller, 10);
            Stream ownersStream = createStream(owner, first);
            Stream callersStream = createStream(caller, first);
            Marker foreign = createMarker(owner, ownersStream, first.plusSeconds(2));
            Marker own = createMarker(caller, callersStream, first.plusSeconds(8));

            mockMvc.perform(saveByMarkersRequest(caller, foreign.getId(), own.getId()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(saveByMarkersRequest(caller, own.getId(), foreign.getId()))
                    .andExpect(status().isForbidden());

            assertNoJobCreated();
            assertThat(vodRepository.count()).isZero();
        }

        @Test
        void missingMarkerReturns404() throws Exception {
            User user = createUser();
            Instant first = writeStreamSegments(user, 5);
            Marker marker = createMarker(user, createStream(user, first), first.plusSeconds(2));

            mockMvc.perform(saveByMarkersRequest(user, marker.getId(), 999_999L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is("error")));
            mockMvc.perform(saveByMarkersRequest(user, 999_999L, marker.getId()))
                    .andExpect(status().isNotFound());
        }

        @Test
        void outputIsStoredUnderUsersVodsWithRowAndThumbnail() throws Exception {
            User user = createUser();
            Instant first = writeStreamSegments(user, 6);

            String uuid = saveSection(user, first, first.plusSeconds(6), "Highlights", "Best bits");
            assertSucceeded(uuid);
            Vod vod = awaitSingleVod(user);

            assertThat(vod.getTitle()).isEqualTo("Highlights");
            assertThat(vod.getDescription()).isEqualTo("Best bits");
            Path vodFile = directoryService.resolvePath(vod.getVideoPath());
            assertThat(vodFile.getParent()).isEqualTo(vodsDir(user));
            assertThat(vodFile).isRegularFile();
            assertThat(listFiles(vodsDir(user))).containsExactly(vodFile);
            awaitThumbnail(vod.getThumbnailPath());

            MockHttpServletResponse download = mockMvc.perform(get(JOBS + uuid + "/download"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse();
            assertThat(download.getContentAsByteArray()).isEqualTo(Files.readAllBytes(vodFile));
        }
    }

    @Nested
    @DisplayName("POST /media/clip")
    class LiveClip {

        @Test
        void clipsLastNSecondsOfLiveStream() throws Exception {
            User user = createUser();
            writeLiveSegments(user);

            assertSucceeded(clip(user, 10));
            Clip clip = awaitSingleClip(user);

            assertClipDuration(clip, 10);
            assertThat(directoryService.resolvePath(clip.getVideoPath()).getParent()).isEqualTo(clipsDir(user));
            awaitThumbnail(clip.getThumbnailPath());
        }

        @ParameterizedTest(name = "duration={0}")
        @ValueSource(floats = {0f, -1f, 180.01f, 181f})
        void durationOutOfRangeReturns400(float duration) throws Exception {
            User user = createUser();
            writeLiveSegments(user);

            mockMvc.perform(clipRequest(user, duration))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is("error")));

            assertNoJobCreated();
        }

        @Test
        void fractionalDurationWorks() throws Exception {
            User user = createUser();
            writeLiveSegments(user);

            assertSucceeded(clip(user, 12.5f));
            Clip clip = awaitSingleClip(user);

            assertClipDuration(clip, 12.5);
        }

        @Test
        void notStreamingReturns400AndCreatesNoJob() throws Exception {
            User user = createUser();

            mockMvc.perform(clipRequest(user, 10))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status", is("error")));

            assertNoJobCreated();
            assertThat(clipRepository.count()).isZero();
        }

        /** Up to one segment of lead-in, since segments are copied whole. */
        private void assertClipDuration(Clip clip, double requested) {
            assertThat((double) clip.getDuration())
                    .isGreaterThanOrEqualTo(requested - COPY_TOLERANCE)
                    .isLessThanOrEqualTo(requested + SEGMENT_SECONDS + COPY_TOLERANCE);
        }

        /** 40 s either side of now */
        private void writeLiveSegments(User user) {
            Instant first = Instant.now().minusSeconds(40);
            TestMedia.writeSegments(streamDir(user), first, 40, SEGMENT_SECONDS);
        }
    }
}
