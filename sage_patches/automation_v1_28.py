#!/usr/bin/env python3
"""Add owner-approved, compiled, event-driven Sage automations."""

from pathlib import Path
import sys


MANAGER = r'''package com.pineapple.sage;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/** Event dispatcher for compiled routines. User data can enable a routine but cannot define code. */
final class SageAutomationManager {
    static final String APK_SIGNER = "download.sage_apk_signer";
    static final String FORGE_FINISHED = "forge.job_finished";
    static final String NETWORK_NEW = "network.new_device";
    static final String EVENT_APK = "downloaded_sage_apk_inspected";
    static final String EVENT_FORGE = "forge_job_finished";
    static final String EVENT_NETWORK = "network_new_device";
    static final String PERMANENT_SIGNER = "99e0a7c655cdefb3bb4ac85e5961d19358ee0ffdb3dce9b3a145f9cbcda78d35";
    private static final String CHANNEL = "sage_owner_automations";
    private static final String PREFS = "sage_automations";

    static final class Routine {
        final String id, purpose, trigger, conditions, steps, tools, target, permission;
        final String risk, confirmation, timeout, cancel, logs, rollback;
        Routine(String id,String purpose,String trigger,String conditions,String steps,String tools,
                String target,String permission,String risk,String confirmation,String timeout,
                String cancel,String logs,String rollback){
            this.id=id;this.purpose=purpose;this.trigger=trigger;this.conditions=conditions;
            this.steps=steps;this.tools=tools;this.target=target;this.permission=permission;
            this.risk=risk;this.confirmation=confirmation;this.timeout=timeout;this.cancel=cancel;
            this.logs=logs;this.rollback=rollback;
        }
        String summary(){return id+"\nPurpose: "+purpose+"\nTrigger: "+trigger+"\nConditions: "+conditions
                +"\nSteps: "+steps+"\nTools: "+tools+"\nTarget: "+target
                +"\nPermission: "+permission+"\nRisk: "+risk+"\nConfirmation: "+confirmation
                +"\nTimeout: "+timeout+"\nCancel: "+cancel+"\nLogs: "+logs+"\nRollback: "+rollback;}
    }

    private static final List<Routine> COMPILED;
    static {
        List<Routine> values=new ArrayList<>();
        values.add(new Routine(APK_SIGNER,"Inspect a completed Sage APK download and report permanent-signer continuity",
                "Android DownloadManager completion","download URI is readable; MIME/name indicates APK; package is Sage",
                "copy to private cache → parse APK → hash → compare signer → record concise result",
                "SagePackageInspector","one completed local APK","read URI; notifications when granted","medium",
                "owner enables once; installer handoff still confirms separately","60 seconds","cancel active inspection from Automation Desk",
                "local Automation Desk + sanitized Sage diagnostics","disable routine; cached APK remains non-executable and is replaceable"));
        values.add(new Routine(FORGE_FINISHED,"Notify when an approved paired-Dell Forge job reaches a terminal state",
                "authenticated Forge poll returns completed/failed/cancelled/interrupted","paired Forge and an owner-approved job ID",
                "validate terminal state → save event → local notification","SageForgeClient","current paired Dell job",
                "paired Forge trust; notifications when granted","low","per-job approval already required","event dispatch under 2 seconds",
                "cancel through Forge while running","local Automation Desk + Forge job log","disable routine; revoke Forge trust separately"));
        values.add(new Routine(NETWORK_NEW,"Alert when a new device appears in an owner-confirmed private-LAN snapshot",
                "confirmed private subnet scan saves a new snapshot","IP appears now but not in the previous saved snapshot",
                "compare exact saved IP sets → record added IPs → local notification","SageNetworkStore",
                "owner-confirmed RFC1918/link-local snapshot","private-LAN scan approval; notifications when granted","medium",
                "each scan still requires owner confirmation","event dispatch under 2 seconds","cancel the active network scan",
                "local Automation Desk + network snapshot","disable routine; delete app data to remove snapshots"));
        COMPILED=Collections.unmodifiableList(values);
    }
    private SageAutomationManager(){}
    static List<Routine> all(){return COMPILED;}
    static boolean enabled(Context c,String id){return prefs(c).getBoolean("enabled."+id,false);}
    static void setEnabled(Context c,String id,boolean value){
        if(find(id)==null)throw new IllegalArgumentException("routine is not compiled");
        prefs(c).edit().putBoolean("enabled."+id,value).apply();
        log(c,id,value?"ENABLED by owner":"DISABLED by owner");
    }
    static Routine find(String id){for(Routine r:COMPILED)if(r.id.equals(id))return r;return null;}
    static String logs(Context c){return prefs(c).getString("activity", "No automation activity yet.");}
    static void clearLogs(Context c){prefs(c).edit().remove("activity").apply();}
    static void emit(Context c,String event,JSONObject evidence){
        String id=EVENT_FORGE.equals(event)?FORGE_FINISHED:EVENT_NETWORK.equals(event)?NETWORK_NEW:null;
        if(id==null||!enabled(c,id))return;
        String concise=evidence==null?"{}":evidence.toString();
        log(c,id,event+" evidence="+concise);
        notifyOwner(c,id,concise);
    }
    static void recordApk(Context c,SagePackageInspector.Report report){
        if(!enabled(c,APK_SIGNER))return;
        boolean signer=PERMANENT_SIGNER.equalsIgnoreCase(report.signerSha256);
        boolean identity="com.pineapple.sagecommander.stable".equals(report.packageName);
        String result="package="+report.packageName+" version="+report.versionName+" ("+report.versionCode+")"
                +" signer_match="+signer+" identity_match="+identity+" file_sha256="+report.fileSha256;
        log(c,APK_SIGNER,result);
        notifyOwner(c,APK_SIGNER,result);
    }
    static void recordFailure(Context c,String id,String detail){if(enabled(c,id)){log(c,id,"ERROR "+detail);notifyOwner(c,id,"ERROR: "+detail);}}
    private static void log(Context c,String id,String detail){
        String line=DateFormat.getDateTimeInstance().format(new Date())+" | "+id+" | "+detail;
        String old=logs(c);if("No automation activity yet.".equals(old))old="";
        String joined=line+(old.isEmpty()?"":"\n"+old);if(joined.length()>24000)joined=joined.substring(0,24000);
        prefs(c).edit().putString("activity",joined).apply();
        SageDiagnostics.appendEvent(c,"AUTOMATION",id+" "+detail);
    }
    private static void notifyOwner(Context c,String id,String detail){
        if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);if(nm==null)return;
        if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Sage owner automations",NotificationManager.IMPORTANCE_DEFAULT));
        Intent open=new Intent(c,SageAutomationActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi=PendingIntent.getActivity(c,128,open,Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE:PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,CHANNEL):new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Sage automation: "+id)
                .setContentText(detail.length()>140?detail.substring(0,140):detail).setStyle(new Notification.BigTextStyle().bigText(detail))
                .setContentIntent(pi).setAutoCancel(true).setOnlyAlertOnce(true);
        nm.notify(Math.abs(id.hashCode()),b.build());
    }
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
}
'''


