package com.ddf.vodsystem.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

/** Generates fixtures with ffmpeg and inspects outputs with ffprobe, so no binaries are checked in. */
final class TestMedia {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, Path> FIXTURES = new ConcurrentHashMap<>();
    private static Path fixtureDir;

    private TestMedia() {}

    /** Generated once per JVM and shared across test classes. */
    static Path fixture(String name, Function<Path, Path> generator) {
        return FIXTURES.computeIfAbsent(name, n -> generator.apply(fixtureDir().resolve(n)));
    }

    private static synchronized Path fixtureDir() {
        if (fixtureDir == null) {
            try {
                fixtureDir = Files.createTempDirectory("vodsystem-fixtures-");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursively(fixtureDir)));
        }
        return fixtureDir;
    }

    static VideoSpec video() {
        return new VideoSpec();
    }

    static final class VideoSpec {
        private int width = 640;
        private int height = 360;
        private String rate = "30";
        private double seconds = 4;
        private boolean audio = true;
        private boolean variableFrameRate = false;

        VideoSpec size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        VideoSpec rate(String rate) {
            this.rate = rate;
            return this;
        }

        VideoSpec seconds(double seconds) {
            this.seconds = seconds;
            return this;
        }

        VideoSpec noAudio() {
            this.audio = false;
            return this;
        }

        /** Leaves a timestamp gap so frame durations aren't uniform. */
        VideoSpec variableFrameRate() {
            this.variableFrameRate = true;
            return this;
        }

        Path writeTo(Path target) {
            List<String> command = new ArrayList<>(List.of(
                    "ffmpeg", "-v", "error", "-y",
                    "-f", "lavfi", "-i", "testsrc2=size=%dx%d:rate=%s:duration=%s".formatted(width, height, rate, seconds)));
            if (audio) {
                command.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:duration=" + seconds));
            }
            if (variableFrameRate) {
                command.addAll(List.of("-vf", "select='lt(n\\,30)+gte(n\\,60)'", "-fps_mode", "vfr"));
            }
            command.addAll(List.of("-c:v", "libx264", "-pix_fmt", "yuv420p"));
            if (audio) {
                command.addAll(List.of("-c:a", "aac", "-shortest"));
            }
            command.add(target.toAbsolutePath().toString());

            run(command, target.getParent());
            return target;
        }
    }

    static Path corruptFile(Path target) {
        byte[] garbage = new byte[64 * 1024];
        new Random(42).nextBytes(garbage);
        try {
            Files.write(target, garbage);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return target;
    }

    /** Back-to-back MPEG-TS segments named by epoch-second start time, as nginx-rtmp writes them. */
    static List<Path> writeSegments(Path streamDir, Instant firstStart, int count, int segmentSeconds) {
        long first = firstStart.getEpochSecond();
        List<Instant> starts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            starts.add(Instant.ofEpochSecond(first + (long) i * segmentSeconds));
        }
        return writeSegments(streamDir, starts, segmentSeconds);
    }

    /**
     * Segments are cut from one continuous encode whatever their names say, so names spaced wider
     * than {@code segmentSeconds} simulate a segment shorter than the gap to the next.
     */
    static List<Path> writeSegments(Path streamDir, List<Instant> startTimes, int segmentSeconds) {
        try {
            Files.createDirectories(streamDir);
            Path work = Files.createTempDirectory("segments-");
            try {
                int count = startTimes.size();
                run(List.of(
                        "ffmpeg", "-v", "error", "-y",
                        "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30:duration=" + count * segmentSeconds,
                        "-f", "lavfi", "-i", "sine=frequency=440:duration=" + count * segmentSeconds,
                        "-c:v", "libx264", "-pix_fmt", "yuv420p",
                        // One keyframe per segment boundary, so every cut lands exactly on one
                        "-g", String.valueOf(30 * segmentSeconds),
                        "-keyint_min", String.valueOf(30 * segmentSeconds),
                        "-sc_threshold", "0",
                        "-c:a", "aac", "-shortest",
                        "-f", "segment",
                        "-segment_time", String.valueOf(segmentSeconds),
                        "-segment_time_delta", "0.05",
                        "-segment_format", "mpegts",
                        "-reset_timestamps", "0",
                        work.resolve("seg%04d.ts").toString()
                ), work);

                List<Path> produced;
                try (Stream<Path> files = Files.list(work)) {
                    produced = files.sorted(Comparator.comparing(Path::toString)).toList();
                }
                if (produced.size() != count) {
                    throw new IllegalStateException("Expected " + count + " segments but ffmpeg produced " + produced.size());
                }

                List<Path> segments = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    Path target = streamDir.resolve(startTimes.get(i).getEpochSecond() + ".ts");
                    Files.move(produced.get(i), target);
                    segments.add(target);
                }
                return segments;
            } finally {
                deleteRecursively(work);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Durations are {@code NaN} when absent. */
    record Probe(
            int width,
            int height,
            String rFrameRate,
            double videoDuration,
            double formatDuration,
            long size,
            boolean hasAudio,
            int videoFrames
    ) {
        double fps() {
            return parseRate(rFrameRate);
        }
    }

    static Probe probe(Path file) {
        String json = run(List.of(
                "ffprobe", "-v", "error",
                "-count_frames",
                "-print_format", "json",
                "-show_format", "-show_streams",
                file.toAbsolutePath().toString()
        ), file.getParent());

        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode video = null;
            boolean hasAudio = false;
            for (JsonNode stream : root.path("streams")) {
                String type = stream.path("codec_type").asText();
                if ("video".equals(type) && video == null) {
                    video = stream;
                } else if ("audio".equals(type)) {
                    hasAudio = true;
                }
            }
            if (video == null) {
                throw new IllegalStateException("No video stream in " + file);
            }
            JsonNode format = root.path("format");
            return new Probe(
                    video.path("width").asInt(),
                    video.path("height").asInt(),
                    video.path("r_frame_rate").asText(),
                    doubleOrNaN(video, "duration"),
                    doubleOrNaN(format, "duration"),
                    format.path("size").asLong(),
                    hasAudio,
                    video.path("nb_read_frames").asInt(-1)
            );
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static double parseRate(String rate) {
        if (rate.contains("/")) {
            String[] parts = rate.split("/");
            return Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
        }
        return Double.parseDouble(rate);
    }

    private static double doubleOrNaN(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asDouble() : Double.NaN;
    }

    private static String run(List<String> command, Path workingDir) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workingDir == null ? null : workingDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("Command failed (" + exit + "): " + String.join(" ", command) + "\n" + output);
            }
            return output;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
