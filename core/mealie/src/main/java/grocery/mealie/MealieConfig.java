package grocery.mealie;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Where Mealie runs and how to authenticate. Read from the environment, falling back to the
 * repository's {@code .env} (written by {@code make setup}).
 */
public record MealieConfig(URI baseUrl, String apiToken) {

    public static MealieConfig fromEnvironment() {
        Map<String, String> values = new HashMap<>(readDotEnv());
        System.getenv().forEach((k, v) -> {
            if (!v.isBlank()) {
                values.put(k, v);
            }
        });
        String token = values.get("MEALIE_API_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("MEALIE_API_TOKEN is not set; run `make setup` first");
        }
        String url = values.getOrDefault("MEALIE_URL",
                "http://localhost:" + values.getOrDefault("MEALIE_PORT", "9925"));
        return new MealieConfig(URI.create(url), token);
    }

    /** Looks for {@code .env} in the working directory and its parents. */
    private static Map<String, String> readDotEnv() {
        Map<String, String> values = new HashMap<>();
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path env = dir.resolve(".env");
            if (Files.isRegularFile(env)) {
                try {
                    for (String line : Files.readAllLines(env)) {
                        int eq = line.indexOf('=');
                        if (!line.startsWith("#") && eq > 0) {
                            values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                break;
            }
        }
        return values;
    }
}
