package databases;

import activities.LocalDbActivity;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.*;
import helpers.AlertUtils;
import helpers.CommonUtils;
import helpers.Constants;
import helpers.LoggingUtils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.*;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import javafx.application.HostServices;

public class DatabaseProcess {
  /**
   * @author Dalton Smith DatabaseProcess Manages low level google sheets handling from API Uses the
   *     power of black magic to manage the google sheet Based on Java QuickStart
   */
  private static final String APPLICATION_NAME = "GrizzlyTime JavaFX Edition";

  private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
  private static final String TOKENS_DIRECTORY_PATH = "tokens";

  private static final List<String> SCOPES = Collections.singletonList(SheetsScopes.SPREADSHEETS);
  private static final String CREDENTIALS_FILE_PATH = "/credentials/credentials.json";

  private AlertUtils util = new AlertUtils();

  private final String spreadsheet;
  private final File tokenDirectory;
  private final boolean headless;

  private static final String mainPage = "Current";
  private static final String logPage = "Date Log";
  private static final String regPage = "Student Registration";

  public static boolean worksheetIsEmpty = false;

  public DatabaseProcess() {
    this(LocalDbActivity.kSheetId, new File(TOKENS_DIRECTORY_PATH), false);
  }

  public DatabaseProcess(TestDatabaseConfig config) {
    this(config.getSpreadsheetId(), config.getTokenDirectory().toFile(), true);
  }

  private DatabaseProcess(String spreadsheet, File tokenDirectory, boolean headless) {
    if (spreadsheet == null || spreadsheet.trim().isEmpty()) {
      throw new IllegalArgumentException("A spreadsheet ID is required.");
    }
    this.spreadsheet = spreadsheet;
    this.tokenDirectory = tokenDirectory;
    this.headless = headless;
  }

