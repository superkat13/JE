package com.pineapple.sage;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class SageSurpriseHarness {
    private static int tests;
    private static void check(boolean value,String name){tests++;if(!value)throw new AssertionError(name);}
    private static SageSurprisePolicy.Candidate candidate(String title){return new SageSurprisePolicy.Candidate(title,"https://youtube.test/"+title,"youtube","weird","standard_accessibility_tree",true,true,false,false,false,true,true);}
    public static void main(String[] args){
        check(SageSurprisePolicy.parse("Sage, surprise me.")==SageSurprisePolicy.Command.SURPRISE&&SageSurprisePolicy.parse("Surprise me on YouTube")==SageSurprisePolicy.Command.YOUTUBE&&SageSurprisePolicy.parse("Find me something weird")==SageSurprisePolicy.Command.WEIRD&&SageSurprisePolicy.parse("Cure my boredom")==SageSurprisePolicy.Command.BORED&&SageSurprisePolicy.parse("I am bored")==SageSurprisePolicy.Command.BORED&&SageSurprisePolicy.parse("Send me down a rabbit hole")==SageSurprisePolicy.Command.RABBIT_HOLE&&SageSurprisePolicy.parse("Another one")==SageSurprisePolicy.Command.ANOTHER&&SageSurprisePolicy.parse("Stop")==SageSurprisePolicy.Command.STOP,"all voice commands");
        check(SageSurprisePolicy.Route.MEDIA_SESSION.ordinal()<SageSurprisePolicy.Route.ACCESSIBILITY_FALLBACK.ordinal(),"direct route priority");
        SageSurprisePolicy.Candidate one=candidate("Odd clocks"),two=candidate("Deep sea radio"),three=candidate("Tiny engines");
        check(SageSurprisePolicy.choose(Arrays.asList(one,two,three),new HashSet<>(),42L,"youtube","")!=null,"one result chosen");
        SageSurprisePolicy.Candidate seeded=SageSurprisePolicy.choose(Arrays.asList(one,two,three),new HashSet<>(),42L,"youtube","");
        check(seeded.identity().equals(SageSurprisePolicy.choose(Arrays.asList(three,one,two),new HashSet<>(),42L,"youtube","").identity()),"injectable seed deterministic");
        Set<String> recent=new HashSet<>();recent.add(seeded.identity());
        check(!SageSurprisePolicy.choose(Arrays.asList(one,two,three),recent,42L,"youtube","").identity().equals(seeded.identity()),"another excludes recent");
        check(!SageSurprisePolicy.eligible(new SageSurprisePolicy.Candidate("Ad","","youtube","","standard",true,true,false,true,false,true,true)),"advertising excluded");
        check(!SageSurprisePolicy.eligible(new SageSurprisePolicy.Candidate("Hidden","","youtube","","standard",false,true,false,false,false,true,true)),"hidden excluded");
        check(!SageSurprisePolicy.eligible(new SageSurprisePolicy.Candidate("Disabled","","youtube","","standard",true,false,false,false,false,true,true)),"disabled excluded");
        check(!SageSurprisePolicy.eligible(new SageSurprisePolicy.Candidate("Stale","","youtube","","standard",true,true,true,false,false,true,true)),"stale excluded");
        check(SageSurprisePolicy.revalidate(one,candidate("Odd clocks"))&&!SageSurprisePolicy.revalidate(one,candidate("Changed")),"immediate identity revalidation");
        check(!SageSurprisePolicy.eligible(new SageSurprisePolicy.Candidate("Private investigation","","youtube","","red_queen_private_vault",true,true,false,false,false,true,true)),"Red Queen excluded");
        check(!SageSurprisePolicy.eligible(new SageSurprisePolicy.Candidate("Sage Brain: ON","","com.pineapple.sagecommander.stable","","standard",true,true,false,false,false,true,true)),"Sage internal window excluded");
        check(!SageSurprisePolicy.eligible(new SageSurprisePolicy.Candidate("Stop","","youtube","","standard",true,true,false,false,false,true,true)),"Stop control excluded");
        check(SageSurprisePolicy.choose(Arrays.asList(new SageSurprisePolicy.Candidate("Unavailable","","youtube","","standard",false,false,true,false,false,true,true)),new HashSet<>(),1L,"youtube","")==null,"unsupported honest");
        System.out.println("surprise harness: "+tests+"/14 passed");
    }
}
