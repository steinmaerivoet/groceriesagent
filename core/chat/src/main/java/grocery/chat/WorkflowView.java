package grocery.chat;

/** Lets the chat module tell whether an interaction still belongs to the current workflow state. */
@FunctionalInterface
public interface WorkflowView {

    boolean isCurrent(String runId, String state);
}
