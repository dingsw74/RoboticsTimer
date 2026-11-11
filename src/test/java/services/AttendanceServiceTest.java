package services;

import databases.AttendanceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;

public class AttendanceServiceTest {
  private static final String USER_ID = "001234";
  private static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");

  @Test
  public void statusReportsNotFoundAndCurrentLoginState() {
    FakeAttendanceRepository repository = new FakeAttendanceRepository();
    AttendanceService service = service(repository, "2026-11-10T20:00:00Z");

    Assert.assertEquals(AttendanceStatus.NOT_FOUND, service.getStatus("missing"));
    Assert.assertEquals(AttendanceStatus.LOGGED_OUT, service.getStatus(USER_ID));
    Assert.assertEquals("00:30:00", service.getSummary(USER_ID).get().getLastTimeHours());
    Assert.assertEquals("12:45:00", service.getSummary(USER_ID).get().getTotalHours());

    repository.loggedIn = true;
    Assert.assertEquals(AttendanceStatus.LOGGED_IN, service.getStatus(USER_ID));
  }

  @Test
  public void loginPersistsInjectedCurrentTime() {
    FakeAttendanceRepository repository = new FakeAttendanceRepository();
    AttendanceService service = service(repository, "2026-11-10T20:00:00Z");

    Assert.assertEquals(AttendanceStatus.LOGGED_IN, service.loginUser(USER_ID));
    Assert.assertEquals(LocalDateTime.of(2026, 11, 10, 12, 0), repository.lastLogin);
  }

  @Test
  public void logoutPersistsElapsedTimeForSameDaySession() {
    FakeAttendanceRepository repository = new FakeAttendanceRepository();
    repository.loggedIn = true;
    repository.lastLogin = LocalDateTime.of(2026, 11, 10, 12, 0);
    AttendanceService service = service(repository, "2026-11-10T21:15:30Z");

    LogoutResult result = service.logoutUser(USER_ID);

    Assert.assertEquals(LogoutResult.Outcome.LOGGED_OUT, result.getOutcome());
    Assert.assertEquals(4530, result.getDurationSeconds());
    Assert.assertEquals("01:15:30", repository.recordedElapsedTime);
  }

  @Test
  public void logoutAcrossDateBoundaryClosesWithoutCreditingDuration() {
    FakeAttendanceRepository repository = new FakeAttendanceRepository();
    repository.loggedIn = true;
    repository.lastLogin = LocalDateTime.of(2026, 11, 10, 23, 55);
    AttendanceService service = service(repository, "2026-11-11T08:05:00Z");

    LogoutResult result = service.logoutUser(USER_ID);

    Assert.assertEquals(LogoutResult.Outcome.LOGGED_OUT_WITHOUT_DURATION, result.getOutcome());
    Assert.assertEquals(0, result.getDurationSeconds());
    Assert.assertNull(repository.recordedElapsedTime);
  }

  private AttendanceService service(FakeAttendanceRepository repository, String now) {
    Clock clock = Clock.fixed(Instant.parse(now), ZONE);
    return new AttendanceService(repository, clock);
  }

  private static class FakeAttendanceRepository implements AttendanceRepository {
    private boolean userExists = true;
    private boolean loggedIn;
    private LocalDateTime lastLogin;
    private String recordedElapsedTime;

    @Override
    public void refresh() {}

    @Override
    public Optional<Integer> findUserRow(String userId) {
      return userExists && USER_ID.equals(userId) ? Optional.of(1) : Optional.empty();
    }

    @Override
    public boolean isLoggedIn(int userRow) {
      return loggedIn;
    }

    @Override
    public LocalDateTime getLastLogin(int userRow) {
      return lastLogin;
    }

    @Override
    public String getLastTimeHours(int userRow) {
      return "00:30:00";
    }

    @Override
    public String getTotalHours(int userRow) {
      return "12:45:00";
    }

    @Override
    public void recordLogin(int userRow, LocalDateTime loginTime) {
      loggedIn = true;
      lastLogin = loginTime;
    }

    @Override
    public void recordLogout(
        String userId, int userRow, LocalDateTime logoutTime, String elapsedTime) {
      loggedIn = false;
      recordedElapsedTime = elapsedTime;
    }
  }
}
