package grocery.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.SerializationFeature;
import grocery.contracts.Json;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persists interactions so the workflow survives restarts and button presses can be checked
 * against the current state. SQLite in the POC; the {@code chat_interaction} table in PostgreSQL
 * once the app exists. Updates use an optimistic version check, so two members pressing at the
 * same moment can never both win.
 */
public final class InteractionStore implements AutoCloseable {

    private final Connection db;

    public InteractionStore(String jdbcUrl) {
        try {
            db = DriverManager.getConnection(jdbcUrl);
            try (Statement s = db.createStatement()) {
                s.execute("""
                        CREATE TABLE IF NOT EXISTS chat_interaction (
                            id          INTEGER PRIMARY KEY AUTOINCREMENT,
                            run_id      TEXT NOT NULL,
                            state       TEXT NOT NULL,
                            message     TEXT NOT NULL,
                            selected    TEXT NOT NULL,
                            status      TEXT NOT NULL,
                            message_id  TEXT,
                            answered_by TEXT,
                            outcome     TEXT,
                            version     INTEGER NOT NULL,
                            created_at  TEXT NOT NULL)""");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot open " + jdbcUrl, e);
        }
    }

    public synchronized Interaction create(String runId, String state, Message message, Set<String> selected) {
        Instant now = Instant.now();
        try (PreparedStatement s = db.prepareStatement(
                "INSERT INTO chat_interaction (run_id, state, message, selected, status, version, created_at) VALUES (?, ?, ?, ?, ?, 0, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, runId);
            s.setString(2, state);
            s.setString(3, write(message));
            s.setString(4, write(selected));
            s.setString(5, Interaction.Status.OPEN.name());
            s.setString(6, now.toString());
            s.executeUpdate();
            try (ResultSet keys = s.getGeneratedKeys()) {
                keys.next();
                return new Interaction(keys.getLong(1), runId, state, message, selected, Interaction.Status.OPEN, null, null,
                        null, 0, now);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized Optional<Interaction> find(long id) {
        try (PreparedStatement s = db.prepareStatement("SELECT * FROM chat_interaction WHERE id = ?")) {
            s.setLong(1, id);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(read(r)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized List<Interaction> findOpen() {
        List<Interaction> open = new ArrayList<>();
        try (PreparedStatement s = db.prepareStatement("SELECT * FROM chat_interaction WHERE status = 'OPEN' ORDER BY id")) {
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    open.add(read(r));
                }
            }
            return open;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Records the channel's message reference; not a state change, so the version stays. */
    public synchronized void attachMessageId(long id, String messageId) {
        try (PreparedStatement s = db.prepareStatement("UPDATE chat_interaction SET message_id = ? WHERE id = ?")) {
            s.setString(1, messageId);
            s.setLong(2, id);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Saves {@code updated} only if nobody changed the row since {@code updated.version()} was read.
     *
     * @return the saved interaction with its new version, or empty when someone else was first
     */
    public synchronized Optional<Interaction> update(Interaction updated) {
        try (PreparedStatement s = db.prepareStatement("""
                UPDATE chat_interaction SET selected = ?, status = ?, answered_by = ?, outcome = ?, version = version + 1
                WHERE id = ? AND version = ?""")) {
            s.setString(1, write(updated.selected()));
            s.setString(2, updated.status().name());
            s.setString(3, updated.answeredBy());
            s.setString(4, updated.outcome());
            s.setLong(5, updated.id());
            s.setLong(6, updated.version());
            if (s.executeUpdate() != 1) {
                return Optional.empty();
            }
            return find(updated.id());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        db.close();
    }

    private static Interaction read(ResultSet r) throws SQLException {
        try {
            return new Interaction(
                    r.getLong("id"),
                    r.getString("run_id"),
                    r.getString("state"),
                    Json.MAPPER.readValue(r.getString("message"), Message.class),
                    Json.MAPPER.readValue(r.getString("selected"), new TypeReference<LinkedHashSet<String>>() {
                    }),
                    Interaction.Status.valueOf(r.getString("status")),
                    r.getString("message_id"),
                    r.getString("answered_by"),
                    r.getString("outcome"),
                    r.getLong("version"),
                    Instant.parse(r.getString("created_at")));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String write(Object value) {
        try {
            return Json.MAPPER.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
