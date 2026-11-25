package com.ddf.vodsystem.integration;

import com.ddf.vodsystem.clip.Clip;
import com.ddf.vodsystem.marker.Marker;
import com.ddf.vodsystem.stream.Stream;
import com.ddf.vodsystem.user.User;
import com.ddf.vodsystem.vod.Vod;
import com.ddf.vodsystem.clip.ClipRepository;
import com.ddf.vodsystem.marker.MarkerRepository;
import com.ddf.vodsystem.stream.StreamRepository;
import com.ddf.vodsystem.vod.VodRepository;
import com.ddf.vodsystem.common.services.DirectoryService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared setup for the ffmpeg-backed media tests. Requires {@code ffmpeg} and {@code ffprobe} on
 * the {@code PATH}. Thumbnails are written after the job succeeds, so they must be awaited.
 */
abstract class MediaTestSupport extends BaseIntegrationTest {

    protected static final String COMPRESS = "/api/v1/media/compress";
    protected static final String SAVE = "/api/v1/media/save";
    protected static final String SAVE_MARKERS = "/api/v1/media/save/markers";
    protected static final String CLIP = "/api/v1/media/clip";
    protected static final String JOBS = "/api/v1/jobs/";

    protected static final Duration JOB_TIMEOUT = Duration.ofSeconds(90);
    protected static final Duration PERSIST_TIMEOUT = Duration.ofSeconds(30);

    /** ±1 frame at 30 fps */
    protected static final double ONE_FRAME = 1.0 / 30;
    /** Segment cuts with {@code -c copy} land on packet boundaries, and audio frames don't align with video ones */
    protected static final double COPY_TOLERANCE = 0.25;

    protected static final int SEGMENT_SECONDS = 2;

    /** 640x360, 30 fps, 6 s */
    protected static Path source() {
        return TestMedia.fixture("source.mp4", p -> TestMedia.video().size(640, 360).seconds(6).writeTo(p));
    }

    /** 320x240, 30 fps, 4 s */
    protected static Path small() {
        return TestMedia.fixture("small.mp4", p -> TestMedia.video().size(320, 240).seconds(4).writeTo(p));
    }

    /** 640x360, 30 fps, 4 s, no audio */
    protected static Path noAudio() {
        return TestMedia.fixture("no-audio.mp4", p -> TestMedia.video().size(640, 360).seconds(4).noAudio().writeTo(p));
    }

    /** 640x360, 30000/1001 fps, 4 s */
    protected static Path ntsc() {
        return TestMedia.fixture("ntsc.mp4", p -> TestMedia.video().size(640, 360).rate("30000/1001").seconds(4).writeTo(p));
    }

    /** 640x360, 4 s, with a gap in the frame timestamps */
    protected static Path variableFrameRate() {
        return TestMedia.fixture("vfr.mp4", p -> TestMedia.video().size(640, 360).seconds(4).variableFrameRate().writeTo(p));
    }

    /** 1280x720, 20 s: slow enough to observe mid-job */
    protected static Path slow() {
        return TestMedia.fixture("slow.mp4", p -> TestMedia.video().size(1280, 720).seconds(20).writeTo(p));
    }

    protected static Path corrupt() {
        return TestMedia.fixture("corrupt.mp4", TestMedia::corruptFile);
    }

    @Autowired protected ClipRepository clipRepository;
    @Autowired protected VodRepository vodRepository;
    @Autowired protected StreamRepository streamRepository;
    @Autowired protected MarkerRepository markerRepository;
    @Autowired protected DirectoryService directoryService;
    @Autowired @Qualifier("ffmpegTaskExecutor") private Executor ffmpegTaskExecutor;

    @TempDir Path scratch;

    /** User IDs restart every test, so leftover files would land under the next test's user. */
    @BeforeEach
    void cleanMediaDirectories() throws IOException {
        for (Path dir : List.of(usersRoot(), streamsRoot())) {
            if (Files.exists(dir)) {
                try (var children = Files.list(dir)) {
                    children.forEach(TestMedia::deleteRecursively);
                }
            }
        }
    }

