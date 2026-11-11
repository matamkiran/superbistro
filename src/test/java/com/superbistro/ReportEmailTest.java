package com.superbistro;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

import com.superbistro.report.ReportService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The report emails build from whatever data there is (also an empty day) without errors. */
@SpringBootTest
class ReportEmailTest {
    @Autowired ReportService reports;

    @Test
    void dailyAndMonthlyReportsBuild() {
        String day = reports.previewDay(LocalDate.now(ZoneId.of("Asia/Kolkata")));
        assertTrue(day.contains("Daily report"), day);
        assertTrue(day.contains("Cash drawer"));
        assertTrue(day.contains("Store: to buy"));
        String month = reports.previewMonth(YearMonth.now(ZoneId.of("Asia/Kolkata")));
        assertTrue(month.contains("Monthly report"));
        assertTrue(month.contains("Top 15 dishes"));
    }
}
