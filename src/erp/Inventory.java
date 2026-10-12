package erp;

import erp.Db.BusinessException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Inventory + Purchasing module. Stock only ever changes through stock_movements rows. */
public final class Inventory {
    private final Db db;
    private final Connection c;

    public Inventory(Db db) { this.db = db; this.c = db.conn(); }

    /** One goods-received line. */
    public record Receipt(long productId, long quantity, long unitCost) {}

    /**
     * Receives goods from a supplier: purchase order + lines, stock movements (+), new weighted-average cost,
     * and ledger entry Dr INVENTORY / Cr ACCOUNTS_PAYABLE.
     */
    public long receivePurchase(long supplierId, List<Receipt> lines) throws SQLException {
        if (lines.isEmpty()) throw new BusinessException("A purchase needs at least one line.");
        return db.tx(cn -> {
            Map<String, Object> s = Db.one(cn, "SELECT active FROM suppliers WHERE supplier_id = ?", supplierId);
            if (s == null) throw new BusinessException("No such supplier: " + supplierId);
            if (Db.num(s, "active") == 0) throw new BusinessException("Supplier is inactive.");

            String now = Db.now();
            long poId = Db.insert(cn, "INSERT INTO purchase_orders(supplier_id,po_date) VALUES(?,?)", supplierId, now);
            long total = 0;
            for (Receipt r : lines) {
                if (r.quantity() <= 0) throw new BusinessException("Quantity must be positive.");
                if (r.unitCost() < 0) throw new BusinessException("Unit cost cannot be negative.");
                Map<String, Object> p = Db.one(cn, "SELECT name, stock_qty, cost_price FROM products WHERE product_id = ?", r.productId());
                if (p == null) throw new BusinessException("No such product: " + r.productId());
                try {
                    Db.insert(cn, "INSERT INTO purchase_order_lines(po_id,product_id,quantity,unit_cost) VALUES(?,?,?,?)",
                            poId, r.productId(), r.quantity(), r.unitCost());
                } catch (SQLException e) {
                    if (Db.kind(e) == Db.Violation.UNIQUE)
                        throw new BusinessException("Product " + r.productId() + " appears twice; combine into one line.");
                    throw e;
                }
                // weighted-average cost, rounded to the nearest shilling (computed BEFORE stock changes)
                long stock = Db.num(p, "stock_qty"), oldCost = Db.num(p, "cost_price");
                long newCost = (stock * oldCost + r.quantity() * r.unitCost() + (stock + r.quantity()) / 2) / (stock + r.quantity());
                Db.update(cn, "UPDATE products SET cost_price = ? WHERE product_id = ?", newCost, r.productId());
                Db.insert(cn, "INSERT INTO stock_movements(product_id,movement_date,movement_type,quantity_change,ref_id,note) VALUES(?,?,?,?,?,?)",
                        r.productId(), now, "PURCHASE", r.quantity(), poId, "Purchase order #" + poId);
                total += r.quantity() * r.unitCost();
            }
            Db.post(cn, "INVENTORY", "ACCOUNTS_PAYABLE", total, "PURCHASE", poId, "Goods received, PO #" + poId);
            return poId;
        });
    }

    /** Manual stock correction (opening stock, damage, count difference), valued at current cost. */
    public void adjust(long productId, long delta, String reason) throws SQLException {
        if (delta == 0) throw new BusinessException("Adjustment cannot be zero.");
        if (reason == null || reason.isBlank()) throw new BusinessException("A reason is required.");
        db.tx(cn -> {
            Map<String, Object> p = Db.one(cn, "SELECT name, stock_qty, cost_price FROM products WHERE product_id = ?", productId);
            if (p == null) throw new BusinessException("No such product: " + productId);
            if (Db.num(p, "stock_qty") + delta < 0)
                throw new BusinessException("Stock cannot go below zero (current " + Db.num(p, "stock_qty") + ").");
            long id = Db.insert(cn, "INSERT INTO stock_movements(product_id,movement_date,movement_type,quantity_change,note) VALUES(?,?,?,?,?)",
                    productId, Db.now(), "ADJUSTMENT", delta, reason.trim());
            long value = Math.abs(delta) * Db.num(p, "cost_price");
            if (delta < 0) Db.post(cn, "INVENTORY_ADJUSTMENT", "INVENTORY", value, "ADJUSTMENT", id, reason.trim());
            else Db.post(cn, "INVENTORY", "INVENTORY_ADJUSTMENT", value, "ADJUSTMENT", id, reason.trim());
            return null;
        });
    }

    // ---------------------------------------------------------- reports
    public void stockLevels() throws SQLException {
        Db.print(c, "SELECT product_id, sku, name, unit, stock_qty, reorder_level, cost_price, stock_qty * cost_price AS stock_value "
                + "FROM products ORDER BY name");
        System.out.printf("  Total stock value (at cost): %,d%n",
                Db.scalar(c, "SELECT COALESCE(SUM(stock_qty * cost_price),0) FROM products"));
    }

    public void lowStock() throws SQLException {
        Db.print(c, "SELECT * FROM v_low_stock ORDER BY name");
    }

    public void movements(long productId) throws SQLException {
        Db.print(c, "SELECT movement_id, movement_date, movement_type, quantity_change, ref_id, note "
                + "FROM stock_movements WHERE product_id = ? ORDER BY movement_id DESC", productId);
    }

    public void listPurchases() throws SQLException {
        Db.print(c, "SELECT po.po_id, s.name AS supplier, po.po_date, COUNT(l.line_id) AS lines, "
                + "SUM(l.quantity * l.unit_cost) AS total_cost FROM purchase_orders po "
                + "JOIN suppliers s ON s.supplier_id = po.supplier_id JOIN purchase_order_lines l ON l.po_id = po.po_id "
                + "GROUP BY po.po_id, s.name, po.po_date ORDER BY po.po_id DESC");
    }
}
