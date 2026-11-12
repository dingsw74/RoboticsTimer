package api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import services.AttendanceService;
import services.AttendanceStatus;
import services.AttendanceSummary;
import services.InvalidAttendanceTransitionException;
import services.LogoutResult;

public class AttendanceHttpServer implements AutoCloseable {
  private static final Logger LOGGER = Logger.getLogger(AttendanceHttpServer.class.getName());
  private static final int MAX_REQUEST_BYTES = 4096;
  private static final DateTimeFormatter RESPONSE_TIME_FORMAT =
      DateTimeFormatter.ISO_OFFSET_DATE_TIME;

  private final HttpServer server;
  private final ExecutorService executor;
  private final AttendanceService attendanceService;

  public AttendanceHttpServer(InetSocketAddress address, AttendanceService attendanceService)
      throws IOException {
    if (!address.getAddress().isLoopbackAddress()) {
      throw new IllegalArgumentException("The attendance API must bind to a loopback address.");
    }

    this.attendanceService = attendanceService;
    this.server = HttpServer.create(address, 0);
    this.executor =
        Executors.newSingleThreadExecutor(
            new ThreadFactory() {
              private int threadNumber;

              @Override
              public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "attendance-api-" + ++threadNumber);
                thread.setDaemon(true);
                return thread;
              }
            });
    server.setExecutor(executor);
    server.createContext("/", this::handle);
  }

  public void start() {
    server.start();
  }

  public int getPort() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      dispatch(exchange);
    } catch (BadRequestException e) {
      sendError(exchange, 400, "INVALID_REQUEST", e.getMessage());
    } catch (InvalidAttendanceTransitionException e) {
      sendError(exchange, 409, "INVALID_TRANSITION", e.getMessage());
    } catch (MethodNotAllowedException e) {
      exchange.getResponseHeaders().set("Allow", e.getAllowedMethod());
      sendError(exchange, 405, "METHOD_NOT_ALLOWED", e.getMessage());
    } catch (RuntimeException e) {
      LOGGER.log(Level.SEVERE, "Attendance API request failed.", e);
      sendError(exchange, 503, "ATTENDANCE_BACKEND_UNAVAILABLE", "Attendance backend unavailable.");
    } finally {
      exchange.close();
    }
  }

  private void dispatch(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    String path = exchange.getRequestURI().getRawPath();

    if ("/health".equals(path)) {
      requireMethod(method, "GET");
      sendJson(exchange, 200, new JSONObject().put("status", "ok"));
      return;
    }

    if (path.startsWith("/api/v1/users/") && path.endsWith("/status")) {
      requireMethod(method, "GET");
      String userId = path.substring("/api/v1/users/".length(), path.length() - "/status".length());
      validateUserId(userId);

      java.util.Optional<AttendanceSummary> summary = attendanceService.getSummary(userId);
      if (!summary.isPresent()) {
        sendError(exchange, 404, "USER_NOT_FOUND", "User was not found.");
        return;
      }

      AttendanceSummary attendanceSummary = summary.get();
      sendJson(
          exchange,
          200,
          statusResponse(userId, attendanceSummary.getStatus())
              .put("lastTimeHours", nullableString(attendanceSummary.getLastTimeHours()))
              .put("totalHours", nullableString(attendanceSummary.getTotalHours())));
      return;
    }

    if ("/api/v1/attendance/login".equals(path)) {
      requireMethod(method, "POST");
      JSONObject request = readJson(exchange);
      String userId = requireUserId(request);
      LocalDateTime occurredAt = readOccurredAt(request);

      AttendanceStatus status = attendanceService.loginUser(userId, occurredAt);
      if (status == AttendanceStatus.NOT_FOUND) {
        sendError(exchange, 404, "USER_NOT_FOUND", "User was not found.");
        return;
      }

      sendJson(
          exchange, 200, statusResponse(userId, status).put("occurredAt", formatTime(occurredAt)));
      return;
    }

    if ("/api/v1/attendance/logout".equals(path)) {
      requireMethod(method, "POST");
      JSONObject request = readJson(exchange);
      String userId = requireUserId(request);
      LocalDateTime occurredAt = readOccurredAt(request);

      LogoutResult result = attendanceService.logoutUser(userId, occurredAt);
      if (result.getOutcome() == LogoutResult.Outcome.USER_NOT_FOUND) {
        sendError(exchange, 404, "USER_NOT_FOUND", "User was not found.");
        return;
      }

      JSONObject response =
          statusResponse(userId, AttendanceStatus.LOGGED_OUT)
              .put("occurredAt", formatTime(occurredAt))
              .put("durationSeconds", result.getDurationSeconds())
              .put("durationCredited", result.getOutcome() == LogoutResult.Outcome.LOGGED_OUT);
      sendJson(exchange, 200, response);
      return;
    }

    sendError(exchange, 404, "NOT_FOUND", "Endpoint was not found.");
  }

  private JSONObject statusResponse(String userId, AttendanceStatus status) {
    return new JSONObject()
        .put("userId", userId)
        .put("state", status == AttendanceStatus.LOGGED_IN ? "logged_in" : "logged_out");
  }

  private Object nullableString(String value) {
    return value == null ? JSONObject.NULL : value;
  }

  private JSONObject readJson(HttpExchange exchange) throws IOException {
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    if (contentType == null || !contentType.toLowerCase().startsWith("application/json")) {
      throw new BadRequestException("Content-Type must be application/json.");
    }

    byte[] body;
    try (InputStream input = exchange.getRequestBody();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[1024];
      int total = 0;
      int read;
      while ((read = input.read(buffer)) != -1) {
        total += read;
        if (total > MAX_REQUEST_BYTES) {
          throw new BadRequestException("Request body exceeds the size limit.");
        }
        output.write(buffer, 0, read);
      }
      body = output.toByteArray();
    }

    try {
      return new JSONObject(new String(body, StandardCharsets.UTF_8));
    } catch (JSONException e) {
      throw new BadRequestException("Request body must be a JSON object.");
    }
  }

  private String requireUserId(JSONObject request) {
    Object value = request.opt("userId");
    if (!(value instanceof String)) {
      throw new BadRequestException("userId must be a string.");
    }
    String userId = ((String) value).trim();
    validateUserId(userId);
    return userId;
  }

  private void validateUserId(String userId) {
    if (userId == null || !userId.matches("[0-9]{1,32}")) {
      throw new BadRequestException("userId must contain 1 to 32 digits.");
    }
  }

  private LocalDateTime readOccurredAt(JSONObject request) {
    if (!request.has("occurredAt")) {
      return LocalDateTime.now();
    }
    Object value = request.opt("occurredAt");
    if (!(value instanceof String)) {
      throw new BadRequestException("occurredAt must be an ISO-8601 timestamp with an offset.");
    }
    try {
      return OffsetDateTime.parse((String) value)
          .atZoneSameInstant(ZoneId.systemDefault())
          .toLocalDateTime();
    } catch (DateTimeParseException e) {
      throw new BadRequestException("occurredAt must be an ISO-8601 timestamp with an offset.");
    }
  }

  private String formatTime(LocalDateTime time) {
    return time.atZone(ZoneId.systemDefault()).format(RESPONSE_TIME_FORMAT);
  }

  private void requireMethod(String actual, String expected) {
    if (!expected.equals(actual)) {
      throw new MethodNotAllowedException(expected);
    }
  }

  private void sendError(HttpExchange exchange, int status, String code, String message)
      throws IOException {
    JSONObject error = new JSONObject().put("code", code).put("message", message);
    sendJson(exchange, status, new JSONObject().put("error", error));
  }

  private void sendJson(HttpExchange exchange, int status, JSONObject response) throws IOException {
    byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  private static class BadRequestException extends RuntimeException {
    private BadRequestException(String message) {
      super(message);
    }
  }

  private static class MethodNotAllowedException extends RuntimeException {
    private final String allowedMethod;

    private MethodNotAllowedException(String allowedMethod) {
      super("Method not allowed.");
      this.allowedMethod = allowedMethod;
    }

    private String getAllowedMethod() {
      return allowedMethod;
    }
  }
}
