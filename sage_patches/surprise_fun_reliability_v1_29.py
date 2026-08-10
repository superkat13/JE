#!/usr/bin/env python3
"""Apply the 1.29 Surprise Me browser handoff and visible fun-control repair."""

from pathlib import Path
import sys


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise SystemExit(f"expected one replacement in {path}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: surprise_fun_reliability_v1_29.py <source-root>")
    root = Path(sys.argv[1]).resolve()
    java = root / "app/src/main/java/com/pineapple/sage"

    (java / "SageSurpriseManager.java").write_text(SURPRISE_MANAGER, encoding="utf-8")

    policy = java / "SageSurprisePolicy.java"
    replace_once(
        policy,
        '''    static Command parse(String raw){String v=normalize(raw).replaceFirst("^(hey )?sage ","");
        if(v.equals("stop"))return Command.STOP;if(v.equals("another")||v.equals("another one"))return Command.ANOTHER;
        if(v.contains("surprise me on youtube"))return Command.YOUTUBE;if(v.contains("rabbit hole"))return Command.RABBIT_HOLE;
        if(v.contains("something weird")||v.equals("weird"))return Command.WEIRD;
        if(v.contains("cure my boredom")||v.contains("cure boredom")||v.equals("i am bored")||v.equals("i m bored")||v.equals("im bored"))return Command.BORED;
        if(v.equals("surprise me"))return Command.SURPRISE;return Command.NONE;}
''',
        '''    static Command parse(String raw){String v=normalize(raw).replaceFirst("^(hey )?sage ","");
        if(v.equals("stop")||v.equals("stop surprise")||v.equals("stop surprising me")||v.equals("that is enough")||v.equals("thats enough"))return Command.STOP;
        if(v.equals("another")||v.equals("another one")||v.equals("one more")||v.equals("something else")||v.equals("surprise me again"))return Command.ANOTHER;
        if(v.contains("surprise me on youtube"))return Command.YOUTUBE;if(v.contains("rabbit hole"))return Command.RABBIT_HOLE;
        if(v.contains("something weird")||v.equals("weird"))return Command.WEIRD;
        if(v.contains("cure my boredom")||v.contains("cure boredom")||v.equals("i am bored")||v.equals("i m bored")||v.equals("im bored")||v.equals("i m fucking bored"))return Command.BORED;
        if(v.equals("surprise me"))return Command.SURPRISE;return Command.NONE;}
''',
    )
    replace_once(
        policy,
        '''    static boolean controlText(String value){String v=normalize(value);return v.equals("stop")||v.startsWith("stop surprise")||v.equals("cancel")||v.equals("back")||v.equals("refresh persistent brain report")||v.contains("download verified qwen")||v.contains("choose downloaded gguf")||v.contains("run deterministic brain test")||v.equals("sage brain on")||v.equals("sage brain off");}
''',
        '''    static boolean pendingProviderMatches(String expected,String actual){String wanted=clean(expected),current=clean(actual);return !wanted.isEmpty()&&wanted.equals(current);}
    static boolean controlText(String value){String v=normalize(value);return v.equals("stop")||v.startsWith("stop surprise")||v.equals("cancel")||v.equals("back")||v.equals("play")||v.equals("pause")||v.equals("next")||v.equals("previous")||v.equals("share")||v.equals("like")||v.equals("dislike")||v.equals("subscribe")||v.equals("comments")||v.equals("settings")||v.equals("full screen")||v.equals("more actions")||v.equals("refresh persistent brain report")||v.contains("download verified qwen")||v.contains("choose downloaded gguf")||v.contains("run deterministic brain test")||v.equals("sage brain on")||v.equals("sage brain off");}
''',
    )

    access = java / "SageAccessibilityService.java"
    replace_once(
        access,
        '''    static SurpriseSelection selectSurpriseVideo(long seed, Set<String> recent) {
        return selectSurprise(seed, recent, "video", true);
    }

    static SurpriseSelection selectSurpriseCurrent(long seed, Set<String> recent) {
        return selectSurprise(seed, recent, "generic", false);
    }

    private static SurpriseSelection selectSurprise(long seed, Set<String> recent,
                                                     String type, boolean requireYouTube) {
''',
        '''    static SurpriseSelection selectSurpriseVideo(long seed, Set<String> recent) {
        return selectSurprise(seed, recent, "video", true, "");
    }

    static SurpriseSelection selectSurpriseVideoForProvider(long seed, Set<String> recent,
                                                             String expectedProvider) {
        return selectSurprise(seed, recent, "video", true, expectedProvider);
    }

    static SurpriseSelection selectSurpriseCurrent(long seed, Set<String> recent) {
        return selectSurprise(seed, recent, "generic", false, "");
    }

    private static SurpriseSelection selectSurprise(long seed, Set<String> recent,
                                                     String type, boolean requireYouTube,
                                                     String expectedProvider) {
''',
    )
    replace_once(
        access,
        '''        if(SageSurprisePolicy.internalProvider(provider))return new SurpriseSelection(false,"","",provider,"","internal_sage_window");
        if(requireYouTube&&!provider.toLowerCase(Locale.US).contains("youtube"))return new SurpriseSelection(false,"","",provider,"","youtube_not_visible");
''',
        '''        if(SageSurprisePolicy.internalProvider(provider))return new SurpriseSelection(false,"","",provider,"","internal_sage_window");
        String expected=expectedProvider==null?"":expectedProvider.trim();
        if(requireYouTube&&!expected.isEmpty()&&!SageSurprisePolicy.pendingProviderMatches(expected,provider))return new SurpriseSelection(false,"","",provider,"","unexpected_provider");
        if(requireYouTube&&expected.isEmpty()&&!provider.toLowerCase(Locale.US).contains("youtube"))return new SurpriseSelection(false,"","",provider,"","youtube_not_visible");
''',
    )
    replace_once(
        access,
        '''    static void showSurpriseStopControl(){SageAccessibilityService service=instance;if(service==null||service.windowManager==null||service.surpriseStopView!=null)return;TextView stop=new TextView(service);stop.setText("Stop");stop.setContentDescription("Stop surprise discovery and media");stop.setTextColor(Color.WHITE);stop.setTextSize(17f);stop.setGravity(Gravity.CENTER);stop.setPadding(28,18,28,18);stop.setBackgroundColor(0xE6B91C1C);stop.setOnClickListener(v->SageSurpriseManager.execute(service,"stop"));WindowManager.LayoutParams params=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,android.graphics.PixelFormat.TRANSLUCENT);params.gravity=Gravity.BOTTOM|Gravity.END;params.x=24;params.y=48;try{service.windowManager.addView(stop,params);service.surpriseStopView=stop;SageDiagnostics.appendEvent(service,"SURPRISE UI","visible_stop=true standard_safe=true");}catch(RuntimeException error){SageDiagnostics.appendEvent(service,"SURPRISE UI","visible_stop=false reason="+error.getClass().getSimpleName());}}
''',
        '''    static void showSurpriseStopControl(){
        SageAccessibilityService service=instance;if(service==null||service.windowManager==null||service.surpriseStopView!=null)return;
        LinearLayout controls=new LinearLayout(service);controls.setOrientation(LinearLayout.HORIZONTAL);controls.setPadding(4,4,4,4);controls.setBackgroundColor(0xE6111827);
        TextView another=new TextView(service);another.setText("Another");another.setContentDescription("Choose another safe surprise");another.setTextColor(Color.WHITE);another.setTextSize(17f);another.setGravity(Gravity.CENTER);another.setPadding(24,18,24,18);another.setBackgroundColor(0xE62563EB);another.setOnClickListener(v->SageSurpriseManager.execute(service,"another one"));controls.addView(another);
        TextView stop=new TextView(service);stop.setText("Stop");stop.setContentDescription("Stop surprise discovery and media");stop.setTextColor(Color.WHITE);stop.setTextSize(17f);stop.setGravity(Gravity.CENTER);stop.setPadding(28,18,28,18);stop.setBackgroundColor(0xE6B91C1C);stop.setOnClickListener(v->SageSurpriseManager.execute(service,"stop"));controls.addView(stop);
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,android.graphics.PixelFormat.TRANSLUCENT);params.gravity=Gravity.BOTTOM|Gravity.END;params.x=24;params.y=48;
        try{service.windowManager.addView(controls,params);service.surpriseStopView=controls;SageDiagnostics.appendEvent(service,"SURPRISE UI","visible_another=true visible_stop=true standard_safe=true");}catch(RuntimeException error){SageDiagnostics.appendEvent(service,"SURPRISE UI","visible_stop=false reason="+error.getClass().getSimpleName());}
    }
''',
    )
    replace_once(
        access,
        '''    static void cancelSurprise(){SageAccessibilityService service=instance;if(service!=null){service.mainHandler.removeCallbacksAndMessages(null);service.clearNumberOverlayInternal("surprise_stop",null);service.hideSurpriseStopControl();}}
''',
        '''    static void cancelSurprise(){SageAccessibilityService service=instance;if(service!=null){service.clearNumberOverlayInternal("surprise_stop",null);service.hideSurpriseStopControl();}}
''',
    )


