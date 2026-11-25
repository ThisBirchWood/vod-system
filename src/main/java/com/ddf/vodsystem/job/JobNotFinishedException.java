package com.ddf.vodsystem.job;

public class JobNotFinishedException extends RuntimeException {
    public JobNotFinishedException(String message) {
        super(message);
    }
}
