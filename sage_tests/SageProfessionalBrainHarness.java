package com.pineapple.sage;
import java.util.Arrays;
public final class SageProfessionalBrainHarness {
  private static int n;
  private static void ok(boolean v,String m){if(!v)throw new AssertionError(m);n++;}
  public static void main(String[] args){
    SageBrainRequestPolicy.Prompt exact=SageBrainRequestPolicy.build(
      "Reply with exactly: Pineapple.","UNFILTERED tone",
      Arrays.asList("entire action allowlist open tap shell","Pineapple history"),
      Arrays.asList("Pineapple secret memory"),4615,1.10f);
    ok(exact.mode==SageBrainRequestPolicy.PromptMode.EXACT_LITERAL,"exact mode");
    ok(exact.deterministic&&exact.outputTokens<=12,"real deterministic bounded inference");
    ok(!exact.system.contains("UNFILTERED")&&!exact.user.contains("memory")
       &&!exact.user.contains("action"),"exact isolation");
    ok(exact.expectedLiteral.equals("Pineapple"),"literal parsed");
    ok(SageBrainRequestPolicy.literalMatches("Pineapple.","Pineapple"),"literal verified");
    SageBrainRequestPolicy.Prompt concise=SageBrainRequestPolicy.build(
      "What is a pineapple?", "clean", Arrays.asList("x".repeat(5000)),
      Arrays.asList("pineapple is a fruit "+"z".repeat(5000)),10000,1.1f);
    ok(concise.formattedCharacters<=concise.formattedBudget,"total formatted budget");
    ok(concise.user.length()<concise.formattedBudget,"long line bounded");
    ok(!concise.system.contains("open TARGET"),"concise excludes action allowlist");
    SageBrainRequestPolicy.Prompt action=SageBrainRequestPolicy.build(
      "open the radio page","clean",Arrays.asList(),Arrays.asList(),4615,1.1f);
    ok(action.mode==SageBrainRequestPolicy.PromptMode.ACTION_SELECTION
       &&action.system.contains("open TARGET")&&!action.system.contains("tap LABEL"),"relevant capability subset");
    SageBrainRequestPolicy.Request boundary=new SageBrainRequestPolicy.Request(77,1000);
    long id=boundary.requestId;
    ok(boundary.complete(id,77,7),"boundary result wins");
    ok(!boundary.terminate(id,77,SageBrainRequestPolicy.Terminal.TIMED_OUT)
       &&boundary.terminal()==SageBrainRequestPolicy.Terminal.SUCCESS,"late timeout loses");
    SageBrainRequestPolicy.Request timeout=new SageBrainRequestPolicy.Request(78,1000);
    ok(timeout.terminate(timeout.requestId,78,SageBrainRequestPolicy.Terminal.TIMED_OUT),"timeout distinct");
    SageBrainRequestPolicy.Request cancel=new SageBrainRequestPolicy.Request(79,1000);
    ok(cancel.terminate(cancel.requestId,79,SageBrainRequestPolicy.Terminal.CANCELLED)
       &&cancel.terminal()!=timeout.terminal(),"cancel distinct");
    SageBrainRequestPolicy.Request fresh=new SageBrainRequestPolicy.Request(80,2000);
    ok(fresh.generatedTokens()==0&&fresh.terminal()==SageBrainRequestPolicy.Terminal.ACTIVE,"fresh telemetry");
    ok(SageBrainRequestPolicy.outputBudget(SageBrainRequestPolicy.PromptMode.CONCISE_ANSWER,"",20000,1f)<=16,
       "deadline output cap");
    System.out.println("professional Brain harness: "+n+"/15 passed");
  }
}
