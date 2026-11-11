package databases;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.json.JSONException;
import org.json.JSONObject;

public class TestDatabaseConfig {
  private final String spreadsheetId;
  private final Path tokenDirectory;

  private TestDatabaseConfig(String spreadsheetId, Path tokenDirectory) {
    this.spreadsheetId = spreadsheetId;
    this.tokenDirectory = tokenDirectory;
  }

  public static TestDatabaseConfig load(Path configPath) throws IOException {
    if (!Files.isRegularFile(configPath)) {
      throw new IOException("Test config does not exist: " + configPath);
    }

    try {
      byte[] configBytes = Files.readAllBytes(configPath);
      int contentOffset =
          configBytes.length >= 3
                  && (configBytes[0] & 0xFF) == 0xEF
                  && (configBytes[1] & 0xFF) == 0xBB
                  && (configBytes[2] & 0xFF) == 0xBF
              ? 3
              : 0;
      JSONObject config =
          new JSONObject(
              new String(
                  configBytes,
                  contentOffset,
                  configBytes.length - contentOffset,
                  java.nio.charset.StandardCharsets.UTF_8));
      if (!config.optBoolean("testMode", false)) {
        throw new IOException("Test config must explicitly set \"testMode\": true.");
      }

      String spreadsheetId = config.optString("sheet", "").trim();
      if (spreadsheetId.isEmpty() || spreadsheetId.startsWith("REPLACE_WITH_")) {
        throw new IOException("Test config must specify a dedicated test spreadsheet ID.");
      }

      String tokenDirectoryValue = config.optString("tokenDirectory", "").trim();
      if (tokenDirectoryValue.isEmpty() || tokenDirectoryValue.equals("tokens")) {
        throw new IOException("Test config must specify a separate tokenDirectory.");
      }

      Path configDirectory = configPath.toAbsolutePath().normalize().getParent();
      Path tokenDirectory = Paths.get(tokenDirectoryValue);
      if (!tokenDirectory.isAbsolute()) {
        tokenDirectory = configDirectory.resolve(tokenDirectory);
      }
      tokenDirectory = tokenDirectory.toAbsolutePath().normalize();
      Path applicationTokenDirectory = Paths.get("tokens").toAbsolutePath().normalize();
      if (tokenDirectory.equals(applicationTokenDirectory)) {
        throw new IOException(
            "Test config must not reuse the application's OAuth token directory.");
      }

      return new TestDatabaseConfig(spreadsheetId, tokenDirectory);
    } catch (JSONException e) {
      throw new IOException("Test config contains invalid JSON.", e);
    }
  }

  public String getSpreadsheetId() {
    return spreadsheetId;
  }

  public Path getTokenDirectory() {
    return tokenDirectory;
  }
}
