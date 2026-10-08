from pathlib import Path
import sys


ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"


def write(name, value):
    (JAVA / name).write_text(value)


def replace_once(path, old, new, label):
    text = path.read_text()
    if text.count(old) != 1:
        raise SystemExit(f"{label}: expected one match, found {text.count(old)}")
    path.write_text(text.replace(old, new, 1))


write("SageOperation.java", r'''package com.pineapple.sage;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

final class SageOperation {
    interface Work { String run(Session session) throws Exception; }
    interface Listener {
        void onProgress(String stage, int completed, int total, long elapsedMs);
        void onComplete(String result, long elapsedMs);
        void onError(String detail, long elapsedMs);
    }
    static final class Session {
        private final AtomicBoolean cancelled;
        private final Listener listener;
        private final Handler main;
        private final long started;
        Session(AtomicBoolean cancelled, Listener listener, Handler main, long started) {
            this.cancelled = cancelled; this.listener = listener; this.main = main; this.started = started;
        }
        boolean isCancelled() { return cancelled.get() || Thread.currentThread().isInterrupted(); }
        void checkCancelled() throws InterruptedException { if (isCancelled()) throw new InterruptedException("cancelled"); }
        void progress(String stage, int completed, int total) {
            long elapsed = System.currentTimeMillis() - started;
            main.post(() -> listener.onProgress(stage, completed, total, elapsed));
        }
    }

    private final Context context;
    private final String operation;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<?> future;

    SageOperation(Context context, String operation) {
        this.context = context.getApplicationContext();
        this.operation = operation;
    }

    void start(Work work, Listener listener) {
        if (future != null) throw new IllegalStateException("operation already started");
        long started = System.currentTimeMillis();
        Handler main = new Handler(Looper.getMainLooper());
        SageDiagnostics.appendEvent(context, "OPERATION", operation + " started");
        future = executor.submit(() -> {
            try {
                String result = work.run(new Session(cancelled, listener, main, started));
                long elapsed = System.currentTimeMillis() - started;
                if (cancelled.get()) {
                    SageDiagnostics.appendEvent(context, "OPERATION", operation + " cancelled elapsed_ms=" + elapsed);
                    main.post(() -> listener.onError("Cancelled", elapsed));
                } else {
                    SageDiagnostics.appendEvent(context, "OPERATION", operation + " completed elapsed_ms=" + elapsed);
                    main.post(() -> listener.onComplete(result, elapsed));
                }
            } catch (InterruptedException cancelledError) {
                long elapsed = System.currentTimeMillis() - started;
                SageDiagnostics.appendEvent(context, "OPERATION", operation + " cancelled elapsed_ms=" + elapsed);
                main.post(() -> listener.onError("Cancelled", elapsed));
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                long elapsed = System.currentTimeMillis() - started;
                String detail = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
                SageDiagnostics.recordError(context, operation + " failed: " + detail);
                main.post(() -> listener.onError(detail, elapsed));
            } finally {
                executor.shutdown();
            }
        });
    }

    void cancel() {
        cancelled.set(true);
        if (future != null) future.cancel(true);
    }
}
''')

write("SageConfirmation.java", r'''package com.pineapple.sage;

import android.app.Activity;
import android.app.AlertDialog;

final class SageConfirmation {
    private SageConfirmation() {}
    static void require(Activity activity, String action, String target, String permissions,
                        String dataLeaving, String reversibility, Runnable approved) {
        String message = "Exact action: " + action
                + "\n\nTarget: " + target
                + "\n\nPermissions involved: " + permissions
                + "\n\nData leaving device: " + dataLeaving
                + "\n\nReversibility: " + reversibility;
        new AlertDialog.Builder(activity)
                .setTitle("Owner approval required")
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Approve", (dialog, which) -> {
                    SageDiagnostics.appendEvent(activity, "APPROVAL", action + " target=" + target);
                    approved.run();
                }).show();
    }
}
''')