ACTIVITY = r'''package com.pineapple.sage;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public class SageAutomationActivity extends Activity {
    private LinearLayout list; private TextView activity;
    @Override public void onCreate(Bundle state){super.onCreate(state);setTitle("Sage Automation Desk");setContentView(build());}
    @Override protected void onResume(){super.onResume();refresh();}
    private View build(){
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(18),dp(18),dp(18));scroll.addView(root);
        root.addView(text("OWNER-APPROVED AUTOMATION",28));
        root.addView(text("Only these compiled routines can run. Enabling data never creates code, shell commands, new authority, or network scope. Every originating install, Forge job, or private-LAN scan retains its own confirmation boundary.",14));
        list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);root.addView(list);
        Button packages=button("Open Package Lab");packages.setOnClickListener(v->startActivity(new Intent(this,SagePackageCenterActivity.class)));root.addView(packages);
        Button forge=button("Open Forge");forge.setOnClickListener(v->startActivity(new Intent(this,SageForgeActivity.class)));root.addView(forge);
        Button network=button("Open Network Operations");network.setOnClickListener(v->startActivity(new Intent(this,SageNetworkActivity.class)));root.addView(network);
        root.addView(text("ACTIVITY / AUDIT",21));activity=text("",12);activity.setTextIsSelectable(true);root.addView(activity);
        Button clear=button("Approve clearing local automation activity");clear.setOnClickListener(v->SageConfirmation.require(this,"Clear Sage automation activity","local Automation Desk log","owner confirmation","Only local routine event text is deleted","This cannot be undone",()->{SageAutomationManager.clearLogs(this);refresh();}));root.addView(clear);
        SageAppearance.apply(this,scroll,root);return scroll;
    }
    private void refresh(){if(list==null)return;list.removeAllViews();for(SageAutomationManager.Routine r:SageAutomationManager.all()){
        TextView detail=text(r.summary(),12);detail.setTextIsSelectable(true);list.addView(detail);
        Switch toggle=new Switch(this);toggle.setText("Enabled: "+r.id);toggle.setChecked(SageAutomationManager.enabled(this,r.id));
        toggle.setOnCheckedChangeListener((button,value)->{if(value){button.setChecked(false);SageConfirmation.require(this,"Enable compiled Sage routine",r.id,r.permission,"Local evidence and an optional notification","Disable this switch at any time",()->{SageAutomationManager.setEnabled(this,r.id,true);requestNotifications();refresh();});}else if(SageAutomationManager.enabled(this,r.id)){SageAutomationManager.setEnabled(this,r.id,false);refresh();}});list.addView(toggle);
    }activity.setText(SageAutomationManager.logs(this));}
    private void requestNotifications(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},128);}
    private Button button(String value){Button b=new Button(this);b.setAllCaps(false);b.setText(value);return b;}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setPadding(0,dp(7),0,dp(7));return t;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
'''


