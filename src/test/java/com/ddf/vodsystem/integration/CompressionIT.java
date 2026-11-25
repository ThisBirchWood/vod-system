package com.ddf.vodsystem.integration;

import com.ddf.vodsystem.clip.Clip;
import com.ddf.vodsystem.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CompressionIT extends MediaTestSupport {

    @Nested
    @DisplayName("POST /media/compress")
    class Compression {

        @Test
        void anonymousUploadSucceedsWithPlayableDownloadAndNoClip() throws Exception {
            String uuid = compress(source(), options(0, 6));

            JsonNode job = awaitJob(uuid);
            assertThat(job.path("state").asText()).isEqualTo("SUCCEEDED");
            assertThat(job.path("isComplete").asBoolean()).isTrue();

            MockHttpServletResponse download = mockMvc.perform(get(JOBS + uuid + "/download"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("video/mp4"))
                    .andReturn().getResponse();
            TestMedia.Probe probe = TestMedia.probe(save(download, "download.mp4"));
            assertThat(probe.width()).isEqualTo(640);
            assertThat(probe.height()).isEqualTo(360);
            assertThat(probe.videoFrames()).isGreaterThan(0);
            assertThat(probe.videoDuration()).isCloseTo(6, within(ONE_FRAME));

            assertThat(clipRepository.count()).isZero();
            assertThat(listFiles(usersRoot())).isEmpty();
        }

        @Test
        void authenticatedUploadPersistsClipFileAndThumbnail() throws Exception {
            User user = createUser();

            String uuid = compress(source(), options(0, 6).title("My clip").description("A description"), user);
            assertSucceeded(uuid);

            Clip clip = awaitSingleClip(user);
            assertThat(clip.getTitle()).isEqualTo("My clip");
            assertThat(clip.getDescription()).isEqualTo("A description");

            Path clipFile = directoryService.resolvePath(clip.getVideoPath());
            assertThat(clipFile).isEqualTo(clipsDir(user).resolve(uuid + ".mp4")).isRegularFile();
            assertThat(TestMedia.probe(clipFile).videoFrames()).isGreaterThan(0);

            awaitThumbnail(clip.getThumbnailPath());
        }

        @Test
        void outputRespectsTargetFileSize() throws Exception {
            long targetKb = 150;
            long targetBytes = targetKb * 1000;
            assertThat(Files.size(source())).as("fixture must be larger than the target for this to mean anything")
                    .isGreaterThan(targetBytes * 2);

            String uuid = compress(source(), options(0, 6).fileSize(targetKb));
            assertSucceeded(uuid);

            long outputBytes = Files.size(tempOutput(uuid));

            assertThat(outputBytes).isLessThanOrEqualTo((long) (targetBytes * 1.25));
            assertThat(outputBytes).as("the bitrate should be used, not undershot wildly")
                    .isGreaterThan(targetBytes / 3);
        }

        @Test
        void trimProducesRequestedDuration() throws Exception {
            String uuid = compress(source(), options(1.5, 2.0));
            assertSucceeded(uuid);

            TestMedia.Probe probe = TestMedia.probe(tempOutput(uuid));
            assertThat(probe.videoDuration()).isCloseTo(2.0, within(ONE_FRAME));
            assertThat(probe.videoFrames()).isBetween(59, 61);
        }

        @ParameterizedTest(name = "width={0}, height={1} -> {2}x{3}")
        @CsvSource(nullValues = "null", value = {
                "320,  null, 320, 180",
                "null, 180,  320, 180",
                "400,  300,  400, 300",
        })
        void resizeProducesExpectedDimensions(Integer width, Integer height, int expectedWidth, int expectedHeight) throws Exception {
            CompressOptions options = options(0, 2);
            if (width != null) options.width(width);
            if (height != null) options.height(height);

            String uuid = compress(source(), options);
            assertSucceeded(uuid);

            TestMedia.Probe probe = TestMedia.probe(tempOutput(uuid));
            assertThat(probe.width()).isEqualTo(expectedWidth);
            assertThat(probe.height()).isEqualTo(expectedHeight);
        }

        @Test
        void fpsChangeIsReflectedInOutputFrameRate() throws Exception {
            String uuid = compress(source(), options(0, 2).fps(15));
            assertSucceeded(uuid);

            TestMedia.Probe probe = TestMedia.probe(tempOutput(uuid));
            assertThat(probe.rFrameRate()).isEqualTo("15/1");
            assertThat(probe.videoFrames()).isBetween(29, 31);
        }

        @ParameterizedTest(name = "width={0}, height={1}")
        @CsvSource(nullValues = "null", value = {
                "null, 240",   // aspect ratio gives width 427
                "321,  null",
                "321,  181",
        })
        void oddDimensionsDoNotFailEncoding(Integer width, Integer height) throws Exception {
            CompressOptions options = options(0, 2);
            if (width != null) options.width(width);
            if (height != null) options.height(height);

            String uuid = compress(source(), options);
            JsonNode job = awaitJob(uuid);

            assertThat(job.path("state").asText()).as("job state (error: %s)", job.path("errorOutput").asText())
                    .isEqualTo("SUCCEEDED");
            TestMedia.Probe probe = TestMedia.probe(tempOutput(uuid));
            assertThat(probe.width() % 2).as("output width %d", probe.width()).isZero();
            assertThat(probe.height() % 2).as("output height %d", probe.height()).isZero();
        }

        @Test
        void inputWithoutAudioSucceeds() throws Exception {
            String uuid = compress(noAudio(), options(0, 4).fileSize(100));
            assertSucceeded(uuid);

            TestMedia.Probe probe = TestMedia.probe(tempOutput(uuid));
            assertThat(probe.hasAudio()).isFalse();
            assertThat(probe.videoDuration()).isCloseTo(4, within(ONE_FRAME));
        }

        @Test
        void corruptInputFailsWithoutClipOrOrphanedFiles() throws Exception {
            User user = createUser();

            String uuid = compress(corrupt(), options(0, 4), user);
            JsonNode job = awaitJob(uuid);

            assertThat(job.path("state").asText()).isEqualTo("FAILED");
            assertThat(job.path("errorOutput").asText()).isNotBlank();
            assertThat(job.path("isComplete").asBoolean()).isFalse();

            Thread.sleep(500);
            assertThat(clipRepository.count()).isZero();
            assertThat(tempOutput(uuid)).doesNotExist();
            assertThat(listFiles(usersRoot())).isEmpty();
        }

        @Test
        void progressRisesMonotonicallyFromZeroToOne() throws Exception {
            String uuid = compress(slow(), options(0, 20));

            List<Float> samples = new ArrayList<>();
            JsonNode job;
            long deadline = System.nanoTime() + JOB_TIMEOUT.toNanos();
            do {
                job = getJob(uuid);
                samples.add((float) job.path("progress").asDouble());
                Thread.sleep(25);
            } while (isRunning(job) && System.nanoTime() < deadline);

            assertThat(job.path("state").asText()).isEqualTo("SUCCEEDED");
            assertThat(samples).allSatisfy(p -> assertThat(p).isBetween(0f, 1f));
            assertThat(samples).as("progress samples").isSorted();
            assertThat(samples.stream().distinct().count())
                    .as("progress should be reported during the encode, not jump straight to done: %s", samples)
                    .isGreaterThanOrEqualTo(3);
            assertThat(samples.getLast()).isCloseTo(1f, within(0.01f));
        }

        @Test
        void downloadBeforeCompletionReturnsJobNotFinished() throws Exception {
            String uuid = compress(slow(), options(0, 20));

            try {
                assertThat(getJob(uuid).path("state").asText()).as("precondition: job still running").isEqualTo("PROCESSING");

                mockMvc.perform(get(JOBS + uuid + "/download"))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.status", is("error")));
            } finally {
                awaitJob(uuid);
            }
        }

        @Test
        void unknownJobReturns404() throws Exception {
            String unknown = UUID.randomUUID().toString();

            mockMvc.perform(get(JOBS + unknown))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is("error")));
            mockMvc.perform(get(JOBS + unknown + "/download"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is("error")));
        }
    }

    @Nested
    @DisplayName("Metadata (ffprobe)")
    class Metadata {

        @Test
        void persistedClipMetadataMatchesFfprobe() throws Exception {
            User user = createUser();

            assertSucceeded(compress(source(), options(0, 6), user));
            Clip clip = awaitSingleClip(user);

            TestMedia.Probe probe = TestMedia.probe(directoryService.resolvePath(clip.getVideoPath()));
            assertThat(clip.getWidth()).isEqualTo(probe.width()).isEqualTo(640);
            assertThat(clip.getHeight()).isEqualTo(probe.height()).isEqualTo(360);
            assertThat((double) clip.getFps()).isCloseTo(probe.fps(), within(0.001)).isCloseTo(30, within(0.001));
            assertThat((double) clip.getDuration()).isCloseTo(probe.videoDuration(), within(0.001));
            assertThat(clip.getFileSize()).isEqualTo(probe.size());
        }

        @Test
        void rationalFrameRateIsParsed() throws Exception {
            User user = createUser();

            assertSucceeded(compress(ntsc(), options(0, 4), user));
            Clip clip = awaitSingleClip(user);

            TestMedia.Probe probe = TestMedia.probe(directoryService.resolvePath(clip.getVideoPath()));
            assertThat(probe.rFrameRate()).as("precondition: fixture keeps its NTSC rate").isEqualTo("30000/1001");
            assertThat((double) clip.getFps()).isCloseTo(30000.0 / 1001, within(0.001));
        }

        @Test
        void variableFrameRateIsParsed() throws Exception {
            User user = createUser();

            assertSucceeded(compress(variableFrameRate(), options(0, 4), user));
            Clip clip = awaitSingleClip(user);

            TestMedia.Probe probe = TestMedia.probe(directoryService.resolvePath(clip.getVideoPath()));
            assertThat(clip.getFps()).isNotNull().isPositive().isFinite();
            assertThat((double) clip.getFps()).isCloseTo(probe.fps(), within(0.001));
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        private static final int CORE_POOL_SIZE = 4;

        @Test
        void jobsBeyondPoolSizeQueueAndAllSucceed() throws Exception {
            int jobs = CORE_POOL_SIZE * 3;
            List<String> uuids = new ArrayList<>();
            for (int i = 0; i < jobs; i++) {
                uuids.add(compress(source(), options(0, 6)));
            }

            // Queued if a job is untouched after another has finished
            boolean sawQueued = false;
            long deadline = System.nanoTime() + JOB_TIMEOUT.toNanos() * 2;
            Map<String, JsonNode> latest;
            do {
                latest = uuids.stream().collect(Collectors.toMap(u -> u, CompressionIT.this::getJobUnchecked));
                boolean anyDone = latest.values().stream().anyMatch(j -> !isRunning(j));
                boolean anyUntouched = latest.values().stream()
                        .anyMatch(j -> isRunning(j) && j.path("progress").asDouble() == 0);
                sawQueued |= anyDone && anyUntouched;
                Thread.sleep(20);
            } while (latest.values().stream().anyMatch(CompressionIT.this::isRunning) && System.nanoTime() < deadline);

            assertThat(latest.values()).allSatisfy(job ->
                    assertThat(job.path("state").asText()).isEqualTo("SUCCEEDED"));
            assertThat(sawQueued).as("a job should have waited in the queue while another finished").isTrue();
            for (String uuid : uuids) {
                assertThat(TestMedia.probe(tempOutput(uuid)).videoDuration()).isCloseTo(6, within(ONE_FRAME));
            }
        }

        @Test
        void simultaneousCompressionsUseSeparateTempFiles() throws Exception {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<String> big = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return compress(source(), options(0, 4));
                });
                Future<String> little = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return compress(small(), options(0, 4));
                });
                ready.await();
                go.countDown();

                String bigUuid = big.get(30, TimeUnit.SECONDS);
                String littleUuid = little.get(30, TimeUnit.SECONDS);
                assertThat(bigUuid).isNotEqualTo(littleUuid);
                assertSucceeded(bigUuid);
                assertSucceeded(littleUuid);

                assertThat(tempInput(bigUuid)).isRegularFile();
                assertThat(tempInput(littleUuid)).isRegularFile();
                assertThat(tempOutput(bigUuid)).isRegularFile();
                assertThat(tempOutput(littleUuid)).isRegularFile();

                assertThat(Files.mismatch(tempInput(bigUuid), source())).isEqualTo(-1L);
                assertThat(Files.mismatch(tempInput(littleUuid), small())).isEqualTo(-1L);
                TestMedia.Probe bigOut = TestMedia.probe(save(download(bigUuid), "big.mp4"));
                TestMedia.Probe littleOut = TestMedia.probe(save(download(littleUuid), "little.mp4"));
                assertThat(bigOut.width()).isEqualTo(640);
                assertThat(bigOut.height()).isEqualTo(360);
                assertThat(littleOut.width()).isEqualTo(320);
                assertThat(littleOut.height()).isEqualTo(240);
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
