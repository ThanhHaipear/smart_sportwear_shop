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

/**
 * Contract test for the isolated V2 1A migrations.
 *
 * <p>The test deliberately refuses non-local databases and database names outside the dedicated
 * test prefix. Example:
 *
 * <pre>
 * ./mvnw -Dtest=V2OneAMigrationTest \
 *   -Dv2.1a.test.url=jdbc:postgresql://127.0.0.1:55432/smart_sportswear_v2_1a_test test
 * </pre>
 */
class V2OneAMigrationTest {

    private static final String URL = System.getProperty("v2.1a.test.url", "");
    private static final String USERNAME = System.getProperty("v2.1a.test.username", "postgres");
    private static final String PASSWORD = System.getProperty("v2.1a.test.password", "");

    @BeforeAll
    static void migrateFreshDatabase() throws Exception {
        assertSafeLocalTestDatabase(URL);

        Flyway flyway = Flyway.configure()
                .dataSource(URL, USERNAME, PASSWORD)
                .locations("classpath:db/v2-migration")
                .schemas("public", "ai_retrieval")
                .defaultSchema("public")
                .cleanDisabled(false)
                .load();

        flyway.clean();
        assertEquals(6, flyway.migrate().migrationsExecuted);
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted, "Migration phải idempotent qua Flyway");
    }

    @Test
    void createsExpectedTablesAndSeedWithoutKids() throws Exception {
        Set<String> expected = Set.of(
                "users", "user_identities", "addresses", "refresh_tokens", "password_reset_tokens",
                "categories", "brands", "size_systems", "sizes", "product_types", "sports",
                "use_cases", "materials", "technologies", "colors", "catalog_datasets", "products",
                "catalog_dataset_items", "product_sports", "product_use_cases", "product_materials",
                "product_technologies", "product_variants", "product_images");

        try (Connection connection = open()) {
            for (String table : expected) {
                assertTrue(tableExists(connection, table), "Thiếu bảng " + table);
            }

            assertEquals(2, scalarInt(connection, "select count(*) from sports"));
            assertEquals(5, scalarInt(connection, "select count(*) from product_types"));
            assertEquals(3, scalarInt(connection, "select count(*) from size_systems"));
            assertEquals(17, scalarInt(connection, "select count(*) from sizes"));
            assertEquals(3, scalarInt(connection, "select count(*) from brands"));
            assertEquals(2, scalarInt(connection,
                    "select count(*) from brands where code in ('NIKE', 'ADIDAS') and status = 'ACTIVE'"));
            assertEquals(0, scalarInt(connection,
                    "select count(*) from products where gender_code = 'KIDS'"));
            assertEquals(0, scalarInt(connection,
                    "select count(*) from categories where code like '%KID%'"));
        }
    }

    @Test
    void entityMappingsValidateAgainstOneASchema() {
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
                "com.dunghaiquyen.ecommerce.modules.product.entity");
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
    void enforcesIdentityAndAddressRules() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into users (email, password_hash, full_name)
                    values ('local-1a@example.com', '$2a$10$localhash', 'Local User')
                    """);
        }

        inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        insert into users (id, email, full_name)
                        values ('10000000-0000-0000-0000-000000000001',
                                'google-1a@example.com', 'Google User')
                        """);
                statement.executeUpdate("""
                        insert into user_identities (user_id, provider, provider_subject, provider_email)
                        values ('10000000-0000-0000-0000-000000000001',
                                'GOOGLE', 'google-subject-1a', 'google-1a@example.com')
                        """);
            }
        });

        assertSqlState("23514", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        insert into users (email, full_name)
                        values ('missing-identity-1a@example.com', 'Invalid External User')
                        """);
            }
        });

        assertSqlState("23505", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        insert into users (email, password_hash, full_name)
                        values ('local-1a@example.com', '$2a$10$duplicate', 'Duplicate User')
                        """);
            }
        });

        assertSqlState("23505", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        insert into addresses
                            (user_id, receiver_name, phone, province_name, ward_name,
                             address_line, is_default)
                        select id, 'Người nhận 1', '0900000001', 'TP Hồ Chí Minh', 'Phường 1',
                               'Số 1 Đường A', true
                          from users where email = 'local-1a@example.com';
                        insert into addresses
                            (user_id, receiver_name, phone, province_name, ward_name,
                             address_line, is_default)
                        select id, 'Người nhận 2', '0900000002', 'TP Hồ Chí Minh', 'Phường 2',
                               'Số 2 Đường B', true
                          from users where email = 'local-1a@example.com'
                        """);
            }
        });
    }

    @Test
    void enforcesTaxonomyCatalogAndPublishingRules() throws Exception {
        assertSqlState("23514", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        update categories
                           set parent_id = (select id from categories where code = 'SPORTS_TOPS')
                         where code = 'APPAREL'
                        """);
            }
        });

        assertSqlState("23514", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        insert into products
                            (category_id, brand_id, product_type_id, size_system_id, name, slug,
                             gender_code, status, data_source)
                        select c.id, b.id, pt.id, ss.id, 'Sản phẩm active lỗi',
                               'san-pham-active-loi', 'MEN', 'ACTIVE', 'DEMO'
                          from categories c, brands b, product_types pt, size_systems ss
                         where c.code = 'SPORTS_TOPS' and b.code = 'NO_BRAND'
                           and pt.code = 'SPORTS_TOP' and ss.code = 'APPAREL_INT'
                        """);
            }
        });

        assertSqlState("23514", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        insert into products
                            (id, category_id, brand_id, product_type_id, size_system_id, name, slug,
                             gender_code, status, data_source)
                        select '20000000-0000-0000-0000-000000000001', c.id, b.id, pt.id, ss.id,
                               'Sản phẩm sai size', 'san-pham-sai-size', 'WOMEN', 'DRAFT', 'DEMO'
                          from categories c, brands b, product_types pt, size_systems ss
                         where c.code = 'SPORTS_TOPS' and b.code = 'NO_BRAND'
                           and pt.code = 'SPORTS_TOP' and ss.code = 'APPAREL_INT';
                        insert into product_variants
                            (product_id, sku, size_id, color_id, price, stock_quantity)
                        select '20000000-0000-0000-0000-000000000001', 'BAD-SIZE-1A', s.id, c.id,
                               100000, 1
                          from sizes s
                          join size_systems ss on ss.id = s.size_system_id
                          cross join colors c
                         where ss.code = 'SHOES_EU' and s.code = '40' and c.code = 'BLACK'
                        """);
            }
        });
    }

    @Test
    void acceptsCompletePublishedProductAndProtectsImageProvenanceAndFrozenDataset() throws Exception {
        inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        insert into products
                            (id, category_id, brand_id, product_type_id, size_system_id, name, slug,
                             gender_code, status, data_source)
                        select '30000000-0000-0000-0000-000000000001', c.id, b.id, pt.id, ss.id,
                               'Áo chạy bộ hợp lệ', 'ao-chay-bo-hop-le', 'UNISEX', 'DRAFT', 'DEMO'
                          from categories c, brands b, product_types pt, size_systems ss
                         where c.code = 'SPORTS_TOPS' and b.code = 'NO_BRAND'
                           and pt.code = 'SPORTS_TOP' and ss.code = 'APPAREL_INT';
                        insert into product_variants
                            (id, product_id, sku, size_id, color_id, price, stock_quantity)
                        select '31000000-0000-0000-0000-000000000001',
                               '30000000-0000-0000-0000-000000000001', 'RUN-TOP-M-BLACK',
                               s.id, c.id, 299000, 10
                          from sizes s
                          join size_systems ss on ss.id = s.size_system_id
                          cross join colors c
                         where ss.code = 'APPAREL_INT' and s.code = 'M' and c.code = 'BLACK';
                        insert into product_images
                            (product_id, variant_id, color_id, public_id, asset_version, secure_url,
                             format, bytes, checksum, width, height,
                             is_primary, source_kind, license_code)
                        select '30000000-0000-0000-0000-000000000001',
                               '31000000-0000-0000-0000-000000000001', c.id,
                               'catalog/1a/run-top-black', '1',
                               'https://res.cloudinary.com/demo/image/upload/run-top-black.jpg',
                               'jpg', 102400, repeat('c', 64), 1200, 1200,
                               true, 'OWNED', 'OWNED_BY_SHOP'
                          from colors c where c.code = 'BLACK';
                        update products set status = 'ACTIVE'
                         where id = '30000000-0000-0000-0000-000000000001'
                        """);
            }
        });

        assertSqlState("23514", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        update products set data_source = 'REAL'
                         where id = '30000000-0000-0000-0000-000000000001'
                        """);
            }
        });

        assertSqlState("23514", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        insert into product_images
                            (product_id, variant_id, color_id, public_id, asset_version, secure_url,
                             format, source_kind, license_code)
                        select '30000000-0000-0000-0000-000000000001',
                               '31000000-0000-0000-0000-000000000001', c.id,
                               'catalog/1a/run-top-wrong-color', '1',
                               'https://res.cloudinary.com/demo/image/upload/run-top-white.jpg',
                               'jpg', 'OWNED', 'OWNED_BY_SHOP'
                          from colors c where c.code = 'WHITE'
                        """);
            }
        });

        inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        insert into catalog_datasets
                            (id, name, version, manifest_uri, manifest_checksum,
                             license_code, status)
                        values ('32000000-0000-0000-0000-000000000001', 'Catalog demo 1A', '1.0',
                                'file:///datasets/catalog-1a.jsonl', repeat('a', 64),
                                'INTERNAL_DEMO', 'DRAFT');
                        insert into catalog_dataset_items
                            (dataset_id, product_id, source_record_id, normalized_checksum)
                        values ('32000000-0000-0000-0000-000000000001',
                                '30000000-0000-0000-0000-000000000001',
                                'product-run-top-1', repeat('b', 64));
                        update catalog_datasets set status = 'VALIDATED'
                         where id = '32000000-0000-0000-0000-000000000001';
                        update catalog_datasets set status = 'FROZEN'
                         where id = '32000000-0000-0000-0000-000000000001'
                        """);
            }
        });

        assertSqlState("23514", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        update catalog_datasets set name = 'Tên không được sửa'
                         where id = '32000000-0000-0000-0000-000000000001'
                        """);
            }
        });

        assertSqlState("23514", connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        delete from catalog_dataset_items
                         where dataset_id = '32000000-0000-0000-0000-000000000001'
                        """);
            }
        });
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

    private static int scalarInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
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
        assertFalse(jdbcUrl.isBlank(), "Phải truyền -Dv2.1a.test.url cho database test local");
        assertTrue(jdbcUrl.startsWith("jdbc:postgresql://"),
                "Test 1A chỉ chấp nhận PostgreSQL JDBC URL");
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        assertTrue("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()),
                "Test 1A chỉ được phép chạy trên PostgreSQL local");
        String database = uri.getPath().replaceFirst("^/", "");
        assertTrue(database.startsWith("smart_sportswear_v2_1a_"),
                "Tên database test phải bắt đầu bằng smart_sportswear_v2_1a_");
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws Exception;
    }
}
