# AI Support Statement and Prompt Log

**Student:** Francis Xavier Kakeeto   **Course / Midterm:** Generative AI-Assisted ERP System Design
**Project:** Wholesale ERP prototype (general goods wholesaler), Java + MySQL/MariaDB

## 1. AI tools used
- Claude (Anthropic), via claude.ai: project planning, technology choice, database schema design, Java code generation,
  test scripting, documentation drafts.
- Other tools / web searches: **[YOU: list any, e.g. MySQL/MariaDB documentation, XAMPP, JDBC tutorials, or "none"]**

## 2. What AI was used for
| Area | How AI was used |
|---|---|
| Task understanding | Summarised the midterm instructions into required deliverables |
| Technology choice | Compared Java and C for a SQL-backed ERP; Java with JDBC recommended (SQLite first, later changed to MySQL/MariaDB at my request) |
| Database design | Proposed the tables, constraints, triggers and views in `sql/schema.sql` |
| Code | Generated the Java classes in `src/erp` (database helpers, master data, sales, inventory, finance, CLI) |
| Testing | Generated `SelfTest.java` and ran scripted CLI sessions |
| Documentation | Drafted README and architecture document |
| NOT used for | The final reflection paper (written by me alone, as the course requires) **[YOU: confirm]** |

## 3. Prompt log
| # | Prompt (my words) | Purpose | Result / what I did with it |
|---|---|---|---|
| 1 | "What am I required to do with those instructions?" (attached midterm PDF) | Understand the assignment | Summary of deliverables and grading criteria |
| 2 | "I will choose a wholesale for the organisation. Can we use java or C? with Sql?" | Choose language and database | Recommendation: Java 17 + SQLite, CLI, Sales + Inventory + Finance (database later replaced, see prompt 7) |
| 3 | "Let's use java then. And the business should be a general goods wholesaler" | Start the build | Full project generated: schema, Java code, self-test, docs |
| 4 | "How do I test run it?" | Run the project | Step-by-step build and run instructions |
| 5 | "Give me an er diagram of my system" | Documentation | `docs/er_diagram.png/.svg` (generated from the schema with Graphviz) |
| 6 | "I have failed to test and run this. What could be the problem?" | Debug my setup | Windows scripts `build.bat` / `run.bat` added; asked where it failed |
| 7 | "I would like to change the Sqlite file to sql. And make the necessary adjustments with the rest of my design for compatibility" (chose MySQL/MariaDB when asked) | Replace the database | Schema rewritten for MySQL/MariaDB, Java database layer changed, tested on a real MariaDB server |** | | |

Internet searches (if any): **[ How do I run .sql files, www.google.com, had an sqlite file instead of sql. could not run in my xampp]**

## 4. Critical evaluation: errors, limits and debugging
Issues observed while the code was being built and tested (verify and extend with your own experience):

1. **Dependency download blocked.** The first attempt to fetch the SQLite JDBC driver from Maven Central failed (HTTP 403);
   the driver was obtained from the project's GitHub release instead. The same happened for the MariaDB driver, which was
   installed from the operating system's package repository instead. Lesson: generated build steps assume network access that may not exist.
2. **Infinite loop on end of input (AI logic error).** The first version of the console helper treated end-of-input as the
   answer "0". In the order-entry loop ("enter product ID, blank to finish") that value was never blank, so the program
   looped forever when input ran out. Found by running a scripted session; fixed so end of input exits cleanly.
3. **Wrong on-screen instruction (AI inconsistency).** The empty-database hint pointed to menu "9) Admin" although Admin is menu 7.
   Found by comparing the text with the actual menu; corrected.
4. **Enterprise logic had to be specified deliberately.** The AI's simplest design would store a stock number and edit it
   directly. Real ERP logic needs: stock changes only through movement records, ledger entries that cannot be edited,
   price and cost frozen on the order line, credit limits, cancellation by reversal, and weighted-average costing.
   **[YOU: add what you noticed or changed. Did you question any design choice? Did the AI miss a business rule you know from
   how a real wholesaler works, e.g. VAT, discounts, returns, supplier payment terms?]**
5. **The SQLite-to-MySQL migration showed how much of the first design was tied to one database.** Found while porting (not
   by a failing run) and then confirmed by the 28 passing checks on MariaDB: string joining with `||` means OR in MySQL (now
   `CONCAT`); SQLite accepts `INTEGER PRIMARY KEY AUTOINCREMENT`, any text as a date and unlimited text, while MySQL needs
   `AUTO_INCREMENT`, real `DATETIME` values and `VARCHAR` lengths; inline `REFERENCES` is silently ignored by MySQL, so foreign
   keys had to be written as explicit `FOREIGN KEY` clauses; triggers need `SIGNAL` instead of `RAISE`, `DELIMITER` for bodies with
   several statements, and `DROP TRIGGER IF EXISTS` to be re-runnable; `SUM()` returns a decimal, so the views cast it.
   The most important lesson: my error handling recognised database rules by searching the error message text (for example
   "UNIQUE"), which only worked by luck on SQLite. MySQL and MariaDB word these messages differently, so it now uses

How the output was verified: `./run.sh --selftest` (28 checks, all passing on MariaDB 10.11) and the integrity check in the Admin menu;
manual runs of every menu **[YOU: confirm what you actually ran]**.

## 5. Declaration
I used generative AI as described above. I understand the design and code I am submitting and can explain it.
The reflection paper is my own writing.

Signature / date: 11/0/2026