write("SagePackageInspector.java", r'''package com.pineapple.sage;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

final class SagePackageInspector {
    static final class Report {
        final File cachedFile;
        final Uri originalUri;
        final String packageName, versionName, signerSha256, fileSha256, source;
        final long versionCode, minSdk, size;
        final String[] permissions;
        final boolean installed, packageMatch, signerMatch, downgrade, allowlisted;
        Report(File file, Uri uri, String pkg, String name, long code, long minSdk,
               String[] permissions, String signer, String hash, long size, String source,
               boolean installed, boolean packageMatch, boolean signerMatch, boolean downgrade, boolean allowlisted) {
            this.cachedFile=file; this.originalUri=uri; this.packageName=pkg; this.versionName=name;
            this.versionCode=code; this.minSdk=minSdk; this.permissions=permissions; this.signerSha256=signer;
            this.fileSha256=hash; this.size=size; this.source=source; this.installed=installed;
            this.packageMatch=packageMatch; this.signerMatch=signerMatch; this.downgrade=downgrade; this.allowlisted=allowlisted;
        }
        String riskSummary() {
            if (!packageMatch) return "BLOCKED: uninstalled package identity is not on the owner's trusted allowlist.";
            if (installed && !signerMatch) return "BLOCKED: signing certificate mismatch.";
            if (downgrade) return "BLOCKED: candidate is older than the installed version.";
            return "Identity checks passed. Android will still show its required installer confirmation.";
        }
        boolean safeForInstall() { return packageMatch && (!installed || signerMatch) && !downgrade; }
        String display() {
            StringBuilder out=new StringBuilder();
            out.append("Package: ").append(packageName).append('\n');
            out.append("Version: ").append(versionName).append(" (").append(versionCode).append(")\n");
            out.append("Minimum Android API: ").append(minSdk).append('\n');
            out.append("Signer certificate SHA-256: ").append(signerSha256).append('\n');
            out.append("File SHA-256: ").append(fileSha256).append('\n');
            out.append("Size: ").append(size).append(" bytes\n");
            out.append("Install source: ").append(source).append('\n');
            out.append("Requested permissions: ");
            if (permissions.length == 0) out.append("none declared");
            for (String permission : permissions) out.append("\n • ").append(permission);
            out.append("\n\nRisk summary: ").append(riskSummary());
            return out.toString();
        }
        JSONObject toJson() throws Exception {
            JSONObject j=new JSONObject(); j.put("package",packageName); j.put("version_name",versionName);
            j.put("version_code",versionCode); j.put("min_sdk",minSdk); j.put("signer_sha256",signerSha256);
            j.put("file_sha256",fileSha256); j.put("size",size); j.put("source",source);
            j.put("safe_for_install",safeForInstall()); j.put("allowlisted",allowlisted); j.put("risk",riskSummary());
            j.put("permissions",new JSONArray(permissions)); return j;
        }
    }

    static Report inspect(Context context, Uri uri, SageOperation.Session session) throws Exception {
        session.progress("Copying selected APK into private inspection cache",0,4);
        File dir=new File(context.getCacheDir(),"package-inspection"); if(!dir.exists()&&!dir.mkdirs()) throw new Exception("cache unavailable");
        File apk=new File(dir,"candidate.apk");
        try(InputStream in=context.getContentResolver().openInputStream(uri); FileOutputStream out=new FileOutputStream(apk)) {
            if(in==null) throw new Exception("selected file could not be opened");
            byte[] b=new byte[65536]; int n; while((n=in.read(b))>=0){ session.checkCancelled(); out.write(b,0,n); }
        }
        session.progress("Parsing Android package metadata",1,4);
        PackageManager pm=context.getPackageManager();
        int flags=PackageManager.GET_PERMISSIONS | (android.os.Build.VERSION.SDK_INT>=28 ? 0x08000000 : PackageManager.GET_SIGNATURES);
        PackageInfo candidate=pm.getPackageArchiveInfo(apk.getAbsolutePath(),flags);
        if(candidate==null||candidate.packageName==null) throw new Exception("file is not a readable Android APK");
        long code=android.os.Build.VERSION.SDK_INT>=28?candidate.getLongVersionCode():candidate.versionCode;
        long min=candidate.applicationInfo==null?0:candidate.applicationInfo.minSdkVersion;
        String signer=signer(candidate);
        session.progress("Calculating SHA-256",2,4);
        String hash=sha256(apk,session);
        PackageInfo installed=null; try { installed=pm.getPackageInfo(candidate.packageName,flags); } catch(PackageManager.NameNotFoundException ignored) {}
        boolean isInstalled=installed!=null;
        boolean allowlisted=context.getSharedPreferences("sage_workbench",Context.MODE_PRIVATE)
                .getStringSet("trusted_package_identities",java.util.Collections.emptySet())
                .contains(candidate.packageName+"|"+signer);
        boolean pkgMatch=isInstalled||context.getPackageName().equals(candidate.packageName)||allowlisted;
        boolean signerMatch=!isInstalled||signer.equals(signer(installed));
        long installedCode=isInstalled?(android.os.Build.VERSION.SDK_INT>=28?installed.getLongVersionCode():installed.versionCode):-1;
        boolean downgrade=isInstalled&&code<installedCode;
        session.progress("Comparing installed identity",4,4);
        SageDiagnostics.appendEvent(context,"PACKAGE","inspected package="+candidate.packageName+" version="+code+" signer_match="+signerMatch+" sha256="+hash);
        return new Report(apk,uri,candidate.packageName,String.valueOf(candidate.versionName),code,min,
                candidate.requestedPermissions==null?new String[0]:candidate.requestedPermissions,signer,hash,apk.length(),uri.toString(),
                isInstalled,pkgMatch,signerMatch,downgrade,allowlisted);
    }

    private static String signer(PackageInfo info) throws Exception {
        Signature signature=null;
        if(android.os.Build.VERSION.SDK_INT>=28&&info.signingInfo!=null){
            Signature[] values=info.signingInfo.hasMultipleSigners()?info.signingInfo.getApkContentsSigners():info.signingInfo.getSigningCertificateHistory();
            if(values!=null&&values.length>0) signature=values[0];
        } else if(info.signatures!=null&&info.signatures.length>0) signature=info.signatures[0];
        if(signature==null) return "unavailable";
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); return hex(digest.digest(signature.toByteArray()));
    }
    static String sha256(File file, SageOperation.Session session) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(FileInputStream in=new FileInputStream(file)){ byte[] b=new byte[65536]; int n; while((n=in.read(b))>=0){session.checkCancelled();digest.update(b,0,n);} }
        return hex(digest.digest());
    }
    private static String hex(byte[] data){StringBuilder s=new StringBuilder();for(byte b:data)s.append(String.format(java.util.Locale.US,"%02x",b));return s.toString();}
}
''')

