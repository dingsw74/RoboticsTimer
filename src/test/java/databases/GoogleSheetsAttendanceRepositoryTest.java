package databases;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;

public class GoogleSheetsAttendanceRepositoryTest {
  @Test
  public void findsFirstEmptyUserRowAfterHeader() {
    Assert.assertEquals(
        2,
        GoogleSheetsAttendanceRepository.findNextAvailableLogSheetRow(
            Arrays.asList("Student ID", "123456", "", "1177730")));
  }

  @Test
  public void appendsAfterExistingUsersWhenThereIsNoEmptyRow() {
    Assert.assertEquals(
        3,
        GoogleSheetsAttendanceRepository.findNextAvailableLogSheetRow(
            Arrays.asList("Student ID", "123456", "1177730")));
  }

  @Test
  public void reservesTheHeaderRowWhenTheSheetIsEmpty() {
    Assert.assertEquals(
        1, GoogleSheetsAttendanceRepository.findNextAvailableLogSheetRow(Collections.emptyList()));
  }
}
