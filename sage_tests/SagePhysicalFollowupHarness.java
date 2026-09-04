package com.pineapple.sage;

import java.util.Arrays;
import java.util.Collections;

public final class SagePhysicalFollowupHarness {
    private static int checks;

    private static void require(boolean passed, String label) {
        if (!passed) throw new AssertionError(label);
        checks++;
    }

    public static void main(String[] args) {
        SageWakeFragmentTracker tracker = new SageWakeFragmentTracker();
        require(tracker.resolve("okay", true, 1000L).isEmpty(), "prefix held");
        require(tracker.isHolding(), "holding visible");
        require(tracker.resolve("age", true, 2200L).equals("okay age"), "fragment combined");
        require(!tracker.isHolding(), "combination clears state");
        require(tracker.resolve("save", true, 2300L).equals("save"), "standalone alias not promoted");
        require(tracker.resolve("hey", true, 3000L).isEmpty(), "second prefix held");
        require(tracker.resolve("page", true, 6001L).equals("page"), "expired fragment rejected");

        require(SageConversationRepairPolicy.isCapabilityQuestion(
                "What all capabilities do you have?"), "capability wording");
        require(SageConversationRepairPolicy.isCapabilityQuestion(
                "Trying to hold a conversation with you. What all can you do?"), "compound wording");
        require(!SageConversationRepairPolicy.isCapabilityQuestion(
                "What do you think about this?"), "ordinary question unaffected");

        SageBrainRequestPolicy.Prompt continuation = SageBrainRequestPolicy.build(
                "finish your thought", "normal",
                Arrays.asList("User: Tell me about the repair.",
                        "Sage: The repair includes three important"),
                Collections.emptyList(), 30000L, 2.0f);
        require(continuation.mode == SageBrainRequestPolicy.PromptMode.CONVERSATIONAL,
                "continuation mode");
        require(continuation.user.contains("The repair includes three important"),
                "latest exchange preserved");
        require(continuation.system.contains("Never stop mid-sentence"),
                "complete sentence instruction");
        require(continuation.outputTokens > 16 && continuation.outputTokens <= 48,
                "expanded bounded output");

        System.out.println("Sage 1.33.4 physical follow-up harness: " + checks + "/14 passed");
    }
}
