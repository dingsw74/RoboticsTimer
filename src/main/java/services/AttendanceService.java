package services;

import databases.AttendanceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

public class AttendanceService {
  private static final DateTimeFormatter ELAPSED_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("HH:mm:ss");

  private final AttendanceRepository repository;
  private final Clock clock;

  public AttendanceService(AttendanceRepository repository) {
    this(repository, Clock.systemDefaultZone());
  }

  public AttendanceService(AttendanceRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  public AttendanceStatus getStatus(String userId) {
    Optional<AttendanceSummary> summary = getSummary(userId);
    return summary.isPresent() ? summary.get().getStatus() : AttendanceStatus.NOT_FOUND;
  }

  public Optional<AttendanceSummary> getSummary(String userId) {
    repository.refresh();
    Optional<Integer> userRow = repository.findUserRow(userId);
    if (!userRow.isPresent()) {
      return Optional.empty();
    }
    int row = userRow.get();
    AttendanceStatus status =
        repository.isLoggedIn(row) ? AttendanceStatus.LOGGED_IN : AttendanceStatus.LOGGED_OUT;
    return Optional.of(
        new AttendanceSummary(
            status, repository.getLastTimeHours(row), repository.getTotalHours(row)));
  }

  public AttendanceStatus loginUser(String userId) {
    return loginUser(userId, LocalDateTime.now(clock));
  }

  public AttendanceStatus loginUser(String userId, LocalDateTime loginTime) {
    repository.refresh();
    Optional<Integer> userRow = repository.findUserRow(userId);
    if (!userRow.isPresent()) {
      return AttendanceStatus.NOT_FOUND;
    }

    if (repository.isLoggedIn(userRow.get())) {
      throw new InvalidAttendanceTransitionException("User is already logged in.");
    }

    repository.recordLogin(userRow.get(), loginTime);
    return AttendanceStatus.LOGGED_IN;
  }

  public LogoutResult logoutUser(String userId) {
    return logoutUser(userId, LocalDateTime.now(clock));
  }

  public LogoutResult logoutUser(String userId, LocalDateTime logoutTime) {
    repository.refresh();
    Optional<Integer> userRow = repository.findUserRow(userId);
    if (!userRow.isPresent()) {
      return new LogoutResult(LogoutResult.Outcome.USER_NOT_FOUND, 0);
    }

    if (!repository.isLoggedIn(userRow.get())) {
      throw new InvalidAttendanceTransitionException("User is not logged in.");
    }

    LocalDateTime loginTime = repository.getLastLogin(userRow.get());
    Duration duration = Duration.between(loginTime, logoutTime);
    boolean durationIsValid =
        !duration.isNegative() && loginTime.toLocalDate().equals(logoutTime.toLocalDate());

    String elapsedTime = null;
    long durationSeconds = 0;
    if (durationIsValid) {
      durationSeconds = duration.getSeconds();
      LocalTime elapsed = LocalTime.ofSecondOfDay(durationSeconds);
      elapsedTime = elapsed.format(ELAPSED_TIME_FORMATTER);
    }

    repository.recordLogout(userId, userRow.get(), logoutTime, elapsedTime);
    LogoutResult.Outcome outcome =
        durationIsValid
            ? LogoutResult.Outcome.LOGGED_OUT
            : LogoutResult.Outcome.LOGGED_OUT_WITHOUT_DURATION;
    return new LogoutResult(outcome, durationSeconds);
  }
}