write("SagePackageCenterActivity.java", r'''package com.pineapple.sage;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

public class SagePackageCenterActivity extends Activity implements SageOperation.Listener {
    private static final int PICK_APK=5201;
    private TextView status, report, history;
    private ProgressBar progress;
    private Button install, cancel;
    private SageOperation operation;
    private SagePackageInspector.Report inspected;

    @Override public void onCreate(Bundle state){super.onCreate(state);setTitle("Sage Package Center");setContentView(build());refreshHistory();}
    private View build(){
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(18),dp(18),dp(18));scroll.addView(root);
        root.addView(text("Package Center",28));root.addView(text("Inspect first. Sage blocks package, signer, and downgrade mismatches and always hands installation to Android's confirmation screen.",15));
        Button browse=button("Browse local APK files");browse.setOnClickListener(v->browse());root.addView(browse);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);root.addView(progress);
        status=text("No APK selected.",14);root.addView(status);report=text("",13);report.setTextIsSelectable(true);root.addView(report);
        cancel=button("Stop active inspection");cancel.setEnabled(false);cancel.setOnClickListener(v->{if(operation!=null)operation.cancel();});root.addView(cancel);
        Button trust=button("Add exact package + signer identity to trusted allowlist");trust.setOnClickListener(v->trustIdentity());root.addView(trust);
        install=button("Approve installer handoff");install.setEnabled(false);install.setOnClickListener(v->confirmInstall());root.addView(install);
        Button unknown=button("Open Android unknown-app install settings");unknown.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName()))));root.addView(unknown);
        TextView htitle=text("Install and inspection history",20);root.addView(htitle);history=text("",12);root.addView(history);
        SageAppearance.apply(this,scroll,root);return scroll;
    }
    private void browse(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/vnd.android.package-archive");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK_APK);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==PICK_APK&&result==RESULT_OK&&data!=null&&data.getData()!=null)inspect(data.getData());}
    private void inspect(Uri uri){inspected=null;install.setEnabled(false);cancel.setEnabled(true);progress.setProgress(0);operation=new SageOperation(this,"package inspection");operation.start(session->{final SagePackageInspector.Report[] box=new SagePackageInspector.Report[1];box[0]=SagePackageInspector.inspect(this,uri,session);inspected=box[0];return box[0].display();},this);}
    @Override public void onProgress(String stage,int completed,int total,long elapsed){status.setText(stage+" • "+elapsed+" ms");progress.setProgress(total==0?0:completed*100/total);}
    @Override public void onComplete(String result,long elapsed){cancel.setEnabled(false);progress.setProgress(100);status.setText("Inspection complete • "+elapsed+" ms");report.setText(result);install.setEnabled(inspected!=null&&inspected.safeForInstall());record("inspection",inspected==null?"unknown":inspected.packageName,result);}
    @Override public void onError(String detail,long elapsed){cancel.setEnabled(false);status.setText("Inspection stopped: "+detail);record("inspection_error","",detail);}
    private void confirmInstall(){if(inspected==null||!inspected.safeForInstall()){Toast.makeText(this,"Package did not pass identity checks.",Toast.LENGTH_LONG).show();return;}SageConfirmation.require(this,"Launch Android package installer",inspected.packageName+" "+inspected.versionName,"REQUEST_INSTALL_PACKAGES; Android owner confirmation","None by Sage","Cancel Android's installer before confirmation",this::launchInstaller);}
    private void trustIdentity(){if(inspected==null){Toast.makeText(this,"Inspect an APK first.",Toast.LENGTH_LONG).show();return;}SageConfirmation.require(this,"Trust exact package and signer identity",inspected.packageName+" | "+inspected.signerSha256,"None","Nothing","Remove Sage app data or trust entry in a future Workbench release",()->{android.content.SharedPreferences p=getSharedPreferences("sage_workbench",MODE_PRIVATE);java.util.Set<String> trusted=new java.util.HashSet<>(p.getStringSet("trusted_package_identities",java.util.Collections.emptySet()));trusted.add(inspected.packageName+"|"+inspected.signerSha256);p.edit().putStringSet("trusted_package_identities",trusted).apply();record("allowlist_add",inspected.packageName,inspected.signerSha256);inspect(inspected.originalUri);});}
    private void launchInstaller(){try{Intent i=new Intent(Intent.ACTION_VIEW);i.setDataAndType(inspected.originalUri,"application/vnd.android.package-archive");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);record("installer_handoff",inspected.packageName,inspected.riskSummary());startActivity(i);}catch(Exception e){record("installer_error",inspected.packageName,e.toString());Toast.makeText(this,"Android installer unavailable: "+e.getClass().getSimpleName(),Toast.LENGTH_LONG).show();}}
    private void record(String action,String target,String detail){try{android.content.SharedPreferences p=getSharedPreferences("sage_workbench",MODE_PRIVATE);JSONArray a=new JSONArray(p.getString("package_history","[]"));org.json.JSONObject j=new org.json.JSONObject();j.put("time",System.currentTimeMillis());j.put("action",action);j.put("target",target);j.put("detail",detail);a.put(j);while(a.length()>30)a.remove(0);p.edit().putString("package_history",a.toString()).apply();SageDiagnostics.appendEvent(this,"PACKAGE",action+" target="+target);refreshHistory();}catch(Exception ignored){}}
    private void refreshHistory(){if(history==null)return;history.setText(getSharedPreferences("sage_workbench",MODE_PRIVATE).getString("package_history","No package history yet."));}
    private Button button(String s){Button b=new Button(this);b.setText(s);return b;}private TextView text(String s,int z){TextView t=new TextView(this);t.setText(s);t.setTextSize(z);return t;}private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
''')

