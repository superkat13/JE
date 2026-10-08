package com.pineapple.sage;

import java.util.Arrays;

public final class SageBrainRouteRepairHarness {
    private static int passed;
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;}
    public static void main(String[] args){
        SageBrainRequestPolicy.Lifecycle life=new SageBrainRequestPolicy.Lifecycle(9,1000);
        long id=life.requestId;
        check(life.token(id,9,1460,7),"first token accepted");
        check(life.firstTokenLatencyMs()==460,"first-token latency");
        check(life.finishSuccess(id,9,7),"success accepted");
        check(life.terminal()==SageBrainRequestPolicy.Terminal.SUCCESS,"success immutable");
        check(!life.finish(id,9,SageBrainRequestPolicy.Terminal.CANCELLED),"stale cancellation rejected");
        check(!life.watchdogActive(),"watchdog cancelled");
        check(life.generatedTokens()==7,"tokens cannot return to zero");
        SageBrainRequestPolicy.Lifecycle fresh=new SageBrainRequestPolicy.Lifecycle(10,2000);
        check(fresh.terminal()==SageBrainRequestPolicy.Terminal.ACTIVE&&fresh.generatedTokens()==0,
                "fresh request reset");
        SageBrainRequestPolicy.Prompt simple=SageBrainRequestPolicy.compact(
                "Reply with exactly: Pineapple.","Answer concisely.",
                Arrays.asList("User: unrelated long command policy and diagnostics registry",
                        "Sage: previous weather discussion"),
                Arrays.asList("favorite radio is a Pineapple model","unrelated private note"));
        check(simple.text.length()<=SageBrainRequestPolicy.SIMPLE_CHAR_BUDGET,"simple bounded");
        check(!simple.text.contains("command policy")&&!simple.text.contains("private note"),
                "unrelated data excluded");
        check(simple.text.contains("Pineapple model"),"relevant memory retained");
        SageBrainRequestPolicy.Prompt complex=SageBrainRequestPolicy.compact(
                "Summarize what did I remember about radio wiring", "Be concise.",
                Arrays.asList("User: compare radio wiring options","unrelated cooking"),
                Arrays.asList("radio wiring uses a fused lead","favorite color blue"));
        check(complex.budget==SageBrainRequestPolicy.COMPLEX_CHAR_BUDGET
                &&complex.selectedContext==1&&complex.selectedMemories==1,
                "complex relevance selection");
        System.out.println("brain route repair harness: "+passed+"/12 passed");
    }
}
