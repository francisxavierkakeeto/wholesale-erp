package erp;

import erp.Db.BusinessException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Scripted scenario that proves the modules share one consistent database. Run with: --selftest */
public final class SelfTest {
    private static int failures = 0;

    private static void expect(String name, boolean cond) {
        System.out.printf("  [%s] %s%n", cond ? "PASS" : "FAIL", name);
        if (!cond) failures++;
    }

    private interface Action { void run() throws Exception; }

    private static boolean rejected(Class<? extends Exception> type, String textInMessage, Action a) {
        try { a.run(); return false; }
        catch (Exception e) { return type.isInstance(e) && String.valueOf(e.getMessage()).contains(textInMessage); }
    }

    /** Like rejected(), but checks WHICH database rule fired (by MySQL/MariaDB error code, not message text). */
    private static boolean rejectedBy(Db.Violation expected, Action a) {
        try { a.run(); return false; }
        catch (java.sql.SQLException e) { return Db.kind(e) == expected; }
        catch (Exception e) { return false; }
    }

    public static int run(Db.Config base) throws Exception {
        Db.Config cfg = base.withDatabase(base.database() + "_selftest");   // scratch database, never your real data
        Db.dropDatabase(cfg);
        try (Db db = new Db(cfg)) {
            Connection c = db.conn();
            Admin.seed(db);
            Sales sales = new Sales(db);
            Finance fin = new Finance(db);
            Inventory inv = new Inventory(db);

            System.out.println("1. Seed data keeps modules consistent");
            expect("integrity check passes after seeding", Admin.integrityCheck(db, false));

            long sugar = Db.scalar(c, "SELECT product_id FROM products WHERE sku='GRC-001'");
            long cust = Db.scalar(c, "SELECT customer_id FROM customers WHERE name LIKE 'Okello%'");
            long before = Db.scalar(c, "SELECT stock_qty FROM products WHERE product_id=?", sugar);

            System.out.println("2. A sale updates Inventory and Finance together");
            long ledgerBefore = Db.scalar(c, "SELECT COUNT(*) FROM ledger_entries");
            long order = sales.createSale(cust, List.of(new Sales.Item(sugar, 10)), 0, "CASH");
            expect("stock fell by 10", Db.scalar(c, "SELECT stock_qty FROM products WHERE product_id=?", sugar) == before - 10);
            expect("invoice created for 10 x 4,500 = 45,000", Db.scalar(c, "SELECT total_amount FROM invoices WHERE order_id=?", order) == 45_000);
            expect("4 ledger rows posted (revenue + COGS)", Db.scalar(c, "SELECT COUNT(*) FROM ledger_entries") == ledgerBefore + 4);
            expect("integrity still holds", Admin.integrityCheck(db, false));

            System.out.println("3. Rollback: a failing order leaves NO partial records");
            long orders = Db.scalar(c, "SELECT COUNT(*) FROM sales_orders");
            long moves = Db.scalar(c, "SELECT COUNT(*) FROM stock_movements");
            expect("oversell rejected with clear message",
                    rejected(BusinessException.class, "Insufficient stock",
                            () -> sales.createSale(cust, List.of(new Sales.Item(sugar, 10), new Sales.Item(sugar + 1, 99999)), 0, "CASH")));
            expect("no order/movement rows left behind",
                    Db.scalar(c, "SELECT COUNT(*) FROM sales_orders") == orders
                            && Db.scalar(c, "SELECT COUNT(*) FROM stock_movements") == moves);
            long small = Db.scalar(c, "SELECT customer_id FROM customers WHERE name LIKE 'Sunrise%'");
            expect("credit limit enforced (and rolled back)",
                    rejected(BusinessException.class, "Credit limit exceeded",
                            () -> sales.createSale(small, List.of(new Sales.Item(sugar, 150)), 0, "CASH"))
                            && Db.scalar(c, "SELECT COUNT(*) FROM sales_orders") == orders);

            System.out.println("4. Database itself refuses bad data (bypassing Java rules)");
            expect("CHECK stops negative stock",
                    rejectedBy(Db.Violation.CHECK,
                            () -> Db.insert(c, "INSERT INTO stock_movements(product_id,movement_date,movement_type,quantity_change) VALUES(?,?,?,?)",
                                    sugar, Db.now(), "ADJUSTMENT", -1_000_000)));
            expect("trigger stops over-payment",
                    rejectedBy(Db.Violation.RULE,
                            () -> Db.insert(c, "INSERT INTO payments(invoice_id,payment_date,amount,method) VALUES(?,?,?,?)",
                                    Db.scalar(c, "SELECT invoice_id FROM invoices WHERE order_id=?", order), Db.now(), 999_999_999, "CASH")));
            expect("ledger is append-only",
                    rejectedBy(Db.Violation.RULE, () -> Db.update(c, "UPDATE ledger_entries SET debit = 1 WHERE entry_id = 1")));
            expect("stock history is append-only",
                    rejectedBy(Db.Violation.RULE, () -> Db.update(c, "DELETE FROM stock_movements WHERE movement_id = 1")));
            expect("foreign key blocks deleting a product with history",
                    rejectedBy(Db.Violation.FOREIGN_KEY, () -> Db.update(c, "DELETE FROM products WHERE product_id = ?", sugar)));
            expect("duplicate SKU blocked by UNIQUE (case-insensitive)",
                    rejectedBy(Db.Violation.UNIQUE, () -> Db.insert(c, "INSERT INTO products(sku,name,category_id,unit_price) VALUES('grc-001','Dup',1,1)")));
            expect("unbalanced ledger row blocked by CHECK",
                    rejectedBy(Db.Violation.CHECK, () -> Db.update(c,
                            "INSERT INTO ledger_entries(entry_date,account,debit,credit,ref_type,ref_id) VALUES('2026-01-01 00:00:00','CASH',5,5,'SALE',1)")));

            System.out.println("5. Payments");
            long invoice = Db.scalar(c, "SELECT invoice_id FROM invoices WHERE order_id=?", order);
            fin.pay(invoice, 20_000, "CASH");
            expect("part payment leaves 25,000 due", Db.scalar(c, "SELECT balance_due FROM v_invoice_balance WHERE invoice_id=?", invoice) == 25_000);
            expect("over-payment rejected by Java layer", rejected(BusinessException.class, "exceeds", () -> fin.pay(invoice, 30_000, "CASH")));
            expect("paid order cannot be cancelled", rejected(BusinessException.class, "cannot cancel", () -> sales.cancel(order)));
            fin.pay(invoice, 25_000, "MOBILE_MONEY");
            expect("invoice now PAID", Db.str(Db.one(c, "SELECT status FROM v_invoice_balance WHERE invoice_id=?", invoice), "status").equals("PAID"));

            System.out.println("6. Cancel (the ERP form of 'delete') restores stock and reverses the ledger");
            long stockNow = Db.scalar(c, "SELECT stock_qty FROM products WHERE product_id=?", sugar);
            long o2 = sales.createSale(cust, List.of(new Sales.Item(sugar, 5)), 0, "CASH");
            sales.cancel(o2);
            expect("stock restored", Db.scalar(c, "SELECT stock_qty FROM products WHERE product_id=?", sugar) == stockNow);
            expect("integrity holds after cancel", Admin.integrityCheck(db, false));

            System.out.println("7. Purchasing and adjustments");
            long sup = Db.scalar(c, "SELECT supplier_id FROM suppliers LIMIT 1");
            long oldCost = Db.scalar(c, "SELECT cost_price FROM products WHERE product_id=?", sugar);
            inv.receivePurchase(sup, List.of(new Inventory.Receipt(sugar, 100, 4200)));
            long newCost = Db.scalar(c, "SELECT cost_price FROM products WHERE product_id=?", sugar);
            expect("weighted-average cost moved up (" + oldCost + " -> " + newCost + ")", newCost > oldCost && newCost < 4200);
            inv.adjust(sugar, -3, "Damaged bags");
            expect("adjustment recorded", Db.scalar(c, "SELECT COUNT(*) FROM stock_movements WHERE movement_type='ADJUSTMENT' AND note='Damaged bags'") == 1);
            expect("integrity holds at the end", Admin.integrityCheck(db, false));

            System.out.println("8. Master-data CRUD");
            Master m = new Master(db);
            long cid = m.addCustomer("Test Customer", "0799000000", "x", 100);
            m.update("customers", cid, "credit_limit", "250");
            expect("update works", Db.scalar(c, "SELECT credit_limit FROM customers WHERE customer_id=?", cid) == 250);
            expect("delete of unused record really deletes", m.delete("customers", cid).equals("Deleted."));
            expect("delete of used record deactivates", m.delete("customers", cust).contains("DEACTIVATED"));
            expect("non-editable field refused", rejected(BusinessException.class, "cannot be edited", () -> m.update("products", sugar, "stock_qty", "99999")));
        } finally {
            Db.dropDatabase(cfg);
        }
        System.out.println(failures == 0 ? "\nALL CHECKS PASSED" : "\n" + failures + " CHECK(S) FAILED");
        return failures == 0 ? 0 : 1;
    }
}