write("SageNetworkStore.java", r'''package com.pineapple.sage;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

final class SageNetworkStore {
    private static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("sage_workbench",Context.MODE_PRIVATE);}
    static JSONArray current(Context c){try{return new JSONArray(prefs(c).getString("network_current","[]"));}catch(Exception e){return new JSONArray();}}
    static JSONArray previous(Context c){try{return new JSONArray(prefs(c).getString("network_previous","[]"));}catch(Exception e){return new JSONArray();}}
    static void save(Context c,JSONArray next){android.content.SharedPreferences p=prefs(c);p.edit().putString("network_previous",p.getString("network_current","[]")).putString("network_current",next.toString()).apply();}
    static void update(Context c,String ip,String name,boolean trusted,boolean hidden,String note){try{JSONArray a=current(c);for(int i=0;i<a.length();i++){JSONObject j=a.getJSONObject(i);if(ip.equals(j.optString("ip"))){j.put("owner_name",name);j.put("trusted",trusted);j.put("hidden",hidden);j.put("note",note);}}prefs(c).edit().putString("network_current",a.toString()).apply();}catch(Exception ignored){}}
    static String changes(Context c){JSONArray now=current(c),before=previous(c);java.util.Set<String> n=new java.util.TreeSet<>(),b=new java.util.TreeSet<>();for(int i=0;i<now.length();i++)n.add(now.optJSONObject(i).optString("ip"));for(int i=0;i<before.length();i++)b.add(before.optJSONObject(i).optString("ip"));java.util.Set<String> added=new java.util.TreeSet<>(n);added.removeAll(b);java.util.Set<String> missing=new java.util.TreeSet<>(b);missing.removeAll(n);return "New: "+added+"\nMissing: "+missing;}
}
''')

