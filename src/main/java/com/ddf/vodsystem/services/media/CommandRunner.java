package com.ddf.vodsystem.services.media;

import com.ddf.vodsystem.dto.CommandOutput;
import com.ddf.vodsystem.dto.ProgressTracker;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CommandRunner {
    private static final Pattern timePattern = Pattern.compile("out_time_ms=(\\d+)");

    /**
     * Runs an external command, capturing its merged stdout/stderr and streaming each line to a callback.
     *
     * @param command  the command and its arguments
     * @param onOutput invoked for each output line as it is read, or {@code null} to skip streaming
     * @return the captured output and exit code
     * @throws IOException          if the process fails to start or exits with a non-zero code
     * @throws InterruptedException if the current thread is interrupted while awaiting the process
     */
    public CommandOutput run(List<String> command, Consumer<String> onOutput) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        CommandOutput commandOutput = new CommandOutput();

        // Read the output and error streams
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            commandOutput.addLine(line);
            if (onOutput != null) {
                onOutput.accept(line);
            }
        }

        int exitCode = process.waitFor();
        commandOutput.setExitCode(exitCode);

        if (exitCode != 0) {
            throw new IOException("Command failed with exit code " + exitCode + ": " + String.join("\n", commandOutput.getOutput()));
        }

        return commandOutput;
    }

    /**
     * Runs an external command, capturing its output without streaming.
     *
     * @param command the command and its arguments
     * @return the captured output and exit code
     * @throws IOException          if the process fails to start or exits with a non-zero code
     * @throws InterruptedException if the current thread is interrupted while awaiting the process
     */
    public CommandOutput run(List<String> command) throws IOException, InterruptedException {
        return run(command, null);
    }

    /**
     * Parses an ffmpeg progress line and updates the tracker with the fraction completed.
     * <p>
     * Lines without an {@code out_time_ms} field are ignored.
     *
     * @param line     a single line of ffmpeg {@code -progress} output
     * @param progress the tracker to update
     * @param length   the total media length in seconds, used as the progress denominator
     */
    public void setProgress(String line, ProgressTracker progress, float length) {
        Matcher matcher = timePattern.matcher(line);
        if (matcher.find()) {
            float timeInMs = Float.parseFloat(matcher.group(1)) / 1000000f;
            progress.setProgress(timeInMs / length);
        }
    }
}