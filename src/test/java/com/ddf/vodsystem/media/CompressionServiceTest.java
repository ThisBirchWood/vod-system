package com.ddf.vodsystem.media;

import com.ddf.vodsystem.media.command.CommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompressionServiceTest {
    @Mock
    private CommandRunner commandRunner;

    private CompressionService compressionService;

    @BeforeEach
    void setUp() {
        compressionService = new CompressionService(commandRunner);
    }

    @Test
    void buildCommand_minimalSpec_smallCommand() {
        Path input = Path.of("/tmp/input.mp4");
        Path output = Path.of("/tmp/output.mp4");
        CompressionSpec options = new CompressionSpec(
                "hello",
                null,
                10.0f,
                30.0f,
                null,
                null,
                null,
                null
        );

        List<String> command = compressionService.buildCommand(input, output, options);

        assertThat(command).containsSubsequence("-ss", "10.0");
        assertThat(command).containsSubsequence("-t", "30.0");
        assertThat(command).containsSubsequence("-i", input.toAbsolutePath().toString());

        assertThat(command).containsSequence("-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2");
        assertThat(command).doesNotContain("-b:v");

        assertThat(command).endsWith(output.toAbsolutePath().toString());
    }

    @Test
    void buildCommand_hasFileSize_hasCorrectBitrate() {
        Path input = Path.of("/tmp/input.mp4");
        Path output = Path.of("/tmp/output.mp4");
        CompressionSpec options = new CompressionSpec(
                "hello",
                null,
                10.0f,
                30.0f,
                null,
                null,
                null,
                10L
        );

        float maximumBitrateKbps = (options.fileSize() * 8 * 1000) / options.duration();

        List<String> command = compressionService.buildCommand(input, output, options);

        assertThat(command).contains("-b:v", "-b:a");

        int bvIndex = command.indexOf("-b:v");
        int baIndex = command.indexOf("-b:a");

        String videoBitrateStr = command.get(bvIndex + 1);
        String audioBitrateStr = command.get(baIndex + 1);

        assertThat(videoBitrateStr).isNotBlank().endsWith("k");
        assertThat(audioBitrateStr).isNotBlank().endsWith("k");

        // Strip the trailing "k" so we can compare the numbers, not the strings
        float videoBitrateKbps = Float.parseFloat(videoBitrateStr.replace("k", ""));
        float audioBitrateKbps = Float.parseFloat(audioBitrateStr.replace("k", ""));

        float totalBitrateKbps = videoBitrateKbps + audioBitrateKbps;

        assertThat(totalBitrateKbps).isLessThanOrEqualTo(maximumBitrateKbps);
    }

    @Test
    void buildCommand_hasWidthHeight_hasVideoFilters() {
        Path input = Path.of("/tmp/input.mp4");
        Path output = Path.of("/tmp/output.mp4");
        CompressionSpec options = new CompressionSpec(
                "hello",
                null,
                10.0f,
                30.0f,
                null,
                1920,
                1080,
                null
        );

        List<String> command = compressionService.buildCommand(input, output, options);

        assertThat(command).containsSequence("-vf", "scale=1920:1080");
    }

    @Test
    void buildCommand_onlyWidth_aspectRatioKept() {
        Path input = Path.of("/tmp/input.mp4");
        Path output = Path.of("/tmp/output.mp4");
        CompressionSpec options = new CompressionSpec(
                "hello",
                null,
                10.0f,
                30.0f,
                null,
                1920,
                null,
                null
        );

        List<String> command = compressionService.buildCommand(input, output, options);

        assertThat(command).containsSequence("-vf", "scale=1920:-2");
    }

    @Test
    void buildCommand_onlyHeight_aspectRatioKept() {
        Path input = Path.of("/tmp/input.mp4");
        Path output = Path.of("/tmp/output.mp4");
        CompressionSpec options = new CompressionSpec(
                "hello",
                null,
                10.0f,
                30.0f,
                null,
                null,
                1080,
                null
        );

        List<String> command = compressionService.buildCommand(input, output, options);

        assertThat(command).containsSequence("-vf", "scale=-2:1080");
    }

    @Test
    void buildCommand_oddDimensions_roundedDownToEven() {
        Path input = Path.of("/tmp/input.mp4");
        Path output = Path.of("/tmp/output.mp4");
        CompressionSpec options = new CompressionSpec(
                "hello",
                null,
                10.0f,
                30.0f,
                null,
                1281,
                721,
                null
        );

        List<String> command = compressionService.buildCommand(input, output, options);

        assertThat(command).containsSequence("-vf", "scale=1280:720");
    }

    @Test
    void buildCommand_dimensionOfOne_clampedToTwo() {
        Path input = Path.of("/tmp/input.mp4");
        Path output = Path.of("/tmp/output.mp4");
        CompressionSpec options = new CompressionSpec(
                "hello",
                null,
                10.0f,
                30.0f,
                null,
                1,
                null,
                null
        );

        List<String> command = compressionService.buildCommand(input, output, options);

        assertThat(command).containsSequence("-vf", "scale=2:-2");
    }

    @Test
    void buildCommand_onlyHasFps_keepsNativeSizeRoundedToEven() {
        Path input = Path.of("/tmp/input.mp4");
        Path output = Path.of("/tmp/output.mp4");
        CompressionSpec options = new CompressionSpec(
                "hello",
                null,
                10.0f,
                30.0f,
                60f,
                null,
                null,
                null
        );

        List<String> command = compressionService.buildCommand(input, output, options);

        assertThat(command).containsSequence("-vf", "fps=60.0,scale=trunc(iw/2)*2:trunc(ih/2)*2");
    }

    @Test
    void buildCommand_noInputFile_throwsNullPointer() {
        Path output = Path.of("/tmp/output.mp4");
        CompressionSpec options = new CompressionSpec(
                "hello",
                null,
                10.0f,
                30.0f,
                null,
                null,
                null,
                null
        );

        assertThrows(NullPointerException.class, () ->
                compressionService.buildCommand(null, output, options)
        );
    }

    @Test
    void buildCommand_noOutputFile_throwsNullPointer() {
        Path input = Path.of("/tmp/output.mp4");
        CompressionSpec options = new CompressionSpec(
                "hello",
                null,
                10.0f,
                30.0f,
                null,
                null,
                null,
                null
        );

        assertThrows(NullPointerException.class, () ->
                compressionService.buildCommand(input, null, options)
        );
    }
}