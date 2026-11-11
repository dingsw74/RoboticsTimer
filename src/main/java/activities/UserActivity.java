package activities;

import databases.BatchUpdateData;
import databases.DatabaseUtils;
import databases.GoogleSheetsAttendanceRepository;
import exceptions.CancelledUserCreationException;
import helpers.AlertUtils;
import helpers.Constants;
import helpers.LoggingUtils;
import java.util.ArrayList;
import java.util.logging.Level;
import javafx.application.Platform;
import scenes.GrizzlyScene;
import services.AttendanceService;
import services.AttendanceStatus;
import services.LogoutResult;

public class UserActivity {
  /**
   * @author Dalton Smith UserActivity class Contains the various methods for handling user
   *     login/logout
   */
  private DatabaseUtils dbUtils = new DatabaseUtils();

  private AlertUtils alertUtils = new AlertUtils();

  private LogoutActivity logoutActivity = new LogoutActivity(dbUtils);
  private LoginActivity loginActivity = new LoginActivity(dbUtils);
  private AttendanceService attendanceService =
      new AttendanceService(new GoogleSheetsAttendanceRepository(dbUtils));

  // check if user is logged in
  public boolean isUserLoggedIn(String userID) throws Exception {
    AttendanceStatus status = attendanceService.getStatus(userID);
    if (status == AttendanceStatus.LOGGED_IN) {
      return true;
    }
    if (status == AttendanceStatus.LOGGED_OUT) {
      return false;
    }

    // request users first name and last name
    LoggingUtils.log(Level.INFO, "New User Detected");
    ArrayList<String> userData = alertUtils.getUserInfo();

    // throws CancelledUserException if registration was cancelled
    createNewUser(userData, userID);

    return false;
  }

  public void createNewUser(ArrayList<String> userData, String userID)
      throws CancelledUserCreationException {
    // cancel if user cancelled or exited registration dialog
    if (("TRUE").equalsIgnoreCase(userData.get(0))) {
      // create user then login
      ArrayList<BatchUpdateData> data = new ArrayList<>();

      int blankRow = dbUtils.nextEmptyCellColumn(Constants.kMainSheet);
      addUserInfoBasic(userData, userID, data, blankRow);
      data.add(new BatchUpdateData(blankRow, Constants.kEmailColumn, userData.get(3)));
      data.add(new BatchUpdateData(blankRow, Constants.kRoleColumn, userData.get(5)));
      data.add(new BatchUpdateData(blankRow, Constants.kGenderColumn, userData.get(4)));

      dbUtils.setCellDataBatch(data, Constants.kMainSheet);
      dbUtils.getUpdatedData();

      ArrayList<String> columnLogged =
          dbUtils.getColumnData(Constants.kStudentIdColumn, Constants.kLogSheet);

      int i;
      for (i = 1; i < columnLogged.size(); i++) {
        if (columnLogged.get(i).equals("")) {
          break;
        }
      }

      data.clear();
      addUserInfoBasic(userData, userID, data, i);

      dbUtils.setCellDataBatch(data, Constants.kLogSheet);
      dbUtils.getUpdatedData();

      // ensure there is a date column
      logoutActivity.getCurrentDateColumn();

    } else {
      LoggingUtils.log(Level.INFO, "Account Creation Cancelled");
      throw new CancelledUserCreationException("Cancelled");
    }
  }

  private void addUserInfoBasic(
      ArrayList<String> userData, String userID, ArrayList<BatchUpdateData> data, int i) {
    data.add(new BatchUpdateData(i, Constants.kStudentIdColumn, userID));
    data.add(new BatchUpdateData(i, Constants.kFirstNameColumn, userData.get(1)));
    data.add(new BatchUpdateData(i, Constants.kLastNameColumn, userData.get(2)));
  }

  public int doesIdExist(ArrayList<String> ids, String userID) {
    // check if the user ID exists
    for (int i = 0; i < ids.size(); i++) {
      // if the user exists, check if logged in or logged out and return state
      if (ids.get(i).equals(userID)) {
        String cellData = dbUtils.getCellData(i, Constants.kLoggedInColumn, Constants.kMainSheet);
        try {
          cellData = cellData.replaceAll("\\s+", "");

        } catch (NullPointerException e) {
          continue;
          // do nothing because the cell doesn't exist?
        }

        if (cellData.equals("TRUE")) {
          return Constants.kIdLoggedIn;

        } else {
          return Constants.kIdNotLoggedIn;
        }
      }
    }

    return Constants.kIdDoesNotExist;
  }

  // login our user
  public void loginUser(String userID) {
    Platform.runLater(() -> GrizzlyScene.setMessageBoxText("Logging in user: " + userID));

    AttendanceStatus status = attendanceService.loginUser(userID);
    if (status == AttendanceStatus.LOGGED_IN) {
      int userRow =
          dbUtils.getCellRowFromColumn(userID, Constants.kStudentIdColumn, Constants.kMainSheet);
      loginActivity.checkRegistrationStatus(userRow);

      Platform.runLater(
          () -> {
            GrizzlyScene.setMessageBoxText("Successfully logged in user: " + userID);
            GrizzlyScene.clearInput();
          });
    }
  }

  // logout the user
  public void logoutUser(String userID) {
    Platform.runLater(() -> GrizzlyScene.setMessageBoxText("Logging out user: " + userID));

    LogoutResult result = attendanceService.logoutUser(userID);
    if (result.getOutcome() == LogoutResult.Outcome.LOGGED_OUT) {
      Platform.runLater(
          () -> {
            GrizzlyScene.setMessageBoxText("Logged out user: " + userID);
            GrizzlyScene.clearInput();
          });
    } else if (result.getOutcome() == LogoutResult.Outcome.LOGGED_OUT_WITHOUT_DURATION) {
      Platform.runLater(
          () -> {
            GrizzlyScene.setMessageBoxText("You forgot to log out! Please re-login!");
            GrizzlyScene.clearInput();
          });
    }
  }

  // checks if ID is valid long and x digit number (x based on config file)
  public boolean isValidID(String userID) {
    int idLength = LocalDbActivity.kIdLength;
    int mentorIdLength = LocalDbActivity.kIdLengthFallback;

    try {
      Long.parseLong(userID);

      if (Constants.kMentorFallback) {
        return userID.length() == idLength || userID.length() == mentorIdLength;

      } else {
        return userID.length() == idLength;
      }

    } catch (NumberFormatException e) {
      // not a valid ID
      return false;
    }
  }
}
