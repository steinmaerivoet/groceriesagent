package grocery.shoppinglist;

import com.fasterxml.jackson.core.type.TypeReference;
import grocery.contracts.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers what Grocery Core last wrote per managed item. A JSON file in the POC; the
 * {@code managed_list_item} table once the app has a database (spec §3.5).
 */
public final class ManagedItemStore {

    private final Path file;

    public ManagedItemStore(Path file) {
        this.file = file;
    }

    public Map<String, WrittenItem> load() {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            List<WrittenItem> items = Json.MAPPER.readValue(file.toFile(), new TypeReference<>() {
            });
            Map<String, WrittenItem> byId = new LinkedHashMap<>();
            items.forEach(i -> byId.put(i.itemId(), i));
            return byId;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    public void save(Map<String, WrittenItem> items) {
        Json.write(file, List.copyOf(items.values()));
    }
}