write("SageNetworkScanner.java", r'''package com.pineapple.sage;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

final class SageNetworkScanner {
    static String localRange(Context c) throws Exception {WifiManager w=(WifiManager)c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);DhcpInfo d=w==null?null:w.getDhcpInfo();if(d==null||d.ipAddress==0)throw new Exception("Wi-Fi local address unavailable");String ip=Formatter.formatIpAddress(d.ipAddress);int cut=ip.lastIndexOf('.');if(cut<0)throw new Exception("Only IPv4 local subnets are supported");return ip.substring(0,cut)+".0/24";}
    static JSONArray scan(Context c,SageOperation.Session session) throws Exception {
        String range=localRange(c);if(!isPrivate(range))throw new SecurityException("public ranges are refused");String prefix=range.substring(0,range.lastIndexOf('.')+1);session.progress("Scanning "+range+" at four concurrent probes",0,254);
        ExecutorService pool=Executors.newFixedThreadPool(4);CompletionService<JSONObject> done=new ExecutorCompletionService<>(pool);List<Future<JSONObject>> futures=new ArrayList<>();
        for(int i=1;i<255;i++){final int host=i;futures.add(done.submit(()->probe(prefix+host,session)));}
        JSONArray found=new JSONArray();try{for(int i=0;i<254;i++){session.checkCancelled();JSONObject device=done.take().get();if(device!=null)found.put(device);session.progress("Scanning "+range,i+1,254);}}finally{for(Future<?> f:futures)f.cancel(true);pool.shutdownNow();}
        SageNetworkStore.save(c,found);SageDiagnostics.appendEvent(c,"NETWORK","scan complete range="+range+" devices="+found.length());return found;
    }
    private static JSONObject probe(String ip,SageOperation.Session session){long start=System.currentTimeMillis();try{session.checkCancelled();InetAddress a=InetAddress.getByName(ip);boolean up=a.isReachable(300);if(!up)return null;JSONObject j=new JSONObject();j.put("ip",ip);String canonical=a.getCanonicalHostName();j.put("hostname",canonical.equals(ip)?"unresolved":canonical);j.put("mac","unavailable on this Android version");j.put("vendor","unknown");j.put("reachable",true);j.put("response_ms",System.currentTimeMillis()-start);j.put("services",services(ip,session));long now=System.currentTimeMillis();j.put("first_seen",now);j.put("last_seen",now);j.put("trusted",false);j.put("hidden",false);j.put("owner_name","");j.put("note","");j.put("identity_confidence","No OS/device-type claim; hostname and services are observations only");return j;}catch(Exception ignored){return null;}}
    private static JSONArray services(String ip,SageOperation.Session session){JSONArray a=new JSONArray();int[] ports={22,53,80,443,445,3389,8080};for(int p:ports){if(session.isCancelled())break;try(Socket s=new Socket()){s.connect(new InetSocketAddress(ip,p),120);a.put(p);}catch(Exception ignored){}}return a;}
    static boolean isPrivate(String range){try{String host=range==null?"":range.split("/",2)[0];byte[] raw=InetAddress.getByName(host).getAddress();if(raw.length!=4)return false;int a=raw[0]&255,b=raw[1]&255;return a==10||(a==172&&b>=16&&b<=31)||(a==192&&b==168);}catch(Exception ignored){return false;}}
}
''')

write("SageNetworkActivity.java", r'''package com.pineapple.sage;

import android.app.Activity;import android.app.AlertDialog;import android.os.Bundle;import android.view.View;import android.widget.*;import org.json.JSONArray;import org.json.JSONObject;

public class SageNetworkActivity extends Activity implements SageOperation.Listener {
    public static final String EXTRA_MODE="mode";private TextView range,status,map,changes;private EditText editIp,editName,editNote;private CheckBox editTrusted,editHidden;private ProgressBar progress;private Button stop;private SageOperation operation;
    @Override public void onCreate(Bundle b){super.onCreate(b);setTitle("Sage Home Lab / Network Map");setContentView(build());refresh();if("scan".equals(getIntent().getStringExtra(EXTRA_MODE)))confirmScan();}
    private View build(){ScrollView s=new ScrollView(this);LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setPadding(dp(18),dp(18),dp(18),dp(18));s.addView(r);r.addView(text("Home Lab & Network Map",27));r.addView(text("Authorized local networks only. Sage refuses public ranges, uses four workers, conservative timeouts, no credentials, exploits, persistence, evasion, or hidden scans.",14));range=text("Target: checking local subnet…",15);r.addView(range);Button scan=button("Confirm and scan displayed local subnet");scan.setOnClickListener(v->confirmScan());r.addView(scan);stop=button("Stop active network work");stop.setEnabled(false);stop.setOnClickListener(v->{if(operation!=null)operation.cancel();});r.addView(stop);progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(254);r.addView(progress);status=text("Idle",13);r.addView(status);Button refresh=button("Refresh saved map");refresh.setOnClickListener(v->refresh());r.addView(refresh);changes=text("",14);r.addView(changes);map=text("",13);map.setTextIsSelectable(true);r.addView(map);r.addView(text("Edit saved device",20));editIp=new EditText(this);editIp.setHint("Exact IP from map");r.addView(editIp);editName=new EditText(this);editName.setHint("Owner name");r.addView(editName);editNote=new EditText(this);editNote.setHint("Owner note");r.addView(editNote);editTrusted=new CheckBox(this);editTrusted.setText("Trusted");r.addView(editTrusted);editHidden=new CheckBox(this);editHidden.setText("Hide known infrastructure");r.addView(editHidden);Button save=button("Save device label");save.setOnClickListener(v->{String ip=editIp.getText().toString().trim();if(!containsIp(ip)){Toast.makeText(this,"Choose an exact IP currently shown in the saved map.",Toast.LENGTH_LONG).show();return;}SageNetworkStore.update(this,ip,editName.getText().toString(),editTrusted.isChecked(),editHidden.isChecked(),editNote.getText().toString());SageDiagnostics.appendEvent(this,"NETWORK","owner metadata updated ip="+ip);refresh();});r.addView(save);SageAppearance.apply(this,s,r);return s;}
    private void refresh(){try{range.setText("Target: "+SageNetworkScanner.localRange(this));}catch(Exception e){range.setText("Target unavailable: "+e.getMessage());}changes.setText("Changes since previous scan\n"+SageNetworkStore.changes(this));JSONArray a=SageNetworkStore.current(this);StringBuilder out=new StringBuilder("Saved devices: "+a.length()+"\n");for(int i=0;i<a.length();i++){JSONObject j=a.optJSONObject(i);if(j==null||j.optBoolean("hidden"))continue;out.append("\n").append(j.optString("owner_name").isEmpty()?j.optString("hostname"):j.optString("owner_name")).append(" • ").append(j.optString("ip")).append(j.optBoolean("trusted")?" • TRUSTED":" • UNKNOWN").append("\n  response ").append(j.optLong("response_ms")).append(" ms • services ").append(j.optJSONArray("services")).append("\n  ").append(j.optString("identity_confidence"));}map.setText(out.toString());}
    private void confirmScan(){String target;try{target=SageNetworkScanner.localRange(this);}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();return;}final String exact=target;SageConfirmation.require(this,"Discover reachable devices and limited common service ports",exact,"INTERNET and local Wi-Fi state","Nothing; results stay in Sage local storage","Stop immediately; delete Sage data to remove snapshots",this::startScan);}
    private void startScan(){stop.setEnabled(true);progress.setProgress(0);operation=new SageOperation(this,"local network scan");operation.start(session->SageNetworkScanner.scan(this,session).toString(2),this);}
    private boolean containsIp(String ip){JSONArray a=SageNetworkStore.current(this);for(int i=0;i<a.length();i++)if(ip.equals(a.optJSONObject(i).optString("ip")))return true;return false;}
    @Override public void onProgress(String stage,int done,int total,long elapsed){status.setText(stage+" • "+elapsed+" ms");progress.setMax(total);progress.setProgress(done);}
    @Override public void onComplete(String result,long elapsed){stop.setEnabled(false);status.setText("Scan complete • "+elapsed+" ms");refresh();}
    @Override public void onError(String detail,long elapsed){stop.setEnabled(false);status.setText("Network operation stopped: "+detail+" • "+elapsed+" ms");refresh();}
    private Button button(String x){Button b=new Button(this);b.setText(x);return b;}private TextView text(String x,int z){TextView t=new TextView(this);t.setText(x);t.setTextSize(z);return t;}private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
''')

