package databases;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AttendanceRepository {
  void refresh();

  Optional<Integer> findUserRow(String userId);

  boolean isLoggedIn(int userRow);

  LocalDateTime getLastLogin(int userRow);

  String getLastTimeHours(int userRow);

  String getTotalHours(int userRow);

  void recordLogin(int userRow, LocalDateTime loginTime);

  void recordLogout(String userId, int userRow, LocalDateTime logoutTime, String elapsedTime);
}
