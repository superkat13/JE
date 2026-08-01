#!/usr/bin/env python3
"""Upgrade Package Lab and add a functional, non-executing File Lab."""

from pathlib import Path
import sys


PACKAGE_INSPECTOR = r'''package com.pineapple.sage;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class SagePackageInspector {
    static final class Report {
        final File cachedFile;
        final Uri originalUri;
        final String packageName, versionName, signerSha256, fileSha256, source;
        final String installedComparison;
        final long versionCode, minSdk, targetSdk, size;
        final String[] permissions, features, components, exportedSurfaces;
        final String[] resources, assets, nativeLibraries;
        final boolean installed, packageMatch, signerMatch, downgrade, allowlisted;

        Report(File file, Uri uri, String pkg, String name, long code, long minSdk,
               long targetSdk, String[] permissions, String[] features, String[] components,
               String[] exportedSurfaces, String[] resources, String[] assets,
               String[] nativeLibraries, String signer, String hash, long size, String source,
               boolean installed, boolean packageMatch, boolean signerMatch, boolean downgrade,
               boolean allowlisted, String installedComparison) {
            this.cachedFile=file; this.originalUri=uri; this.packageName=pkg;
            this.versionName=name; this.versionCode=code; this.minSdk=minSdk;
            this.targetSdk=targetSdk; this.permissions=permissions; this.features=features;
            this.components=components; this.exportedSurfaces=exportedSurfaces;
            this.resources=resources; this.assets=assets; this.nativeLibraries=nativeLibraries;
            this.signerSha256=signer; this.fileSha256=hash; this.size=size; this.source=source;
            this.installed=installed; this.packageMatch=packageMatch; this.signerMatch=signerMatch;
            this.downgrade=downgrade; this.allowlisted=allowlisted;
            this.installedComparison=installedComparison;
        }

        String riskSummary() {
            if (!packageMatch) return "BLOCKED: uninstalled package identity is not on the owner's trusted signer list.";
            if (installed && !signerMatch) return "BLOCKED: signing certificate mismatch.";
            if (downgrade) return "BLOCKED: candidate is older than the installed version.";
            if (exportedSurfaces.length > 0) return "Identity checks passed. Review exported surfaces before Android installer confirmation.";
            return "Identity checks passed. Android will still show its required installer confirmation.";
        }

        boolean safeForInstall() {
            return packageMatch && (!installed || signerMatch) && !downgrade;
        }

        String display() {
            StringBuilder out=new StringBuilder();
            out.append("Package: ").append(packageName).append('\n');
            out.append("Version: ").append(versionName).append(" (").append(versionCode).append(")\n");
            out.append("Minimum Android API: ").append(minSdk).append('\n');
            out.append("Target Android API: ").append(targetSdk).append('\n');
            out.append("Signer certificate SHA-256: ").append(signerSha256).append('\n');
            out.append("File SHA-256: ").append(fileSha256).append('\n');
            out.append("Size: ").append(size).append(" bytes\n");
            out.append("Install source: ").append(source).append('\n');
            append(out,"Requested permissions",permissions);
            append(out,"Declared features",features);
            append(out,"Components",components);
            append(out,"Exported surfaces",exportedSurfaces);
            append(out,"Resources",resources);
            append(out,"Assets",assets);
            append(out,"Native libraries",nativeLibraries);
            out.append("\nInstalled-versus-candidate comparison: ").append(installedComparison);
            out.append("\n\nTrusted signer-list match: ").append(allowlisted);
            out.append("\nRisk summary: ").append(riskSummary());
            return out.toString();
        }

        JSONObject toJson() throws Exception {
            JSONObject j=new JSONObject();
            j.put("package",packageName); j.put("version_name",versionName);
            j.put("version_code",versionCode); j.put("min_sdk",minSdk);
            j.put("target_sdk",targetSdk); j.put("signer_sha256",signerSha256);
            j.put("file_sha256",fileSha256); j.put("size",size); j.put("source",source);
            j.put("safe_for_install",safeForInstall()); j.put("allowlisted",allowlisted);
            j.put("risk",riskSummary()); j.put("installed_comparison",installedComparison);
            j.put("permissions",new JSONArray(permissions));
            j.put("features",new JSONArray(features));
            j.put("components",new JSONArray(components));
            j.put("exported_surfaces",new JSONArray(exportedSurfaces));
            j.put("resources",new JSONArray(resources));
            j.put("assets",new JSONArray(assets));
            j.put("native_libraries",new JSONArray(nativeLibraries));
            return j;
        }

        private static void append(StringBuilder out,String title,String[] values){
            out.append("\n").append(title).append(": ");
            if(values.length==0) out.append("none declared");
            for(String value:values) out.append("\n • ").append(value);
            out.append('\n');
        }
    }

    static Report inspect(Context context, Uri uri, SageOperation.Session session) throws Exception {
        session.progress("Copying selected APK into private inspection cache",0,6);
        File dir=new File(context.getCacheDir(),"package-inspection");
        if(!dir.exists()&&!dir.mkdirs()) throw new Exception("cache unavailable");
        File apk=new File(dir,"candidate.apk");
        try(InputStream in=context.getContentResolver().openInputStream(uri);
            FileOutputStream out=new FileOutputStream(apk)) {
            if(in==null) throw new Exception("selected file could not be opened");
            byte[] b=new byte[65536]; int n;
            while((n=in.read(b))>=0){session.checkCancelled();out.write(b,0,n);}
        }
        session.progress("Parsing Android package, features, and components",1,6);
        PackageManager pm=context.getPackageManager();
        int flags=PackageManager.GET_PERMISSIONS | PackageManager.GET_ACTIVITIES
                | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS
                | PackageManager.GET_PROVIDERS | PackageManager.GET_CONFIGURATIONS
                | PackageManager.GET_META_DATA
                | (android.os.Build.VERSION.SDK_INT>=28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
        PackageInfo candidate=pm.getPackageArchiveInfo(apk.getAbsolutePath(),flags);
        if(candidate==null||candidate.packageName==null)
            throw new Exception("file is not a readable Android APK");
        long code=android.os.Build.VERSION.SDK_INT>=28
                ?candidate.getLongVersionCode():candidate.versionCode;
        long min=candidate.applicationInfo==null?0:candidate.applicationInfo.minSdkVersion;
        long target=candidate.applicationInfo==null?0:candidate.applicationInfo.targetSdkVersion;
        String signer=signer(candidate);
        String[] features=features(candidate.reqFeatures);
        List<String> components=new ArrayList<>();
        List<String> exported=new ArrayList<>();
        addComponents("activity",candidate.activities,components,exported);
        addComponents("service",candidate.services,components,exported);
        addComponents("receiver",candidate.receivers,components,exported);
        addComponents("provider",candidate.providers,components,exported);

        session.progress("Inventorying resources, assets, and native libraries",2,6);
        List<String> resources=new ArrayList<>(), assets=new ArrayList<>(), natives=new ArrayList<>();
        inventory(apk,resources,assets,natives,session);
        session.progress("Calculating APK SHA-256",3,6);
        String hash=sha256(apk,session);
        session.progress("Comparing installed identity and trusted signer list",4,6);
        PackageInfo installed=null;
        try { installed=pm.getPackageInfo(candidate.packageName,flags); }
        catch(PackageManager.NameNotFoundException ignored) {}
        boolean isInstalled=installed!=null;
        boolean allowlisted=context.getSharedPreferences("sage_workbench",Context.MODE_PRIVATE)
                .getStringSet("trusted_package_identities", Collections.emptySet())
                .contains(candidate.packageName+"|"+signer);
        boolean pkgMatch=isInstalled||context.getPackageName().equals(candidate.packageName)||allowlisted;
        boolean signerMatch=!isInstalled||signer.equals(signer(installed));
        long installedCode=isInstalled?(android.os.Build.VERSION.SDK_INT>=28
                ?installed.getLongVersionCode():installed.versionCode):-1;
        boolean downgrade=isInstalled&&code<installedCode;
        String installedComparison=isInstalled
                ? "installed="+String.valueOf(installed.versionName)+" ("+installedCode+") signer="+signer(installed)
                    +"; candidate="+String.valueOf(candidate.versionName)+" ("+code+") signer="+signer
                    +"; package_match=true signer_match="+signerMatch+" downgrade="+downgrade
                : "not installed; candidate identity must be Sage or an owner-approved exact package+signer allowlist entry";
        session.progress("Package report complete",6,6);
        SageDiagnostics.appendEvent(context,"PACKAGE","inspected package="+candidate.packageName
                +" version="+code+" signer_match="+signerMatch+" exported="+exported.size()
                +" sha256="+hash);
        return new Report(apk,uri,candidate.packageName,String.valueOf(candidate.versionName),
                code,min,target,candidate.requestedPermissions==null?new String[0]:candidate.requestedPermissions,
                features,components.toArray(new String[0]),exported.toArray(new String[0]),
                resources.toArray(new String[0]),assets.toArray(new String[0]),natives.toArray(new String[0]),
                signer,hash,apk.length(),uri.toString(),isInstalled,pkgMatch,signerMatch,downgrade,
                allowlisted,installedComparison);
    }

    private static void addComponents(String type,ComponentInfo[] values,List<String> all,List<String> exported){
        if(values==null)return;
        for(ComponentInfo value:values){
            String permission="";
            if(value instanceof ActivityInfo)permission=((ActivityInfo)value).permission;
            else if(value instanceof ServiceInfo)permission=((ServiceInfo)value).permission;
            else if(value instanceof ProviderInfo)permission=((ProviderInfo)value).readPermission;
            String item=type+":"+value.name+" exported="+value.exported
                    +(permission==null||permission.isEmpty()?"":" permission="+permission);
            all.add(item);if(value.exported)exported.add(item);
        }
    }

    private static String[] features(FeatureInfo[] values){
        if(values==null)return new String[0];
        List<String> out=new ArrayList<>();
        for(FeatureInfo value:values){
            String name=value.name==null?"OpenGL ES 0x"+Integer.toHexString(value.reqGlEsVersion):value.name;
            out.add(name+" required="+((value.flags&FeatureInfo.FLAG_REQUIRED)!=0));
        }
        return out.toArray(new String[0]);
    }

    private static void inventory(File apk,List<String> resources,List<String> assets,
                                  List<String> natives,SageOperation.Session session)throws Exception{
        try(ZipFile zip=new ZipFile(apk)){
            Enumeration<? extends ZipEntry> entries=zip.entries();int seen=0;
            while(entries.hasMoreElements()){
                session.checkCancelled();String name=entries.nextElement().getName();seen++;
                if(name.startsWith("lib/")&&!name.endsWith("/")) addLimited(natives,name);
                else if(name.startsWith("assets/")&&!name.endsWith("/")) addLimited(assets,name);
                else if((name.startsWith("res/")||name.equals("resources.arsc"))&&!name.endsWith("/"))
                    addLimited(resources,name);
            }
            if(seen==0)throw new Exception("APK ZIP contains no entries");
        }
    }

    private static void addLimited(List<String> values,String value){
        if(values.size()<200)values.add(value);
        else if(values.size()==200)values.add("… additional entries omitted from display");
    }

    private static String signer(PackageInfo info) throws Exception {
        Signature signature=null;
        if(android.os.Build.VERSION.SDK_INT>=28&&info.signingInfo!=null){
            Signature[] values=info.signingInfo.hasMultipleSigners()
                    ?info.signingInfo.getApkContentsSigners():info.signingInfo.getSigningCertificateHistory();
            if(values!=null&&values.length>0)signature=values[0];
        }else if(info.signatures!=null&&info.signatures.length>0)signature=info.signatures[0];
        if(signature==null)return "unavailable";
        return hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()));
    }

    static String sha256(File file,SageOperation.Session session)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(FileInputStream in=new FileInputStream(file)){
            byte[] b=new byte[65536];int n;
            while((n=in.read(b))>=0){session.checkCancelled();digest.update(b,0,n);}
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] data){
        StringBuilder s=new StringBuilder();
        for(byte b:data)s.append(String.format(java.util.Locale.US,"%02x",b));
        return s.toString();
    }
}
'''

