package erp;

import erp.Db.BusinessException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

/** Finance module: payments, receivables, general ledger and reports. */
public final class Finance {
    private final Db db;
    private final Connection c;
    private static final Set<String> METHODS = Set.of("CASH", "MOBILE_MONEY", "BANK");

    public Finance(Db db) { this.db = db; this.c = db.conn(); }

    /** Records a customer payment inside the caller's transaction: payment row + Dr CASH / Cr RECEIVABLE. */
    static long recordPayment(Connection c, long invoiceId, long amount, String method) throws SQLException {
        if (amount <= 0) throw new BusinessException("Payment amount must be positive.");
        if (!METHODS.contains(method)) throw new BusinessException("Method must be one of " + METHODS);
        Map<String, Object> inv = Db.one(c, "SELECT balance_due, status FROM v_invoice_balance WHERE invoice_id = ?", invoiceId);
        if (inv == null) throw new BusinessException("No such invoice: " + invoiceId);
        if (Db.str(inv, "status").equals("CANCELLED")) throw new BusinessException("Invoice is cancelled.");
        if (amount > Db.num(inv, "balance_due"))
            throw new BusinessException("Payment " + String.format("%,d", amount) + " exceeds the balance due of "
                    + String.format("%,d", Db.num(inv, "balance_due")) + ".");
        long id = Db.insert(c, "INSERT INTO payments(invoice_id,payment_date,amount,method) VALUES(?,?,?,?)",
                invoiceId, Db.now(), amount, method);
        Db.post(c, "CASH", "ACCOUNTS_RECEIVABLE", amount, "PAYMENT", id, "Payment for invoice #" + invoiceId + " (" + method + ")");
        return id;
    }

    public long pay(long invoiceId, long amount, String method) throws SQLException {
        return db.tx(cn -> recordPayment(cn, invoiceId, amount, method));
    }

    // ---------------------------------------------------------- reports
    public void listInvoices(boolean onlyUnpaid) throws SQLException {
        Db.print(c, "SELECT v.invoice_id, v.order_id, cu.name AS customer, v.invoice_date, v.total_amount, "
                + "v.paid_amount, v.balance_due, v.status FROM v_invoice_balance v "
                + "JOIN customers cu ON cu.customer_id = v.customer_id "
                + (onlyUnpaid ? "WHERE v.balance_due > 0 " : "") + "ORDER BY v.invoice_id DESC");
    }

    public void listPayments() throws SQLException {
        Db.print(c, "SELECT p.payment_id, p.invoice_id, cu.name AS customer, p.payment_date, p.amount, p.method "
                + "FROM payments p JOIN invoices i ON i.invoice_id = p.invoice_id "
                + "JOIN sales_orders so ON so.order_id = i.order_id JOIN customers cu ON cu.customer_id = so.customer_id "
                + "ORDER BY p.payment_id DESC");
    }

    public void customerBalances() throws SQLException {
        Db.print(c, "SELECT customer_id, name, credit_limit, outstanding, credit_limit - outstanding AS credit_available "
                + "FROM v_customer_balance ORDER BY outstanding DESC");
    }

    public void ledger(int limit) throws SQLException {
        Db.print(c, "SELECT entry_id, entry_date, account, debit, credit, ref_type, ref_id, description "
                + "FROM ledger_entries ORDER BY entry_id DESC LIMIT ?", limit);
    }

    public void trialBalance() throws SQLException {
        Db.print(c, "SELECT account, total_debit, total_credit, net_debit FROM v_trial_balance ORDER BY account");
        long d = Db.scalar(c, "SELECT COALESCE(SUM(debit),0) FROM ledger_entries");
        long cr = Db.scalar(c, "SELECT COALESCE(SUM(credit),0) FROM ledger_entries");
        System.out.printf("  TOTAL debits %,d  |  credits %,d  ->  %s%n", d, cr, d == cr ? "BALANCED" : "OUT OF BALANCE!");
    }

    public void profitAndLoss() throws SQLException {
        long rev = Db.scalar(c, "SELECT COALESCE(SUM(credit - debit),0) FROM ledger_entries WHERE account='SALES_REVENUE'");
        long cogs = Db.scalar(c, "SELECT COALESCE(SUM(debit - credit),0) FROM ledger_entries WHERE account='COGS'");
        long adj = Db.scalar(c, "SELECT COALESCE(SUM(debit - credit),0) FROM ledger_entries WHERE account='INVENTORY_ADJUSTMENT'");
        System.out.printf("  Sales revenue            %,15d%n  Cost of goods sold       %,15d%n  Stock adjustments (loss) %,15d%n"
                        + "  ---------------------------------------%n  NET PROFIT               %,15d%n",
                rev, cogs, adj, rev - cogs - adj);
    }
}
