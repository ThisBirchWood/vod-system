package com.ddf.vodsystem.media;

import com.ddf.vodsystem.media.command.CommandOutput;
import com.ddf.vodsystem.media.command.CommandRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThumbnailServiceTest {

    @Mock
    private CommandRunner commandRunner;

    private ThumbnailService thumbnailService;

    @BeforeEach
    void setUp() {
        thumbnailService = new ThumbnailService(commandRunner);
        Thread.interrupted();
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void createThumbnail_success_completesWithCommandOutput() throws Exception {
        Path input = Path.of("videos/input.mp4");
        Path output = Path.of("thumbnails/output.jpg");
        CommandOutput commandOutput = new CommandOutput();
        when(commandRunner.run(anyList())).thenReturn(commandOutput);

        var future = thumbnailService.createThumbnail(input, output, 12.5f);

        assertThat(future.get()).isSameAs(commandOutput);
    }

    @Test
    void createThumbnail_ioFailure_completesExceptionally() throws Exception {
        when(commandRunner.run(anyList())).thenThrow(new IOException("ffmpeg failed"));

        var future = thumbnailService.createThumbnail(
                Path.of("videos/input.mp4"),
                Path.of("thumbnails/output.jpg"),
                12.5f
        );

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IOException.class);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void createThumbnail_interruption_completesExceptionallyAndRestoresInterruptFlag() throws Exception {
        when(commandRunner.run(anyList())).thenThrow(new InterruptedException("interrupted"));

        var future = thumbnailService.createThumbnail(
                Path.of("videos/input.mp4"),
                Path.of("thumbnails/output.jpg"),
                12.5f
        );

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }
}
