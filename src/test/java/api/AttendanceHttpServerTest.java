package api;

import databases.AttendanceRepository;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import services.AttendanceService;

public class AttendanceHttpServerTest {
  private static final String USER_ID = "001234";

  private AttendanceHttpServer server;
  private FakeAttendanceRepository repository;

  @Before
  public void startServer() throws Exception {
    repository = new FakeAttendanceRepository();
    server =
        new AttendanceHttpServer(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
            new AttendanceService(repository));
    server.start();
  }

  @After
  public void stopServer() {
    if (server != null) {
      server.close();
    }
  }

  @Test
  public void supportsLoginStatusAndLogoutFlow() throws Exception {
    Response login =
        request(
            "POST",
            "/api/v1/attendance/login",
            "{\"userId\":\"001234\",\"occurredAt\":\"2026-11-10T12:00:00-08:00\"}");
    Assert.assertEquals(200, login.status);
    Assert.assertEquals("logged_in", login.body.getString("state"));

    Response duplicateLogin =
        request("POST", "/api/v1/attendance/login", "{\"userId\":\"001234\"}");
    Assert.assertEquals(409, duplicateLogin.status);

    Response status = request("GET", "/api/v1/users/001234/status", null);
    Assert.assertEquals(200, status.status);
    Assert.assertEquals("logged_in", status.body.getString("state"));
    Assert.assertEquals("01:00:00", status.body.getString("lastTimeHours"));
    Assert.assertEquals("23:45:00", status.body.getString("totalHours"));

    Response logout =
        request(
            "POST",
            "/api/v1/attendance/logout",
            "{\"userId\":\"001234\",\"occurredAt\":\"2026-11-10T13:00:00-08:00\"}");
    Assert.assertEquals(200, logout.status);
    Assert.assertEquals("logged_out", logout.body.getString("state"));
    Assert.assertEquals(3600, logout.body.getLong("durationSeconds"));
    Assert.assertEquals("01:00:00", repository.recordedElapsedTime);
  }

  @Test
  public void closesCrossMidnightSessionWithoutCreditingDuration() throws Exception {
    String loginTime =
        LocalDateTime.of(2026, 11, 10, 23, 55)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    String logoutTime =
        LocalDateTime.of(2026, 11, 11, 0, 5)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    Response login =
        request(
            "POST",
            "/api/v1/attendance/login",
            "{\"userId\":\"001234\",\"occurredAt\":\"" + loginTime + "\"}");
    Assert.assertEquals(200, login.status);

    Response logout =
        request(
            "POST",
            "/api/v1/attendance/logout",
            "{\"userId\":\"001234\",\"occurredAt\":\"" + logoutTime + "\"}");

    Assert.assertEquals(200, logout.status);
    Assert.assertFalse(logout.body.getBoolean("durationCredited"));
    Assert.assertEquals(0, logout.body.getLong("durationSeconds"));
    Assert.assertNull(repository.recordedElapsedTime);
  }

  @Test
  public void rejectsUnknownUserAndInvalidTransition() throws Exception {
    Response missing = request("GET", "/api/v1/users/009999/status", null);
    Assert.assertEquals(404, missing.status);
    Assert.assertEquals("USER_NOT_FOUND", missing.body.getJSONObject("error").getString("code"));

    Response alreadyLoggedOut =
        request("POST", "/api/v1/attendance/logout", "{\"userId\":\"001234\"}");
    Assert.assertEquals(409, alreadyLoggedOut.status);
    Assert.assertEquals(
        "INVALID_TRANSITION", alreadyLoggedOut.body.getJSONObject("error").getString("code"));
  }

  @Test
  public void rejectsMalformedInputAndWrongMethod() throws Exception {
    Response invalidTimestamp =
        request(
            "POST",
            "/api/v1/attendance/login",
            "{\"userId\":\"001234\",\"occurredAt\":\"not-a-time\"}");
    Assert.assertEquals(400, invalidTimestamp.status);

    Response wrongMethod = request("POST", "/health", null);
    Assert.assertEquals(405, wrongMethod.status);
    Assert.assertEquals("GET", wrongMethod.allow);
  }

  @Test
  public void rejectsOversizedRequestBody() throws Exception {
    StringBuilder body = new StringBuilder("{\"userId\":\"001234\",\"extra\":\"");
    for (int i = 0; i < 5000; i++) {
      body.append('x');
    }
    body.append("\"}");

    Response response = request("POST", "/api/v1/attendance/login", body.toString());

    Assert.assertEquals(400, response.status);
    Assert.assertEquals("INVALID_REQUEST", response.body.getJSONObject("error").getString("code"));
  }

  @Test
  public void healthIsAvailableAfterServerInitialization() throws Exception {
    Response health = request("GET", "/health", null);
    Assert.assertEquals(200, health.status);
    Assert.assertEquals("ok", health.body.getString("status"));
  }

  @Test
  public void refusesNonLoopbackBinding() throws Exception {
    try {
      new AttendanceHttpServer(
          new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0),
          new AttendanceService(repository));
      Assert.fail("Expected a non-loopback bind address to be rejected.");
    } catch (IllegalArgumentException expected) {
      Assert.assertTrue(expected.getMessage().contains("loopback"));
    }
  }

  private Response request(String method, String path, String body) throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection) new URL("http://127.0.0.1:" + server.getPort() + path).openConnection();
    connection.setRequestMethod(method);
    connection.setConnectTimeout(2000);
    connection.setReadTimeout(2000);
    if (body != null) {
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json");
      try (OutputStream output = connection.getOutputStream()) {
        output.write(body.getBytes(StandardCharsets.UTF_8));
      }
    }

    int status = connection.getResponseCode();
    InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream();
    byte[] responseBytes;
    try (InputStream response = input;
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[1024];
      int read;
      while ((read = response.read(buffer)) != -1) {
        output.write(buffer, 0, read);
      }
      responseBytes = output.toByteArray();
    }

    String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
    return new Response(
        status,
        responseBody.isEmpty() ? new JSONObject() : new JSONObject(responseBody),
        connection.getHeaderField("Allow"));
  }

  private static class Response {
    private final int status;
    private final JSONObject body;
    private final String allow;

    private Response(int status, JSONObject body, String allow) {
      this.status = status;
      this.body = body;
      this.allow = allow;
    }
  }

  private static class FakeAttendanceRepository implements AttendanceRepository {
    private boolean loggedIn;
    private LocalDateTime lastLogin;
    private String recordedElapsedTime;

    @Override
    public void refresh() {}

    @Override
    public Optional<Integer> findUserRow(String userId) {
      return USER_ID.equals(userId) ? Optional.of(1) : Optional.empty();
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
      return "01:00:00";
    }

    @Override
    public String getTotalHours(int userRow) {
      return "23:45:00";
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
