package com.ddf.vodsystem.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CommandOutput {
    private List<String> output = new ArrayList<>();
    private int exitCode;

    /**
     * Appends a line to the captured command output.
     *
     * @param line the output line to append
     */
    public void addLine(String line) {
        output.add(line);
    }
}