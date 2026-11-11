package services;

public class AttendanceSummary {
  private final AttendanceStatus status;
  private final String lastTimeHours;
  private final String totalHours;

  public AttendanceSummary(AttendanceStatus status, String lastTimeHours, String totalHours) {
    this.status = status;
    this.lastTimeHours = lastTimeHours;
    this.totalHours = totalHours;
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
}
