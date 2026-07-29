package com.ddf.vodsystem.services.media;

import com.ddf.vodsystem.dto.ClipOptions;
import com.ddf.vodsystem.dto.CommandOutput;
import com.ddf.vodsystem.dto.ProgressTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamActionsServiceTest {

    @Mock
    private CommandRunner commandRunner;

    @Mock
    private MetadataService metadataService;

    private StreamActionsService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new StreamActionsService(commandRunner, metadataService);
    }

    private void createSegment(String name) throws IOException {
        Files.createFile(tempDir.resolve(name));
    }

    /** Stubs the (async) metadata probe so {@code segments.getFirst()} reports {@code seconds}. */
    private void stubHeadDuration(float seconds) {
        ClipOptions meta = new ClipOptions();
        meta.setDuration(seconds);
        when(metadataService.getVideoMetadata(any())).thenReturn(CompletableFuture.completedFuture(meta));
    }

    /**
     * Stubs the two-arg {@link CommandRunner#run} so the head re-encode pass
     * actually writes a non-empty file at its output path — {@code encodeHead}
     * verifies its output exists and is non-empty before continuing. The concat
     * pass (no {@code mpegts} muxer) is left untouched. Returns {@code output}
     * for both invocations.
     */
    private void stubRunnerProducingHead(CommandOutput output) throws IOException, InterruptedException {
        when(commandRunner.run(anyList(), any())).thenAnswer(inv -> {
            List<String> cmd = inv.getArgument(0);
            if (cmd.contains("mpegts")) {
                Files.writeString(Path.of(cmd.getLast()), "x");
            }
            return output;
        });
    }

    // ---------------------------------------------------------------
    // selectSegments: missing / invalid directories
    // ---------------------------------------------------------------

    @Test
    void selectSegments_directoryDoesNotExist_throwsIOException() {
        Path missing = tempDir.resolve("does-not-exist");

        assertThatThrownBy(() -> service.selectSegments(missing, Instant.EPOCH, Instant.now()))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void selectSegments_pathIsAFileNotADirectory_throwsIOException() throws IOException {
        Path file = tempDir.resolve("not-a-directory.txt");
        Files.createFile(file);

        assertThatThrownBy(() -> service.selectSegments(file, Instant.EPOCH, Instant.now()))
                .isInstanceOf(IOException.class);
    }

    // ---------------------------------------------------------------
    // selectSegments: filename filtering
    // ---------------------------------------------------------------

    @Test
    void selectSegments_ignoresFilesNotEndingInTs() throws IOException {
        createSegment("1.ts");
        createSegment("playlist.m3u8");
        createSegment("1.ts.tmp");
        createSegment("readme.txt");

        var result = service.selectSegments(tempDir, Instant.ofEpochMilli(0), Instant.ofEpochMilli(5000)).segments();

        assertThat(result).extracting(p -> p.getFileName().toString()).containsExactly("1.ts");
    }

    @Test
    void selectSegments_directoryNamedLikeASegment_isNotIncluded() throws IOException {
        Files.createDirectory(tempDir.resolve("5.ts"));

        // the directory is the only ".ts" entry; excluding it leaves nothing to select
        assertThatThrownBy(() ->
                service.selectSegments(tempDir, Instant.ofEpochMilli(0), Instant.ofEpochMilli(10_000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void selectSegments_malformedNonNumericFilename_throwsNumberFormatException() throws IOException {
        // A real HLS fragment name is always numeric. A corrupted or partially-written
        // file (e.g. "index.ts") crashes the whole listing instead of just being skipped.
        createSegment("index.ts");

        assertThatThrownBy(() ->
                service.selectSegments(tempDir, Instant.ofEpochMilli(0), Instant.ofEpochMilli(10_000)))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void selectSegments_emptyFilenameBeforeExtension_throwsNumberFormatException() throws IOException {
        createSegment(".ts");

        assertThatThrownBy(() ->
                service.selectSegments(tempDir, Instant.ofEpochMilli(0), Instant.ofEpochMilli(10_000)))
                .isInstanceOf(NumberFormatException.class);
    }

    // ---------------------------------------------------------------
    // selectSegments: range-boundary arithmetic
    // ---------------------------------------------------------------

    @Test
    void selectSegments_segmentEndsExactlyAtRangeStart_isExcluded() throws IOException {
        createSegment("0.ts");
        createSegment("3.ts");

        var result = service.selectSegments(tempDir, Instant.ofEpochMilli(3000), Instant.ofEpochMilli(10_000)).segments();

        assertThat(result).extracting(p -> p.getFileName().toString())
                .containsExactly("3.ts");
    }

    @Test
    void selectSegments_segmentOverlapsRangeStartByOneMs_isIncluded() throws IOException {
        createSegment("0.ts"); // spans [0, 3000)
        stubHeadDuration(3f);   // start 2999ms trims into 0.ts, so its duration is probed

        var result = service.selectSegments(tempDir, Instant.ofEpochMilli(2999), Instant.ofEpochMilli(10_000)).segments();

        assertThat(result).hasSize(1);
    }

    @Test
    void selectSegments_segmentStartsExactlyAtRangeEnd_isExcluded() throws IOException {
        createSegment("5.ts"); // starts at 5000ms

        assertThatThrownBy(() ->
                service.selectSegments(tempDir, Instant.ofEpochMilli(0), Instant.ofEpochMilli(5000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void selectSegments_segmentStartsOneMsBeforeRangeEnd_isIncluded() throws IOException {
        createSegment("5.ts"); // starts at 5000ms

        var result = service.selectSegments(tempDir, Instant.ofEpochMilli(0), Instant.ofEpochMilli(5001)).segments();

        assertThat(result).hasSize(1);
    }

    @Test
    void selectSegments_startAfterEnd_throwsIllegalArgument() throws IOException {
        createSegment("10.ts");

        assertThatThrownBy(() ->
                service.selectSegments(tempDir, Instant.ofEpochMilli(20_000), Instant.ofEpochMilli(10_000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void selectSegments_segmentsOutOfOrder_returnedInOrder() throws IOException {
        createSegment("20.ts");
        createSegment("10.ts");
        createSegment("0.ts");

        var result = service.selectSegments(tempDir, Instant.ofEpochMilli(0), Instant.ofEpochMilli(25_000)).segments();

        assertThat(result)
                .extracting(p -> p.getFileName().toString())
                .containsExactly("0.ts", "10.ts", "20.ts");
    }

    @Test
    void selectSegments_mixOfOverlappingAndNonOverlappingSegments_returnsOnlyOverlapping() throws IOException {
        createSegment("0.ts"); // [0, 3000)
        createSegment("3.ts"); // [3000, 6000)
        createSegment("6.ts"); // [6000, 9000)
        createSegment("9.ts"); // [9000, 12000)
        stubHeadDuration(3f);   // start 4000ms trims into 3.ts, so its duration is probed

        var result = service.selectSegments(tempDir, Instant.ofEpochMilli(4000), Instant.ofEpochMilli(8000)).segments();

        assertThat(result).extracting(p -> p.getFileName().toString())
                .containsExactly("3.ts", "6.ts");
    }

    @Test
    void selectSegments_resultsAreSortedByTimestampRegardlessOfDirectoryOrder() throws IOException {
        createSegment("30.ts");
        createSegment("10.ts");
        createSegment("20.ts");

        var result = service.selectSegments(tempDir, Instant.ofEpochMilli(0), Instant.ofEpochMilli(100_000)).segments();

        assertThat(result).extracting(p -> p.getFileName().toString())
                .containsExactly("10.ts", "20.ts", "30.ts");
    }

    // ---------------------------------------------------------------
    // selectSegments: head selection and trim-offset
    // ---------------------------------------------------------------

    @Test
    void selectSegments_startWithinFirstSegment_keepsItAndComputesOffset() throws IOException {
        createSegment("30.ts");
        createSegment("33.ts");
        stubHeadDuration(3f); // 30.ts spans [30s, 33s)

        var selection = service.selectSegments(tempDir, Instant.ofEpochMilli(31_500), Instant.ofEpochMilli(40_000));

        assertThat(selection.segments()).extracting(p -> p.getFileName().toString())
                .containsExactly("30.ts", "33.ts");
        assertThat(selection.trimOffset()).isEqualTo(1.5f);
        // effective start equals the requested 31.5s, so duration runs to endTime: 40 - 31.5
        assertThat(selection.duration()).isEqualTo(8.5f);
    }

    @Test
    void selectSegments_startInGapAfterFirstSegment_dropsItAndStartsAtNext() throws IOException {
        createSegment("30.ts");
        // user stops streaming; a gap follows 30.ts
        createSegment("100.ts");
        createSegment("110.ts");
        stubHeadDuration(3f); // 30.ts only holds [30s, 33s); requested start 70s is past it

        var selection = service.selectSegments(tempDir, Instant.ofEpochMilli(70_000), Instant.ofEpochMilli(111_000));

        assertThat(selection.segments()).extracting(p -> p.getFileName().toString())
                .containsExactly("100.ts", "110.ts");
        assertThat(selection.trimOffset()).isZero();
        // the dropped head shifts the effective start to 100s, so the duration
        // shrinks to end at endTime (111 - 100 = 11s) instead of the 41s the
        // original 70s->111s window would have implied
        assertThat(selection.duration()).isEqualTo(11f);
    }

    @Test
    void selectSegments_startPastOnlySegment_throwsIllegalArgument() throws IOException {
        createSegment("0.ts");
        stubHeadDuration(3f); // 0.ts holds [0s, 3s); requested start 50s has no footage

        assertThatThrownBy(() ->
                service.selectSegments(tempDir, Instant.ofEpochMilli(50_000), Instant.ofEpochMilli(60_000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void selectSegments_noSegmentsInRange_throwsIllegalArgument() {
        assertThatThrownBy(() ->
                service.selectSegments(tempDir, Instant.ofEpochMilli(0), Instant.ofEpochMilli(10_000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void selectSegments_probeFailure_surfacesAsIOException() throws IOException {
        createSegment("30.ts");
        createSegment("100.ts");
        when(metadataService.getVideoMetadata(any()))
                .thenReturn(CompletableFuture.failedFuture(new IOException("ffprobe boom")));

        assertThatThrownBy(() ->
                service.selectSegments(tempDir, Instant.ofEpochMilli(70_000), Instant.ofEpochMilli(111_000)))
                .isInstanceOf(IOException.class);
    }

    // ---------------------------------------------------------------
    // parseTimestampMs: unit-based conversion
    // ---------------------------------------------------------------

    @Test
    void parseTimestampMs_secondsBasedName_convertsToMillis() {
        long result = service.parseTimestampMs(Path.of("42.ts"));

        assertThat(result).isEqualTo(42_000L);
    }

    @Test
    void parseTimestampMs_millisecondBasedName_usedAsIs() {
        long result = service.parseTimestampMs(Path.of("1700000000000.ts"));

        assertThat(result).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void parseTimestampMs_valueExactlyAtUnitThreshold_treatedAsMillis() {
        long result = service.parseTimestampMs(Path.of("1000000000000.ts"));

        assertThat(result).isEqualTo(1_000_000_000_000L);
    }

    @Test
    void parseTimestampMs_valueOneBelowUnitThreshold_treatedAsSecondsAndMultiplied() {
        long result = service.parseTimestampMs(Path.of("999999999999.ts"));

        assertThat(result).isEqualTo(999_999_999_999_000L);
    }

    @Test
    void parseTimestampMs_nonNumericName_throwsNumberFormatException() {
        assertThatThrownBy(() -> service.parseTimestampMs(Path.of("abc.ts")))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void parseTimestampMs_filenameContainsExtraTsSubstring_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> service.parseTimestampMs(Path.of("1.ts5.ts")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------
    // saveSection
    // ---------------------------------------------------------------

    @Test
    void saveSection_success_buildsExpectedCommandAndCompletesWithCommandOutput() throws Exception {
        Path segmentA = Path.of("/tmp/streams/a.ts");
        Path segmentB = Path.of("/tmp/streams/b.ts");
        Path output = Path.of("/tmp/out/clip.mp4");
        ProgressTracker progress = new ProgressTracker();
        CommandOutput commandOutput = new CommandOutput();

        stubRunnerProducingHead(commandOutput);

        var future = service.saveSection(
                new StreamActionsService.SegmentSelection(List.of(segmentA, segmentB), 5.0f, 12.0f), output, progress);

        assertThat(future.get()).isSameAs(commandOutput);
        assertThat(progress.isComplete()).isTrue();

        // With a non-zero trim offset saveSection issues two ffmpeg passes: the
        // head re-encode (trims the first segment via -ss) followed by the
        // concat-demuxer stream copy (-f concat / -t / -c copy).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(commandRunner, Mockito.times(2)).run(captor.capture(), any());
        List<String> headCommand = captor.getAllValues().get(0);
        List<String> concatCommand = captor.getAllValues().get(1);

        assertThat(headCommand).containsSequence("-ss", "5.0");
        assertThat(concatCommand).containsSequence("-f", "concat");
        assertThat(concatCommand).containsSequence("-t", "12.0");
        assertThat(concatCommand).containsSequence("-c", "copy");
        assertThat(concatCommand).endsWith(output.toAbsolutePath().toString());
    }

    @Test
    void saveSection_zeroTrimOffset_skipsHeadReencodeAndRunsSingleConcatPass() throws Exception {
        Path output = Path.of("/tmp/out/clip.mp4");
        ProgressTracker progress = new ProgressTracker();
        CommandOutput commandOutput = new CommandOutput();

        when(commandRunner.run(anyList(), any())).thenReturn(commandOutput);

        var future = service.saveSection(
                new StreamActionsService.SegmentSelection(
                        List.of(Path.of("/tmp/a.ts"), Path.of("/tmp/b.ts")), 0f, 10f), output, progress);

        assertThat(future.get()).isSameAs(commandOutput);
        assertThat(progress.isComplete()).isTrue();

        // trimOffset 0 means the first segment already starts at the requested point:
        // no head re-encode pass, just a single concat-demuxer copy (no -ss).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(commandRunner, Mockito.times(1)).run(captor.capture(), any());
        List<String> command = captor.getValue();

        assertThat(command).containsSequence("-f", "concat");
        assertThat(command).containsSequence("-t", "10.0");
        assertThat(command).doesNotContain("-ss");
    }

    @Test
    void saveSection_commandRunnerThrowsIOException_completesExceptionallyWithoutMarkingProgressComplete() throws Exception {
        Path output = Path.of("/tmp/out/clip.mp4");
        ProgressTracker progress = new ProgressTracker();

        // With a non-zero trim offset the head re-encode is the first ffmpeg pass;
        // its failure must surface.
        when(commandRunner.run(anyList(), any())).thenThrow(new IOException("ffmpeg boom"));

        var future = service.saveSection(
                new StreamActionsService.SegmentSelection(List.of(Path.of("/tmp/a.ts")), 2f, 10f), output, progress);

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IOException.class);
        assertThat(progress.isComplete()).isFalse();
    }

    @Test
    void saveSection_emptySegmentList_completesExceptionallyWithIllegalArgumentException() {
        Path output = Path.of("/tmp/out/clip.mp4");
        ProgressTracker progress = new ProgressTracker();

        var future = service.saveSection(
                new StreamActionsService.SegmentSelection(List.of(), 0f, 10f), output, progress);

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(progress.isComplete()).isFalse();
    }

    @Test
    void saveSection_nullOutputFile_completesExceptionally() {
        ProgressTracker progress = new ProgressTracker();

        // trimOffset 0 skips the head pass; building the concat command dereferences
        // the null output path, so no ffmpeg pass ever runs.
        var future = service.saveSection(
                new StreamActionsService.SegmentSelection(List.of(Path.of("/tmp/a.ts")), 0f, 10f), null, progress);

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(NullPointerException.class);
        assertThat(progress.isComplete()).isFalse();
    }
}
