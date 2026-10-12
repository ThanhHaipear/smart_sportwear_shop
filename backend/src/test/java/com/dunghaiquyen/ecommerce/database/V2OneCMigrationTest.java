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

/** Contract tests for the 1C.1-1C.2 pgvector foundation and exact cosine baseline. */
class V2OneCMigrationTest {

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
        assertEquals(0, flyway.migrate().migrationsExecuted);
        seedFixture();
    }

    @Test
    void createsVectorSchemaTablesAndOnlyExactVectorSearch() throws Exception {
        Set<String> expected = Set.of(
                "embedding_models", "product_search_documents", "search_variant_facets",
                "processed_events", "product_text_embeddings", "product_image_embeddings",
                "indexing_jobs", "indexing_job_items");
        try (Connection connection = open()) {
            assertTrue(queryBoolean(connection,
                    "select exists (select 1 from pg_extension where extname='vector')"));
            for (String table : expected) {
                assertTrue(queryBoolean(connection, "select to_regclass('ai_retrieval." + table + "') is not null"),
                        "Missing ai_retrieval." + table);
            }
            assertEquals("extensions.vector(1024)", queryString(connection, """
                    select format_type(a.atttypid, a.atttypmod)
                      from pg_attribute a
                     where a.attrelid='ai_retrieval.product_text_embeddings'::regclass
                       and a.attname='embedding'
                    """));
            assertEquals("extensions.vector(768)", queryString(connection, """
                    select format_type(a.atttypid, a.atttypmod)
                      from pg_attribute a
                     where a.attrelid='ai_retrieval.product_image_embeddings'::regclass
                       and a.attname='embedding'
                    """));
            assertFalse(queryBoolean(connection, """
                    select exists (
                        select 1 from pg_indexes
                         where schemaname='ai_retrieval'
                           and (lower(indexdef) like '%hnsw%' or lower(indexdef) like '%ivfflat%')
                    )
                    """), "Pilot must not create HNSW/IVFFlat indexes");
        }
    }

    @Test
    void enforcesModelVersionEmbeddingAndIdempotencyContracts() throws Exception {
        assertSqlState("23505", """
                insert into ai_retrieval.embedding_models
                    (modality, provider, model_name, model_version, dimensions,
                     preprocessing_version, status, activated_at)
                values ('PRODUCT_TEXT', 'HUGGING_FACE', 'another-text-model', 'commit', 1024,
                        'text-v1', 'ACTIVE', now())
                """);
        assertSqlState("23514", """
                insert into ai_retrieval.product_text_embeddings
                    (product_id, model_id, document_hash, status, last_error)
                values ('62000000-0000-0000-0000-000000000001',
                        '61000000-0000-0000-0000-000000000002', repeat('a', 64),
                        'FAILED', 'wrong modality')
                """);
        assertSqlState("23514", """
                update ai_retrieval.product_text_embeddings
                   set document_hash=repeat('c', 64)
                 where product_id='62000000-0000-0000-0000-000000000001'
                """);
        assertSqlState("55000", """
                update ai_retrieval.embedding_models set model_version='mutated'
                 where id='61000000-0000-0000-0000-000000000001'
                """);
        assertSqlState("23505", """
                insert into ai_retrieval.processed_events
                    (consumer_name, event_id, aggregate_version)
                values ('catalog-indexer', '64000000-0000-0000-0000-000000000001', 1)
                """);
    }

    private static void seedFixture() throws SQLException {
        execute("""
                insert into ai_retrieval.embedding_models
                    (id, modality, provider, model_name, model_version, dimensions,
                     preprocessing_version, status, activated_at)
                values
                    ('61000000-0000-0000-0000-000000000001', 'PRODUCT_TEXT', 'HUGGING_FACE',
                     'BAAI/bge-m3', 'fixture-commit', 1024, 'text-v1', 'ACTIVE', now()),
                    ('61000000-0000-0000-0000-000000000002', 'PRODUCT_IMAGE', 'HUGGING_FACE',
                     'google/siglip2-base-patch16-224', 'fixture-commit', 768, 'image-v1', 'ACTIVE', now());

                insert into ai_retrieval.product_search_documents
                    (product_id, name, document_text, document_hash, source_version)
                values ('62000000-0000-0000-0000-000000000001', 'Running shirt',
                        'running shirt unisex blue', repeat('a', 64), 1);

                insert into ai_retrieval.product_text_embeddings
                    (product_id, model_id, document_hash, embedding, status, embedded_at)
                values ('62000000-0000-0000-0000-000000000001',
                        '61000000-0000-0000-0000-000000000001', repeat('a', 64),
                        array_fill((1.0 / sqrt(1024))::real, array[1024])::extensions.vector,
                        'READY', now());

                insert into ai_retrieval.product_image_embeddings
                    (image_id, product_id, model_id, source_public_id, source_asset_version,
                     image_hash, embedding, status, embedded_at)
                values ('63000000-0000-0000-0000-000000000001',
                        '62000000-0000-0000-0000-000000000001',
                        '61000000-0000-0000-0000-000000000002', 'catalog/running-shirt', '1',
                        repeat('b', 64),
                        array_fill((1.0 / sqrt(768))::real, array[768])::extensions.vector,
                        'READY', now());

                insert into ai_retrieval.processed_events
                    (consumer_name, event_id, aggregate_version)
                values ('catalog-indexer', '64000000-0000-0000-0000-000000000001', 1);

                insert into ai_retrieval.indexing_jobs
                    (id, job_type, status, total_count)
                values ('65000000-0000-0000-0000-000000000001',
                        'PRODUCT_TEXT_BACKFILL', 'PENDING', 1);
                """);
    }

    @Test
    void exactCosineFunctionsReturnTheExpectedProductAndImage() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("""
                    select product_id, distance
                      from ai_retrieval.search_product_text_exact(
                          '61000000-0000-0000-0000-000000000001',
                          array_fill((1.0 / sqrt(1024))::real, array[1024])::extensions.vector,
                          5)
                    """)) {
                assertTrue(rs.next());
                assertEquals("62000000-0000-0000-0000-000000000001", rs.getString("product_id"));
                assertTrue(Math.abs(rs.getDouble("distance")) < 0.000001);
                assertFalse(rs.next());
            }
            try (ResultSet rs = statement.executeQuery("""
                    select image_id, product_id, distance
                      from ai_retrieval.search_product_images_exact(
                          '61000000-0000-0000-0000-000000000002',
                          array_fill((1.0 / sqrt(768))::real, array[768])::extensions.vector,
                          5)
                    """)) {
                assertTrue(rs.next());
                assertEquals("63000000-0000-0000-0000-000000000001", rs.getString("image_id"));
                assertEquals("62000000-0000-0000-0000-000000000001", rs.getString("product_id"));
                assertTrue(Math.abs(rs.getDouble("distance")) < 0.000001);
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void enforcesIndexingJobCountersAndLifecycle() throws Exception {
        assertSqlState("23514", """
                insert into ai_retrieval.indexing_jobs
                    (job_type, status, total_count, success_count, failed_count)
                values ('PRODUCT_TEXT_BACKFILL', 'COMPLETED', 10, 9, 0)
                """);
        assertSqlState("23514", """
                insert into ai_retrieval.indexing_job_items
                    (job_id, entity_type, entity_id, status, last_error)
                values ('65000000-0000-0000-0000-000000000001', 'PRODUCT',
                        '62000000-0000-0000-0000-000000000001', 'FAILED', null)
                """);
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(URL, USERNAME, PASSWORD);
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static boolean queryBoolean(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void assertSqlState(String state, String sql) {
        SQLException exception = assertThrows(SQLException.class, () -> execute(sql));
        assertEquals(state, exception.getSQLState());
    }

    private static void assertSafeLocalTestDatabase(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException("Set -Dv2.1a.test.url to the dedicated local 1C database");
        }
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        String host = uri.getHost();
        String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        if (!("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host))) {
            throw new IllegalStateException("Refusing to clean a non-local database: " + host);
        }
        if (!database.startsWith("smart_sportswear_v2_1c_")) {
            throw new IllegalStateException("Refusing to clean unexpected database: " + database);
        }
    }
}
