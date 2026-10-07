package grocery.agent;

import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Stands in for Amazon Bedrock's Converse API: answers each request with the next scripted
 * response and keeps the requests, so tests run the real Spring AI tool loop without AWS.
 */
class ScriptedBedrock implements BedrockRuntimeClient {

    final List<ConverseRequest> requests = new ArrayList<>();
    private final Deque<ConverseResponse> responses = new ArrayDeque<>();

    ScriptedBedrock thenToolUse(String tool, Document input) {
        ContentBlock use = ContentBlock.fromToolUse(ToolUseBlock.builder()
                .toolUseId("tool-" + (responses.size() + 1)).name(tool).input(input).build());
        responses.add(response(StopReason.TOOL_USE, use));
        return this;
    }

    ScriptedBedrock thenText(String text) {
        responses.add(response(StopReason.END_TURN, ContentBlock.fromText(text)));
        return this;
    }

    @Override
    public ConverseResponse converse(ConverseRequest request) {
        requests.add(request);
        if (responses.isEmpty()) {
            throw new IllegalStateException("No scripted response left for request " + requests.size());
        }
        return responses.removeFirst();
    }

    @Override
    public String serviceName() {
        return "bedrock-runtime";
    }

    @Override
    public void close() {
    }

    private static ConverseResponse response(StopReason stop, ContentBlock block) {
        return ConverseResponse.builder()
                .output(o -> o.message(Message.builder().role(ConversationRole.ASSISTANT).content(block).build()))
                .stopReason(stop)
                .usage(u -> u.inputTokens(100).outputTokens(20).totalTokens(120))
                .metrics(m -> m.latencyMs(1L))
                .build();
    }
}
