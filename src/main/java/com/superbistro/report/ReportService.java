package com.superbistro.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.mail.internet.MimeMessage;

import com.superbistro.backup.SystemSettings;
import com.superbistro.config.AppProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Report emails: every day (sales, payments, channels, top dishes, cancelled bills, cash drawer,
 * expenses, stock to buy, customers) with the day's orders as a CSV, and on the 1st the previous
 * month. Sent through the SMTP account set on the System page (e.g. Gmail with an app password).
 * If the PC was off at the report time, the missed day's report goes out when Super Bistro starts.
 */
@Service
public class ReportService {
    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    public static final String TO = "report.to";
    public static final String ENABLED = "report.enabled";
    public static final String TIME = "report.time";
    public static final String LAST_DAY = "report.lastDay";
    public static final String LAST_MONTH = "report.lastMonth";
    public static final String LAST_RESULT = "report.lastResult";
    public static final String SMTP_HOST = "smtp.host";
    public static final String SMTP_PORT = "smtp.port";
    public static final String SMTP_USER = "smtp.user";
    public static final String SMTP_PASSWORD = "smtp.password";

    private static final DateTimeFormatter DAY_TEXT = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_TEXT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_TEXT = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private final JdbcTemplate jdbc;
    private final SystemSettings settings;
    private final AppProperties props;
    private final AtomicBoolean sending = new AtomicBoolean(false);

