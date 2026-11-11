package services;

public class LogoutResult {
  public enum Outcome {
    USER_NOT_FOUND,
    LOGGED_OUT,
    LOGGED_OUT_WITHOUT_DURATION
  }

  private final Outcome outcome;
  private final long durationSeconds;

  public LogoutResult(Outcome outcome, long durationSeconds) {
    this.outcome = outcome;
    this.durationSeconds = durationSeconds;
  }

  public Outcome getOutcome() {
    return outcome;
  }

  public long getDurationSeconds() {
    return durationSeconds;
  }
}
