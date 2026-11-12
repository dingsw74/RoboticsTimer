package scenes;

import activities.KeyActivity;
import activities.LocalDbActivity;
import activities.UserActivity;
import exceptions.CancelledUserCreationException;
import exceptions.ConnectToWorksheetException;
import helpers.AlertUtils;
import helpers.CommonUtils;
import helpers.Constants;
import helpers.LoggingUtils;
import java.io.File;
import java.net.NoRouteToHostException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.logging.Level;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import services.AttendanceStatus;
import services.AttendanceSummary;

public class GrizzlyScene {
  /**
   * @author Dalton Smith GrizzlyScene Manages the main interface
   */

  // object that should be able to be modified by calling
  // this scene directly
  private static Label messageText = new Label("");

  private static TextField studentIDBox = new TextField();

  // define our scene objects
  private Button loginButton = new Button("Login/Logout");
  private UserActivity userActivity = new UserActivity();
  private Text description = new Text(Constants.kUserTutorial);
  private Hyperlink creditsLink = new Hyperlink("Credits");
  private Text creditsText = new Text("v" + Constants.kVersion);
  private Hyperlink optionsLink = new Hyperlink("Full Screen");
  private BorderPane bottomPane = new BorderPane();

  private AlertUtils alertUtils = new AlertUtils();

  private static final DateTimeFormatter ATTENDANCE_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private GridPane subRoot = new GridPane();

  // boolean state variables
  private boolean handsFreeMode = LocalDbActivity.kHandsFreeMode;

  // our upper image
  private ImageView imageView;

  public GrizzlyScene() {
    Image splash;
    File file =
        new File(
            CommonUtils.getCurrentDir() + File.separator + "images" + File.separator + "error.png");

    // check for custom splash
    if (file.exists()) {
      splash = new Image(file.toURI().toString());

    } else {
      splash = new Image(Constants.kErrorImage);
    }

    imageView = new ImageView(splash);
  }

  public void updateInterface(GridPane root) {

    // create the upper image
    imageView.setFitHeight(Constants.kCameraHeight);
    GridPane.setHalignment(imageView, HPos.CENTER);
    root.add(imageView, 0, 0);

    // update CSS IDS
    messageText.setId("messageText");
    studentIDBox.setId("textBox");
    loginButton.setId("confirmButton");
    creditsLink.setId("hyperlinkBottom");
    optionsLink.setId("hyperlinkBottom");
    creditsText.setId("hyperlinkBottom");

    // create our panes
    GridPane options = new GridPane();
    GridPane title = new GridPane();

    subRoot.setId("bottomView");

    // confirm alignments
    subRoot.setAlignment(Pos.CENTER);
    options.setAlignment(Pos.CENTER);
    title.setAlignment(Pos.CENTER);
    messageText.setAlignment(Pos.CENTER);
    description.setTextAlignment(TextAlignment.CENTER);
    description.setId("textDescription");

    // manually align message text because Gridpane is weird
    GridPane.setHalignment(messageText, HPos.CENTER);
    GridPane.setHalignment(description, HPos.CENTER);
    GridPane.setHalignment(subRoot, HPos.CENTER);

    // set bottom pane details
    bottomPane.setId("bottomPane");
    bottomPane.setLeft(optionsLink);
    bottomPane.setCenter(creditsText);
    bottomPane.setRight(creditsLink);
    bottomPane.setMinWidth(subRoot.getWidth());

    // add our various nodes to respective panes
    title.add(description, 0, 1);
    options.add(studentIDBox, 0, 0);
    options.add(loginButton, 1, 0);
    subRoot.add(title, 0, 0);
    subRoot.add(options, 0, 1);
    subRoot.add(messageText, 0, 2);

    // sub root details
    subRoot.setVgap(10);

    // add to root pane
    root.add(subRoot, 0, 1);
    root.add(bottomPane, 0, 2);

    // handle our buttons
    setEventHandlers();
  }

  public void reShowUI(GridPane root) {
    root.setId("main");

    // add to root pane
    root.add(imageView, 0, 0);
    root.add(subRoot, 0, 1);
    root.add(bottomPane, 0, 2);
  }

  // our event handlers for interactivity
  private void setEventHandlers() {
    // login on enter key press
    studentIDBox.setOnAction(event -> confirmLogin());

    // login button event handler
    loginButton.setOnAction(event -> confirmLogin());

    creditsLink.setOnAction(event -> showCredits());

    optionsLink.setOnAction(
        event -> {
          Stage stage = (Stage) optionsLink.getScene().getWindow();
          if (KeyActivity.isFullscreen) {
            stage.setFullScreen(false);
            KeyActivity.isFullscreen = false;
          } else {
            stage.setFullScreen(true);
            KeyActivity.isFullscreen = true;
          }
        });
  }

  private void showCredits() {
    SceneManager.updateScene(Constants.kCreditsSceneState);
  }

  // helper login method
  private void confirmLogin() {
    setMessageBoxText("Processing...");

    // confirm that the ID is valid
    if (!userActivity.isValidID(studentIDBox.getText())) {
      setMessageBoxText("ID " + studentIDBox.getText() + " is invalid.");

      Task<Void> wait =
          new Task<Void>() {
            @Override
            protected Void call() throws Exception {
              Thread.sleep(5000);
              return null;
            }
          };

      wait.setOnSucceeded(e -> setMessageBoxText(""));

      // no need to set as daemon as will end after x seconds.
      new Thread(wait).start();
      return;
    }

    String userId = studentIDBox.getText();
    if (handsFreeMode) {
      loginUser(userId);
      return;
    }

    studentIDBox.setDisable(true);
    loginButton.setDisable(true);
    Thread preview =
        new Thread(
            () -> {
              try {
                Optional<AttendanceSummary> summary = userActivity.getAttendanceSummary(userId);
                Platform.runLater(() -> confirmAttendance(userId, summary));
              } catch (Exception e) {
                LoggingUtils.log(Level.SEVERE, e);
                Platform.runLater(
                    () -> {
                      showAttendanceError(e);
                      setInputDisabled(false);
                    });
              }
            });
    preview.setDaemon(true);
    preview.start();
  }