    /** Stops jobs and thumbnails from one test writing into the next. Runs before the base class truncates. */
    @AfterEach
    void awaitBackgroundWork() {
        var pool = ((ThreadPoolTaskExecutor) ffmpegTaskExecutor).getThreadPoolExecutor();
        await("background ffmpeg work to finish", JOB_TIMEOUT,
                () -> pool.getActiveCount() == 0 && pool.getQueue().isEmpty());
    }

    protected static final class CompressOptions {
        private final Map<String, String> params = new java.util.LinkedHashMap<>();

        CompressOptions title(String title) { params.put("title", title); return this; }
        CompressOptions description(String description) { params.put("description", description); return this; }
        CompressOptions width(int width) { params.put("width", String.valueOf(width)); return this; }
        CompressOptions height(int height) { params.put("height", String.valueOf(height)); return this; }
        CompressOptions fps(float fps) { params.put("fps", String.valueOf(fps)); return this; }
        /** KB */
        CompressOptions fileSize(long kb) { params.put("fileSize", String.valueOf(kb)); return this; }
    }

    protected static CompressOptions options(double startPoint, double duration) {
        CompressOptions options = new CompressOptions().title("Test clip");
        options.params.put("startPoint", String.valueOf(startPoint));
        options.params.put("duration", String.valueOf(duration));
        return options;
    }

    protected String compress(Path file, CompressOptions options) throws Exception {
        return compress(file, options, null);
    }

