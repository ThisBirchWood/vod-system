package com.ddf.vodsystem.exceptions;

public class VodMediaUnavailable extends RuntimeException {
  private final Long vodId;

  public VodMediaUnavailable(Long vodId, String message) {
    this(vodId, message, null);
  }

  public VodMediaUnavailable(Long vodId, String message, Throwable cause) {
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