  private void confirmAttendance(String userId, Optional<AttendanceSummary> optionalSummary) {
    AttendanceSummary summary = optionalSummary.orElse(null);
    boolean loggingIn = summary == null || summary.getStatus() != AttendanceStatus.LOGGED_IN;
    String action = loggingIn ? "Login" : "Logout";
    String message = attendanceConfirmationDetails(userId, summary, loggingIn);

    if (alertUtils.confirmInput(action, summary == null ? null : summary.getUserName(), message)) {
      loginUser(userId);
    } else {
      setMessageBoxText("");
      setInputDisabled(false);
    }
  }

  private String attendanceConfirmationDetails(
      String userId, AttendanceSummary summary, boolean loggingIn) {
    StringBuilder details = new StringBuilder("User ID: ").append(userId);
    if (summary != null
        && summary.getUserName() != null
        && !summary.getUserName().trim().isEmpty()) {
      details.append("\nName: ").append(summary.getUserName().trim());
    }
    details.append("\nAction: ").append(loggingIn ? "LOGIN" : "LOGOUT");
    details
        .append("\nTotal hours so far: ")
        .append(summary == null ? "00:00:00" : valueOrUnavailable(summary.getTotalHours()));

    if (summary == null) {
      details.append("\nNo previous check-in.");
    } else if (loggingIn) {
      if (!summary.getLastLogin().isPresent() && !summary.getLastLogout().isPresent()) {
        details.append("\nNo previous check-in.");
      } else {
        details
            .append("\nLast check-in start: ")
            .append(formatAttendanceTime(summary.getLastLogin()));
        details
            .append("\nLast check-in end: ")
            .append(formatAttendanceTime(summary.getLastLogout()));
        details
            .append("\nLast check-in period: ")
            .append(valueOrUnavailable(summary.getLastTimeHours()));
      }
    } else {
      details
          .append("\nCurrent check-in start: ")
          .append(formatAttendanceTime(summary.getLastLogin()));
      details
          .append("\nCurrent check-in period: ")
          .append(formatDuration(summary.getCurrentCheckInDuration()));
    }
    return details.toString();
  }

  private String formatAttendanceTime(Optional<LocalDateTime> time) {
    return time.isPresent() ? time.get().format(ATTENDANCE_TIME_FORMAT) : "Not available";
  }

  private String formatDuration(Optional<Duration> optionalDuration) {
    if (!optionalDuration.isPresent()) {
      return "Not available";
    }
    Duration duration = optionalDuration.get();
    boolean negative = duration.isNegative();
    long seconds = Math.abs(duration.getSeconds());
    long hours = seconds / 3600;
    long minutes = (seconds % 3600) / 60;
    long remainingSeconds = seconds % 60;
    return String.format("%s%02d:%02d:%02d", negative ? "-" : "", hours, minutes, remainingSeconds);
  }

  private String valueOrUnavailable(String value) {
    return value == null || value.trim().isEmpty() ? "Not available" : value;
  }

  private void setInputDisabled(boolean disabled) {
    studentIDBox.setDisable(disabled);
    loginButton.setDisable(disabled);
  }

  // login the user, check if hands free or not
  private void loginUser(String userId) {
    // separate login process on different thread to ensure
    // main application does not freeze
    // also allows in for multiple users login simultaneously
    Runnable loginUser =
        () -> {
          // ensure that the user typed something in
          if (userId.isEmpty()) {
            setMessageBoxText("Nothing was entered!");
            Platform.runLater(() -> setInputDisabled(false));
            return;
          }

          // attempt login/logout and or account creation
          // do nothing if account creation was cancelled
          try {
            // check if the user is logged in, and that user exists
            if (!(userActivity.isUserLoggedIn(userId))) {
              LoggingUtils.log(Level.INFO, "Logging in: " + userId);
              userActivity.loginUser(userId);

            } else {
              LoggingUtils.log(Level.INFO, "Logging out: " + userId);
              userActivity.logoutUser(userId);
            }

          } catch (CancelledUserCreationException e) {
            setMessageBoxText("Cancelled account creation");

          } catch (Exception e) {
            showAttendanceError(e);
          } finally {
            Platform.runLater(() -> setInputDisabled(false));
          }

          // refocus the textbox
          Platform.runLater(() -> studentIDBox.requestFocus());
        };

    // start our thread
    Thread t = new Thread(loginUser);
    t.setDaemon(true);
    t.start();
  }

  private void showAttendanceError(Exception e) {
    if (e instanceof ConnectToWorksheetException) {
      setMessageBoxText("There was an error connecting to the database. Please retry.");
    } else if (e instanceof NoRouteToHostException) {
      setMessageBoxText("Unable to connect to database. Check internet and retry.");
    } else {
      LoggingUtils.log(Level.SEVERE, e);
      setMessageBoxText("An unknown error has occurred, see log file.");
    }
  }

  // helper methods for setting and clearing text box
  public static void setMessageBoxText(String text) {
    if (Platform.isFxApplicationThread()) {
      messageText.setText(text);
    } else {
      Platform.runLater(() -> messageText.setText(text));
    }
  }

  public static void clearInput() {
    studentIDBox.clear();
  }
}