write("SageDeviceToolsActivity.java", r'''package com.pineapple.sage;

import android.Manifest;import android.app.Activity;import android.content.*;import android.content.pm.PackageManager;import android.hardware.camera2.CameraManager;import android.net.Uri;import android.os.*;import android.provider.Settings;import android.util.Base64;import android.view.View;import android.widget.*;import java.net.URLDecoder;import java.net.URLEncoder;import java.nio.charset.StandardCharsets;

public class SageDeviceToolsActivity extends Activity {
    private static final int PICK_HASH=7401;private TextView info;private EditText input;private boolean torch;
    @Override public void onCreate(Bundle b){super.onCreate(b);setTitle("Sage Device Tools");setContentView(build());refreshInfo();}
    private View build(){ScrollView s=new ScrollView(this);LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setPadding(dp(18),dp(18),dp(18),dp(18));s.addView(r);r.addView(text("Device Tools",27));info=text("",14);r.addView(info);Button light=button("Toggle flashlight");light.setOnClickListener(v->toggleTorch());r.addView(light);Button hash=button("Choose file and calculate SHA-256");hash.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK_HASH);});r.addView(hash);input=new EditText(this);input.setHint("Local text for Base64 / hex / URL / JWT / password tools");input.setMinLines(2);r.addView(input);Button b64e=button("Base64 encode locally");b64e.setOnClickListener(v->set(Base64.encodeToString(input.getText().toString().getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP)));r.addView(b64e);Button b64d=button("Base64 decode locally");b64d.setOnClickListener(v->safe(()->new String(Base64.decode(input.getText().toString(),Base64.DEFAULT),StandardCharsets.UTF_8)));r.addView(b64d);Button hex=button("Hex encode locally");hex.setOnClickListener(v->{StringBuilder x=new StringBuilder();for(byte q:input.getText().toString().getBytes(StandardCharsets.UTF_8))x.append(String.format(java.util.Locale.US,"%02x",q));set(x.toString());});r.addView(hex);Button url=button("URL encode locally");url.setOnClickListener(v->safe(()->URLEncoder.encode(input.getText().toString(),"UTF-8")));r.addView(url);Button urld=button("URL decode locally");urld.setOnClickListener(v->safe(()->URLDecoder.decode(input.getText().toString(),"UTF-8")));r.addView(urld);Button jwt=button("Decode JWT header and payload only");jwt.setOnClickListener(v->safe(()->decodeJwt(input.getText().toString())));r.addView(jwt);Button strength=button("Estimate password strength locally");strength.setOnClickListener(v->set(passwordStrength(input.getText().toString())));r.addView(strength);Button calc=button("Calculate basic arithmetic");calc.setOnClickListener(v->safe(()->String.valueOf(new Parser(input.getText().toString()).parse())));r.addView(calc);addSetting(r,"Wi-Fi settings",Settings.ACTION_WIFI_SETTINGS);addSetting(r,"Bluetooth settings",Settings.ACTION_BLUETOOTH_SETTINGS);addSetting(r,"Notification access",Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);addSetting(r,"Accessibility",Settings.ACTION_ACCESSIBILITY_SETTINGS);addSetting(r,"Usage access",Settings.ACTION_USAGE_ACCESS_SETTINGS);addSetting(r,"Battery settings",Settings.ACTION_BATTERY_SAVER_SETTINGS);Button qr=button("QR scanner — disabled: no verified offline decoder in this release");qr.setEnabled(false);r.addView(qr);SageAppearance.apply(this,s,r);return s;}
    private void refreshInfo(){android.os.BatteryManager b=(android.os.BatteryManager)getSystemService(BATTERY_SERVICE);long free=getFilesDir().getFreeSpace(),total=getFilesDir().getTotalSpace();info.setText("Battery: "+(b==null?"unavailable":b.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)+"%")+"\nStorage: "+free+" free of "+total+" bytes\nClipboard access follows Android foreground restrictions.");}
    private void toggleTorch(){if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},73);return;}try{CameraManager m=(CameraManager)getSystemService(CAMERA_SERVICE);String id=m.getCameraIdList()[0];torch=!torch;m.setTorchMode(id,torch);SageDiagnostics.appendEvent(this,"DEVICE","flashlight="+torch);}catch(Exception e){Toast.makeText(this,"Flashlight unavailable: "+e.getClass().getSimpleName(),Toast.LENGTH_LONG).show();}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==PICK_HASH&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();new Thread(()->{try{java.security.MessageDigest d=java.security.MessageDigest.getInstance("SHA-256");try(java.io.InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new Exception("file unavailable");byte[] b=new byte[65536];int n;while((n=in.read(b))>=0)d.update(b,0,n);}StringBuilder x=new StringBuilder();for(byte q:d.digest())x.append(String.format(java.util.Locale.US,"%02x",q));runOnUiThread(()->set("SHA-256: "+x));}catch(Exception e){runOnUiThread(()->Toast.makeText(this,"Hash failed: "+e.getMessage(),Toast.LENGTH_LONG).show());}}).start();}}
    private String decodeJwt(String value){String[] p=value.split("\\.");if(p.length<2)throw new IllegalArgumentException("JWT requires header.payload");return "Header:\n"+new String(Base64.decode(p[0],Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING),StandardCharsets.UTF_8)+"\n\nPayload:\n"+new String(Base64.decode(p[1],Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING),StandardCharsets.UTF_8)+"\n\nSignature not verified; cracking is not supported.";}
    private String passwordStrength(String value){int score=0;if(value.length()>=12)score++;if(value.length()>=16)score++;if(value.matches(".*[a-z].*")&&value.matches(".*[A-Z].*"))score++;if(value.matches(".*[0-9].*"))score++;if(value.matches(".*[^A-Za-z0-9].*"))score++;String label=score<=1?"weak":score<=3?"moderate":"strong";return "Local estimate: "+label+" ("+score+"/5). Length and uniqueness matter most; this value was not uploaded or stored.";}
    private void addSetting(LinearLayout r,String name,String action){Button b=button(name);b.setOnClickListener(v->startActivity(new Intent(action)));r.addView(b);}private interface Value{String get()throws Exception;}private void safe(Value v){try{set(v.get());}catch(Exception e){Toast.makeText(this,"Invalid input: "+e.getMessage(),Toast.LENGTH_LONG).show();}}private void set(String v){input.setText(v);SageDiagnostics.appendEvent(this,"DEVICE","local utility completed");}
    private static final class Parser{final String s;int p;Parser(String s){this.s=s.replace(" ","");}double parse(){double v=expr();if(p!=s.length())throw new IllegalArgumentException("unexpected character");return v;}double expr(){double v=term();while(p<s.length()&&(s.charAt(p)=='+'||s.charAt(p)=='-')){char o=s.charAt(p++);double q=term();v=o=='+'?v+q:v-q;}return v;}double term(){double v=factor();while(p<s.length()&&(s.charAt(p)=='*'||s.charAt(p)=='/')){char o=s.charAt(p++);double q=factor();v=o=='*'?v*q:v/q;}return v;}double factor(){if(p<s.length()&&s.charAt(p)=='('){p++;double v=expr();if(p>=s.length()||s.charAt(p++)!=')')throw new IllegalArgumentException("missing )");return v;}int a=p;if(p<s.length()&&(s.charAt(p)=='+'||s.charAt(p)=='-'))p++;while(p<s.length()&&(Character.isDigit(s.charAt(p))||s.charAt(p)=='.'))p++;if(a==p)throw new IllegalArgumentException("number expected");return Double.parseDouble(s.substring(a,p));}}
    private Button button(String x){Button b=new Button(this);b.setText(x);return b;}private TextView text(String x,int z){TextView t=new TextView(this);t.setText(x);t.setTextSize(z);return t;}private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
''')

