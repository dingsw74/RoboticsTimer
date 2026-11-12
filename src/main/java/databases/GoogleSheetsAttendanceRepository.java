package databases;

import activities.LogoutActivity;
import helpers.Constants;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class GoogleSheetsAttendanceRepository implements AttendanceRepository {
  private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;

  private final DatabaseUtils dbUtils;
  private final LogoutActivity logoutActivity;

  public GoogleSheetsAttendanceRepository(DatabaseUtils dbUtils) {
    this.dbUtils = dbUtils;
    this.logoutActivity = new LogoutActivity(dbUtils);
  }

  @Override
  public void refresh() {
    dbUtils.getUpdatedData();
  }

  @Override
  public Optional<Integer> findUserRow(String userId) {
    int userRow =
        dbUtils.getCellRowFromColumn(userId, Constants.kStudentIdColumn, Constants.kMainSheet);
    return userRow == -1 ? Optional.empty() : Optional.of(userRow);
  }

  @Override
  public boolean isLoggedIn(int userRow) {
    String cellData = dbUtils.getCellData(userRow, Constants.kLoggedInColumn, Constants.kMainSheet);
    return cellData != null && cellData.replaceAll("\\s+", "").equalsIgnoreCase("TRUE");
  }

  @Override
  public String getUserName(int userRow) {
    String firstName =
        valueOrEmpty(dbUtils.getCellData(userRow, Constants.kFirstNameColumn, Constants.kMainSheet))
            .trim();
    String lastName =
        valueOrEmpty(dbUtils.getCellData(userRow, Constants.kLastNameColumn, Constants.kMainSheet))
            .trim();
    return (firstName + " " + lastName).trim();
  }

  @Override
  public Optional<LocalDateTime> getLastLogin(int userRow) {
    return getTimestamp(userRow, Constants.kLastLoginColumn);
  }

  @Override
  public Optional<LocalDateTime> getLastLogout(int userRow) {
    return getTimestamp(userRow, Constants.kLastLogoutColumn);
  }

  @Override
  public String getLastTimeHours(int userRow) {
    return dbUtils.getCellData(userRow, Constants.kHoursColumn, Constants.kMainSheet);
  }

  @Override
  public String getTotalHours(int userRow) {
    return dbUtils.getCellData(userRow, Constants.kTotalHoursColumn, Constants.kMainSheet);
  }

  @Override
  public void recordLogin(int userRow, LocalDateTime loginTime) {
    String userId = dbUtils.getCellData(userRow, Constants.kStudentIdColumn, Constants.kMainSheet);
    ensureLogSheetUserRow(userId, userRow);

    ArrayList<BatchUpdateData> data = new ArrayList<>();
    data.add(
        new BatchUpdateData(
            userRow, Constants.kLastLoginColumn, loginTime.format(TIMESTAMP_FORMATTER)));
    data.add(new BatchUpdateData(userRow, Constants.kLoggedInColumn, "TRUE"));
    data.add(new BatchUpdateData(userRow, Constants.kLastLogoutColumn, "LOGGED IN"));
    dbUtils.setCellDataBatch(data, Constants.kMainSheet);
  }

  @Override
  public void recordLogout(
      String userId, int userRow, LocalDateTime logoutTime, String elapsedTime) {
    ensureLogSheetUserRow(userId, userRow);

    dbUtils.setCellData(
        userRow,
        Constants.kLastLogoutColumn,
        logoutTime.format(TIMESTAMP_FORMATTER),
        Constants.kMainSheet);

    if (elapsedTime != null) {
      logoutActivity.logoutUserWithHours(userId, userRow, elapsedTime, logoutTime.toLocalDate());
    }

    dbUtils.setCellData(userRow, Constants.kLoggedInColumn, "FALSE", Constants.kMainSheet);
  }

  private void ensureLogSheetUserRow(String userId, int userRow) {
    if (userId == null || userId.trim().isEmpty()) {
      throw new IllegalStateException("Cannot create a Date Log row without a user ID.");
    }

    int existingRow =
        dbUtils.getCellRowFromColumn(userId, Constants.kStudentIdColumn, Constants.kLogSheet);
    if (existingRow >= 0) {
      return;
    }

    String currentSheetUserId =
        dbUtils.getCellData(userRow, Constants.kStudentIdColumn, Constants.kMainSheet);
    if (!userId.equals(currentSheetUserId)) {
      throw new IllegalStateException(
          "Cannot create a Date Log row because the Current-sheet user row does not match.");
    }

    List<String> logUserIds =
        dbUtils.getColumnData(Constants.kStudentIdColumn, Constants.kLogSheet);
    if (logUserIds == null) {
      throw new IllegalStateException("Cannot read user IDs from the Date Log sheet.");
    }

    int newRow = findNextAvailableLogSheetRow(logUserIds);
    ArrayList<BatchUpdateData> rowData = new ArrayList<>();
    rowData.add(new BatchUpdateData(newRow, Constants.kStudentIdColumn, userId));
    rowData.add(
        new BatchUpdateData(
            newRow,
            Constants.kFirstNameColumn,
            valueOrEmpty(
                dbUtils.getCellData(userRow, Constants.kFirstNameColumn, Constants.kMainSheet))));
    rowData.add(
        new BatchUpdateData(
            newRow,
            Constants.kLastNameColumn,
            valueOrEmpty(
                dbUtils.getCellData(userRow, Constants.kLastNameColumn, Constants.kMainSheet))));

    dbUtils.setCellDataBatch(rowData, Constants.kLogSheet);
    dbUtils.getUpdatedData();

    if (dbUtils.getCellRowFromColumn(userId, Constants.kStudentIdColumn, Constants.kLogSheet) < 0) {
      throw new IllegalStateException("Failed to create a Date Log row for the user.");
    }
  }

  static int findNextAvailableLogSheetRow(List<String> logUserIds) {
    for (int row = 1; row < logUserIds.size(); row++) {
      String userId = logUserIds.get(row);
      if (userId == null || userId.trim().isEmpty()) {
        return row;
      }
    }
    return Math.max(1, logUserIds.size());
  }

  private String valueOrEmpty(String value) {
    return value == null ? "" : value;
  }

  private Optional<LocalDateTime> getTimestamp(int userRow, int column) {
    String value = dbUtils.getCellData(userRow, column, Constants.kMainSheet);
    if (value == null || value.trim().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(LocalDateTime.parse(value, TIMESTAMP_FORMATTER));
  }
}
