package com.ddf.vodsystem.media;

import com.ddf.vodsystem.media.command.CommandOutput;
import com.ddf.vodsystem.media.command.CommandRunner;
import com.ddf.vodsystem.media.command.FFMPEGException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataServiceTest {

    @Mock
    private CommandRunner commandRunner;

    private MetadataService metadataService;

    private static final Path INPUT = Path.of("/tmp/input.mp4");

    @BeforeEach
    void setUp() {
        metadataService = new MetadataService(commandRunner);
        Thread.interrupted(); // clear any flag leaked from a previous test
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    /** Wraps a raw ffprobe JSON payload into a successful CommandOutput. */
    private CommandOutput ffprobeOutput(String json) {
        CommandOutput output = new CommandOutput();
        output.addLine(json);
        return output;
    }

    private VideoMetadata runMetadata() throws Exception {
        return metadataService.getVideoMetadata(INPUT).get();
    }
    private void assertMetadataFailsWith(Class<? extends Throwable> exceptionType, String messageFragment) {
        assertThatThrownBy(() -> metadataService.getVideoMetadata(INPUT).get())
                .satisfies(thrown -> {
                    Throwable cause = thrown instanceof ExecutionException ? thrown.getCause() : thrown;
                    assertThat(cause).isInstanceOf(exceptionType);
                    assertThat(cause.getMessage()).contains(messageFragment);
                });
    }

    private void assertMetadataFailsWith(String messageFragment) {
        assertMetadataFailsWith(FFMPEGException.class, messageFragment);
    }

    // ---------------------------------------------------------------
    // Happy path / parsing behavior
    // ---------------------------------------------------------------

    @Test
    void getVideoMetadata_validJson_parsesAllFields() throws Exception {
        String json = """
                {
                    "streams": [
                        {
                            "width": 1920,
                            "height": 1080,
                            "r_frame_rate": "30/1",
                            "duration": "60.000000"
                        }
                    ],
                    "format": {
                        "size": "10485760",
                        "duration": "60.000000"
                    }
                }
                """;
        when(commandRunner.run(anyList())).thenReturn(ffprobeOutput(json));

        VideoMetadata metadata = runMetadata();

        assertThat(metadata.duration()).isEqualTo(60.0f);
        assertThat(metadata.width()).isEqualTo(1920);
        assertThat(metadata.height()).isEqualTo(1080);
        assertThat(metadata.fps()).isEqualTo(30.0f);
        assertThat(metadata.fileSizeBytes()).isEqualTo(10485760L);
    }

    @Test
    void getVideoMetadata_ffprobeCommandIsCorrect() throws Exception {
        String json = """
                {
                    "streams": [ { "width": 1920, "height": 1080, "r_frame_rate": "30/1", "duration": "60.0" } ],
                    "format": { "size": "1000", "duration": "60.0" }
                }
                """;
        when(commandRunner.run(anyList())).thenReturn(ffprobeOutput(json));

        runMetadata();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(commandRunner).run(captor.capture());
        List<String> command = captor.getValue();

        assertThat(command).startsWith("ffprobe");
        assertThat(command).containsSequence("-print_format", "json");
        assertThat(command).containsSequence("-select_streams", "v:0");
        assertThat(command).containsSequence("-i", INPUT.toAbsolutePath().toString());
    }

    @Test
    void getVideoMetadata_fractionalFrameRate_computesFps() throws Exception {
        String json = """
                {
                    "streams": [ { "width": 1280, "height": 720, "r_frame_rate": "30000/1001", "duration": "10.0" } ],
                    "format": { "size": "500", "duration": "10.0" }
                }
                """;
        when(commandRunner.run(anyList())).thenReturn(ffprobeOutput(json));

        VideoMetadata metadata = runMetadata();

        assertThat(metadata.fps()).isCloseTo(29.97f, org.assertj.core.data.Offset.offset(0.01f));
    }

    @Test
    void getVideoMetadata_integerFrameRate_parsesFps() throws Exception {
        String json = """
                {
                    "streams": [ { "width": 1280, "height": 720, "r_frame_rate": "25", "duration": "10.0" } ],
                    "format": { "size": "500", "duration": "10.0" }
                }
                """;
        when(commandRunner.run(anyList())).thenReturn(ffprobeOutput(json));

        VideoMetadata metadata = runMetadata();

        assertThat(metadata.fps()).isEqualTo(25.0f);
    }

    @Test
    void getVideoMetadata_zeroDenominatorFrameRate_fpsIsNull() throws Exception {
        String json = """
                {
                    "streams": [ { "width": 1280, "height": 720, "r_frame_rate": "0/0", "duration": "10.0" } ],
                    "format": { "size": "500", "duration": "10.0" }
                }
                """;
        when(commandRunner.run(anyList())).thenReturn(ffprobeOutput(json));

        VideoMetadata metadata = runMetadata();

        assertThat(metadata.fps()).isNull();
    }

    @Test
    void getVideoMetadata_missingFrameRate_fpsIsNull() throws Exception {
        String json = """
                {
                    "streams": [ { "width": 1280, "height": 720, "duration": "10.0" } ],
                    "format": { "size": "500", "duration": "10.0" }
                }
                """;
        when(commandRunner.run(anyList())).thenReturn(ffprobeOutput(json));

        VideoMetadata metadata = runMetadata();

        assertThat(metadata.fps()).isNull();
    }

    @Test
    void getVideoMetadata_missingStreams_throwsFFMPEGException() throws Exception {
        String json = """
                {
                    "streams": [],
                    "format": { "size": "500", "duration": "10.0" }
                }
                """;
        when(commandRunner.run(anyList())).thenReturn(ffprobeOutput(json));

        assertMetadataFailsWith("streams missing");
    }

    @Test
    void getVideoMetadata_missingWidth_throwsFFMPEGException() throws Exception {
        String json = """
                {
                    "streams": [ { "height": 720, "r_frame_rate": "25", "duration": "10.0" } ],
                    "format": { "size": "500", "duration": "10.0" }
                }
                """;
        when(commandRunner.run(anyList())).thenReturn(ffprobeOutput(json));

        assertMetadataFailsWith("width missing");
    }

    @Test
    void getVideoMetadata_missingHeight_throwsFFMPEGException() throws Exception {
        String json = """
                {
                    "streams": [ { "width": 1280, "r_frame_rate": "25", "duration": "10.0" } ],
                    "format": { "size": "500", "duration": "10.0" }
                }
                """;
        when(commandRunner.run(anyList())).thenReturn(ffprobeOutput(json));

        assertMetadataFailsWith("height missing");
    }

    @Test
    void getVideoMetadata_missingFileSize_throwsFFMPEGException() throws Exception {
        String json = """
                {
                    "streams": [ { "width": 1280, "height": 720, "r_frame_rate": "25", "duration": "10.0" } ],
                    "format": { "duration": "10.0" }
                }
                """;
        when(commandRunner.run(anyList())).thenReturn(ffprobeOutput(json));

        assertMetadataFailsWith("file size missing");
    }

    @Test
    void getVideoMetadata_durationMissingEverywhere_throwsFFMPEGException() throws Exception {
        String json = """
                {
                    "streams": [ { "width": 1280, "height": 720, "r_frame_rate": "25" } ],
                    "format": { "size": "500" }
                }
                """;
        when(commandRunner.run(anyList())).thenReturn(ffprobeOutput(json));

        assertMetadataFailsWith("duration missing");
    }

    @Test
    void getVideoMetadata_commandThrowsIOException_completesExceptionally() throws Exception {
        when(commandRunner.run(anyList())).thenThrow(new IOException("ffprobe boom"));

        assertMetadataFailsWith(IOException.class, "ffprobe boom");
    }

    @Test
    void getVideoMetadata_streamMissingDuration_fallsBackToFormatDuration() throws Exception {
        // format=duration is requested from ffprobe specifically as a fallback source,
        // used when the stream itself lacks "duration".
        String json = """
                {
                    "streams": [ { "width": 1280, "height": 720, "r_frame_rate": "25" } ],
                    "format": { "size": "500", "duration": "45.5" }
                }
                """;
        when(commandRunner.run(anyList())).thenReturn(ffprobeOutput(json));

        VideoMetadata metadata = runMetadata();

        assertThat(metadata.duration()).isEqualTo(45.5f);
    }

    @Test
    void getVideoMetadata_largeFileSize_preservesExactByteCount() throws Exception {
        // fileSize is a Long specifically so byte counts for large files (any real
        // video is well past the 2^24 float-precision boundary) survive exactly.
        long exactBytes = 16_777_217L; // smallest integer a 32-bit float cannot represent exactly
        String json = """
                {
                    "streams": [ { "width": 1280, "height": 720, "r_frame_rate": "25", "duration": "10.0" } ],
                    "format": { "size": "%d", "duration": "10.0" }
                }
                """.formatted(exactBytes);
        when(commandRunner.run(anyList())).thenReturn(ffprobeOutput(json));

        VideoMetadata metadata = runMetadata();

        assertThat(metadata.fileSizeBytes()).isEqualTo(exactBytes);
    }

    @Test
    void getVideoMetadata_plainIOException_doesNotSetThreadInterruptFlag() throws Exception {
        when(commandRunner.run(anyList())).thenThrow(new IOException("ffprobe boom"));

        assertMetadataFailsWith(IOException.class, "ffprobe boom");

        assertThat(Thread.currentThread().isInterrupted())
                .as("a plain IOException must not leave the calling thread's interrupt flag set")
                .isFalse();
    }

    @Test
    void getVideoMetadata_interruptedException_setsThreadInterruptFlag() throws Exception {
        when(commandRunner.run(anyList())).thenThrow(new InterruptedException("interrupted"));

        assertMetadataFailsWith(InterruptedException.class, "interrupted");

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }
}
