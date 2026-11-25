package com.ddf.vodsystem.media;

import com.ddf.vodsystem.job.ProgressTracker;
import com.ddf.vodsystem.media.command.CommandOutput;
import com.ddf.vodsystem.media.command.CommandRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RemuxServiceTest {

    @Mock
    private CommandRunner commandRunner;

    private RemuxService remuxService;

    @BeforeEach
    void setUp() {
        remuxService = new RemuxService(commandRunner);
        Thread.interrupted();
    }

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void remux_success_completesWithCommandOutput() throws Exception {
        File input = new File("videos/input.ts");
        File output = new File("videos/output.mp4");
        CommandOutput commandOutput = new CommandOutput();
        when(commandRunner.run(anyList(), any())).thenReturn(commandOutput);

        var future = remuxService.remux(input, output, new ProgressTracker(), 60f);

        assertThat(future.get()).isSameAs(commandOutput);
    }

    @Test
    void remux_ioFailure_completesExceptionally() throws Exception {
        when(commandRunner.run(anyList(), any())).thenThrow(new IOException("ffmpeg failed"));

        var future = remuxService.remux(
                new File("videos/input.ts"),
                new File("videos/output.mp4"),
                new ProgressTracker(),
                60f
        );

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IOException.class);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void remux_interruption_completesExceptionallyAndRestoresInterruptFlag() throws Exception {
        when(commandRunner.run(anyList(), any())).thenThrow(new InterruptedException("interrupted"));

        var future = remuxService.remux(
                new File("videos/input.ts"),
                new File("videos/output.mp4"),
                new ProgressTracker(),
                60f
        );

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }
}
