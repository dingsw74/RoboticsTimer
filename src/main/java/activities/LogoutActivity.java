package activities;

import databases.DatabaseUtils;
import helpers.Constants;
import helpers.LoggingUtils;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.logging.Level;

public class LogoutActivity {
  private DatabaseUtils dbUtils;

  public LogoutActivity(DatabaseUtils dbUtils) {
    this.dbUtils = dbUtils;
  }

  public void logoutUserWithHours(
      String userID, int userRow, String totalTimeFromDifference, LocalDate logoutDate) {
    LocalTime totalHoursTime = LocalTime.parse(totalTimeFromDifference);

    // grab the current total hours
    String totalHours =
        dbUtils.getCellData(userRow, Constants.kTotalHoursColumn, Constants.kMainSheet);
    String[] prevTotalTime;
    double[] prevTotalTimeNum = new double[3];

    // manually manipulate the time
    try {
      prevTotalTime = totalHours.split("\\s*:\\s*");

      prevTotalTimeNum[0] = Double.parseDouble(prevTotalTime[0]);
      prevTotalTimeNum[1] = Double.parseDouble(prevTotalTime[1]);
      prevTotalTimeNum[2] = Double.parseDouble(prevTotalTime[2]);

    } catch (DateTimeParseException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
      LoggingUtils.log(Level.WARNING, "Error parsing previous total time, using fallback of 0.0");
      prevTotalTimeNum[0] = 0.0;
      prevTotalTimeNum[1] = 0.0;
      prevTotalTimeNum[2] = 0.0;
    }

    // calculate the new total time
    double totalHour = prevTotalTimeNum[0] + totalHoursTime.getHour();
    double totalMinute = prevTotalTimeNum[1] + totalHoursTime.getMinute();
    double totalSeconds = prevTotalTimeNum[2] + totalHoursTime.getSecond();

    while (totalSeconds > 60) {
      totalSeconds -= 60;
      totalMinute += 1;
    }

    while (totalMinute > 60) {
      totalMinute -= 60;
      totalHour += 1;
    }

    String timeTotal =
        String.format("%02d:%02d:%02d", (int) totalHour, (int) totalMinute, (int) totalSeconds);

    // Locate the user and date column before writing any attendance totals.
    int userRowLogout =
        dbUtils.getCellRowFromColumn(userID, Constants.kStudentIdColumn, Constants.kLogSheet);
    if (userRowLogout < 0) {
      throw new IllegalStateException("User does not have a valid row in the Date Log sheet.");
    }
    int userTimeColumn = getCurrentDateColumn(logoutDate);

    // add together day times
    LocalTime prevDayTime;
    try {
      String prevData = dbUtils.getCellData(userRowLogout, userTimeColumn, Constants.kLogSheet);
      prevDayTime = LocalTime.parse(prevData);

    } catch (DateTimeParseException | NullPointerException e) {
      prevDayTime = LocalTime.parse("00:00:01");
      LoggingUtils.log(
          Level.WARNING,
          "There was an issue adding cell data, using fallback. \n" + e.getMessage());
    }

    LocalTime tempTotalDayTime;
    try {
      tempTotalDayTime =
          LocalTime.parse(totalTimeFromDifference)
              .plusHours(prevDayTime.getHour())
              .plusMinutes(prevDayTime.getMinute())
              .plusSeconds(prevDayTime.getSecond());

    } catch (DateTimeParseException e) {
      LoggingUtils.log(
          Level.WARNING, "There was an error adding together total time. Using fallback.");
      tempTotalDayTime = prevDayTime;
    }

    String timeTotalDay =
        String.format(
            "%02d:%02d:%02d",
            tempTotalDayTime.getHour(), tempTotalDayTime.getMinute(), tempTotalDayTime.getSecond());

    dbUtils.setCellData(
        userRow, Constants.kHoursColumn, totalTimeFromDifference, Constants.kMainSheet);
    dbUtils.setCellData(userRowLogout, userTimeColumn, timeTotalDay, Constants.kLogSheet);
    dbUtils.setCellData(userRow, Constants.kTotalHoursColumn, timeTotal, Constants.kMainSheet);
  }

  // helper method for grabbing the column that contains the current date
  public int getCurrentDateColumn() {
    return getCurrentDateColumn(LocalDate.now());
  }

  public int getCurrentDateColumn(LocalDate date) {
    ArrayList<String> data;
    try {
      data = dbUtils.getRowData(1, Constants.kLogSheet);
    } catch (NullPointerException e) {
      data = new ArrayList<>();
    }

    String currentDate = date.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));

    // check if our date already exists
    int i;
    for (i = 0; i < data.size(); i++) {
      String checkDate = data.get(i);
      checkDate = checkDate.replace("'", "");

      // return column containing our date
      if (checkDate.equals(currentDate)) {
        return i;
      }
    }

    // confirm that the sheet isn't empty
    if (data.size() == 0) {
      i = 3;
    }

    // log the current date and return column
    dbUtils.setCellData(0, i, currentDate, Constants.kLogSheet);
    return i;
  }
}
