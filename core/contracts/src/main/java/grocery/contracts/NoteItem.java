package grocery.contracts;

/** An ingredient without a Food: added to the list as a plain note (spec §4.3.1). */
public record NoteItem(String text, String recipe) {
}
