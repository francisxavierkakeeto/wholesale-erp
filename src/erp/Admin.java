package erp;

import java.sql.Connection;
import java.sql.SQLException;

/** Cross-module integrity checks and demo data. */
public final class Admin {
    private Admin() {}

    /** Verifies the single-source-of-truth rules across modules. @return true when everything agrees. */
    public static boolean integrityCheck(Db db, boolean verbose) throws SQLException {
        Connection c = db.conn();
        boolean ok = true;
        ok &= check(verbose, "Stock balance equals the sum of its movements (every product)",
                Db.scalar(c, "SELECT COUNT(*) FROM products p WHERE p.stock_qty <> "
                        + "COALESCE((SELECT SUM(quantity_change) FROM stock_movements m WHERE m.product_id = p.product_id),0)"));
        ok &= check(verbose, "No product has negative stock",
                Db.scalar(c, "SELECT COUNT(*) FROM products WHERE stock_qty < 0"));
        ok &= check(verbose, "Ledger debits equal credits",
                Math.abs(Db.scalar(c, "SELECT COALESCE(SUM(debit) - SUM(credit),0) FROM ledger_entries")));
        ok &= check(verbose, "Every invoice total equals the sum of its order lines",
                Db.scalar(c, "SELECT COUNT(*) FROM invoices i WHERE i.total_amount <> "
                        + "COALESCE((SELECT SUM(quantity * unit_price) FROM sales_order_lines l WHERE l.order_id = i.order_id),0)"));
        ok &= check(verbose, "Ledger receivables equal the sum of open invoice balances",
                Math.abs(Db.scalar(c, "SELECT COALESCE((SELECT SUM(debit - credit) FROM ledger_entries WHERE account='ACCOUNTS_RECEIVABLE'),0)"
                        + " - COALESCE((SELECT SUM(balance_due) FROM v_invoice_balance),0)")));
        ok &= check(verbose, "Ledger revenue equals non-cancelled invoice totals",
                Math.abs(Db.scalar(c, "SELECT COALESCE((SELECT SUM(credit - debit) FROM ledger_entries WHERE account='SALES_REVENUE'),0)"
                        + " - COALESCE((SELECT SUM(total_amount) FROM v_invoice_balance WHERE order_status='CONFIRMED'),0)")));
        ok &= check(verbose, "No invoice is over-paid",
                Db.scalar(c, "SELECT COUNT(*) FROM v_invoice_balance WHERE paid_amount > total_amount AND order_status='CONFIRMED'"));
        return ok;
    }

    private static boolean check(boolean verbose, String name, long violations) {
        if (verbose) System.out.printf("  [%s] %s%n", violations == 0 ? "PASS" : "FAIL", name);
        return violations == 0;
    }

    /** Loads a small realistic dataset through the normal services, so ledger and stock stay consistent. */
    public static void seed(Db db) throws SQLException {
        Master m = new Master(db);
        Inventory inv = new Inventory(db);
        Sales sales = new Sales(db);
        Finance fin = new Finance(db);

        long bev = m.addCategory("Beverages"), stp = m.addCategory("Grocery staples"),
                hh = m.addCategory("Household"), pc = m.addCategory("Personal care"), st = m.addCategory("Stationery");

        long sugar = m.addProduct("GRC-001", "Sugar 1kg", stp, "bag", 4500, 50);
        long rice = m.addProduct("GRC-002", "Rice 25kg", stp, "sack", 98000, 10);
        long oil = m.addProduct("GRC-003", "Cooking oil 5L", stp, "jerrycan", 42000, 15);
        long flour = m.addProduct("GRC-004", "Wheat flour 2kg", stp, "bag", 8500, 30);
        long soda = m.addProduct("BEV-001", "Soda 500ml (crate of 24)", bev, "crate", 29000, 20);
        long water = m.addProduct("BEV-002", "Mineral water 500ml (pack of 12)", bev, "pack", 9500, 40);
        long tea = m.addProduct("BEV-003", "Tea leaves 500g", bev, "packet", 6500, 25);
        long soap = m.addProduct("HH-001", "Laundry soap bar (box of 25)", hh, "box", 32000, 15);
        long tissue = m.addProduct("HH-002", "Toilet tissue (pack of 10)", hh, "pack", 11000, 30);
        long paste = m.addProduct("PC-001", "Toothpaste 100ml (box of 12)", pc, "box", 21000, 10);
        long book = m.addProduct("ST-001", "Exercise book 96pg (pack of 12)", st, "pack", 13000, 20);
        long pen = m.addProduct("ST-002", "Ballpoint pens (box of 50)", st, "box", 15000, 10);

        long s1 = m.addSupplier("Kampala Trade Distributors", "0700111222", "Industrial Area, Kampala");
        long s2 = m.addSupplier("Mukwano Wholesale Supplies", "0700333444", "Nakawa, Kampala");
        long s3 = m.addSupplier("Eastern Foods Ltd", "0700555666", "Jinja Road, Kampala");

        long c1 = m.addCustomer("Nakato General Store", "0771000001", "Kalerwe, Kampala", 2_000_000);
        long c2 = m.addCustomer("Okello Mini Supermarket", "0771000002", "Ntinda, Kampala", 3_000_000);
        long c3 = m.addCustomer("Namuli Retail Shop", "0771000003", "Mpigi Town", 1_000_000);
        m.addCustomer("Sunrise Canteen", "0771000004", "Wandegeya, Kampala", 500_000);

        inv.receivePurchase(s1, java.util.List.of(
                new Inventory.Receipt(sugar, 400, 3800), new Inventory.Receipt(rice, 60, 90000),
                new Inventory.Receipt(oil, 80, 37000), new Inventory.Receipt(flour, 150, 7200)));
        inv.receivePurchase(s2, java.util.List.of(
                new Inventory.Receipt(soap, 70, 27500), new Inventory.Receipt(tissue, 120, 9000),
                new Inventory.Receipt(paste, 50, 18000), new Inventory.Receipt(book, 100, 10500),
                new Inventory.Receipt(pen, 40, 12000)));
        inv.receivePurchase(s3, java.util.List.of(
                new Inventory.Receipt(soda, 100, 25500), new Inventory.Receipt(water, 200, 8000),
                new Inventory.Receipt(tea, 90, 5400)));

        sales.createSale(c1, java.util.List.of(new Sales.Item(sugar, 40), new Sales.Item(oil, 5),
                new Sales.Item(flour, 20)), 300_000, "CASH");
        sales.createSale(c2, java.util.List.of(new Sales.Item(rice, 10), new Sales.Item(soda, 12),
                new Sales.Item(water, 30)), 0, "CASH");
        sales.createSale(c3, java.util.List.of(new Sales.Item(soap, 6), new Sales.Item(tissue, 10)), 150_000, "MOBILE_MONEY");
        fin.pay(2, 500_000, "BANK"); // part-payment of invoice #2 (order o2)
    }
}
