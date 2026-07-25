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

  public Long getVodId() {
    return vodId;
  }
}