    public ReportService(JdbcTemplate jdbc, SystemSettings settings, AppProperties props) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.props = props;
    }

    public boolean configured(Map<String, String> s) {
        return notBlank(s.get(TO)) && notBlank(s.get(SMTP_HOST)) && notBlank(s.get(SMTP_USER)) && notBlank(s.get(SMTP_PASSWORD));
    }

    /** Every minute: the day's report after the report time (or the missed one), and last month's on the 1st. */
    @Scheduled(initialDelay = 90_000, fixedDelay = 60_000)
    public void scheduledCheck() {
        Map<String, String> s = settings.all();
        if (!configured(s) || "false".equals(s.get(ENABLED))) return;
        LocalDateTime now = LocalDateTime.now(props.zone());
        LocalTime at = LocalTime.parse(s.getOrDefault(TIME, "23:15"));
        LocalDate target = now.toLocalTime().isBefore(at) ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        String last = s.get(LAST_DAY);
        if (last == null || last.compareTo(target.toString()) < 0) {
            settings.put(LAST_DAY, target.toString()); // first: never send the same day twice
            if (hasActivity(target)) {
                send(() -> dayMail(target), "Daily report " + target);
            }
        }
        String prevMonth = YearMonth.from(now).minusMonths(1).toString();
        String lastMonth = s.get(LAST_MONTH);
        if (lastMonth == null || lastMonth.compareTo(prevMonth) < 0) {
            settings.put(LAST_MONTH, prevMonth);
            YearMonth m = YearMonth.parse(prevMonth);
            if (hasActivity(m.atDay(1), m.atEndOfMonth())) {
                send(() -> monthMail(m), "Monthly report " + prevMonth);
            }
        }
    }

    /** "Send now" on the System page: today's report so far, or a test. Returns what happened. */
    public String sendNow(boolean test) {
        LocalDate today = LocalDate.now(props.zone());
        return send(test ? this::testMail : () -> dayMail(today), test ? "Test email" : "Daily report " + today);
    }

    /** The day's report as it would be emailed (for the preview link on the System page). */
    public String previewDay(LocalDate day) {
        return dayMail(day).html();
    }

    /** The month's report as it would be emailed. */
    public String previewMonth(YearMonth m) {
        return monthMail(m).html();
    }

    private interface MailBuilder {
        Mail build();
    }

    private record Mail(String subject, String html, String fileName, String csv) {
    }

    private String send(MailBuilder builder, String what) {
        if (!sending.compareAndSet(false, true)) throw new IllegalStateException("An email is being sent right now");
        try {
            Map<String, String> s = settings.all();
            if (!configured(s)) throw new IllegalStateException("Fill in the email settings first");
            Mail m = builder.build();
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(s.get(SMTP_HOST).trim());
            int port = Integer.parseInt(s.getOrDefault(SMTP_PORT, "587").trim());
            sender.setPort(port);
            sender.setUsername(s.get(SMTP_USER).trim());
            sender.setPassword(s.get(SMTP_PASSWORD).replace(" ", ""));
            sender.setDefaultEncoding("UTF-8");
            Properties p = sender.getJavaMailProperties();
            p.put("mail.smtp.auth", "true");
            p.put(port == 465 ? "mail.smtp.ssl.enable" : "mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.connectiontimeout", "20000");
            p.put("mail.smtp.timeout", "30000");
            p.put("mail.smtp.writetimeout", "30000");
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, m.csv() != null, "UTF-8");
            h.setFrom(s.get(SMTP_USER).trim(), restaurantName());
            h.setTo(s.get(TO).split("\\s*[,;]\\s*"));
            h.setSubject(m.subject());
            h.setText(m.html(), true);
            if (m.csv() != null) {
                h.addAttachment(m.fileName(), new ByteArrayResource(("﻿" + m.csv()).getBytes(StandardCharsets.UTF_8)), "text/csv");
            }
            sender.send(msg);
            String ok = "OK " + LocalDateTime.now(props.zone()).withNano(0) + " · " + what + " sent to " + s.get(TO);
            settings.put(LAST_RESULT, ok);
            log.info("{} emailed to {}", what, s.get(TO));
            return ok;
        } catch (Exception e) {
            String msg = friendly(e);
            settings.put(LAST_RESULT, "FAILED " + LocalDateTime.now(props.zone()).withNano(0) + " · " + what + ": " + msg);
            log.warn("{} not emailed: {}", what, msg);
            throw new IllegalStateException(msg, e);
        } finally {
            sending.set(false);
        }
    }

    private static String friendly(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (m.contains("535") || m.toLowerCase(Locale.ROOT).contains("authentication")) {
            return "The email account refused the login. For Gmail use an App password (Google Account → Security → "
                + "2-Step Verification → App passwords), not the normal password.";
        }
        if (m.toLowerCase(Locale.ROOT).contains("unknownhost") || m.toLowerCase(Locale.ROOT).contains("couldn't connect")
            || m.toLowerCase(Locale.ROOT).contains("timed out")) {
            return "Couldn't reach the email server. Check the internet connection. (" + m + ")";
        }
        return m.length() > 300 ? m.substring(0, 300) : m;
    }

    // ------------------------------------------------------------------ data

    private UUID restaurantId() {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM restaurant ORDER BY created_at", UUID.class);
        if (ids.isEmpty()) throw new IllegalStateException("No restaurant yet");
        return ids.get(0);
    }

    private String restaurantName() {
        List<String> n = jdbc.queryForList("SELECT name FROM restaurant ORDER BY created_at", String.class);
        return n.isEmpty() ? "Super Bistro" : n.get(0);
    }

    private boolean hasActivity(LocalDate day) {
        return hasActivity(day, day);
    }

    private boolean hasActivity(LocalDate from, LocalDate to) {
        Integer n = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM customer_order WHERE business_day BETWEEN ? AND ?)"
            + " + (SELECT COUNT(*) FROM cash_entry WHERE business_day BETWEEN ? AND ?)", Integer.class, from, to, from, to);
        return n != null && n > 0;
    }

    private Mail testMail() {
        String html = page("Test email", "<p>Super Bistro can send emails. Daily reports will arrive here every night"
            + " after " + esc(settings.get(TIME, "23:15")) + ", and a monthly report on the 1st.</p>");
        return new Mail(restaurantName() + " · test email", html, null, null);
    }

    private Mail dayMail(LocalDate day) {
        UUID r = restaurantId();
        StringBuilder b = new StringBuilder();

        List<Map<String, Object>> orders = jdbc.queryForList(
            "SELECT id, order_no, created_at, source, order_type, table_no, customer, phone, total, payment, status, cancel_reason"
                + " FROM customer_order WHERE restaurant_id = ? AND business_day = ? ORDER BY order_no", r, day);
        List<Map<String, Object>> kept = orders.stream().filter(o -> !"CANCELLED".equals(o.get("status"))).toList();
        BigDecimal sales = sum(kept, "total");
        BigDecimal unpaid = sum(kept.stream().filter(o -> "UNPAID".equals(o.get("payment"))).toList(), "total");

        b.append(kpis(new String[][] {
            {"Sales", inr(sales)}, {"Orders", String.valueOf(kept.size())},
            {"Average bill", kept.isEmpty() ? "-" : inr(sales.divide(BigDecimal.valueOf(kept.size()), 2, RoundingMode.HALF_UP))},
            {"Not paid yet", inr(unpaid)},
        }));

        b.append(section("Sales by payment", table(new String[] {"Payment", "Orders", "Amount"},
            group(kept, "payment", Map.of("CASH", "Cash", "UPI", "UPI", "CARD", "Card", "PLATFORM", "Zomato / Swiggy", "UNPAID", "Pay later")))));
        b.append(section("Sales by channel", table(new String[] {"Channel", "Orders", "Amount"},
            group(kept, "source", Map.of("OFFLINE", "Offline", "ZOMATO", "Zomato", "SWIGGY", "Swiggy")))));

        List<String[]> top = jdbc.query("SELECT i.name, SUM(i.qty) q, SUM(i.price * i.qty - i.line_discount) amt FROM order_item i"
                + " JOIN customer_order o ON o.id = i.order_id WHERE o.restaurant_id = ? AND o.business_day = ? AND o.status <> 'CANCELLED'"
                + " GROUP BY i.name ORDER BY q DESC, amt DESC LIMIT 10",
            (rs, i) -> new String[] {rs.getString(1), rs.getString(2), inr(rs.getBigDecimal(3))}, r, day);
        b.append(section("Top dishes", top.isEmpty() ? "<p>No dishes sold.</p>" : table(new String[] {"Dish", "Qty", "Amount"}, top)));

        List<String[]> cancelled = jdbc.query("SELECT o.order_no, o.total, o.cancel_reason, u.name FROM customer_order o"
                + " LEFT JOIN app_user u ON u.id = o.cancelled_by WHERE o.restaurant_id = ? AND o.business_day = ?"
                + " AND o.status = 'CANCELLED' ORDER BY o.order_no",
            (rs, i) -> new String[] {"#" + rs.getInt(1), inr(rs.getBigDecimal(2)), nz(rs.getString(3)), nz(rs.getString(4))}, r, day);
        b.append(section("Cancelled bills (" + cancelled.size() + ")",
            cancelled.isEmpty() ? "<p>None.</p>" : table(new String[] {"Bill", "Amount", "Reason", "Cancelled by"}, cancelled)));

        // Cash drawer and money in/out
        List<Map<String, Object>> cash = jdbc.queryForList(
            "SELECT direction, method, category, amount FROM cash_entry WHERE restaurant_id = ? AND business_day = ?", r, day);
        BigDecimal cashIn = BigDecimal.ZERO, cashOut = BigDecimal.ZERO, paidOut = BigDecimal.ZERO;
        Map<String, BigDecimal> outByCat = new LinkedHashMap<>();
        for (Map<String, Object> e : cash) {
            BigDecimal amt = (BigDecimal) e.get("amount");
            boolean out = "OUT".equals(e.get("direction"));
            boolean isCash = "CASH".equals(e.get("method"));
            if (out) {
                if (!"CREDIT".equals(e.get("method"))) paidOut = paidOut.add(amt);
                outByCat.merge(String.valueOf(e.get("category")), amt, BigDecimal::add);
                if (isCash) cashOut = cashOut.add(amt);
            } else if (isCash) {
                cashIn = cashIn.add(amt);
            }
        }
        BigDecimal cashSales = sum(kept.stream().filter(o -> "CASH".equals(o.get("payment"))).toList(), "total");
        List<BigDecimal[]> dc = jdbc.query("SELECT opening_cash, counted_cash FROM day_close WHERE restaurant_id = ? AND business_day = ?",
            (rs, i) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}, r, day);
        BigDecimal opening = dc.isEmpty() || dc.get(0)[0] == null ? BigDecimal.ZERO : dc.get(0)[0];
        BigDecimal counted = dc.isEmpty() ? null : dc.get(0)[1];
        BigDecimal expected = opening.add(cashSales).add(cashIn).subtract(cashOut);
        List<String[]> drawer = new ArrayList<>();
        drawer.add(new String[] {"Opening cash", inr(opening)});
        drawer.add(new String[] {"+ Cash sales", inr(cashSales)});
        drawer.add(new String[] {"+ Cash added", inr(cashIn)});
        drawer.add(new String[] {"− Cash paid out", inr(cashOut)});
        drawer.add(new String[] {"Should be in drawer", inr(expected)});
        drawer.add(new String[] {"Counted", counted == null ? "not counted" : inr(counted)});
        if (counted != null) {
            BigDecimal diff = counted.subtract(expected);
            drawer.add(new String[] {"Difference", (diff.signum() < 0 ? "SHORT " : diff.signum() > 0 ? "EXTRA " : "") + inr(diff.abs())});
        }
        b.append(section("Cash drawer", table(new String[] {"", "Amount"}, drawer)));

        List<String[]> out = new ArrayList<>();
        outByCat.forEach((k, v) -> out.add(new String[] {k, inr(v)}));
        b.append(section("Expenses and purchases · " + inr(paidOut) + " paid",
            out.isEmpty() ? "<p>None.</p>" : table(new String[] {"Category", "Amount"}, out)));

        // Stock to buy
        List<String[]> toBuy = jdbc.query("SELECT name, current_qty, unit, min_qty FROM stock_item WHERE restaurant_id = ? AND active"
                + " AND (current_qty <= 0 OR (min_qty > 0 AND current_qty < min_qty)) ORDER BY current_qty <= 0 DESC, name",
            (rs, i) -> new String[] {rs.getString(1), qty(rs.getBigDecimal(2)) + " " + rs.getString(3),
                rs.getBigDecimal(2).signum() <= 0 ? "OUT" : "low (min " + qty(rs.getBigDecimal(4)) + ")"}, r);
        b.append(section("Store: to buy (" + toBuy.size() + ")",
            toBuy.isEmpty() ? "<p>Nothing is low.</p>" : table(new String[] {"Item", "Have", "Status"}, toBuy)));

        // Customers
        long withPhone = kept.stream().filter(o -> notBlank((String) o.get("phone"))).count();
        b.append(section("Customers", "<p>" + withPhone + " of " + kept.size() + " orders had a mobile number.</p>"));

        String title = "Daily report · " + DAY_TEXT.format(day);
        StringBuilder csv = new StringBuilder("Bill,Time,Channel,Type,Table,Customer,Mobile,Dishes,Total,Payment,Status,Cancel reason\r\n");
        Map<Object, String> dishes = dishesByOrder(orders);
        for (Map<String, Object> o : orders) {
            csv.append(String.join(",", List.of(
                String.valueOf(o.get("order_no")),
                ((Timestamp) o.get("created_at")).toInstant().atZone(props.zone()).format(TIME_TEXT),
                String.valueOf(o.get("source")), String.valueOf(o.get("order_type")),
                csvCell(o.get("table_no")), csvCell(o.get("customer")), csvCell(o.get("phone")),
                csvCell(dishes.getOrDefault(o.get("id"), "")),
                String.valueOf(o.get("total")), String.valueOf(o.get("payment")), String.valueOf(o.get("status")),
                csvCell(o.get("cancel_reason"))))).append("\r\n");
        }
        return new Mail(restaurantName() + " · " + title + " · " + inr(sales), page(title, b.toString()),
            "orders-" + day + ".csv", csv.toString());
    }

    private Mail monthMail(YearMonth m) {
        UUID r = restaurantId();
        LocalDate from = m.atDay(1), to = m.atEndOfMonth();
        StringBuilder b = new StringBuilder();
        List<Map<String, Object>> kept = jdbc.queryForList("SELECT business_day, source, payment, total, phone FROM customer_order"
            + " WHERE restaurant_id = ? AND business_day BETWEEN ? AND ? AND status <> 'CANCELLED'", r, from, to);
        BigDecimal sales = sum(kept, "total");
        Integer cancelledN = jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE restaurant_id = ? AND business_day BETWEEN ? AND ?"
            + " AND status = 'CANCELLED'", Integer.class, r, from, to);
        BigDecimal paidOut = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM cash_entry WHERE restaurant_id = ?"
            + " AND business_day BETWEEN ? AND ? AND direction = 'OUT' AND method <> 'CREDIT'", BigDecimal.class, r, from, to);
        b.append(kpis(new String[][] {
            {"Sales", inr(sales)}, {"Orders", String.valueOf(kept.size())},
            {"Average bill", kept.isEmpty() ? "-" : inr(sales.divide(BigDecimal.valueOf(kept.size()), 2, RoundingMode.HALF_UP))},
            {"Paid out", inr(paidOut)},
        }));
        b.append("<p>Cancelled bills: " + cancelledN + ".</p>");
        b.append(section("Sales by channel", table(new String[] {"Channel", "Orders", "Amount"},
            group(kept, "source", Map.of("OFFLINE", "Offline", "ZOMATO", "Zomato", "SWIGGY", "Swiggy")))));
        b.append(section("Sales by payment", table(new String[] {"Payment", "Orders", "Amount"},
            group(kept, "payment", Map.of("CASH", "Cash", "UPI", "UPI", "CARD", "Card", "PLATFORM", "Zomato / Swiggy", "UNPAID", "Pay later")))));

        Map<String, BigDecimal[]> perDay = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) perDay.put(d.toString(), new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        for (Map<String, Object> o : kept) {
            BigDecimal[] v = perDay.get(String.valueOf(o.get("business_day")));
            if (v != null) {
                v[0] = v[0].add(BigDecimal.ONE);
                v[1] = v[1].add((BigDecimal) o.get("total"));
            }
        }
        List<String[]> days = new ArrayList<>();
        perDay.forEach((d, v) -> {
            if (v[0].signum() > 0) days.add(new String[] {DAY_TEXT.format(LocalDate.parse(d)), v[0].toPlainString(), inr(v[1])});
        });
        b.append(section("Day by day", days.isEmpty() ? "<p>No sales.</p>" : table(new String[] {"Day", "Orders", "Sales"}, days)));

        List<String[]> top = jdbc.query("SELECT i.name, SUM(i.qty) q, SUM(i.price * i.qty - i.line_discount) amt FROM order_item i"
                + " JOIN customer_order o ON o.id = i.order_id WHERE o.restaurant_id = ? AND o.business_day BETWEEN ? AND ?"
                + " AND o.status <> 'CANCELLED' GROUP BY i.name ORDER BY q DESC, amt DESC LIMIT 15",
            (rs, i) -> new String[] {rs.getString(1), rs.getString(2), inr(rs.getBigDecimal(3))}, r, from, to);
        b.append(section("Top 15 dishes", top.isEmpty() ? "<p>No dishes sold.</p>" : table(new String[] {"Dish", "Qty", "Amount"}, top)));

        List<String[]> out = jdbc.query("SELECT category, SUM(amount) FROM cash_entry WHERE restaurant_id = ? AND business_day BETWEEN ? AND ?"
                + " AND direction = 'OUT' GROUP BY category ORDER BY 2 DESC",
            (rs, i) -> new String[] {rs.getString(1), inr(rs.getBigDecimal(2))}, r, from, to);
        b.append(section("Expenses and purchases", out.isEmpty() ? "<p>None.</p>" : table(new String[] {"Category", "Amount"}, out)));

        String title = "Monthly report · " + MONTH_TEXT.format(m);
        StringBuilder csv = new StringBuilder("Day,Orders,Sales\r\n");
        perDay.forEach((d, v) -> csv.append(d).append(',').append(v[0].toPlainString()).append(',').append(v[1].toPlainString()).append("\r\n"));
        return new Mail(restaurantName() + " · " + title + " · " + inr(sales), page(title, b.toString()), "sales-" + m + ".csv", csv.toString());
    }

    private Map<Object, String> dishesByOrder(List<Map<String, Object>> orders) {
        Map<Object, String> out = new LinkedHashMap<>();
        if (orders.isEmpty()) return out;
        Object[] ids = orders.stream().map(o -> o.get("id")).toArray();
        jdbc.query("SELECT order_id, qty, name FROM order_item WHERE order_id IN (" + String.join(",", java.util.Collections.nCopies(ids.length, "?"))
            + ") ORDER BY line_no", rs -> {
                Object id = rs.getObject("order_id");
                String item = rs.getInt("qty") + "x " + rs.getString("name");
                out.merge(id, item, (a, c) -> a + "; " + c);
            }, ids);
        return out;
    }

    // ------------------------------------------------------------------ html helpers

    private List<String[]> group(List<Map<String, Object>> rows, String key, Map<String, String> labels) {
        Map<String, BigDecimal[]> g = new LinkedHashMap<>();
        for (Map<String, Object> o : rows) {
            BigDecimal[] v = g.computeIfAbsent(String.valueOf(o.get(key)), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            v[0] = v[0].add(BigDecimal.ONE);
            v[1] = v[1].add((BigDecimal) o.get("total"));
        }
        List<String[]> out = new ArrayList<>();
        g.forEach((k, v) -> out.add(new String[] {labels.getOrDefault(k, k), v[0].toPlainString(), inr(v[1])}));
        if (out.isEmpty()) out.add(new String[] {"-", "0", inr(BigDecimal.ZERO)});
        return out;
    }

    private String page(String title, String body) {
        return "<div style=\"font-family:Segoe UI,Arial,sans-serif;color:#17221b;max-width:640px\">"
            + "<h1 style=\"font-size:20px;margin:0 0 4px\">" + esc(restaurantName()) + "</h1>"
            + "<h2 style=\"font-size:16px;color:#1d6a44;margin:0 0 16px\">" + esc(title) + "</h2>" + body
            + "<p style=\"color:#5a695f;font-size:12px;margin-top:24px\">Sent by Super Bistro order desk. Change who gets this on the System page.</p></div>";
    }

    private static String kpis(String[][] k) {
        StringBuilder b = new StringBuilder("<table style=\"border-collapse:collapse;margin-bottom:12px\"><tr>");
        for (String[] x : k) {
            b.append("<td style=\"padding:8px 14px;border:1px solid #d3ddd4\"><div style=\"font-size:11px;color:#5a695f\">")
                .append(esc(x[0])).append("</div><div style=\"font-size:18px;font-weight:700\">").append(esc(x[1])).append("</div></td>");
        }
        return b.append("</tr></table>").toString();
    }

    private static String section(String title, String inner) {
        return "<h3 style=\"font-size:15px;margin:18px 0 6px\">" + esc(title) + "</h3>" + inner;
    }

    private static String table(String[] head, List<String[]> rows) {
        StringBuilder b = new StringBuilder("<table style=\"border-collapse:collapse;width:100%;font-size:13px\"><tr>");
        for (String h : head) b.append("<th style=\"text-align:left;border-bottom:2px solid #17221b;padding:4px 6px\">").append(esc(h)).append("</th>");
        b.append("</tr>");
        for (String[] r : rows) {
            b.append("<tr>");
            for (String c : r) b.append("<td style=\"border-bottom:1px solid #e7ede6;padding:4px 6px\">").append(esc(c)).append("</td>");
            b.append("</tr>");
        }
        return b.append("</table>").toString();
    }

    private static BigDecimal sum(List<Map<String, Object>> rows, String key) {
        return rows.stream().map(o -> (BigDecimal) o.get(key)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static String inr(BigDecimal v) {
        if (v == null) return "₹0";
        java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
        f.setMaximumFractionDigits(2);
        f.setMinimumFractionDigits(v.stripTrailingZeros().scale() > 0 ? 2 : 0);
        return "₹" + f.format(v);
    }

    private static String qty(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String csvCell(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
