package erp;

import erp.Db.BusinessException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/** Command-line interface for the wholesale ERP prototype. */
public final class Main {
    private static final Scanner IN = new Scanner(System.in);

    public static void main(String[] args) throws Exception {
        Db.Config cfg = Db.Config.load();
        boolean selftest = args.length > 0 && args[0].equals("--selftest");
        if (args.length > 0 && !selftest) cfg = cfg.withDatabase(args[0]);   // ./run.sh mytest  -> another database
        try {
            if (selftest) System.exit(SelfTest.run(cfg));
            try (Db db = new Db(cfg)) {
                System.out.println("=== General Goods Wholesaler ERP  (database: " + cfg.database()
                        + " on " + cfg.host() + ":" + cfg.port() + ") ===");
                if (Db.scalar(db.conn(), "SELECT COUNT(*) FROM products") == 0)
                    System.out.println("The database is empty. Use  7) Admin > 1) Load demo data  to fill it.");
                mainMenu(db);
            }
            System.out.println("Goodbye.");
        } catch (SQLException e) {
            System.out.println("\nCannot use the database: " + e.getMessage());
            System.out.println("Check that MySQL/MariaDB is running (XAMPP: Control Panel > MySQL > Start)");
            System.out.println("and that host, port, user and password in erp.properties are correct.");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------- menus
    private static void mainMenu(Db db) {
        Master master = new Master(db);
        Sales sales = new Sales(db);
        Inventory inv = new Inventory(db);
        Finance fin = new Finance(db);
        while (true) {
            String ch = menu("MAIN MENU", "Customers", "Suppliers", "Products & categories", "Sales",
                    "Purchasing & inventory", "Finance", "Admin (demo data, integrity check)");
            if (ch.equals("0")) return;
            safely(() -> {
                switch (ch) {
                    case "1" -> partyMenu(master, "customers", "Customers");
                    case "2" -> partyMenu(master, "suppliers", "Suppliers");
                    case "3" -> productMenu(master);
                    case "4" -> salesMenu(sales, master);
                    case "5" -> inventoryMenu(inv, master);
                    case "6" -> financeMenu(fin);
                    case "7" -> adminMenu(db);
                    default -> System.out.println("Invalid choice.");
                }
            });
        }
    }

    private static void partyMenu(Master m, String table, String title) {
        boolean cust = table.equals("customers");
        while (true) {
            String ch = menu(title.toUpperCase(), "List", "Add", "Update a field", "Delete (or deactivate if it has history)");
            if (ch.equals("0")) return;
            safely(() -> {
                switch (ch) {
                    case "1" -> { if (cust) m.listCustomers(); else m.listSuppliers(); }
                    case "2" -> {
                        String name = ask("Name"), phone = ask("Phone (optional)"), addr = ask("Address (optional)");
                        long id = cust ? m.addCustomer(name, phone, addr, askLong("Credit limit (UGX)"))
                                : m.addSupplier(name, phone, addr);
                        System.out.println("Created #" + id);
                    }
                    case "3" -> {
                        long id = askLong("ID");
                        System.out.println("Editable fields: " + m.editableFields(table));
                        m.update(table, id, ask("Field"), ask("New value"));
                        System.out.println("Updated.");
                    }
                    case "4" -> System.out.println(m.delete(table, askLong("ID")));
                    default -> System.out.println("Invalid choice.");
                }
            });
        }
    }

    private static void productMenu(Master m) {
        while (true) {
            String ch = menu("PRODUCTS & CATEGORIES", "List products", "Add product", "Update a product field",
                    "Delete / deactivate product", "List categories", "Add category", "Rename category", "Delete category");
            if (ch.equals("0")) return;
            safely(() -> {
                switch (ch) {
                    case "1" -> m.listProducts();
                    case "2" -> {
                        m.listCategories();
                        long id = m.addProduct(ask("SKU"), ask("Name"), askLong("Category ID"), ask("Unit (e.g. bag, crate)"),
                                askLong("Selling price (UGX)"), askLong("Reorder level"));
                        System.out.println("Created product #" + id + ". Add opening stock via Purchasing & inventory.");
                    }
                    case "3" -> {
                        long id = askLong("Product ID");
                        System.out.println("Editable fields: " + m.editableFields("products")
                                + "  (stock changes go through Inventory; cost changes through purchases)");
                        m.update("products", id, ask("Field"), ask("New value"));
                        System.out.println("Updated.");
                    }
                    case "4" -> System.out.println(m.delete("products", askLong("Product ID")));
                    case "5" -> m.listCategories();
                    case "6" -> System.out.println("Created category #" + m.addCategory(ask("Name")));
                    case "7" -> { m.update("categories", askLong("Category ID"), "name", ask("New name")); System.out.println("Updated."); }
                    case "8" -> System.out.println(m.delete("categories", askLong("Category ID")));
                    default -> System.out.println("Invalid choice.");
                }
            });
        }
    }

    private static void salesMenu(Sales s, Master m) {
        while (true) {
            String ch = menu("SALES", "New sales order (creates invoice, reduces stock, posts ledger)", "List orders",
                    "View order / invoice", "Cancel order (reverses stock & ledger)");
            if (ch.equals("0")) return;
            safely(() -> {
                switch (ch) {
                    case "1" -> {
                        m.listCustomers();
                        long cust = askLong("Customer ID");
                        m.listProducts();
                        List<Sales.Item> items = new ArrayList<>();
                        while (true) {
                            String pid = ask("Product ID (blank = finish)");
                            if (pid.isBlank()) break;
                            items.add(new Sales.Item(Long.parseLong(pid.trim()), askLong("Quantity")));
                        }
                        String pay = ask("Upfront payment in UGX (blank = none)");
                        long up = pay.isBlank() ? 0 : Long.parseLong(pay.trim());
                        String method = up > 0 ? ask("Method (CASH / MOBILE_MONEY / BANK)").toUpperCase() : "CASH";
                        long id = s.createSale(cust, items, up, method);
                        System.out.println("Order #" + id + " confirmed.");
                        s.showOrder(id);
                    }
                    case "2" -> s.listOrders();
                    case "3" -> s.showOrder(askLong("Order ID"));
                    case "4" -> { s.cancel(askLong("Order ID")); System.out.println("Order cancelled; stock restored and ledger reversed."); }
                    default -> System.out.println("Invalid choice.");
                }
            });
        }
    }

    private static void inventoryMenu(Inventory inv, Master m) {
        while (true) {
            String ch = menu("PURCHASING & INVENTORY", "Stock levels & value", "Low-stock report", "Receive goods from supplier",
                    "Adjust stock (opening stock, damage, count)", "Stock movement history", "List purchase orders");
            if (ch.equals("0")) return;
            safely(() -> {
                switch (ch) {
                    case "1" -> inv.stockLevels();
                    case "2" -> inv.lowStock();
                    case "3" -> {
                        m.listSuppliers();
                        long sup = askLong("Supplier ID");
                        m.listProducts();
                        List<Inventory.Receipt> lines = new ArrayList<>();
                        while (true) {
                            String pid = ask("Product ID (blank = finish)");
                            if (pid.isBlank()) break;
                            lines.add(new Inventory.Receipt(Long.parseLong(pid.trim()), askLong("Quantity"), askLong("Unit cost (UGX)")));
                        }
                        System.out.println("Purchase order #" + inv.receivePurchase(sup, lines) + " received.");
                    }
                    case "4" -> {
                        long id = askLong("Product ID");
                        inv.adjust(id, askLong("Quantity change (+ add / - remove)"), ask("Reason"));
                        System.out.println("Stock adjusted.");
                    }
                    case "5" -> inv.movements(askLong("Product ID"));
                    case "6" -> inv.listPurchases();
                    default -> System.out.println("Invalid choice.");
                }
            });
        }
    }

    private static void financeMenu(Finance f) {
        while (true) {
            String ch = menu("FINANCE", "Record customer payment", "Invoices (all)", "Unpaid invoices", "Payments",
                    "Customer balances", "General ledger (latest 30)", "Trial balance", "Profit & loss");
            if (ch.equals("0")) return;
            safely(() -> {
                switch (ch) {
                    case "1" -> {
                        f.listInvoices(true);
                        long inv = askLong("Invoice ID");
                        long id = f.pay(inv, askLong("Amount (UGX)"), ask("Method (CASH / MOBILE_MONEY / BANK)").toUpperCase());
                        System.out.println("Payment #" + id + " recorded.");
                    }
                    case "2" -> f.listInvoices(false);
                    case "3" -> f.listInvoices(true);
                    case "4" -> f.listPayments();
                    case "5" -> f.customerBalances();
                    case "6" -> f.ledger(30);
                    case "7" -> f.trialBalance();
                    case "8" -> f.profitAndLoss();
                    default -> System.out.println("Invalid choice.");
                }
            });
        }
    }

    private static void adminMenu(Db db) {
        while (true) {
            String ch = menu("ADMIN", "Load demo data (only into an empty database)", "Run integrity check");
            if (ch.equals("0")) return;
            safely(() -> {
                switch (ch) {
                    case "1" -> {
                        if (Db.scalar(db.conn(), "SELECT COUNT(*) FROM products") > 0)
                            throw new BusinessException("Database already has data; start with a new file to load the demo.");
                        Admin.seed(db);
                        System.out.println("Demo data loaded.");
                    }
                    case "2" -> System.out.println(Admin.integrityCheck(db, true) ? "  => ALL CHECKS PASSED" : "  => PROBLEMS FOUND");
                    default -> System.out.println("Invalid choice.");
                }
            });
        }
    }

    // ----------------------------------------------------------- console
    private interface Step { void run() throws Exception; }

    /** Runs one menu action and turns every kind of error into a readable message. */
    private static void safely(Step s) {
        try { s.run(); }
        catch (BusinessException e) { System.out.println("  ! " + e.getMessage()); }
        catch (NumberFormatException e) { System.out.println("  ! Please enter a whole number."); }
        catch (SQLException e) { System.out.println("  ! Database rule: " + friendly(e)); }
        catch (Exception e) { System.out.println("  ! Unexpected error: " + e); }
    }

    private static String friendly(SQLException e) {
        return switch (Db.kind(e)) {
            case UNIQUE -> "that value already exists (duplicate SKU, phone or name).";
            case FOREIGN_KEY -> "that ID does not exist, or the record is in use.";
            case CHECK -> "value rejected (negative, empty or out of range).";
            case RULE -> String.valueOf(e.getMessage());           // message written by our own trigger
            case NOT_NULL -> "a required value is missing.";
            case BAD_VALUE -> "value has the wrong format or is too long.";
            case OTHER -> String.valueOf(e.getMessage());
        };
    }

    private static String menu(String title, String... options) {
        System.out.println("\n--- " + title + " ---");
        for (int i = 0; i < options.length; i++) System.out.println("  " + (i + 1) + ") " + options[i]);
        System.out.println("  0) " + (title.equals("MAIN MENU") ? "Exit" : "Back"));
        return ask("Choose").trim();
    }

    private static String ask(String prompt) {
        System.out.print("  " + prompt + ": ");
        System.out.flush();
        if (!IN.hasNextLine()) { System.out.println(); System.out.println("(end of input) Goodbye."); System.exit(0); }
        return IN.nextLine();
    }

    private static long askLong(String prompt) {
        return Long.parseLong(ask(prompt).trim().replace(",", ""));
    }
}