  // based upon the Java Google Sheets quickstart
  // settings google sheets logging level to SEVERE only
  private <LocalServerReceiver> Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT)
      throws IOException {
    // set google logging level to severe due to permissions bug, see
    // https://github.com/googleapis/google-http-java-client/issues/315
    java.util.logging.Logger.getLogger(FileDataStoreFactory.class.getName()).setLevel(Level.SEVERE);

    // Load client secrets.
    InputStream in = DatabaseProcess.class.getResourceAsStream(CREDENTIALS_FILE_PATH);

    if (in == null) {
      throw new IOException(
          "Google OAuth credentials were not found at " + CREDENTIALS_FILE_PATH + ".");
    }

    GoogleClientSecrets clientSecrets =
        GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

    // Build flow and trigger user authorization request.
    GoogleAuthorizationCodeFlow flow =
        new GoogleAuthorizationCodeFlow.Builder(HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
            .setDataStoreFactory(new FileDataStoreFactory(tokenDirectory))
            .setAccessType("offline")
            .build();

    return new AuthorizationCodeInstalledApp(
            flow,
            new com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver(),
            new AuthorizationCodeInstalledApp.Browser() {
              @Override
              public void browse(String url) {
                LoggingUtils.log(
                    Level.INFO, "Or navigate to " + url + " to authorize the application.");
                if (CommonUtils.application != null) {
                  HostServices hostServices = CommonUtils.application.getHostServices();
                  hostServices.showDocument(url);
                } else {
                  LoggingUtils.log(
                      Level.INFO,
                      "Open this URL in a browser to authorize the application: " + url);
                }
              }
            })
        .authorize("user");
  }

  // return a list of rows and columns of a specified sheet
  public List<List<Object>> returnWorksheetData(int page) {

    try {
      String range = getPage(page);

      final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
      Sheets service =
          new Sheets.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
              .setApplicationName(APPLICATION_NAME)
              .build();

      return getDataFromSheet(service, range);

    } catch (NoRouteToHostException | UnknownHostException e) {
      fail("No network connection while accessing Google Sheets.", e);
      return null;

    } catch (GeneralSecurityException e2) {
      fail("Google Sheets security or authorization setup failed.", e2);
      return null;

    } catch (GoogleJsonResponseException e) {
      LoggingUtils.log(Level.SEVERE, e.getDetails().getMessage());

      try {
        if (e.getDetails().getMessage().contains("Unable to parse range")) {
          LoggingUtils.log(Level.WARNING, "Attempting to create our sheets");

          final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
          Sheets service =
              new Sheets.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
                  .setApplicationName(APPLICATION_NAME)
                  .build();

          ArrayList<Request> requests = new ArrayList<>();

          // add our sheets to the list of sheets to be processed
          if (e.getDetails().getMessage().contains("Current")) {
            requests.add(
                new Request()
                    .setAddSheet(
                        new AddSheetRequest()
                            .setProperties(new SheetProperties().setTitle("Current"))));
            LoggingUtils.log(Level.INFO, "Creating Current Sheet");
          }

          if (e.getDetails().getMessage().contains("Date Log")) {
            requests.add(
                new Request()
                    .setAddSheet(
                        new AddSheetRequest()
                            .setProperties(new SheetProperties().setTitle("Date Log"))));
            LoggingUtils.log(Level.INFO, "Creating Date Log Sheet");
          }

          BatchUpdateSpreadsheetRequest body =
              new BatchUpdateSpreadsheetRequest().setRequests(requests);

          BatchUpdateSpreadsheetResponse updateResponse =
              service.spreadsheets().batchUpdate(spreadsheet, body).execute();

          LoggingUtils.log(Level.INFO, updateResponse.getReplies().toString());

          String range = getPage(page);

          return getDataFromSheet(service, range);
        }

        fail("Google Sheets rejected the request.", e);
        return null;

      } catch (Exception e2) {
        fail("Failed to prepare the Google Sheets worksheets.", e2);
        return null;
      }

      // attempt to create our sheets

    } catch (IOException e3) {
      fail("Unable to connect to Google Sheets.", e3);
      return null;
    }
  }

  private List<List<Object>> getDataFromSheet(Sheets service, String range) throws IOException {
    ValueRange response = null;

    try {
      response = service.spreadsheets().values().get(spreadsheet, range).execute();

    } catch (NoRouteToHostException e) {
      fail("No route to host while reading Google Sheets.", e);

    } catch (SocketTimeoutException e) {
      fail("Timed out while reading Google Sheets.", e);
    }

    if (response.getValues() == null) {
      worksheetIsEmpty = true;
    }

    return response.getValues();
  }

  public void updateSpreadSheet(int row, int column, String data, int page) {

    // Build a new authorized API client service.
    String columnLetter = toAlphabetic(column - 1);

    String range = columnLetter + row;
    String sheetPage = getPage(page);
    range = sheetPage + "!" + range;

    ValueRange requestBody = new ValueRange();
    requestBody.setValues(Collections.singletonList(Collections.singletonList(data)));

    try {
      final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
      Sheets service =
          new Sheets.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
              .setApplicationName(APPLICATION_NAME)
              .build();

      service
          .spreadsheets()
          .values()
          .update(spreadsheet, range, requestBody)
          .setValueInputOption("RAW")
          .execute();

    } catch (GeneralSecurityException e) {
      fail("Google Sheets security or authorization setup failed.", e);

    } catch (GoogleJsonResponseException e) {
      if (e.getDetails().getMessage().contains("exceeds grid limits")) {
        LoggingUtils.log(Level.WARNING, "Appending Data");
        try {
          final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
          Sheets service =
              new Sheets.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
                  .setApplicationName(APPLICATION_NAME)
                  .build();

          service
              .spreadsheets()
              .values()
              .append(spreadsheet, range, requestBody)
              .setValueInputOption("RAW")
              .execute();

        } catch (IOException e1) {
          LoggingUtils.log(Level.SEVERE, e);

        } catch (GeneralSecurityException e1) {
          fail("Google Sheets authorization failed while appending data.", e1);
        }

      } else {
        fail("Google Sheets rejected the update.", e);
      }

    } catch (IOException e2) {
      fail("Unable to update Google Sheets.", e2);
    }
  }

  public void updateSpreadSheetBatch(ArrayList<BatchUpdateData> batchData, int page) {
    try {
      LoggingUtils.log(Level.INFO, "Batch updating sheet");
      final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
      Sheets service =
          new Sheets.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
              .setApplicationName(APPLICATION_NAME)
              .build();

      List<ValueRange> requestData = new ArrayList<>();

      for (BatchUpdateData data : batchData) {
        // Build a new authorized API client service.
        String columnLetter = toAlphabetic(data.getColumn() - 1);

        String range = columnLetter + data.getRow();

        String sheetPage = getPage(page);
        range = sheetPage + "!" + range;

        requestData.add(
            new ValueRange()
                .setRange(range)
                .setValues(Collections.singletonList(Collections.singletonList(data.getData()))));
      }

      BatchUpdateValuesRequest batchBody =
          new BatchUpdateValuesRequest().setValueInputOption("RAW").setData(requestData);

      // TODO check response
      service.spreadsheets().values().batchUpdate(spreadsheet, batchBody).execute();

    } catch (GeneralSecurityException e) {
      fail("Google Sheets security or authorization setup failed.", e);

    } catch (IOException e2) {
      fail("Unable to batch update Google Sheets.", e2);
    }
  }

  // update current working page
  private String getPage(int page) {
    switch (page) {
      case Constants.kMainSheet:
        return mainPage;

      case Constants.kLogSheet:
        return logPage;

      case Constants.kRegistrationSheet:
        return regPage;

      default:
        return mainPage;
    }
  }

  private void fail(String message, Exception cause) {
    LoggingUtils.log(Level.SEVERE, message);
    LoggingUtils.log(Level.SEVERE, cause);
    if (headless) {
      throw new IllegalStateException(message, cause);
    }
    util.createAlert("ERROR", "Google Sheets Error", message + "\n" + cause.getMessage());
    CommonUtils.exitApplication();
  }

  public static String toAlphabetic(int i) {
    if (i < 0) {
      return "-" + toAlphabetic(-i - 1);
    }

    int quot = i / 26;
    int rem = i % 26;
    char letter = (char) ((int) 'A' + rem);
    if (quot == 0) {
      return "" + letter;

    } else {
      return toAlphabetic(quot - 1) + letter;
    }
  }
}
