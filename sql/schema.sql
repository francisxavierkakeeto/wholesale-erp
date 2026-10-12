-- ============================================================================
-- Wholesale ERP prototype: single integrated database (MySQL / MariaDB, InnoDB)
-- Modules sharing ONE schema:  Sales  |  Inventory (incl. Purchasing)  |  Finance
--
-- Money is stored as BIGINT Uganda Shillings (UGX has no minor unit), which
-- avoids floating-point rounding errors in the ledger.
--
-- The Java program creates the database and runs this script automatically.
-- To load it by hand (phpMyAdmin, MySQL Workbench or the mysql client):
--     CREATE DATABASE wholesale_erp CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
--     USE wholesale_erp;
--     (then run this whole file; it is safe to run more than once)
-- Requires MariaDB 10.2.1+ or MySQL 8.0.16+ (earlier versions ignore CHECK constraints).
-- ============================================================================

-- ---------------------------- MASTER DATA ----------------------------------

CREATE TABLE IF NOT EXISTS categories (
    category_id BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(80)  NOT NULL,
    PRIMARY KEY (category_id),
    UNIQUE KEY uq_categories_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS products (
    product_id    BIGINT       NOT NULL AUTO_INCREMENT,
    sku           VARCHAR(40)  NOT NULL,
    name          VARCHAR(150) NOT NULL,
    category_id   BIGINT       NOT NULL,
    unit          VARCHAR(30)  NOT NULL DEFAULT 'piece',
    cost_price    BIGINT       NOT NULL DEFAULT 0,
    unit_price    BIGINT       NOT NULL,
    stock_qty     BIGINT       NOT NULL DEFAULT 0,
    reorder_level BIGINT       NOT NULL DEFAULT 0,
    active        TINYINT      NOT NULL DEFAULT 1,
    PRIMARY KEY (product_id),
    UNIQUE KEY uq_products_sku (sku),
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories (category_id) ON DELETE RESTRICT,
    CONSTRAINT chk_products_name    CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_products_cost    CHECK (cost_price >= 0),
    CONSTRAINT chk_products_price   CHECK (unit_price >= 0),
    CONSTRAINT chk_products_stock   CHECK (stock_qty >= 0),
    CONSTRAINT chk_products_reorder CHECK (reorder_level >= 0),
    CONSTRAINT chk_products_active  CHECK (active IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS customers (
    customer_id  BIGINT       NOT NULL AUTO_INCREMENT,
    name         VARCHAR(150) NOT NULL,
    phone        VARCHAR(30)  NULL,
    address      VARCHAR(255) NULL,
    credit_limit BIGINT       NOT NULL DEFAULT 0,
    active       TINYINT      NOT NULL DEFAULT 1,
    PRIMARY KEY (customer_id),
    UNIQUE KEY uq_customers_phone (phone),
    CONSTRAINT chk_customers_name   CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_customers_credit CHECK (credit_limit >= 0),
    CONSTRAINT chk_customers_active CHECK (active IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS suppliers (
    supplier_id BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(150) NOT NULL,
    phone       VARCHAR(30)  NULL,
    address     VARCHAR(255) NULL,
    active      TINYINT      NOT NULL DEFAULT 1,
    PRIMARY KEY (supplier_id),
    UNIQUE KEY uq_suppliers_phone (phone),
    CONSTRAINT chk_suppliers_name   CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_suppliers_active CHECK (active IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------- SALES -------------------------------------

CREATE TABLE IF NOT EXISTS sales_orders (
    order_id    BIGINT      NOT NULL AUTO_INCREMENT,
    customer_id BIGINT      NOT NULL,
    order_date  DATETIME    NOT NULL,
    status      VARCHAR(12) NOT NULL DEFAULT 'CONFIRMED',
    PRIMARY KEY (order_id),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers (customer_id) ON DELETE RESTRICT,
    CONSTRAINT chk_orders_status  CHECK (status IN ('CONFIRMED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sales_order_lines (
    line_id    BIGINT NOT NULL AUTO_INCREMENT,
    order_id   BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity   BIGINT NOT NULL,
    unit_price BIGINT NOT NULL,
    unit_cost  BIGINT NOT NULL,
    PRIMARY KEY (line_id),
    UNIQUE KEY uq_sol_order_product (order_id, product_id),
    CONSTRAINT fk_sol_order   FOREIGN KEY (order_id)   REFERENCES sales_orders (order_id) ON DELETE RESTRICT,
    CONSTRAINT fk_sol_product FOREIGN KEY (product_id) REFERENCES products (product_id)   ON DELETE RESTRICT,
    CONSTRAINT chk_sol_qty    CHECK (quantity > 0),
    CONSTRAINT chk_sol_price  CHECK (unit_price >= 0),
    CONSTRAINT chk_sol_cost   CHECK (unit_cost >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------ FINANCE ------------------------------------

CREATE TABLE IF NOT EXISTS invoices (
    invoice_id   BIGINT   NOT NULL AUTO_INCREMENT,
    order_id     BIGINT   NOT NULL,
    invoice_date DATETIME NOT NULL,
    total_amount BIGINT   NOT NULL,
    PRIMARY KEY (invoice_id),
    UNIQUE KEY uq_invoices_order (order_id),
    CONSTRAINT fk_invoices_order  FOREIGN KEY (order_id) REFERENCES sales_orders (order_id) ON DELETE RESTRICT,
    CONSTRAINT chk_invoices_total CHECK (total_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS payments (
    payment_id   BIGINT      NOT NULL AUTO_INCREMENT,
    invoice_id   BIGINT      NOT NULL,
    payment_date DATETIME    NOT NULL,
    amount       BIGINT      NOT NULL,
    method       VARCHAR(20) NOT NULL,
    PRIMARY KEY (payment_id),
    CONSTRAINT fk_payments_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (invoice_id) ON DELETE RESTRICT,
    CONSTRAINT chk_payments_amount CHECK (amount > 0),
    CONSTRAINT chk_payments_method CHECK (method IN ('CASH', 'MOBILE_MONEY', 'BANK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Double-entry general ledger. Every row is either a debit or a credit.
CREATE TABLE IF NOT EXISTS ledger_entries (
    entry_id    BIGINT       NOT NULL AUTO_INCREMENT,
    entry_date  DATETIME     NOT NULL,
    account     VARCHAR(30)  NOT NULL,
    debit       BIGINT       NOT NULL DEFAULT 0,
    credit      BIGINT       NOT NULL DEFAULT 0,
    ref_type    VARCHAR(15)  NOT NULL,
    ref_id      BIGINT       NOT NULL,
    description VARCHAR(300) NULL,
    PRIMARY KEY (entry_id),
    KEY ix_ledger_ref (ref_type, ref_id),
    CONSTRAINT chk_ledger_account CHECK (account IN
        ('CASH', 'ACCOUNTS_RECEIVABLE', 'INVENTORY', 'ACCOUNTS_PAYABLE',
         'SALES_REVENUE', 'COGS', 'INVENTORY_ADJUSTMENT')),
    CONSTRAINT chk_ledger_reftype CHECK (ref_type IN ('SALE', 'SALE_CANCEL', 'PAYMENT', 'PURCHASE', 'ADJUSTMENT')),
    CONSTRAINT chk_ledger_amounts CHECK (debit >= 0 AND credit >= 0),
    CONSTRAINT chk_ledger_one_side CHECK ((debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------- INVENTORY / PURCHASING ----------------------------

CREATE TABLE IF NOT EXISTS purchase_orders (
    po_id       BIGINT   NOT NULL AUTO_INCREMENT,
    supplier_id BIGINT   NOT NULL,
    po_date     DATETIME NOT NULL,
    PRIMARY KEY (po_id),
    CONSTRAINT fk_po_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (supplier_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS purchase_order_lines (
    line_id    BIGINT NOT NULL AUTO_INCREMENT,
    po_id      BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity   BIGINT NOT NULL,
    unit_cost  BIGINT NOT NULL,
    PRIMARY KEY (line_id),
    UNIQUE KEY uq_pol_po_product (po_id, product_id),
    CONSTRAINT fk_pol_po      FOREIGN KEY (po_id)      REFERENCES purchase_orders (po_id)  ON DELETE RESTRICT,
    CONSTRAINT fk_pol_product FOREIGN KEY (product_id) REFERENCES products (product_id)    ON DELETE RESTRICT,
    CONSTRAINT chk_pol_qty    CHECK (quantity > 0),
    CONSTRAINT chk_pol_cost   CHECK (unit_cost >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Every change of stock is a row here. products.stock_qty is derived from it by trigger.
CREATE TABLE IF NOT EXISTS stock_movements (
    movement_id     BIGINT       NOT NULL AUTO_INCREMENT,
    product_id      BIGINT       NOT NULL,
    movement_date   DATETIME     NOT NULL,
    movement_type   VARCHAR(15)  NOT NULL,
    quantity_change BIGINT       NOT NULL,
    ref_id          BIGINT       NULL,
    note            VARCHAR(255) NULL,
    PRIMARY KEY (movement_id),
    KEY ix_movements_product (product_id),
    CONSTRAINT fk_movements_product FOREIGN KEY (product_id) REFERENCES products (product_id) ON DELETE RESTRICT,
    CONSTRAINT chk_movements_type   CHECK (movement_type IN ('SALE', 'SALE_CANCEL', 'PURCHASE', 'ADJUSTMENT')),
    CONSTRAINT chk_movements_qty    CHECK (quantity_change <> 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ------------------------------- VIEWS -------------------------------------
-- Balances are DERIVED, never stored, so they can never disagree with the transactions.

CREATE OR REPLACE VIEW v_invoice_balance AS
SELECT d.*,
       CASE WHEN d.order_status = 'CANCELLED' THEN 'CANCELLED'
            WHEN d.paid_amount >= d.total_amount THEN 'PAID'
            WHEN d.paid_amount > 0 THEN 'PARTIAL'
            ELSE 'OPEN' END AS status,
       CASE WHEN d.order_status = 'CANCELLED' THEN 0
            ELSE d.total_amount - d.paid_amount END AS balance_due
FROM (
    SELECT i.invoice_id, i.order_id, so.customer_id, i.invoice_date, i.total_amount,
           CAST(COALESCE((SELECT SUM(p.amount) FROM payments p WHERE p.invoice_id = i.invoice_id), 0) AS SIGNED) AS paid_amount,
           so.status AS order_status
    FROM invoices i JOIN sales_orders so ON so.order_id = i.order_id
) d;

CREATE OR REPLACE VIEW v_customer_balance AS
SELECT c.customer_id, c.name, c.credit_limit,
       CAST(COALESCE(SUM(v.balance_due), 0) AS SIGNED) AS outstanding
FROM customers c LEFT JOIN v_invoice_balance v ON v.customer_id = c.customer_id
GROUP BY c.customer_id, c.name, c.credit_limit;

CREATE OR REPLACE VIEW v_trial_balance AS
SELECT account,
       CAST(SUM(debit) AS SIGNED) AS total_debit,
       CAST(SUM(credit) AS SIGNED) AS total_credit,
       CAST(SUM(debit) - SUM(credit) AS SIGNED) AS net_debit
FROM ledger_entries GROUP BY account;

CREATE OR REPLACE VIEW v_low_stock AS
SELECT product_id, sku, name, stock_qty, reorder_level
FROM products WHERE active = 1 AND stock_qty <= reorder_level;

-- ------------------------------ TRIGGERS -----------------------------------
-- DROP + CREATE keeps this script safe to run more than once.
-- Errors raised with SIGNAL SQLSTATE '45000' arrive in Java as error code 1644.

-- Single source of truth for stock: applying a movement updates the product balance.
-- If it would make stock negative, products.CHECK(stock_qty >= 0) aborts the whole transaction.
DROP TRIGGER IF EXISTS trg_stock_apply;
CREATE TRIGGER trg_stock_apply AFTER INSERT ON stock_movements FOR EACH ROW
    UPDATE products SET stock_qty = stock_qty + NEW.quantity_change WHERE product_id = NEW.product_id;

-- Audit trails are append-only.
DROP TRIGGER IF EXISTS trg_stock_movements_no_update;
CREATE TRIGGER trg_stock_movements_no_update BEFORE UPDATE ON stock_movements FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stock_movements is append-only';

DROP TRIGGER IF EXISTS trg_stock_movements_no_delete;
CREATE TRIGGER trg_stock_movements_no_delete BEFORE DELETE ON stock_movements FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stock_movements is append-only';

DROP TRIGGER IF EXISTS trg_ledger_no_update;
CREATE TRIGGER trg_ledger_no_update BEFORE UPDATE ON ledger_entries FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ledger_entries is append-only: post a reversing entry instead';

DROP TRIGGER IF EXISTS trg_ledger_no_delete;
CREATE TRIGGER trg_ledger_no_delete BEFORE DELETE ON ledger_entries FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ledger_entries is append-only: post a reversing entry instead';

-- Cannot over-pay an invoice or pay a cancelled one (checked in the database, not only in Java).
DROP TRIGGER IF EXISTS trg_payment_guard;
DELIMITER $$
CREATE TRIGGER trg_payment_guard BEFORE INSERT ON payments FOR EACH ROW
BEGIN
    IF NEW.amount > COALESCE((SELECT balance_due FROM v_invoice_balance WHERE invoice_id = NEW.invoice_id), 0) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Payment exceeds the invoice balance (or invoice is cancelled)';
    END IF;
END$$
DELIMITER ;
