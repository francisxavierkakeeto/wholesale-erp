package erp;

import erp.Db.BusinessException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Sales module. One call to {@link #createSale} touches all three modules atomically:
 *   Sales     -> sales_orders + sales_order_lines
 *   Inventory -> stock_movements (trigger lowers products.stock_qty)
 *   Finance   -> invoices + ledger_entries (+ optional payment)
 * If any step fails (e.g. not enough stock), the whole transaction is rolled back.
 */
public final class Sales {
    private final Db db;
    private final Connection c;

    public Sales(Db db) { this.db = db; this.c = db.conn(); }

    /** One order line request. */
    public record Item(long productId, long quantity) {}

    public long createSale(long customerId, List<Item> items, long upfrontPayment, String method) throws SQLException {
        if (items.isEmpty()) throw new BusinessException("An order needs at least one line.");
        if (upfrontPayment < 0) throw new BusinessException("Upfront payment cannot be negative.");
        return db.tx(cn -> {
            Map<String, Object> cust = Db.one(cn, "SELECT name, credit_limit, active FROM customers WHERE customer_id = ?", customerId);
            if (cust == null) throw new BusinessException("No such customer: " + customerId);
            if (Db.num(cust, "active") == 0) throw new BusinessException("Customer is inactive.");

            String now = Db.now();
            long orderId = Db.insert(cn, "INSERT INTO sales_orders(customer_id,order_date) VALUES(?,?)", customerId, now);
            long total = 0, cogs = 0;
            for (Item it : items) {
                if (it.quantity() <= 0) throw new BusinessException("Quantity must be positive.");
                Map<String, Object> p = Db.one(cn, "SELECT name, unit_price, cost_price, stock_qty, active FROM products WHERE product_id = ?", it.productId());
                if (p == null) throw new BusinessException("No such product: " + it.productId());
                if (Db.num(p, "active") == 0) throw new BusinessException(Db.str(p, "name") + " is inactive.");
                if (Db.num(p, "stock_qty") < it.quantity())
                    throw new BusinessException("Insufficient stock for " + Db.str(p, "name") + ": requested "
                            + it.quantity() + ", available " + Db.num(p, "stock_qty") + ".");
                long price = Db.num(p, "unit_price"), cost = Db.num(p, "cost_price");
                try {
                    Db.insert(cn, "INSERT INTO sales_order_lines(order_id,product_id,quantity,unit_price,unit_cost) VALUES(?,?,?,?,?)",
                            orderId, it.productId(), it.quantity(), price, cost);
                } catch (SQLException e) {
                    if (Db.kind(e) == Db.Violation.UNIQUE)
                        throw new BusinessException("Product " + it.productId() + " appears twice; combine into one line.");
                    throw e;
                }
                Db.insert(cn, "INSERT INTO stock_movements(product_id,movement_date,movement_type,quantity_change,ref_id,note) VALUES(?,?,?,?,?,?)",
                        it.productId(), now, "SALE", -it.quantity(), orderId, "Sales order #" + orderId);
                total += it.quantity() * price;
                cogs += it.quantity() * cost;
            }
            long invoiceId = Db.insert(cn, "INSERT INTO invoices(order_id,invoice_date,total_amount) VALUES(?,?,?)", orderId, now, total);
            String d = "Order #" + orderId + " / invoice #" + invoiceId;
            Db.post(cn, "ACCOUNTS_RECEIVABLE", "SALES_REVENUE", total, "SALE", orderId, "Revenue: " + d);
            Db.post(cn, "COGS", "INVENTORY", cogs, "SALE", orderId, "Cost of goods sold: " + d);

            if (upfrontPayment > 0) Finance.recordPayment(cn, invoiceId, upfrontPayment, method);

            long outstanding = Db.scalar(cn, "SELECT outstanding FROM v_customer_balance WHERE customer_id = ?", customerId);
            long limit = Db.num(cust, "credit_limit");
            if (outstanding > limit)
                throw new BusinessException(String.format("Credit limit exceeded for %s: outstanding would be %,d, limit %,d.",
                        Db.str(cust, "name"), outstanding, limit));
            return orderId;
        });
    }

    /**
     * Cancels an order: restores stock and posts reversing ledger entries (history is never deleted).
     * Not allowed once a payment exists, so cash records stay consistent.
     */
    public void cancel(long orderId) throws SQLException {
        db.tx(cn -> {
            Map<String, Object> o = Db.one(cn, "SELECT status FROM sales_orders WHERE order_id = ?", orderId);
            if (o == null) throw new BusinessException("No such order: " + orderId);
            if (!Db.str(o, "status").equals("CONFIRMED")) throw new BusinessException("Order is already cancelled.");
            long paid = Db.scalar(cn, "SELECT paid_amount FROM v_invoice_balance WHERE order_id = ?", orderId);
            if (paid > 0) throw new BusinessException("Order has payments (" + String.format("%,d", paid) + "); cannot cancel.");

            String now = Db.now();
            for (Map<String, Object> l : Db.list(cn, "SELECT product_id, quantity FROM sales_order_lines WHERE order_id = ?", orderId))
                Db.insert(cn, "INSERT INTO stock_movements(product_id,movement_date,movement_type,quantity_change,ref_id,note) VALUES(?,?,?,?,?,?)",
                        Db.num(l, "product_id"), now, "SALE_CANCEL", Db.num(l, "quantity"), orderId, "Cancelled order #" + orderId);
            Db.update(cn, "INSERT INTO ledger_entries(entry_date,account,debit,credit,ref_type,ref_id,description) "
                    + "SELECT ?, account, credit, debit, 'SALE_CANCEL', ref_id, CONCAT('Reversal: ', description) "
                    + "FROM ledger_entries WHERE ref_type = 'SALE' AND ref_id = ?", now, orderId);
            Db.update(cn, "UPDATE sales_orders SET status = 'CANCELLED' WHERE order_id = ?", orderId);
            return null;
        });
    }

    // ---------------------------------------------------------- reports
    public void listOrders() throws SQLException {
        Db.print(c, "SELECT so.order_id, cu.name AS customer, so.order_date, so.status, "
                + "(SELECT COUNT(*) FROM sales_order_lines l WHERE l.order_id = so.order_id) AS lines, "
                + "i.invoice_id, i.total_amount "
                + "FROM sales_orders so JOIN customers cu ON cu.customer_id = so.customer_id "
                + "JOIN invoices i ON i.order_id = so.order_id ORDER BY so.order_id DESC");
    }

    public void showOrder(long orderId) throws SQLException {
        Map<String, Object> h = Db.one(c, "SELECT so.order_id, cu.name AS customer, so.order_date, so.status, v.invoice_id, "
                + "v.total_amount, v.paid_amount, v.balance_due FROM sales_orders so "
                + "JOIN customers cu ON cu.customer_id = so.customer_id JOIN v_invoice_balance v ON v.order_id = so.order_id "
                + "WHERE so.order_id = ?", orderId);
        if (h == null) throw new BusinessException("No such order: " + orderId);
        System.out.printf("  Order #%d | %s | %s | %s%n  Invoice #%d  total %,d  paid %,d  balance %,d%n",
                orderId, Db.str(h, "customer"), Db.str(h, "order_date"), Db.str(h, "status"),
                Db.num(h, "invoice_id"), Db.num(h, "total_amount"), Db.num(h, "paid_amount"), Db.num(h, "balance_due"));
        Db.print(c, "SELECT p.sku, p.name, l.quantity, l.unit_price, l.quantity * l.unit_price AS line_total "
                + "FROM sales_order_lines l JOIN products p ON p.product_id = l.product_id WHERE l.order_id = ?", orderId);
    }
}