RECEIVER = r'''package com.pineapple.sage;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;

/** Inspects only a completed DownloadManager item whose URI the OS grants Sage permission to read. */
public class SageDownloadAutomationReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())||!SageAutomationManager.enabled(context,SageAutomationManager.APK_SIGNER))return;
        long id=intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1L);if(id<0)return;
        DownloadManager manager=(DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE);if(manager==null)return;
        Uri uri=null;String title="",mime="";
        try(Cursor c=manager.query(new DownloadManager.Query().setFilterById(id))){if(c==null||!c.moveToFirst())return;
            int status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));if(status!=DownloadManager.STATUS_SUCCESSFUL)return;
            title=c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));mime=c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
            uri=manager.getUriForDownloadedFile(id);
        }catch(Exception e){SageAutomationManager.recordFailure(context,SageAutomationManager.APK_SIGNER,"download metadata unavailable: "+e.getMessage());return;}
        boolean apk=(title!=null&&title.toLowerCase(java.util.Locale.ROOT).endsWith(".apk"))||"application/vnd.android.package-archive".equals(mime);
        if(!apk||uri==null)return;
        final PendingResult pending=goAsync();final Uri selected=uri;
        SageOperation op=new SageOperation(context,"downloaded Sage APK automation");
        op.start(session->{SagePackageInspector.Report report=SagePackageInspector.inspect(context,selected,session);SageAutomationManager.recordApk(context,report);return report.fileSha256;},new SageOperation.Listener(){
            public void onProgress(String stage,int completed,int total,long elapsedMs){}
            public void onComplete(String result,long elapsedMs){pending.finish();}
            public void onError(String detail,long elapsedMs){SageAutomationManager.recordFailure(context,SageAutomationManager.APK_SIGNER,detail);pending.finish();}
        });
    }
}
'''


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    if text.count(old) != 1:
        raise SystemExit(f"{label}: expected one anchor, found {text.count(old)}")
    path.write_text(text.replace(old, new, 1))


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: automation_v1_28.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    (java / "SageAutomationManager.java").write_text(MANAGER)
    (java / "SageAutomationActivity.java").write_text(ACTIVITY)
    (java / "SageDownloadAutomationReceiver.java").write_text(RECEIVER)

    manifest = root / "app/src/main/AndroidManifest.xml"
    replace_once(manifest, '<uses-permission android:name="android.permission.INTERNET" />',
        '<uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />', "notification permission")
    replace_once(manifest, '        <activity android:name=".SageHostInspectorActivity" android:exported="false" />',
        '        <activity android:name=".SageHostInspectorActivity" android:exported="false" />\n'
        '        <activity android:name=".SageAutomationActivity" android:exported="false" />\n'
        '        <receiver android:name=".SageDownloadAutomationReceiver" android:exported="false">\n'
        '            <intent-filter><action android:name="android.intent.action.DOWNLOAD_COMPLETE" /></intent-filter>\n'
        '        </receiver>', "automation manifest")

    toolbelt = java / "SageToolbeltActivity.java"
    replace_once(toolbelt, '''        card(root, "Media Inspector",
                "Inspect active MediaSession app, title, playback state and controls; use direct play, pause, next, and previous APIs.",
                SageMediaInspectorActivity.class);''', '''        card(root, "Automation Desk",
                "Enable only compiled owner-approved routines; inspect triggers, conditions, risk, confirmation, cancellation, logs, and rollback.",
                SageAutomationActivity.class);
        card(root, "Media Inspector",
                "Inspect active MediaSession app, title, playback state and controls; use direct play, pause, next, and previous APIs.",
                SageMediaInspectorActivity.class);''', "Toolbelt automation card")

    redqueen = java / "SageRedQueenActivity.java"
    replace_once(redqueen, '        deferred(root, "Automation", "Deferred: unrestricted scripts are never accepted.");',
        '        functional(root, "Automation", "Three compiled event-driven routines with owner enablement, local logs, cancellation, and no arbitrary scripts", SageAutomationActivity.class);', "Red Queen automation")

    command = java / "SageCommandEngine.java"
    replace_once(command, '''        if (isAny(lower, "open media inspector", "inspect active media")) {
            return openWorkbench(SageMediaInspectorActivity.class, null);
        }''', '''        if (isAny(lower, "open automation desk", "show my automations", "manage routines")) {
            return openWorkbench(SageAutomationActivity.class, null);
        }
        if (isAny(lower, "open media inspector", "inspect active media")) {
            return openWorkbench(SageMediaInspectorActivity.class, null);
        }''', "voice automation route")

    forge = java / "SageForgeActivity.java"
    replace_once(forge, 'SageDiagnostics.appendEvent(SageForgeActivity.this,"FORGE","system.info completed job="+activeJob);activeJob="";',
        'SageDiagnostics.appendEvent(SageForgeActivity.this,"FORGE","system.info completed job="+activeJob);try{JSONObject event=new JSONObject();event.put("job_id",activeJob);event.put("status","completed");SageAutomationManager.emit(SageForgeActivity.this,SageAutomationManager.EVENT_FORGE,event);}catch(Exception ignored){}activeJob="";', "Forge completion event")

    network = java / "SageNetworkStore.java"
    replace_once(network, '''        prefs(context).edit()
                .putString("network_previous", old.toString())
                .putString("network_current", merged.toString())
                .putLong("network_snapshot_at", now)
                .apply();''', '''        prefs(context).edit()
                .putString("network_previous", old.toString())
                .putString("network_current", merged.toString())
                .putLong("network_snapshot_at", now)
                .apply();
        Set<String> added=ips(merged);added.removeAll(ips(old));
        if(!added.isEmpty()){try{JSONObject event=new JSONObject();event.put("added_ips",new JSONArray(added));event.put("snapshot_at",now);SageAutomationManager.emit(context,SageAutomationManager.EVENT_NETWORK,event);}catch(Exception ignored){}}''', "network event")

    registry = java / "SageCapabilityRegistry.java"
    replace_once(registry, '''        values.add(entry("osint.curated", "Curated public-source research with evidence summaries",''', '''        values.add(entry("automation.compiled", "Owner-approved event routines from a compiled allowlist",
                "automation", "Show my automations; manage routines", "Android",
                "compiled trigger + owner enablement", "local audit and optional notification", "per-routine declared permissions",
                "medium", "enable once; originating operation confirms separately", "per routine", "disable + operation cancellation",
                "none beyond already-approved Forge/private-LAN scope", "local device/private LAN only", false, false,
                "SageAutomationManager", SupportState.ACTIVE));
        values.add(entry("osint.curated", "Curated public-source research with evidence summaries",''', "automation registry")

    print("Applied Sage 1.28 compiled owner-approved automations")


if __name__ == "__main__":
    main()
