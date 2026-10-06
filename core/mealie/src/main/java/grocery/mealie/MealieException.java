package grocery.mealie;

/** A Mealie call failed. {@code status} is the HTTP status, or -1 when Mealie was unreachable. */
public class MealieException extends RuntimeException {

    private final int status;

    public MealieException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
