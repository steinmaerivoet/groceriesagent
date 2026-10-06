package grocery.chat;

import java.util.Set;

/**
 * Spec §5.2: the bot belongs to one household group and only allowlisted members may press
 * buttons or make requests.
 */
public record AccessPolicy(long householdChatId, Set<Long> allowedUserIds) {

    public boolean isHouseholdChat(long chatId) {
        return chatId == householdChatId;
    }

    public boolean isAllowed(long userId) {
        return allowedUserIds.contains(userId);
    }
}
