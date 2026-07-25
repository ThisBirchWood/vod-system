package com.ddf.vodsystem.exceptions;

public class ClipMediaUnavailable extends RuntimeException {
  private final Long clipId;

  public ClipMediaUnavailable(Long clipId, String message) {
    this(clipId, message, null);
  }

  public ClipMediaUnavailable(Long clipId, String message, Throwable cause) {
    super(message, cause);
    this.clipId = clipId;
  }

  public Long getClipId() {
    return clipId;
  }
}
