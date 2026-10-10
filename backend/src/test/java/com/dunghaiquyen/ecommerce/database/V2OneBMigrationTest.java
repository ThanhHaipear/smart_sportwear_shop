package com.dunghaiquyen.ecommerce.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Contract tests for the isolated V2 baseline through batch 1B. */
class V2OneBMigrationTest {

    private static final String URL = System.getProperty("v2.1a.test.url", "");
    private static final String USERNAME = System.getProperty("v2.1a.test.username", "postgres");
    private static final String PASSWORD = System.getProperty("v2.1a.test.password", "");

    @BeforeAll
    static void migrateFreshDatabase() throws Exception {
        assertSafeLocalTestDatabase(URL);
        Flyway flyway = Flyway.configure()
                .dataSource(URL, USERNAME, PASSWORD)
                .locations("classpath:db/v2-migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        assertEquals(3, flyway.migrate().migrationsExecuted);
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        seedCommerceFixture();
    }

    @Test
    void createsAllOneBTables() throws Exception {
        Set<String> expected = Set.of(
                "carts", "cart_items", "orders", "order_items", "order_status_history",
                "payments", "payment_events", "returns", "return_items", "refunds",
                "inventory_transactions", "site_settings", "audit_logs", "integration_outbox",
                "user_interactions");
        try (Connection connection = open()) {
            for (String table : expected) {
                assertTrue(tableExists(connection, table), "Thiếu bảng " + table);
            }
        }
    }

    @Test
    void entityMappingsValidateAgainstSchemaThroughOneB() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(URL, USERNAME, PASSWORD);
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPackagesToScan(
                "com.dunghaiquyen.ecommerce.modules.user.entity",
                "com.dunghaiquyen.ecommerce.modules.auth.entity",
                "com.dunghaiquyen.ecommerce.modules.address.entity",
                "com.dunghaiquyen.ecommerce.modules.brand.entity",
                "com.dunghaiquyen.ecommerce.modules.category.entity",
                "com.dunghaiquyen.ecommerce.modules.catalog.entity",
                "com.dunghaiquyen.ecommerce.modules.product.entity",
                "com.dunghaiquyen.ecommerce.modules.cart.entity",
                "com.dunghaiquyen.ecommerce.modules.order.entity",
                "com.dunghaiquyen.ecommerce.modules.payment.entity",
                "com.dunghaiquyen.ecommerce.modules.returns.entity",
                "com.dunghaiquyen.ecommerce.modules.inventory.entity",
                "com.dunghaiquyen.ecommerce.modules.setting.entity",
                "com.dunghaiquyen.ecommerce.modules.audit.entity",
                "com.dunghaiquyen.ecommerce.modules.interaction.entity");
        factory.setJpaPropertyMap(java.util.Map.of(
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.show_sql", "false"));
        try {
            factory.afterPropertiesSet();
        } finally {
            factory.destroy();
        }
    }

    @Test
    void enforcesCartOrderAndPaymentRules() throws Exception {
        assertSqlState("23514", connection -> execute(connection, """
                insert into carts (user_id, session_id)
                values ('41000000-0000-0000-0000-000000000001', 'both-not-allowed')
                """));

        assertSqlState("23514", connection -> execute(connection, """
                insert into orders
                    (order_code, user_id, address_snapshot_json, subtotal_amount, shipping_fee,
                     discount_amount, total_amount, payment_method, order_status, payment_status, data_source)
                values ('BAD-TOTAL', '41000000-0000-0000-0000-000000000001', '{}'::jsonb,
                        100, 0, 0, 99, 'COD', 'PENDING_CONFIRMATION', 'UNPAID', 'DEMO')
                """));

        assertSqlState("23505", connection -> execute(connection, """
                insert into payments (order_id, provider, transaction_ref, amount, status, expires_at)
                values ('43000000-0000-0000-0000-000000000001', 'VNPAY', 'SECOND-PENDING',
                        100000, 'PENDING', now() + interval '15 minutes')
                """));

        assertSqlState("55000", connection -> execute(connection, """
                update order_status_history set reason = 'mutated'
                 where order_id = '43000000-0000-0000-0000-000000000001'
                """));
    }

    @Test
    void enforcesInventoryLedgerDeltaIdempotencyAndAppendOnly() throws Exception {
        assertSqlState("23514", connection -> execute(connection, """
                insert into inventory_transactions
                    (variant_id, order_id, type, quantity,
                     before_stock_quantity, after_stock_quantity,
                     before_reserved_quantity, after_reserved_quantity,
                     idempotency_key, reason)
                values ('42000000-0000-0000-0000-000000000001',
                        '43000000-0000-0000-0000-000000000001',
                        'ORDER_RESERVE', 2, 10, 10, 0, 1, 'BAD-DELTA', 'invalid')
                """));

        inTransaction(connection -> execute(connection, """
                insert into inventory_transactions
                    (variant_id, order_id, type, quantity,
                     before_stock_quantity, after_stock_quantity,
                     before_reserved_quantity, after_reserved_quantity,
                     idempotency_key, reason)
                values ('42000000-0000-0000-0000-000000000001',
                        '43000000-0000-0000-0000-000000000001',
                        'ORDER_RESERVE', 2, 10, 10, 0, 2, 'ORDER_RESERVE:fixture', 'fixture')
                """));

        assertSqlState("23505", connection -> execute(connection, """
                insert into inventory_transactions
                    (variant_id, order_id, type, quantity,
                     before_stock_quantity, after_stock_quantity,
                     before_reserved_quantity, after_reserved_quantity,
                     idempotency_key, reason)
                values ('42000000-0000-0000-0000-000000000001',
                        '43000000-0000-0000-0000-000000000001',
                        'ORDER_RESERVE', 2, 10, 10, 0, 2, 'ORDER_RESERVE:fixture', 'retry')
                """));

        assertSqlState("55000", connection -> execute(connection, """
                delete from inventory_transactions where idempotency_key = 'ORDER_RESERVE:fixture'
                """));
    }

    @Test
    void enforcesReturnRefundOutboxAndInteractionRules() throws Exception {
        assertSqlState("23505", connection -> execute(connection, """
                insert into refunds
                    (order_id, payment_id, refund_code, amount, provider, status)
                values ('43000000-0000-0000-0000-000000000001',
                        '44000000-0000-0000-0000-000000000001',
                        'RFD-DUPLICATE', 100000, 'VNPAY', 'PENDING')
                """));

        assertSqlState("23505", connection -> execute(connection, """
                insert into integration_outbox
                    (idempotency_key, event_type, event_version, aggregate_type,
                     aggregate_id, aggregate_version, payload)
                values ('PRODUCT:fixture:1', 'PRODUCT_UPDATED', 1, 'PRODUCT',
                        '40000000-0000-0000-0000-000000000001', 1, '{}'::jsonb)
                """));

        assertSqlState("23514", connection -> execute(connection, """
                insert into user_interactions
                    (event_type, source, data_source)
                values ('VIEW', 'STOREFRONT', 'REAL')
                """));
    }

    private static void seedCommerceFixture() throws Exception {
        inTransaction(connection -> execute(connection, """
                insert into users (id, email, password_hash, full_name)
                values ('41000000-0000-0000-0000-000000000001',
                        'commerce-1b@example.com', '$2a$10$fixture', 'Commerce User');

                insert into products
                    (id, category_id, brand_id, product_type_id, size_system_id, name, slug,
                     gender_code, status, data_source)
                select '40000000-0000-0000-0000-000000000001', c.id, b.id, pt.id, ss.id,
                       'Commerce fixture', 'commerce-fixture', 'UNISEX', 'DRAFT', 'DEMO'
                  from categories c, brands b, product_types pt, size_systems ss
                 where c.code = 'SPORTS_TOPS' and b.code = 'NO_BRAND'
                   and pt.code = 'SPORTS_TOP' and ss.code = 'APPAREL_INT';

                insert into product_variants
                    (id, product_id, sku, size_id, color_id, price, stock_quantity)
                select '42000000-0000-0000-0000-000000000001',
                       '40000000-0000-0000-0000-000000000001', 'COMMERCE-1B-M-BLACK',
                       s.id, c.id, 100000, 10
                  from sizes s join size_systems ss on ss.id = s.size_system_id
                  cross join colors c
                 where ss.code = 'APPAREL_INT' and s.code = 'M' and c.code = 'BLACK';

                insert into orders
                    (id, order_code, user_id, address_snapshot_json, subtotal_amount, shipping_fee,
                     discount_amount, total_amount, payment_method, order_status, payment_status, data_source)
                values ('43000000-0000-0000-0000-000000000001', 'ORDER-1B-FIXTURE',
                        '41000000-0000-0000-0000-000000000001',
                        '{"receiverName":"Commerce User"}'::jsonb,
                        100000, 0, 0, 100000, 'VNPAY', 'PENDING_CONFIRMATION', 'PENDING', 'DEMO');

                insert into order_items
                    (id, order_id, product_id, variant_id, product_name_snapshot, sku_snapshot,
                     size_snapshot, color_snapshot, unit_price_snapshot, quantity, line_total,
                     discount_allocated, net_line_amount)
                values ('43100000-0000-0000-0000-000000000001',
                        '43000000-0000-0000-0000-000000000001',
                        '40000000-0000-0000-0000-000000000001',
                        '42000000-0000-0000-0000-000000000001',
                        'Commerce fixture', 'COMMERCE-1B-M-BLACK', 'M', 'Black',
                        100000, 1, 100000, 0, 100000);

                insert into order_status_history (order_id, to_status, actor_user_id)
                values ('43000000-0000-0000-0000-000000000001',
                        'PENDING_CONFIRMATION', '41000000-0000-0000-0000-000000000001');

                insert into payments
                    (id, order_id, provider, transaction_ref, amount, status, expires_at)
                values ('44000000-0000-0000-0000-000000000001',
                        '43000000-0000-0000-0000-000000000001',
                        'VNPAY', 'FIXTURE-PENDING', 100000, 'PENDING', now() + interval '15 minutes');

                insert into refunds
                    (order_id, payment_id, refund_code, amount, provider, status)
                values ('43000000-0000-0000-0000-000000000001',
                        '44000000-0000-0000-0000-000000000001',
                        'RFD-FIXTURE', 100000, 'VNPAY', 'PENDING');

                insert into integration_outbox
                    (idempotency_key, event_type, event_version, aggregate_type,
                     aggregate_id, aggregate_version, payload)
                values ('PRODUCT:fixture:1', 'PRODUCT_UPDATED', 1, 'PRODUCT',
                        '40000000-0000-0000-0000-000000000001', 1, '{}'::jsonb)
                """));
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(URL, USERNAME, PASSWORD);
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement("""
                select exists (
                    select 1 from information_schema.tables
                     where table_schema = 'public' and table_name = ?
                )
                """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void assertSqlState(String expected, SqlWork work) throws Exception {
        SQLException exception = assertThrows(SQLException.class, () -> inTransaction(work));
        SQLException cursor = exception;
        while (cursor != null && !expected.equals(cursor.getSQLState())) {
            cursor = cursor.getNextException();
        }
        assertFalse(cursor == null,
                () -> "Mong đợi SQLSTATE " + expected + " nhưng nhận " + exception.getSQLState());
    }

    private static void inTransaction(SqlWork work) throws Exception {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                work.run(connection);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void assertSafeLocalTestDatabase(String jdbcUrl) throws Exception {
        assertFalse(jdbcUrl.isBlank(), "Phải truyền URL database test local");
        assertTrue(jdbcUrl.startsWith("jdbc:postgresql://"));
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        assertTrue("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
        assertTrue(uri.getPath().replaceFirst("^/", "").startsWith("smart_sportswear_v2_1a_"));
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws Exception;
    }
}
