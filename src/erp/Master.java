package erp;

import erp.Db.BusinessException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

/** Master-data CRUD: categories, products, customers, suppliers. */
public final class Master {
    private final Connection c;

    public Master(Db db) { this.c = db.conn(); }

    private static final Map<String, String> PK = Map.of(
            "categories", "category_id", "products", "product_id",
            "customers", "customer_id", "suppliers", "supplier_id");

    /** Whitelist of columns the user may edit (also protects against SQL injection via column names). */
    private static final Map<String, Set<String>> EDITABLE = Map.of(
            "categories", Set.of("name"),
            "products", Set.of("name", "unit", "unit_price", "reorder_level", "category_id", "active"),
            "customers", Set.of("name", "phone", "address", "credit_limit", "active"),
            "suppliers", Set.of("name", "phone", "address", "active"));

    private static final Set<String> NUMERIC =
            Set.of("unit_price", "reorder_level", "category_id", "credit_limit", "active");

    // ------------------------------------------------------------ CREATE
    public long addCategory(String name) throws SQLException {
        require(name, "Category name");
        return Db.insert(c, "INSERT INTO categories(name) VALUES(?)", name.trim());
    }

    /** Opening stock is NOT set here: use Inventory > adjust stock or receive a purchase. */
    public long addProduct(String sku, String name, long categoryId, String unit, long unitPrice, long reorder)
            throws SQLException {
        require(sku, "SKU"); require(name, "Product name");
        if (unitPrice < 0 || reorder < 0) throw new BusinessException("Price and reorder level cannot be negative.");
        return Db.insert(c, "INSERT INTO products(sku,name,category_id,unit,unit_price,reorder_level) VALUES(?,?,?,?,?,?)",
                sku.trim(), name.trim(), categoryId, blank(unit) ? "piece" : unit.trim(), unitPrice, reorder);
    }

    public long addCustomer(String name, String phone, String address, long creditLimit) throws SQLException {
        require(name, "Customer name");
        if (creditLimit < 0) throw new BusinessException("Credit limit cannot be negative.");
        return Db.insert(c, "INSERT INTO customers(name,phone,address,credit_limit) VALUES(?,?,?,?)",
                name.trim(), nullIfBlank(phone), nullIfBlank(address), creditLimit);
    }

    public long addSupplier(String name, String phone, String address) throws SQLException {
        require(name, "Supplier name");
        return Db.insert(c, "INSERT INTO suppliers(name,phone,address) VALUES(?,?,?)",
                name.trim(), nullIfBlank(phone), nullIfBlank(address));
    }

    // ------------------------------------------------------------ READ
    public void listCategories() throws SQLException {
        Db.print(c, "SELECT category_id, name FROM categories ORDER BY name");
    }

    public void listProducts() throws SQLException {
        Db.print(c, "SELECT p.product_id, p.sku, p.name, g.name AS category, p.unit, p.cost_price, p.unit_price, "
                + "p.stock_qty, p.reorder_level, CASE p.active WHEN 1 THEN 'yes' ELSE 'no' END AS active "
                + "FROM products p JOIN categories g ON g.category_id = p.category_id ORDER BY p.name");
    }

    public void listCustomers() throws SQLException {
        Db.print(c, "SELECT c.customer_id, c.name, c.phone, c.address, c.credit_limit, b.outstanding, "
                + "CASE c.active WHEN 1 THEN 'yes' ELSE 'no' END AS active "
                + "FROM customers c JOIN v_customer_balance b ON b.customer_id = c.customer_id ORDER BY c.name");
    }

    public void listSuppliers() throws SQLException {
        Db.print(c, "SELECT supplier_id, name, phone, address, CASE active WHEN 1 THEN 'yes' ELSE 'no' END AS active "
                + "FROM suppliers ORDER BY name");
    }

    // ------------------------------------------------------------ UPDATE
    public void update(String table, long id, String column, String value) throws SQLException {
        Set<String> ok = EDITABLE.get(table);
        if (ok == null || !ok.contains(column))
            throw new BusinessException("Field '" + column + "' cannot be edited. Allowed: " + (ok == null ? "-" : ok));
        Object v;
        if (NUMERIC.contains(column)) {
            try { v = Long.parseLong(value.trim()); }
            catch (NumberFormatException e) { throw new BusinessException("'" + column + "' needs a whole number."); }
        } else if (column.equals("name")) {
            require(value, "Name");
            v = value.trim();
        } else {
            v = nullIfBlank(value);
        }
        int n = Db.update(c, "UPDATE " + table + " SET " + column + " = ? WHERE " + PK.get(table) + " = ?", v, id);
        if (n == 0) throw new BusinessException("No such record: " + id);
    }

    public Set<String> editableFields(String table) { return EDITABLE.get(table); }

    // ------------------------------------------------------------ DELETE
    /**
     * Deletes the record if nothing references it; otherwise (history exists) it is deactivated instead,
     * because deleting it would orphan invoices, ledger entries and stock history.
     * @return a message describing what happened
     */
    public String delete(String table, long id) throws SQLException {
        String pk = PK.get(table);
        if (pk == null) throw new BusinessException("Unknown table.");
        try {
            int n = Db.update(c, "DELETE FROM " + table + " WHERE " + pk + " = ?", id);
            if (n == 0) throw new BusinessException("No such record: " + id);
            return "Deleted.";
        } catch (SQLException e) {
            if (Db.kind(e) != Db.Violation.FOREIGN_KEY) throw e;
            if (table.equals("categories"))
                throw new BusinessException("Category is still used by products; move or deactivate them first.");
            Db.update(c, "UPDATE " + table + " SET active = 0 WHERE " + pk + " = ?", id);
            return "Record has transaction history, so it was DEACTIVATED instead of deleted.";
        }
    }

    // ----------------------------------------------------------- helpers
    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
    private static String nullIfBlank(String s) { return blank(s) ? null : s.trim(); }
    private static void require(String s, String what) {
        if (blank(s)) throw new BusinessException(what + " is required.");
    }
}
