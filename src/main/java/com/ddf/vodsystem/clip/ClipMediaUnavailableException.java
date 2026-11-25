package com.ddf.vodsystem.clip;

public class ClipMediaUnavailableException extends RuntimeException {
  private final Long clipId;

  public ClipMediaUnavailableException(Long clipId, String message) {
    this(clipId, message, null);
  }

  public ClipMediaUnavailableException(Long clipId, String message, Throwable cause) {
    super(message, cause);
    this.clipId = clipId;
  }

  /**
   * Returns the ID of the clip whose media was unavailable.
   *
   * @return the affected clip's ID
   */
  public Long getClipId() {
    return clipId;
  }
}
