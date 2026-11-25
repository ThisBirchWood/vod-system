package com.ddf.vodsystem.vod;

public class VodMediaUnavailableException extends RuntimeException {
  private final Long vodId;

  public VodMediaUnavailableException(Long vodId, String message) {
    this(vodId, message, null);
  }

  public VodMediaUnavailableException(Long vodId, String message, Throwable cause) {
    super(message, cause);
    this.vodId = vodId;
  }

  /**
   * Returns the ID of the VoD whose media was unavailable.
   *
   * @return the affected VoD's ID
   */
  public Long getVodId() {
    return vodId;
  }
}
