package activities;

import databases.DatabaseUtils;
import helpers.AlertUtils;
import helpers.CommonUtils;
import notifiers.LoginNotifier;

public class LoginActivity {
  private CommonUtils utils = new CommonUtils();
  private AlertUtils alertUtils = new AlertUtils();
  private LoginNotifier notifier = new LoginNotifier();

  private DatabaseUtils dbUtils;

  public static final boolean grizzlyPrompt = LocalDbActivity.kGrizzlyVerification;

  public LoginActivity(DatabaseUtils dbUtils) {
    this.dbUtils = dbUtils;
  }

  public void checkRegistrationStatus(int userRow) {
    if (grizzlyPrompt && !notifier.checkNotifier(userRow, dbUtils)) {
      utils.playDing();

      alertUtils.createAlert(
          "Registration not complete!",
          "Registration not complete!",
          "It seems you have not completed your user registration!"
              + " Please visit https://ycsrobotics.org/registration to finish your registration");
    }
  }
}