    protected String compress(Path file, CompressOptions options, User user) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart(COMPRESS)
                .file(new MockMultipartFile("file", file.getFileName().toString(), "video/mp4", Files.readAllBytes(file)));
        options.params.forEach(request::param);
        if (user != null) {
            request.with(authenticatedAs(user));
        }
        return uuidFrom(mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse());
    }

    protected String saveSection(User user, Instant start, Instant end) throws Exception {
        return saveSection(user, start, end, null, null);
    }

    protected String saveSection(User user, Instant start, Instant end, String title, String description) throws Exception {
        return uuidFrom(mockMvc.perform(saveRequest(user, start, end, title, description))
                .andExpect(status().isOk()).andReturn().getResponse());
    }

    protected MockHttpServletRequestBuilder saveRequest(User user, Instant start, Instant end) throws Exception {
        return saveRequest(user, start, end, null, null);
    }

    protected MockHttpServletRequestBuilder saveRequest(User user, Instant start, Instant end, String title, String description) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("startTime", start.toString());
        body.put("endTime", end.toString());
        body.put("title", title);
        body.put("description", description);
        return json(post(SAVE), body).with(authenticatedAs(user));
    }

    protected String saveByMarkers(User user, Long startMarkerId, Long endMarkerId) throws Exception {
        return uuidFrom(mockMvc.perform(saveByMarkersRequest(user, startMarkerId, endMarkerId))
                .andExpect(status().isOk()).andReturn().getResponse());
    }

    protected MockHttpServletRequestBuilder saveByMarkersRequest(User user, Long startMarkerId, Long endMarkerId) throws Exception {
        return json(post(SAVE_MARKERS), Map.of("startMarkerId", startMarkerId, "endMarkerId", endMarkerId))
                .with(authenticatedAs(user));
    }

    protected String clip(User user, float duration) throws Exception {
        return uuidFrom(mockMvc.perform(clipRequest(user, duration))
                .andExpect(status().isOk()).andReturn().getResponse());
    }

    protected MockHttpServletRequestBuilder clipRequest(User user, float duration) throws Exception {
        return json(post(CLIP), Map.of("duration", duration, "title", "Live clip")).with(authenticatedAs(user));
    }

    protected MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    protected String uuidFrom(MockHttpServletResponse response) throws Exception {
        String uuid = objectMapper.readTree(response.getContentAsString()).path("data").path("uuid").asText();
        assertThat(uuid).as("job UUID in response").isNotBlank();
        return uuid;
    }

    protected MockHttpServletResponse download(String uuid) throws Exception {
        return mockMvc.perform(get(JOBS + uuid + "/download"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
    }

    protected JsonNode getJob(String uuid) throws Exception {
        String body = mockMvc.perform(get(JOBS + uuid))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    protected JsonNode getJobUnchecked(String uuid) {
        try {
            return getJob(uuid);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected boolean isRunning(JsonNode job) {
        String state = job.path("state").asText();
        return !"SUCCEEDED".equals(state) && !"FAILED".equals(state);
    }

    protected JsonNode awaitJob(String uuid) throws Exception {
        long deadline = System.nanoTime() + JOB_TIMEOUT.toNanos();
        JsonNode job = getJob(uuid);
        while (isRunning(job)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Job " + uuid + " still " + job.path("state").asText() + " after " + JOB_TIMEOUT);
            }
            Thread.sleep(50);
            job = getJob(uuid);
        }
        return job;
    }

    protected void assertSucceeded(String uuid) throws Exception {
        JsonNode job = awaitJob(uuid);
        assertThat(job.path("state").asText())
                .as("job %s state (error: %s)", uuid, job.path("errorOutput").asText())
                .isEqualTo("SUCCEEDED");
    }

    protected void assertNoJobCreated() {
        verify(jobRegistryService, never()).generateJob();
    }

    protected Clip awaitSingleClip(User user) {
        await("a clip to be persisted for user " + user.getId(), () -> !clipRepository.findByUser(user).isEmpty());
        List<Clip> clips = clipRepository.findByUser(user);
        assertThat(clips).hasSize(1);
        return clips.getFirst();
    }

    protected Vod awaitSingleVod(User user) {
        return awaitVodCount(user, 1).getFirst();
    }

    protected List<Vod> awaitVodCount(User user, int count) {
        await(count + " VoD(s) to be persisted for user " + user.getId(), () -> vodRepository.findByUser(user).size() >= count);
        List<Vod> vods = vodRepository.findByUser(user);
        assertThat(vods).hasSize(count);
        return vods;
    }

    protected BufferedImage awaitThumbnail(String storedPath) throws IOException {
        Path file = directoryService.resolvePath(storedPath);
        assertThat(file.getFileName().toString()).endsWith(".png");
        await("thumbnail " + file, () -> readImage(file) != null);

        byte[] signature = new byte[8];
        try (var in = Files.newInputStream(file)) {
            assertThat(in.read(signature)).isEqualTo(8);
        }
        assertThat(signature).as("PNG signature")
                .isEqualTo(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        return readImage(file);
    }

    protected static BufferedImage readImage(Path file) {
        try {
            return Files.isRegularFile(file) ? ImageIO.read(file.toFile()) : null;
        } catch (IOException e) {
            return null; // still being written
        }
    }

    protected Stream createStream(User user, Instant start) {
        Stream stream = new Stream();
        stream.setUser(user);
        stream.setStartDate(start);
        stream.setLastSeen(start);
        return streamRepository.save(stream);
    }

    protected Marker createMarker(User user, Stream stream, Instant timestamp) {
        Marker marker = new Marker();
        marker.setUser(user);
        marker.setStream(stream);
        marker.setMessage("marker");
        marker.setTimestamp(timestamp);
        return markerRepository.save(marker);
    }

    protected static Path usersRoot() {
        return mediaRoot().resolve("users");
    }

    protected static Path streamsRoot() {
        return mediaRoot().resolve("streams");
    }

    protected static Path streamDir(User user) {
        return streamsRoot().resolve(user.getStreamKey());
    }

    protected static Path clipsDir(User user) {
        return usersRoot().resolve(String.valueOf(user.getId())).resolve("clips");
    }

    protected static Path vodsDir(User user) {
        return usersRoot().resolve(String.valueOf(user.getId())).resolve("vods");
    }

    protected static Path tempInput(String uuid) {
        return mediaRoot().resolve("inputs").resolve(uuid + ".mp4");
    }

    protected static Path tempOutput(String uuid) {
        return mediaRoot().resolve("outputs").resolve(uuid + ".mp4");
    }

    /** Returns the first segment's start, an hour ago. */
    protected Instant writeStreamSegments(User user, int count) {
        Instant first = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        TestMedia.writeSegments(streamDir(user), first, count, SEGMENT_SECONDS);
        return first;
    }

    protected static List<Path> listFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (var paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }

    protected Path save(MockHttpServletResponse response, String name) throws IOException {
        Path file = scratch.resolve(name);
        Files.write(file, response.getContentAsByteArray());
        return file;
    }

    protected static void await(String description, BooleanSupplier condition) {
        await(description, PERSIST_TIMEOUT, condition);
    }

    protected static void await(String description, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out after " + timeout + " waiting for " + description);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for " + description, e);
            }
        }
    }
}
