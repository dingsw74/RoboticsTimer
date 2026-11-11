package api;

import databases.DatabaseProcess;
import databases.DatabaseUtils;
import databases.GoogleSheetsAttendanceRepository;
import databases.TestDatabaseConfig;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.util.logging.Logger;
import services.AttendanceService;

public class AttendanceApiMain {
  private static final Logger LOGGER = Logger.getLogger(AttendanceApiMain.class.getName());
  private static final int DEFAULT_PORT = 8765;

  public static void main(String[] args) throws Exception {
    Arguments arguments = Arguments.parse(args);
    TestDatabaseConfig config = TestDatabaseConfig.load(Paths.get(arguments.configPath));
    DatabaseUtils databaseUtils = new DatabaseUtils(new DatabaseProcess(config));
    AttendanceService attendanceService =
        new AttendanceService(new GoogleSheetsAttendanceRepository(databaseUtils));
    InetAddress loopbackAddress = InetAddress.getByName("127.0.0.1");
    AttendanceHttpServer server =
        new AttendanceHttpServer(
            new InetSocketAddress(loopbackAddress, arguments.port), attendanceService);

    Runtime.getRuntime().addShutdownHook(new Thread(server::close, "attendance-api-shutdown"));
    server.start();
    LOGGER.info(
        "Attendance test API listening at http://"
            + loopbackAddress.getHostAddress()
            + ":"
            + server.getPort());
  }

  private static class Arguments {
    private final String configPath;
    private final int port;

    private Arguments(String configPath, int port) {
      this.configPath = configPath;
      this.port = port;
    }

    private static Arguments parse(String[] args) {
      boolean testMode = false;
      String configPath = null;
      int port = DEFAULT_PORT;

      for (int i = 0; i < args.length; i++) {
        if ("--test-mode".equals(args[i])) {
          testMode = true;
        } else if ("--config".equals(args[i]) && i + 1 < args.length) {
          configPath = args[++i];
        } else if ("--port".equals(args[i]) && i + 1 < args.length) {
          try {
            port = Integer.parseInt(args[++i]);
          } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--port must be a number from 1 to 65535.", e);
          }
        } else {
          throw new IllegalArgumentException(
              "Usage: --test-mode --config <test-config-path> [--port <1-65535>]");
        }
      }

      if (!testMode) {
        throw new IllegalArgumentException("The API requires the explicit --test-mode flag.");
      }
      if (configPath == null || configPath.trim().isEmpty()) {
        throw new IllegalArgumentException("The API requires --config <test-config-path>.");
      }
      if (port < 1 || port > 65535) {
        throw new IllegalArgumentException("--port must be a number from 1 to 65535.");
      }

      return new Arguments(configPath, port);
    }
  }
}
