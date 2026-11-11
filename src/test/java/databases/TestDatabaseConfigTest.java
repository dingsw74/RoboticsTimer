package databases;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TestDatabaseConfigTest {
  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void requiresExplicitTestMode() throws Exception {
    Path configPath = writeConfig("{\"sheet\":\"test-sheet\",\"tokenDirectory\":\"tokens-test\"}");

    try {
      TestDatabaseConfig.load(configPath);
      Assert.fail("Expected test mode validation to fail.");
    } catch (IOException expected) {
      Assert.assertTrue(expected.getMessage().contains("testMode"));
    }
  }

  @Test
  public void rejectsTemplateSpreadsheetId() throws Exception {
    Path configPath =
        writeConfig(
            "{\"testMode\":true,\"sheet\":\"REPLACE_WITH_TEST_ID\","
                + "\"tokenDirectory\":\"tokens-test\"}");

    try {
      TestDatabaseConfig.load(configPath);
      Assert.fail("Expected placeholder spreadsheet validation to fail.");
    } catch (IOException expected) {
      Assert.assertTrue(expected.getMessage().contains("dedicated test spreadsheet"));
    }
  }

  @Test
  public void rejectsApplicationTokenDirectory() throws Exception {
    Path applicationTokenDirectory = java.nio.file.Paths.get("tokens").toAbsolutePath().normalize();
    Path configPath =
        writeConfig(
            "{\"testMode\":true,\"sheet\":\"dedicated-test-sheet\","
                + "\"tokenDirectory\":\""
                + applicationTokenDirectory.toString().replace("\\", "\\\\")
                + "\"}");

    try {
      TestDatabaseConfig.load(configPath);
      Assert.fail("Expected production OAuth token directory validation to fail.");
    } catch (IOException expected) {
      Assert.assertTrue(expected.getMessage().contains("OAuth token directory"));
    }
  }

  @Test
  public void resolvesSeparateTokenDirectoryRelativeToConfig() throws Exception {
    Path configPath =
        writeConfig(
            "{\"testMode\":true,\"sheet\":\"dedicated-test-sheet\","
                + "\"tokenDirectory\":\"tokens-test\"}");

    TestDatabaseConfig config = TestDatabaseConfig.load(configPath);

    Assert.assertEquals("dedicated-test-sheet", config.getSpreadsheetId());
    Assert.assertEquals(
        configPath.getParent().resolve("tokens-test").toAbsolutePath().normalize(),
        config.getTokenDirectory());
  }

  @Test
  public void acceptsUtf8Bom() throws Exception {
    Path directory = temporaryFolder.newFolder().toPath();
    Path configPath = directory.resolve("config.json");
    byte[] content =
        "{\"testMode\":true,\"sheet\":\"dedicated-test-sheet\","
            .concat("\"tokenDirectory\":\"tokens-test\"}")
            .getBytes(StandardCharsets.UTF_8);
    byte[] withBom = new byte[content.length + 3];
    withBom[0] = (byte) 0xEF;
    withBom[1] = (byte) 0xBB;
    withBom[2] = (byte) 0xBF;
    System.arraycopy(content, 0, withBom, 3, content.length);
    Files.write(configPath, withBom);

    TestDatabaseConfig config = TestDatabaseConfig.load(configPath);

    Assert.assertEquals("dedicated-test-sheet", config.getSpreadsheetId());
  }

  private Path writeConfig(String content) throws IOException {
    Path directory = temporaryFolder.newFolder().toPath();
    Path configPath = directory.resolve("config.json");
    Files.write(configPath, content.getBytes(StandardCharsets.UTF_8));
    return configPath;
  }
}
