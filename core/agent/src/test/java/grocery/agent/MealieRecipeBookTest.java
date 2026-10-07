package grocery.agent;

import com.sun.net.httpserver.HttpServer;
import grocery.mealie.MealieClient;
import grocery.mealie.MealieConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link MealieRecipeBook} against a tiny stand-in for the Mealie API (the seeded data's shapes). */
class MealieRecipeBookTest {

    private static final String TAGS = """
            {"page":1,"total_pages":1,"items":[
              {"id":"t-fish","name":"Fish","slug":"fish"},
              {"id":"t-quick","name":"Quick","slug":"quick"}]}""";
    private static final String SEARCH = """
            {"page":1,"total_pages":1,"items":[
              {"name":"Oven-baked salmon","slug":"oven-baked-salmon","description":"Roasted salmon.",
               "rating":5,"totalTime":"40 minutes","tags":[{"name":"Fish"},{"name":"Potatoes"}]}]}""";
    private static final String RECIPE = """
            {"name":"Oven-baked salmon","slug":"oven-baked-salmon","description":null,"recipeServings":4,
             "totalTime":"40 minutes","tags":[{"name":"Fish"}],
             "recipeIngredient":[{"display":"4 salmon fillets"},{"display":"","note":"dill"}],
             "recipeInstructions":[{"text":"Roast the potatoes."},{"text":"Add the salmon."}]}""";

    private final List<String> requests = new ArrayList<>();
    private HttpServer server;
    private MealieRecipeBook book;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery() == null ? path
                    : path + "?" + exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            String body = switch (path) {
                case "/api/organizers/tags" -> TAGS;
                case "/api/recipes" -> SEARCH;
                case "/api/recipes/oven-baked-salmon" -> RECIPE;
                case "/api/groups/self" -> "{\"slug\":\"home\"}";
                default -> null;
            };
            byte[] bytes = (body == null ? "{\"detail\":\"not found\"}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        book = new MealieRecipeBook(new MealieClient(new MealieConfig(base, "token")));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void searchesByTagIdAndText() {
        List<RecipeBook.RecipeHit> hits = book.search("salmon", List.of("fish"), 10);

        assertThat(hits).singleElement().satisfies(h -> {
            assertThat(h.slug()).isEqualTo("oven-baked-salmon");
            assertThat(h.tags()).containsExactly("Fish", "Potatoes");
            assertThat(h.rating()).isEqualTo(5);
        });
        assertThat(requests).last().asString()
                .startsWith("/api/recipes?page=1&perPage=10")
                .contains("requireAllTags=true", "search=salmon", "tags=t-fish");
    }

    @Test
    void anUnknownTagMatchesNothingWithoutAskingMealie() {
        assertThat(book.search("", List.of("Dessert"), 10)).isEmpty();
        assertThat(requests).noneMatch(r -> r.startsWith("/api/recipes"));
    }

    @Test
    void readsARecipeWithItsMealieLink() {
        RecipeBook.RecipeDetail recipe = book.recipe("oven-baked-salmon").orElseThrow();

        assertThat(recipe.ingredients()).containsExactly("4 salmon fillets", "dill");
        assertThat(recipe.steps()).containsExactly("Roast the potatoes.", "Add the salmon.");
        assertThat(recipe.servings()).isEqualTo("4");
        assertThat(recipe.description()).isNull();
        assertThat(recipe.url()).endsWith("/g/home/r/oven-baked-salmon");
        assertThat(book.recipe("missing")).isEmpty();
    }
}
