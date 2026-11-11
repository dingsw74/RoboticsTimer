package services;

public class InvalidAttendanceTransitionException extends RuntimeException {
  public InvalidAttendanceTransitionException(String message) {
    super(message);
  }
}
