package com.ddf.vodsystem.stream;

public class NotStreamingException extends RuntimeException {
    public NotStreamingException(String message) {
        super(message);
    }
}
