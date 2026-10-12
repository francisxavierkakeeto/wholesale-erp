package erp;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Single shared MySQL/MariaDB connection + small JDBC helpers used by every module. */
public final class Db implements AutoCloseable {

    public interface Work<T> { T run(Connection c) throws SQLException; }

    /** Business-rule violation (as opposed to a database/technical error). */
    public static class BusinessException extends RuntimeException {
        public BusinessException(String m) { super(m); }
    }

    /** Connection settings, read from erp.properties (defaults suit a fresh XAMPP install). */
    public record Config(String host, int port, String user, String password, String database) {

        public static Config load() {
            Properties p = new Properties();
            Path f = Path.of("erp.properties");
            if (Files.exists(f)) {
                try (InputStream in = Files.newInputStream(f)) { p.load(in); }
                catch (IOException e) { throw new IllegalStateException("Cannot read erp.properties: " + e.getMessage()); }
            }
            return new Config(
                    p.getProperty("db.host", "localhost").trim(),
                    Integer.parseInt(p.getProperty("db.port", "3306").trim()),
                    p.getProperty("db.user", "root").trim(),
                    p.getProperty("db.password", ""),
                    p.getProperty("db.name", "wholesale_erp").trim());
        }

        public Config withDatabase(String name) { return new Config(host, port, user, password, name); }

        private String url(String db) {
            return "jdbc:mariadb://" + host + ":" + port + "/" + db + "?useUnicode=true&characterEncoding=utf8";
        }
    }

    /** What kind of rule the database enforced when a statement was rejected (by MySQL/MariaDB error code). */
    public enum Violation { UNIQUE, FOREIGN_KEY, CHECK, RULE, NOT_NULL, BAD_VALUE, OTHER }

    public static Violation kind(SQLException e) {
        for (SQLException x = e; x != null; x = x.getNextException()) {
            switch (x.getErrorCode()) {
                case 1062, 1586 -> { return Violation.UNIQUE; }              // duplicate entry
                case 1451, 1452, 1216, 1217 -> { return Violation.FOREIGN_KEY; } // parent/child row
                case 3819, 4025 -> { return Violation.CHECK; }               // CHECK constraint (MySQL / MariaDB)
                case 1644 -> { return Violation.RULE; }                      // SIGNAL from one of our triggers
                case 1048 -> { return Violation.NOT_NULL; }
                case 1264, 1292, 1366, 1406 -> { return Violation.BAD_VALUE; }
                default -> { }
            }
        }
        return Violation.OTHER;
    }

    private static final java.util.regex.Pattern SAFE_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Connection conn;
    private final Config cfg;

