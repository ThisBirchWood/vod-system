package com.ddf.vodsystem.vod;

public class VodNotFoundException extends RuntimeException {
    public VodNotFoundException(String message) {
        super(message);
    }
}
