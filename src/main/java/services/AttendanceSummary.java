package services;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

public class AttendanceSummary {
  private final AttendanceStatus status;
  private final String lastTimeHours;
  private final String totalHours;
  private final Optional<LocalDateTime> lastLogin;
  private final Optional<LocalDateTime> lastLogout;
  private final Optional<Duration> currentCheckInDuration;

  public AttendanceSummary(
      AttendanceStatus status,
      String lastTimeHours,
      String totalHours,
      Optional<LocalDateTime> lastLogin,
      Optional<LocalDateTime> lastLogout,
      Optional<Duration> currentCheckInDuration) {
    this.status = status;
    this.lastTimeHours = lastTimeHours;
    this.totalHours = totalHours;
    this.lastLogin = lastLogin;
    this.lastLogout = lastLogout;
    this.currentCheckInDuration = currentCheckInDuration;
  }

  public AttendanceStatus getStatus() {
    return status;
  }

  public String getLastTimeHours() {
    return lastTimeHours;
  }

  public String getTotalHours() {
    return totalHours;
  }

  public Optional<LocalDateTime> getLastLogin() {
    return lastLogin;
  }

  public Optional<LocalDateTime> getLastLogout() {
    return lastLogout;
  }

  public Optional<Duration> getCurrentCheckInDuration() {
    return currentCheckInDuration;
  }
}
