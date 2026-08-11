package com.pineapple.sage;

public final class SageOwnerConversationFinishHarness {
    private static int tests;
    private static void check(boolean value, String name) {
        tests++;
        if (!value) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        SageConversationStateMachine machine = new SageConversationStateMachine();
        machine.beginCommand(1L, 1000L, true, false);
        SageConversationStateMachine.Decision held = machine.finalTranscript(
                "[open you tube]", "open you tube", .20f, 1L, 1100L,
                3000L, "", 0L, 3000L, false, true);
        check(!held.executable, "uncertain command is not executed");
        check("confirmation_required".equals(held.rejectionReason), "one confirmation route");
        check(machine.dispatchCount() == 0, "confirmation does not count as dispatch");
        check(machine.state() == SageConversationStateMachine.State.AWAITING_CONFIRMATION,
                "explicit confirmation state");
        SageConversationStateMachine.Decision duplicate = machine.finalTranscript(
                "[open you tube]", "open you tube", .20f, 1L, 1200L,
                3000L, "", 0L, 3000L, false, true);
        check(!duplicate.executable, "duplicate uncertain callback blocked");

        machine.beginCommand(2L, 2000L, true, false);
        SageConversationStateMachine.Decision clear = machine.finalTranscript(
                "[open youtube]", "open youtube", .98f, 2L, 2100L,
                3000L, "", 0L, 3000L, false, false);
        check(clear.executable, "clear command executes");
        check(machine.dispatchCount() == 1, "exactly one dispatch");
        check(clear.dispatchCount == 1, "diagnostic dispatch count exact");
        System.out.println("owner conversation finish harness: " + tests + "/8 passed");
    }
}
