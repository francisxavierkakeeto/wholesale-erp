# Wholesale ERP Prototype (General Goods Wholesaler)

A lightweight ERP built in **Java 17+** on a **single MySQL / MariaDB database**. Three integrated modules share one schema:

| Module | Tables | What it does |
|---|---|---|
| **Sales** | `customers`, `sales_orders`, `sales_order_lines` | Orders, credit-limit control, cancellation |
| **Inventory & Purchasing** | `products`, `categories`, `suppliers`, `purchase_orders`, `purchase_order_lines`, `stock_movements` | Goods receipt, stock levels, adjustments, weighted-average cost |
| **Finance** | `invoices`, `payments`, `ledger_entries` | Invoicing, payments, receivables, double-entry ledger, trial balance, P&L |

**Key integration:** one sales order creates the order lines (Sales), reduces stock (Inventory) and posts the invoice
and ledger entries (Finance) in a single transaction. If anything fails, nothing is saved.

## Requirements
1. **Java 17 or newer** (`java -version`).
2. **A MySQL/MariaDB server.** The easiest way on Windows is **XAMPP** (https://www.apachefriends.org): install it, open the
   XAMPP Control Panel and click **Start** next to **MySQL** (Apache is not needed). The default login is user `root`
   with an empty password, which is what `erp.properties` already contains.
3. Nothing else: the database driver (MariaDB Connector/J, LGPL) is included in `lib/`.

Tested on MariaDB 10.11 (the family XAMPP ships). Needs MariaDB 10.2.1+ or MySQL 8.0.16+, because older versions ignore CHECK constraints.

## Build and run
Mac / Linux:
```bash
./build.sh                 # compile
./run.sh --selftest        # automated checks (expect ALL CHECKS PASSED)
./run.sh                   # start the menu
```
Windows (Command Prompt in this folder):
```
build.bat
run.bat --selftest
run.bat
```
Manual Windows equivalent of `build.bat`:
```
mkdir out
javac -d out -cp lib\mariadb-java-client.jar src\erp\*.java
copy sql\schema.sql out\schema.sql
```

The database `wholesale_erp` and all its tables are **created automatically** on first run.
First use: **7) Admin > 1) Load demo data**, then explore. To start clean, use another database name:
`./run.sh mytest` (or `run.bat mytest`).

To look inside the database while you test, open phpMyAdmin from XAMPP (http://localhost/phpmyadmin) and pick `wholesale_erp`.

## Settings (`erp.properties`)
```
db.host=localhost
db.port=3306
db.user=root
db.password=
db.name=wholesale_erp
```
Change these if your server uses a password or a different port.

## Troubleshooting
| Message | Cause and fix |
|---|---|
| `Connection refused` / `Could not connect` | MySQL is not running. Start it in the XAMPP Control Panel. |
| `Access denied for user` | Wrong `db.user` or `db.password` in `erp.properties`. |
| Port 3306 busy, MySQL will not start | Another MySQL is running. Stop it, or change the port in XAMPP and `db.port`. |
| `'java' is not recognized` | Java is not installed, or the terminal was opened before installing. Install a JDK and reopen the terminal. |
| Error mentioning `CHECK` or `CONSTRAINT` syntax at start-up | MariaDB/MySQL is too old (see Requirements). |

## Automated check
`./run.sh --selftest` runs a scripted scenario with 28 checks on a scratch database called `wholesale_erp_selftest`
(created and dropped automatically; your real data is never touched): cross-module updates, rollback on failure,
database-level constraint tests, cancellation reversal, weighted-average costing, CRUD. Expected last line: `ALL CHECKS PASSED`.

## CRUD coverage
| | Create | Read | Update | Delete |
|---|---|---|---|---|
| Customers, suppliers, products, categories | add | list | edit a field | delete, or deactivate if it has history |
| Sales orders / invoices | new order | list, view | record payment | cancel (reverses stock and ledger; records are never erased) |
| Purchases / stock | receive goods, adjust stock | stock levels, movements, low stock | adjust stock | reversing adjustment |
| Ledger | automatic postings | ledger, trial balance, P&L | not allowed (append-only) | not allowed (post a reversing entry) |

In an ERP, financial and stock records are corrected by reversing entries, not by deleting rows. That is why "delete" on
transactions means "cancel with reversal".

## Files
```
sql/schema.sql             the complete database design (MySQL DDL, views, triggers); also loadable by hand in phpMyAdmin
erp.properties             database connection settings
src/erp/*.java             Db, Master, Sales, Inventory, Finance, Admin, SelfTest, Main
docs/ARCHITECTURE.md       architecture, constraint list, data flows
docs/er_diagram.png/.svg   entity-relationship diagram
docs/AI_SUPPORT_STATEMENT.md   AI Support Statement and prompt log (complete the marked parts)
```