write("SageWorkbenchActivity.java", r'''package com.pineapple.sage;

import android.app.Activity;import android.content.Intent;import android.os.Bundle;import android.view.View;import android.widget.*;

public class SageWorkbenchActivity extends Activity {
    @Override public void onCreate(Bundle b){super.onCreate(b);setTitle("Sage Workbench");setContentView(build());}
    private View build(){ScrollView s=new ScrollView(this);LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setPadding(dp(18),dp(18),dp(18),dp(18));s.addView(r);r.addView(text("SAGE WORKBENCH",30));r.addView(text("Advanced tools stay modular, owner-controlled, logged, and honest about Android limits.",15));card(r,"Repair Center","Diagnose, review evidence and theories, prepare and explicitly export repair packets",SageRepairActivity.class);card(r,"Package Center","Inspect local APK identity, compare Sage updates, and hand approved packages to Android",SagePackageCenterActivity.class);card(r,"Home Lab & Network Map","Confirm a local-only discovery scan, stop it immediately, and compare snapshots",SageNetworkActivity.class);card(r,"Authority & Permissions","See real authority states and open Android's owner-controlled setup screens",SageAuthorityActivity.class);card(r,"Device Tools","Flashlight, battery/storage, calculator, local encoders, and settings shortcuts",SageDeviceToolsActivity.class);SageAppearance.apply(this,s,r);return s;}
    private void card(LinearLayout r,String title,String detail,Class<?> activity){Button b=new Button(this);b.setAllCaps(false);b.setText(title+"\n"+detail);b.setMinHeight(dp(82));b.setOnClickListener(v->startActivity(new Intent(this,activity)));r.addView(b);}
    private TextView text(String x,int z){TextView t=new TextView(this);t.setText(x);t.setTextSize(z);return t;}private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
''')