FILE_LAB = r'''package com.pineapple.sage;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class SageFileLabActivity extends Activity implements SageOperation.Listener {
    private static final int PICK_INSPECT = 12810;
    private static final int PICK_COMPARE = 12811;
    private static final int SAMPLE_LIMIT = 1024 * 1024;
    private TextView status, report;
    private ProgressBar progress;
    private Button cancel, copy, share, preview, compare;
    private SageOperation operation;
    private Analysis baseline;
    private Uri baselineUri;
    private String resultText="";

    static final class Analysis {
        String name,mime,extension,detectedType,sha256,sha1,md5,strings;
        long size,lastModified;
        double entropy;
        boolean typeMismatch,duplicate,previewSafe;

        String report(){
            return "FILE LAB REPORT\nName: "+name+"\nDeclared MIME: "+mime
                    +"\nExtension: "+extension+"\nDetected type: "+detectedType
                    +"\nType mismatch: "+typeMismatch+"\nSize: "+size+" bytes"
                    +"\nLast modified: "+(lastModified<=0?"unavailable":DateFormat.getDateTimeInstance().format(new Date(lastModified)))
                    +"\nSHA-256: "+sha256+"\nSHA-1 (legacy, not trusted for identity): "+sha1
                    +"\nMD5 (legacy, not trusted for identity): "+md5
                    +"\nSample Shannon entropy: "+String.format(Locale.US,"%.4f bits/byte",entropy)
                    +"\nPacking indicator: "+(entropy>7.5?"high entropy; compressed/encrypted/packed content possible":"no high-entropy indicator in sample")
                    +"\nKnown duplicate SHA-256: "+duplicate
                    +"\nSafe preview supported: "+previewSafe
                    +"\n\nPrintable strings preview (sample only):\n"+strings
                    +"\n\nSafety: this tool never executes the inspected file.";
        }
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);setTitle("Sage File Lab");setContentView(build());
    }

    private View build(){
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(18),dp(18),dp(20));
        scroll.addView(root);root.addView(text("FILE LAB",28));
        root.addView(text("Validate type, MIME and extension; calculate SHA-256 plus clearly labeled legacy hashes; inspect metadata, timestamps, strings, entropy, duplicates, and comparisons without executing the file.",14));
        Button choose=button("Choose file to inspect safely");choose.setOnClickListener(v->choose(PICK_INSPECT));root.addView(choose);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);root.addView(progress);
        status=text("No file selected.",14);root.addView(status);
        cancel=button("Cancel inspection");cancel.setEnabled(false);cancel.setOnClickListener(v->{if(operation!=null)operation.cancel();});root.addView(cancel);
        compare=button("Compare with another file");compare.setEnabled(false);compare.setOnClickListener(v->choose(PICK_COMPARE));root.addView(compare);
        preview=button("Open safe preview");preview.setEnabled(false);preview.setOnClickListener(v->safePreview());root.addView(preview);
        report=text("",13);report.setTextIsSelectable(true);root.addView(report);
        copy=button("Copy report");copy.setEnabled(false);copy.setOnClickListener(v->copy());root.addView(copy);
        share=button("Export/share report");share.setEnabled(false);share.setOnClickListener(v->share());root.addView(share);
        SageAppearance.apply(this,scroll,root);return scroll;
    }

    private void choose(int request){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");startActivityForResult(intent,request);
    }

    @Override protected void onActivityResult(int request,int response,Intent data){
        super.onActivityResult(request,response,data);
        if(response!=RESULT_OK||data==null||data.getData()==null)return;
        if(request==PICK_INSPECT)inspect(data.getData(),false);
        else if(request==PICK_COMPARE&&baseline!=null)inspect(data.getData(),true);
    }

    private void inspect(Uri uri,boolean comparison){
        cancel.setEnabled(true);copy.setEnabled(false);share.setEnabled(false);preview.setEnabled(false);
        progress.setProgress(0);operation=new SageOperation(this,comparison?"file comparison":"file lab inspection");
        operation.start(session->{
            Analysis value=analyze(uri,session);
            if(comparison){
                String comparisonReport=compare(baseline,value);resultText=baseline.report()+"\n\n"+comparisonReport;
            }else{
                baseline=value;baselineUri=uri;resultText=value.report();
            }
            return resultText;
        },this);
    }

    private Analysis analyze(Uri uri,SageOperation.Session session)throws Exception{
        Analysis value=new Analysis();metadata(uri,value);session.progress("Reading and hashing owner-selected file",1,5);
        MessageDigest sha256=MessageDigest.getInstance("SHA-256"),sha1=MessageDigest.getInstance("SHA-1"),md5=MessageDigest.getInstance("MD5");
        ByteArrayOutputStream sample=new ByteArrayOutputStream();long total=0;
        try(InputStream input=getContentResolver().openInputStream(uri)){
            if(input==null)throw new Exception("Android could not open the selected file");
            byte[] buffer=new byte[128*1024];int count;
            while((count=input.read(buffer))>=0){session.checkCancelled();if(count==0)continue;
                sha256.update(buffer,0,count);sha1.update(buffer,0,count);md5.update(buffer,0,count);
                int copy=Math.min(count,SAMPLE_LIMIT-sample.size());if(copy>0)sample.write(buffer,0,copy);
                total+=count;session.progress("Read "+total+" bytes",2,5);
            }
        }
        value.size=total;value.sha256=hex(sha256.digest());value.sha1=hex(sha1.digest());value.md5=hex(md5.digest());
        byte[] bytes=sample.toByteArray();value.detectedType=detect(bytes);value.entropy=entropy(bytes);
        value.strings=strings(bytes);value.typeMismatch=mismatch(value.extension,value.mime,value.detectedType);
        value.previewSafe=safeMime(value.mime,value.detectedType);
        session.progress("Checking local duplicate history",4,5);value.duplicate=rememberHash(value.sha256,value.name,total);
        session.progress("File report complete",5,5);
        SageDiagnostics.appendEvent(this,"FILE LAB","name="+value.name+" size="+total+" sha256="+value.sha256+" mismatch="+value.typeMismatch);
        return value;
    }

    private void metadata(Uri uri,Analysis value){
        value.mime=String.valueOf(getContentResolver().getType(uri));value.name="unknown";value.size=-1;value.lastModified=-1;
        try(Cursor cursor=getContentResolver().query(uri,null,null,null,null)){
            if(cursor!=null&&cursor.moveToFirst()){
                int name=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME),size=cursor.getColumnIndex(OpenableColumns.SIZE);
                int modified=cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if(name>=0&&!cursor.isNull(name))value.name=cursor.getString(name);
                if(size>=0&&!cursor.isNull(size))value.size=cursor.getLong(size);
                if(modified>=0&&!cursor.isNull(modified))value.lastModified=cursor.getLong(modified);
            }
        }catch(Exception ignored){}
        int dot=value.name.lastIndexOf('.');value.extension=dot<0?"none":value.name.substring(dot+1).toLowerCase(Locale.US);
    }

    private boolean rememberHash(String hash,String name,long size){
        android.content.SharedPreferences p=getSharedPreferences("sage_file_lab_history",MODE_PRIVATE);
        Set<String> current=new HashSet<>(p.getStringSet("hashes",new HashSet<>()));boolean duplicate=false;
        for(String item:current)if(item.startsWith(hash+"|")){duplicate=true;break;}
        current.add(hash+"|"+name+"|"+size+"|"+System.currentTimeMillis());
        while(current.size()>250)current.remove(current.iterator().next());p.edit().putStringSet("hashes",current).apply();return duplicate;
    }

    private static String compare(Analysis left,Analysis right){
        return "FILE COMPARISON\nSame SHA-256: "+left.sha256.equals(right.sha256)
                +"\nSize difference: "+(right.size-left.size)+" bytes"
                +"\nDeclared MIME: "+left.mime+" → "+right.mime
                +"\nDetected type: "+left.detectedType+" → "+right.detectedType
                +"\nEntropy change: "+String.format(Locale.US,"%.4f",right.entropy-left.entropy)
                +"\nCompared file SHA-256: "+right.sha256;
    }

    private static String detect(byte[] b){
        if(starts(b,0x50,0x4b,0x03,0x04))return "ZIP/APK container";
        if(starts(b,0x89,0x50,0x4e,0x47))return "PNG image";
        if(starts(b,0xff,0xd8,0xff))return "JPEG image";
        if(starts(b,0x25,0x50,0x44,0x46))return "PDF document";
        if(b.length>12&&b[4]=='f'&&b[5]=='t'&&b[6]=='y'&&b[7]=='p')return "ISO media (MP4/MOV)";
        if(starts(b,0x7f,0x45,0x4c,0x46))return "ELF executable/library";
        int printable=0;for(byte v:b)if(v==9||v==10||v==13||(v>=32&&v<127))printable++;
        return b.length>0&&printable>=(b.length*9/10)?"plain text":"unknown binary";
    }

    private static boolean starts(byte[] b,int...v){if(b.length<v.length)return false;for(int i=0;i<v.length;i++)if((b[i]&255)!=v[i])return false;return true;}
    private static boolean mismatch(String extension,String mime,String detected){
        String all=(extension+" "+mime).toLowerCase(Locale.US);
        if(detected.contains("PNG"))return !all.contains("png");if(detected.contains("JPEG"))return !(all.contains("jpg")||all.contains("jpeg"));
        if(detected.contains("PDF"))return !all.contains("pdf");if(detected.contains("ZIP/APK"))return !(all.contains("zip")||all.contains("apk")||all.contains("archive"));
        if(detected.contains("ISO media"))return !(all.contains("mp4")||all.contains("mov")||all.contains("video"));return false;
    }
    private static boolean safeMime(String mime,String detected){
        String m=mime==null?"":mime.toLowerCase(Locale.US);return m.startsWith("image/")||m.startsWith("audio/")||m.startsWith("video/")
                ||m.equals("application/pdf")||m.startsWith("text/")||detected.equals("plain text");
    }
    private static double entropy(byte[] b){if(b.length==0)return 0;long[] counts=new long[256];for(byte v:b)counts[v&255]++;
        double e=0;for(long c:counts)if(c>0){double p=(double)c/b.length;e-=p*(Math.log(p)/Math.log(2));}return e;}
    private static String strings(byte[] b){StringBuilder out=new StringBuilder(),run=new StringBuilder();int found=0;
        for(byte v:b){int x=v&255;if(x>=32&&x<127){if(run.length()<120)run.append((char)x);}else{if(run.length()>=4&&found++<100)out.append(run).append('\n');run.setLength(0);}}
        if(out.length()==0)return "No printable strings found in sample.";return out.toString().trim();}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.US,"%02x",v));return s.toString();}

    @Override public void onProgress(String stage,int completed,int total,long elapsed){status.setText(stage+" • "+elapsed+" ms");progress.setProgress(total<=0?0:completed*1000/total);}
    @Override public void onComplete(String value,long elapsed){cancel.setEnabled(false);progress.setProgress(1000);report.setText(value);status.setText("Inspection complete • "+elapsed+" ms");copy.setEnabled(true);share.setEnabled(true);compare.setEnabled(baseline!=null);preview.setEnabled(baseline!=null&&baseline.previewSafe);}
    @Override public void onError(String detail,long elapsed){cancel.setEnabled(false);status.setText("Inspection stopped: "+detail+" • "+elapsed+" ms");}

    private void safePreview(){if(baseline==null||baselineUri==null||!baseline.previewSafe)return;Intent view=new Intent(Intent.ACTION_VIEW,baselineUri);
        view.setDataAndType(baselineUri,baseline.mime);view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivity(view);}catch(Exception e){status.setText("No safe preview application is available.");}}
    private void copy(){ClipboardManager c=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);if(c!=null)c.setPrimaryClip(ClipData.newPlainText("Sage File Lab report",resultText));}
    private void share(){Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,resultText);startActivity(Intent.createChooser(i,"Export Sage File Lab report"));}
    private Button button(String v){Button b=new Button(this);b.setText(v);b.setAllCaps(false);return b;}
    private TextView text(String v,int s){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);return t;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
'''


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected one {label}, found {count}")
    path.write_text(text.replace(old, new, 1))


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: package_file_labs_v1_28.py <reconstructed-source>")
    root = Path(sys.argv[1])
    java = root / "app/src/main/java/com/pineapple/sage"
    (java / "SagePackageInspector.java").write_text(PACKAGE_INSPECTOR)
    (java / "SageFileLabActivity.java").write_text(FILE_LAB)

    manifest = root / "app/src/main/AndroidManifest.xml"
    replace_once(manifest,
        '        <activity android:name=".SageFileHasherActivity" android:exported="false" />',
        '        <activity android:name=".SageFileHasherActivity" android:exported="false" />\n'
        '        <activity android:name=".SageFileLabActivity" android:exported="false" />',
        "File Lab manifest")

    toolbelt = java / "SageToolbeltActivity.java"
    replace_once(toolbelt,
        '''        card(root, "File Hasher",
                "Calculate SHA-256 and byte size locally, with progress, cancellation, copy, and share.",
                SageFileHasherActivity.class);''',
        '''        card(root, "File Lab",
                "Validate MIME/extension/type, hashes, timestamps, strings, entropy, duplicates, safe preview, comparison, and export without execution.",
                SageFileLabActivity.class);''', "Toolbelt File Lab")

    redqueen = java / "SageRedQueenActivity.java"
    replace_once(redqueen,
        '''        functional(root, "File Lab", "Owner-selected local file hashing with cancellation and export",
                SageFileHasherActivity.class);''',
        '''        functional(root, "File Lab", "Type validation, hashes, metadata, entropy, strings, duplicate and comparison reports",
                SageFileLabActivity.class);''', "Red Queen File Lab")

    command = java / "SageCommandEngine.java"
    replace_once(command,
        '''        if (isAny(lower, "open the file hasher", "open file hasher", "hash this file")) {
            return openWorkbench(SageFileHasherActivity.class, null);
        }''',
        '''        if (isAny(lower, "open the file hasher", "open file hasher", "hash this file",
                "open file lab", "inspect this file", "compare these files")) {
            return openWorkbench(SageFileLabActivity.class, null);
        }''', "File Lab voice route")
    print("Applied Sage 1.28 Package Lab and File Lab upgrades")


if __name__ == "__main__":
    main()
