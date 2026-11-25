package com.ddf.vodsystem.stream;

public class AlreadyStreamingException extends RuntimeException {
    public AlreadyStreamingException(String message) {
        super(message);
    }
}