    public Db(Config cfg) throws SQLException {
        this.cfg = cfg;
        requireSafeName(cfg.database());
        try (Connection admin = DriverManager.getConnection(cfg.url(""), cfg.user(), cfg.password());
             Statement s = admin.createStatement()) {
            s.execute("CREATE DATABASE IF NOT EXISTS `" + cfg.database() + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        conn = DriverManager.getConnection(cfg.url(cfg.database()), cfg.user(), cfg.password());
        try (Statement s = conn.createStatement()) {
            // strict mode: reject bad values instead of silently truncating them (XAMPP's default is lenient)
            s.execute("SET SESSION sql_mode = 'STRICT_ALL_TABLES,NO_ZERO_DATE,NO_ENGINE_SUBSTITUTION'");
        }
        initSchema();
    }

    public Config config() { return cfg; }
    public Connection conn() { return conn; }

    /** Used by the self-test to remove its scratch database. */
    public static void dropDatabase(Config cfg) throws SQLException {
        requireSafeName(cfg.database());
        try (Connection admin = DriverManager.getConnection(cfg.url(""), cfg.user(), cfg.password());
             Statement s = admin.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS `" + cfg.database() + "`");
        }
    }

    private static void requireSafeName(String name) {
        if (!SAFE_NAME.matcher(name).matches())
            throw new IllegalArgumentException("Database name may contain only letters, digits and underscore: " + name);
    }

    // ------------------------------------------------------------ schema
    private void initSchema() throws SQLException {
        for (String stmt : splitScript(loadSchema())) {
            try (Statement s = conn.createStatement()) { s.execute(stmt); }
        }
    }

    private static String loadSchema() {
        try (InputStream in = Db.class.getResourceAsStream("/schema.sql")) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Files.readString(Path.of("sql", "schema.sql"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load schema.sql", e);
        }
    }

    /**
     * Splits a MySQL script into statements. Understands the client command DELIMITER (needed for
     * triggers whose body contains semicolons) and skips full-line comments, so the very same
     * schema.sql also runs in phpMyAdmin, MySQL Workbench or the mysql command-line client.
     */
    static List<String> splitScript(String script) {
        List<String> out = new ArrayList<>();
        String delimiter = ";";
        StringBuilder cur = new StringBuilder();
        for (String raw : script.split("\\R")) {
            String line = raw.strip();
            if (cur.length() == 0) {
                if (line.isEmpty() || line.startsWith("--")) continue;
                if (line.regionMatches(true, 0, "DELIMITER ", 0, 10)) { delimiter = line.substring(10).strip(); continue; }
            } else if (line.startsWith("--")) continue;
            cur.append(raw).append('\n');
            if (line.endsWith(delimiter)) {
                String stmt = cur.toString().strip();
                out.add(stmt.substring(0, stmt.length() - delimiter.length()).strip());
                cur.setLength(0);
            }
        }
        if (cur.toString().strip().length() > 0) out.add(cur.toString().strip());
        return out;
    }

    // ------------------------------------------------------- transactions
    /** Runs work atomically: commit on success, rollback on ANY failure. */
    public <T> T tx(Work<T> w) throws SQLException {
        boolean old = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            T r = w.run(conn);
            conn.commit();
            return r;
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(old);
        }
    }

    @Override public void close() throws SQLException { conn.close(); }

    // ------------------------------------------------------------ helpers
    public static String now() { return LocalDateTime.now().format(STAMP); }

    private static void bind(PreparedStatement ps, Object[] p) throws SQLException {
        for (int i = 0; i < p.length; i++) ps.setObject(i + 1, p[i]);
    }

    public static int update(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) { bind(ps, p); return ps.executeUpdate(); }
    }

    /** Runs an INSERT and returns the AUTO_INCREMENT id it generated. */
    public static long insert(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, p);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                if (!k.next()) throw new SQLException("No generated key returned");
                return k.getLong(1);
            }
        }
    }

    public static List<Map<String, Object>> list(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, p);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                    out.add(row);
                }
                return out;
            }
        }
    }

    public static Map<String, Object> one(Connection c, String sql, Object... p) throws SQLException {
        List<Map<String, Object>> r = list(c, sql, p);
        return r.isEmpty() ? null : r.get(0);
    }

    /** First column of first row as long; 0 when there is no row or the value is NULL. */
    public static long scalar(Connection c, String sql, Object... p) throws SQLException {
        Map<String, Object> r = one(c, sql, p);
        if (r == null) return 0;
        Object v = r.values().iterator().next();
        return v == null ? 0 : ((Number) v).longValue();
    }

    public static long num(Map<String, Object> row, String col) { return ((Number) row.get(col)).longValue(); }
    public static String str(Map<String, Object> row, String col) { return text(row.get(col)); }

    /** Display form of a value: dates as yyyy-MM-dd HH:mm:ss, never as Java's default toString. */
    private static String text(Object v) {
        if (v == null) return "";
        if (v instanceof Timestamp t) return t.toLocalDateTime().withNano(0).format(STAMP);
        if (v instanceof LocalDateTime t) return t.withNano(0).format(STAMP);
        return v.toString();
    }

    /** Prints a query result as an aligned text table. */
    public static void print(Connection c, String sql, Object... p) throws SQLException {
        List<Map<String, Object>> rows = list(c, sql, p);
        if (rows.isEmpty()) { System.out.println("  (no rows)"); return; }
        List<String> cols = new ArrayList<>(rows.get(0).keySet());
        int[] w = new int[cols.size()];
        List<String[]> cells = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) w[i] = cols.get(i).length();
        for (Map<String, Object> r : rows) {
            String[] line = new String[cols.size()];
            for (int i = 0; i < cols.size(); i++) {
                Object v = r.get(cols.get(i));
                boolean whole = v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte
                        || (v instanceof BigDecimal bd && bd.scale() <= 0);
                String s = whole && !cols.get(i).endsWith("id")
                        ? String.format("%,d", ((Number) v).longValue()) : text(v);
                if (s.length() > 38) s = s.substring(0, 35) + "...";
                line[i] = s;
                w[i] = Math.max(w[i], s.length());
            }
            cells.add(line);
        }
        StringBuilder head = new StringBuilder("  "), sep = new StringBuilder("  ");
        for (int i = 0; i < cols.size(); i++) {
            head.append(String.format("%-" + w[i] + "s  ", cols.get(i)));
            sep.append("-".repeat(w[i])).append("  ");
        }
        System.out.println(head); System.out.println(sep);
        for (String[] line : cells) {
            StringBuilder sb = new StringBuilder("  ");
            for (int i = 0; i < line.length; i++) sb.append(String.format("%-" + w[i] + "s  ", line[i]));
            System.out.println(sb);
        }
        System.out.println("  (" + rows.size() + " row" + (rows.size() == 1 ? "" : "s") + ")");
    }

    // ------------------------------------------------------ ledger helper
    /** Posts one balanced double entry (debit account / credit account) for the same amount. No-op for 0. */
    public static void post(Connection c, String debitAcct, String creditAcct, long amount,
                            String refType, long refId, String description) throws SQLException {
        if (amount <= 0) return;
        String d = now();
        update(c, "INSERT INTO ledger_entries(entry_date,account,debit,credit,ref_type,ref_id,description) VALUES(?,?,?,0,?,?,?)",
                d, debitAcct, amount, refType, refId, description);
        update(c, "INSERT INTO ledger_entries(entry_date,account,debit,credit,ref_type,ref_id,description) VALUES(?,?,0,?,?,?,?)",
                d, creditAcct, amount, refType, refId, description);
    }
}