SURPRISE_MANAGER = r'''package com.pineapple.sage;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import java.util.Locale;
import java.util.Set;

/** Real Standard-Sage discovery coordinator. It never touches Red Queen storage. */
final class SageSurpriseManager {
    private static final String PREFS="sage_surprise_standard_v1";
    private static final long PENDING_TTL_MS=20_000L;
    private static final long SEED_STEP=0x9E3779B97F4A7C15L;
    static final class Outcome {
        final boolean matched, opened, pending, quiet; final String message, route;
        Outcome(boolean matched,boolean opened,boolean pending,boolean quiet,String message,String route){this.matched=matched;this.opened=opened;this.pending=pending;this.quiet=quiet;this.message=message;this.route=route;}
    }
    private SageSurpriseManager(){}
    static Outcome execute(Context context,String spoken){return execute(context,spoken,0L);}
    static Outcome execute(Context context,String spoken,long injectedSeed){
        SageSurprisePolicy.Command command=SageSurprisePolicy.parse(spoken);if(command==SageSurprisePolicy.Command.NONE)return new Outcome(false,false,false,false,"","");
        SharedPreferences p=prefs(context);long seed=selectionSeed(p,injectedSeed);
        if(command==SageSurprisePolicy.Command.STOP)return stop(context,seed);
        String provider=command==SageSurprisePolicy.Command.ANOTHER?p.getString("last_provider",""):"";
        String category=command==SageSurprisePolicy.Command.ANOTHER?p.getString("last_category","surprise"):category(command);
        String activeProvider=SageAccessibilityService.activePackageName();
        if(SageSurprisePolicy.internalProvider(provider))provider="youtube";
        if(command==SageSurprisePolicy.Command.YOUTUBE||command==SageSurprisePolicy.Command.WEIRD||command==SageSurprisePolicy.Command.BORED||command==SageSurprisePolicy.Command.RABBIT_HOLE)provider="youtube";
        if(command==SageSurprisePolicy.Command.SURPRISE&&activeProvider.toLowerCase(Locale.US).contains("youtube"))provider="youtube";
        if(command==SageSurprisePolicy.Command.ANOTHER&&provider.isEmpty())return outcome(context,seed,false,false,false,"I need a current provider before I can choose another one.","unsupported","standard_safe_context_only");
        String topic=topic(category,seed);
        if(provider.equals("youtube")){SageAccessibilityService.SurpriseSelection immediate=SageAccessibilityService.selectSurpriseVideo(seed,recent(p));
            if(immediate.opened)return selected(context,p,seed,immediate.title,immediate.uri,"youtube",category,topic,"accessibility_fallback");
            record(context,seed,"candidate_unavailable","youtube",immediate.rejection,"standard_safe_visible_semantics");
            return launchYouTubeSearch(context,p,seed,category,topic);}
        SageMediaSessionBridge media=new SageMediaSessionBridge(context);SageMediaSessionBridge.Snapshot snapshot=media.snapshot();
        if(snapshot.activePlayback&&!"unavailable".equals(snapshot.packageName)&&media.control("next"))return selected(context,p,seed,"Next from "+snapshot.title,"",snapshot.packageName,"current_media","current media","media_session");
        SageAccessibilityService.SurpriseSelection current=SageAccessibilityService.selectSurpriseCurrent(seed,recent(p));
        if(current.opened)return selected(context,p,seed,current.title,current.uri,current.provider,"current_page","current page","accessibility_fallback");
        return launchYouTubeSearch(context,p,seed,category,topic);
    }
    private static Outcome launchYouTubeSearch(Context context,SharedPreferences p,long seed,String category,String topic){String query=topic.isEmpty()?"unexpected fascinating videos":topic;
        String encoded=Uri.encode(query);Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.youtube.com/results?search_query="+encoded)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if(context.getPackageManager().getLaunchIntentForPackage("com.google.android.youtube")!=null)intent.setPackage("com.google.android.youtube");
        ComponentName resolved=intent.resolveActivity(context.getPackageManager());
        if(resolved==null)return outcome(context,seed,false,false,false,"YouTube is unavailable, and no browser can open the search.","unsupported","standard_safe_context_only");
        String expectedPackage=resolved.getPackageName();long created=System.currentTimeMillis();
        p.edit().putBoolean("pending",true).putBoolean("opening",false).putInt("pending_attempts",0).putLong("pending_seed",seed).putLong("pending_created",created).putString("pending_package",expectedPackage).putString("last_provider","youtube").putString("last_category",category).putString("last_topic",query).putString("last_route","pending_semantic_selection").commit();SageAccessibilityService.showSurpriseStopControl();
        new Handler(Looper.getMainLooper()).postDelayed(()->expirePending(context,created,seed),PENDING_TTL_MS+250L);
        context.startActivity(intent);record(context,seed,"pending_semantic_selection","youtube",query,"standard_safe_visible_semantics expected_package="+expectedPackage);
        return new Outcome(true,false,true,false,"Searching YouTube; I’ll open one verified result when it appears.","pending_semantic_selection");}
    static void onAccessibilityEvent(Context context,AccessibilityEvent event){if(event==null)return;SharedPreferences p=prefs(context);if(!p.getBoolean("pending",false)||p.getBoolean("opening",false))return;
        long created=p.getLong("pending_created",0L);if(System.currentTimeMillis()-created>PENDING_TTL_MS){expirePending(context,created,p.getLong("pending_seed",0L));return;}
        String pkg=event.getPackageName()==null?"":event.getPackageName().toString();String expected=p.getString("pending_package","");if(!SageSurprisePolicy.pendingProviderMatches(expected,pkg))return;
        int type=event.getEventType();if(type!=AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED&&type!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED&&type!=AccessibilityEvent.TYPE_VIEW_SCROLLED)return;
        if(!p.edit().putBoolean("opening",true).commit())return;new Handler(Looper.getMainLooper()).postDelayed(()->{SurpriseCompletion completion=completePending(context);if(!completion.finished)prefs(context).edit().putBoolean("opening",false).commit();},500L);}
    private static SurpriseCompletion completePending(Context context){SharedPreferences p=prefs(context);if(!p.getBoolean("pending",false))return new SurpriseCompletion(true);
        long seed=p.getLong("pending_seed",0L);String expected=p.getString("pending_package","");SageAccessibilityService.SurpriseSelection selected=SageAccessibilityService.selectSurpriseVideoForProvider(seed,recent(p),expected);
        if(!selected.opened){int attempts=p.getInt("pending_attempts",0)+1;p.edit().putInt("pending_attempts",attempts).commit();record(context,seed,"pending_retry",expected,selected.rejection,"standard_safe_visible_semantics attempt="+attempts);return new SurpriseCompletion(false);}
        p.edit().putBoolean("pending",false).putBoolean("opening",false).putString("last_title",selected.title).putString("last_uri",selected.uri).putString("last_provider","youtube").putString("last_route","accessibility_fallback").putString("recent",appendRecent(p,selected.identity)).commit();record(context,seed,"accessibility_fallback","youtube",selected.title,"standard_safe_visible_semantics_revalidated expected_package="+expected);return new SurpriseCompletion(true);}
    private static void expirePending(Context context,long created,long seed){SharedPreferences p=prefs(context);if(!p.getBoolean("pending",false)||p.getLong("pending_created",0L)!=created)return;p.edit().putBoolean("pending",false).putBoolean("opening",false).putString("last_route","unsupported").commit();SageAccessibilityService.cancelSurprise();record(context,seed,"unsupported","youtube","selection_timeout","standard_safe_timeout");}
    private static Outcome stop(Context context,long seed){SharedPreferences p=prefs(context);boolean hadPending=p.getBoolean("pending",false);p.edit().putBoolean("pending",false).putBoolean("opening",false).putString("last_route","cancelled").commit();SageAccessibilityService.cancelSurprise();boolean media=new SageMediaSessionBridge(context).stopPlayback();record(context,seed,"cancelled","standard",hadPending?"pending cancelled":"idle","standard_safe_no_private_read");return new Outcome(true,false,false,true,"",media?"media_session":"cancelled");}
    private static Outcome selected(Context c,SharedPreferences p,long seed,String title,String uri,String provider,String category,String topic,String route){String identity=new SageSurprisePolicy.Candidate(title,uri,provider,topic,"standard_context",true,true,false,false,false,true,true).identity();p.edit().putString("last_title",title).putString("last_uri",uri).putString("last_provider",provider).putString("last_category",category).putString("last_topic",topic).putString("last_route",route).putString("recent",appendRecent(p,identity)).commit();SageAccessibilityService.showSurpriseStopControl();record(c,seed,route,provider,title,"standard_safe_context_only category="+category);return new Outcome(true,true,false,true,"",route);}
    private static Outcome outcome(Context c,long seed,boolean opened,boolean pending,boolean quiet,String message,String route,String source){record(c,seed,route,"standard",message,source);return new Outcome(true,opened,pending,quiet,message,route);}
    private static long selectionSeed(SharedPreferences p,long injectedSeed){if(injectedSeed!=0L)return injectedSeed;long session=p.getLong("session_seed",0L);if(session==0L)session=System.currentTimeMillis()^System.nanoTime();long counter=p.getLong("selection_counter",0L)+1L;p.edit().putLong("session_seed",session).putLong("selection_counter",counter).commit();return session^(SEED_STEP*counter);}
    private static Set<String> recent(SharedPreferences p){return SageSurprisePolicy.recent(p.getString("recent",""));}
    private static String appendRecent(SharedPreferences p,String identity){Set<String> values=recent(p);values.add(identity);while(values.size()>24)values.remove(values.iterator().next());return SageSurprisePolicy.encodeRecent(values);}
    private static String category(SageSurprisePolicy.Command command){if(command==SageSurprisePolicy.Command.WEIRD)return "weird";if(command==SageSurprisePolicy.Command.RABBIT_HOLE)return "rabbit";if(command==SageSurprisePolicy.Command.BORED)return "bored";return "surprise";}
    private static String topic(String category,long seed){String[] values;if("weird".equals(category))values=new String[]{"weird obscure fascinating videos","strange forgotten inventions","bizarre nature mini documentaries","odd machines nobody remembers"};else if("rabbit".equals(category))values=new String[]{"unexpected deep dive rabbit hole","mysterious engineering history explained","strange true stories deep dive","how obscure things actually work"};else if("bored".equals(category))values=new String[]{"short fascinating unexpected videos","satisfying science and nature","clever builds under ten minutes","tiny documentaries worth watching"};else values=new String[]{"unexpected fascinating videos","beautiful strange mini documentaries","clever inventions and odd history","surprising science stories"};return values[(int)Math.floorMod(seed,values.length)];}
    static String summary(Context c){SharedPreferences p=prefs(c);String title=p.getString("last_title","None yet");return "Last: "+title+"\nRoute: "+p.getString("last_route","idle")+"\nTopic: "+p.getString("last_topic","none");}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    private static void record(Context c,long seed,String route,String provider,String selection,String source){SageDiagnostics.appendEvent(c,"SURPRISE","seed="+seed+" route="+route+" provider="+provider+" selection="+selection+" content_source="+source+" standard_safe=true red_queen_queried=false");}
    private static final class SurpriseCompletion{final boolean finished;SurpriseCompletion(boolean value){finished=value;}}
}
'''


if __name__ == "__main__":
    main()
