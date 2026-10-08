package com.pineapple.sage;

public final class SageConfirmationToneHarness {
    private static int tests;
    private static void check(boolean value,String name){tests++;if(!value)throw new AssertionError(name);}
    public static void main(String[] args){
        SageConfirmationLearning learning=new SageConfirmationLearning();
        long now=1_000L;
        SageConfirmationLearning.Pending p=learning.begin(1,10,"open you tube","open you tube","[open youtube]",.31f,"open_app","app=youtube","subject=video","LOW",now);
        check(SageConfirmationLearning.question(p.raw).equals("Did you say ‘open you tube’?"),"one exact question");
        check(learning.pending(now)!=null,"partials do not mutate confirmation");
        SageConfirmationLearning.Answer yes=learning.answer("Yes",2,11,now+1);
        check(yes.execute&&yes.save,"yes executes and saves");
        check(!learning.answer("Yes",2,11,now+2).execute,"duplicate yes blocked");
        learning.begin(3,12,"open tunes","open tunes","",.2f,"open_app","","subject=music","LOW",now+3);
        SageConfirmationLearning.Answer no=learning.answer("No",4,13,now+4);
        check(no.retry&&!no.execute&&no.response.equals("Okay—say it again."),"no retry exact");
        SageConfirmationLearning.Correction correction=learning.learn("open tunes","open youtube","open_app","subject=music","LOW",.98f,now+5);
        check(correction!=null&&correction.relationship.equals("rejected_to_corrected"),"corrected relationship");
        check(learning.apply("open tunes","subject=music","LOW").equals("open youtube"),"learned correction reused");
        learning.begin(5,14,"play","play","",.1f,"media","","","LOW",now);
        check(!learning.answer("Yes",6,15,now+SageConfirmationLearning.TTL_MS+1).execute,"expired blocked");
        check(!SageConfirmationLearning.learnableRisk("authentication permission destructive installer authority Red Queen"),"protected learning denied");
        SageTonePolicy.Tone tone=SageTonePolicy.command(SageTonePolicy.Tone.CLEAN,"Sage, you can cuss around me.");
        check(tone==SageTonePolicy.Tone.UNFILTERED&&SageTonePolicy.command(tone,"tone it down")==SageTonePolicy.Tone.CASUAL&&SageTonePolicy.command(tone,"stop cussing")==SageTonePolicy.Tone.CLEAN,"tone commands persist values");
        check(!SageTonePolicy.brainInstruction(SageTonePolicy.Tone.CLEAN).equals(SageTonePolicy.brainInstruction(SageTonePolicy.Tone.UNFILTERED))&&SageTonePolicy.format(SageTonePolicy.Tone.CASUAL,"Done.",SageTonePolicy.MessageClass.CONVERSATION).contains("got you"),"tone affects response and prompt");
        String exact="Certificate SHA-256: 99e0";
        check(SageTonePolicy.format(SageTonePolicy.Tone.UNFILTERED,exact,SageTonePolicy.MessageClass.SECURITY).equals(exact)&&SageTonePolicy.format(SageTonePolicy.Tone.UNFILTERED,exact,SageTonePolicy.MessageClass.DIAGNOSTIC).equals(exact)&&SageTonePolicy.format(SageTonePolicy.Tone.UNFILTERED,exact,SageTonePolicy.MessageClass.VERIFICATION).equals(exact),"protected text exact");
        System.out.println("confirmation-tone harness: "+tests+"/12 passed");
    }
}
