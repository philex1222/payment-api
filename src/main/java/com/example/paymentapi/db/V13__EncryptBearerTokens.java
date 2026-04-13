package com.example.paymentapi.db;

import com.example.paymentapi.config.AesGcmAttributeConverter;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encrypts all existing plaintext bearer_token values in webhook_subscriptions.
 *
 * <p>Spring Boot's Flyway auto-configuration picks up {@code @Component}-annotated
 * {@code JavaMigration} beans and injects Spring dependencies automatically.
 * This migration is a no-op in environments where no rows exist (e.g. fresh installs).</p>
 *
 * <p>Flyway is disabled in tests ({@code spring.flyway.enabled=false}) —
 * H2 uses {@code ddl-auto=create-drop} and starts with no data.</p>
 */
@Component
public class V13__EncryptBearerTokens extends BaseJavaMigration {

    @Autowired
    private AesGcmAttributeConverter converter;

    @Override
    public void migrate(Context context) throws Exception {
        // Read all existing rows first to avoid cursor conflicts on the same connection
        Map<String, String> rows = new LinkedHashMap<>();
        try (var stmt = context.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, bearer_token FROM webhook_subscriptions")) {
            while (rs.next()) {
                rows.put(rs.getString("id"), rs.getString("bearer_token"));
            }
        }

        // Encrypt each plaintext token and write it back
        for (Map.Entry<String, String> entry : rows.entrySet()) {
            if (entry.getValue() == null) continue;
            String encrypted = converter.convertToDatabaseColumn(entry.getValue());
            try (var ps = context.getConnection().prepareStatement(
                    "UPDATE webhook_subscriptions SET bearer_token = ? WHERE id = ?")) {
                ps.setString(1, encrypted);
                ps.setString(2, entry.getKey());
                ps.executeUpdate();
            }
        }
    }
}