manifest = ROOT / "app/src/main/AndroidManifest.xml"
replace_once(manifest,
'''    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />''',
'''    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />
    <uses-feature android:name="android.hardware.camera.flash" android:required="false" />''',
"workbench permissions")
replace_once(manifest,
'''        <activity
            android:name=".SageRepairActivity"''',
'''        <activity android:name=".SageWorkbenchActivity" android:exported="false" />
        <activity android:name=".SagePackageCenterActivity" android:exported="false" />
        <activity android:name=".SageNetworkActivity" android:exported="false" />
        <activity android:name=".SageDeviceToolsActivity" android:exported="false" />

        <activity
            android:name=".SageRepairActivity"''',
"workbench activities")

main = JAVA / "MainActivity.java"
replace_once(main,
'''        Button diagnose = makeButton("Diagnose Sage / prepare repair bundle");''',
'''        Button workbench = makeButton("Open Sage Workbench");
        workbench.setOnClickListener(v -> startActivity(new Intent(this, SageWorkbenchActivity.class)));
        root.addView(workbench, spacedSmall());

        Button diagnose = makeButton("Diagnose Sage / prepare repair bundle");''',
"top-level workbench entry")

commands = JAVA / "SageCommandEngine.java"
replace_once(commands,
'''        if (isAny(lower, "diagnose yourself", "diagnose sage", "run self diagnosis")) {''',
'''        if (isAny(lower, "open the workbench", "open workbench", "sage workbench")) {
            return openWorkbench(SageWorkbenchActivity.class, null);
        }
        if (isAny(lower, "inspect this apk", "inspect an apk", "open package center", "install this package")) {
            return openWorkbench(SagePackageCenterActivity.class, null);
        }
        if (isAny(lower, "scan my network")) {
            return openWorkbench(SageNetworkActivity.class, "scan");
        }
        if (isAny(lower, "show my network map", "what devices are on my network", "what changed on my network", "show unknown devices", "is my pc online")) {
            return openWorkbench(SageNetworkActivity.class, null);
        }
        if (isAny(lower, "hash this file", "open device tools")) {
            return openWorkbench(SageDeviceToolsActivity.class, null);
        }

        if (isAny(lower, "diagnose yourself", "diagnose sage", "run self diagnosis")) {''',
"workbench commands")
replace_once(commands,
'''    private Result openRepair(boolean prepareFix) {''',
'''    private Result openWorkbench(Class<?> activity, String mode) {
        Intent intent = new Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (mode != null) intent.putExtra(SageNetworkActivity.EXTRA_MODE, mode);
        try {
            context.startActivity(intent);
            return Result.quiet("Opening Sage Workbench.");
        } catch (Exception error) {
            SageDiagnostics.recordError(context, "Workbench launch failed: " + error);
            return new Result("I could not open the Workbench. Open Sage and tap Sage Workbench.");
        }
    }

    private Result openRepair(boolean prepareFix) {''',
"workbench launcher")

print("Applied Sage Commander 1.25.0 functional Workbench")
