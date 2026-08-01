#!/usr/bin/env python3
"""Add confirmed selected-host private-LAN port, TLS, DNS, and HTTP inspection."""

from pathlib import Path
import sys


INSPECTOR = r'''package com.pineapple.sage;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class SageHostInspector {
    private static final int[] CONSERVATIVE_PORTS = {22,53,80,443,445,631,8080,8443};
    private static final int CONNECT_TIMEOUT_MS = 550;

    private SageHostInspector() {}

    static String inspect(Context context,String ip,SageOperation.Session session)throws Exception{
        if(!SageNetworkScanner.isPrivate(ip+"/32"))
            throw new SecurityException("public or invalid selected host refused");
        if(!savedHost(context,ip))
            throw new SecurityException("select an exact host from Sage's saved private-LAN snapshot");
        InetAddress address=InetAddress.getByName(ip);
        String reverse=address.getCanonicalHostName();
        long reachStarted=System.currentTimeMillis();boolean reachable=address.isReachable(700);
        long latency=System.currentTimeMillis()-reachStarted;
        session.progress("Conservative selected-host port check",0,CONSERVATIVE_PORTS.length+3);
        JSONArray open=new JSONArray();
        for(int index=0;index<CONSERVATIVE_PORTS.length;index++){
            session.checkCancelled();int port=CONSERVATIVE_PORTS[index];
            if(open(ip,port))open.put(port);
            session.progress("Checked selected host port "+port,index+1,CONSERVATIVE_PORTS.length+3);
        }
        String tls="not inspected: 443 and 8443 closed";
        if(contains(open,443)||contains(open,8443)){
            int port=contains(open,443)?443:8443;tls=tls(ip,port);session.checkCancelled();
        }
        session.progress("TLS evidence captured",CONSERVATIVE_PORTS.length+1,CONSERVATIVE_PORTS.length+3);
        String http="not inspected: 80 and 8080 closed";
        if(contains(open,80)||contains(open,8080)){
            int port=contains(open,80)?80:8080;http=http(ip,port);session.checkCancelled();
        }
        session.progress("HTTP headers captured",CONSERVATIVE_PORTS.length+2,CONSERVATIVE_PORTS.length+3);
        JSONObject result=new JSONObject();
        result.put("scope","owner-confirmed private LAN selected host only");
        result.put("ip",ip);result.put("dns_reverse",reverse.equals(ip)?"unresolved":reverse);
        result.put("reachable",reachable);result.put("latency_ms",latency);
        result.put("ports_checked",new JSONArray(Arrays.asList(22,53,80,443,445,631,8080,8443)));
        result.put("open_ports",open);result.put("tls",tls);result.put("http_headers",http);
        result.put("mac_vendor","unavailable through ordinary Android app authority; not claimed");
        result.put("safety","No public scanning, credentials, exploitation, persistence, evasion, hidden scan, destructive action, or denial of service.");
        session.progress("Selected-host report complete",CONSERVATIVE_PORTS.length+3,CONSERVATIVE_PORTS.length+3);
        SageDiagnostics.appendEvent(context,"NETWORK HOST","selected private host="+ip+" open="+open);
        return result.toString(2);
    }

    private static boolean savedHost(Context context,String ip){
        JSONArray values=SageNetworkStore.current(context);
        for(int i=0;i<values.length();i++){
            JSONObject item=values.optJSONObject(i);if(item!=null&&ip.equals(item.optString("ip")))return true;
        }
        return false;
    }
    private static boolean open(String ip,int port){try(Socket socket=new Socket()){
        socket.connect(new InetSocketAddress(ip,port),CONNECT_TIMEOUT_MS);return true;
    }catch(Exception ignored){return false;}}
    private static boolean contains(JSONArray values,int wanted){for(int i=0;i<values.length();i++)if(values.optInt(i)==wanted)return true;return false;}

    private static String tls(String ip,int port){
        try(SSLSocket socket=(SSLSocket)SSLSocketFactory.getDefault().createSocket()){
            socket.connect(new InetSocketAddress(ip,port),1200);socket.setSoTimeout(1800);socket.startHandshake();
            SSLSession session=socket.getSession();StringBuilder out=new StringBuilder();
            out.append("protocol=").append(session.getProtocol()).append(" cipher=").append(session.getCipherSuite());
            java.security.cert.Certificate[] chain=session.getPeerCertificates();
            if(chain.length>0&&chain[0] instanceof X509Certificate){X509Certificate cert=(X509Certificate)chain[0];
                out.append(" subject=").append(cert.getSubjectX500Principal()).append(" issuer=").append(cert.getIssuerX500Principal())
                    .append(" not_before=").append(cert.getNotBefore()).append(" not_after=").append(cert.getNotAfter())
                    .append(" cert_sha256=").append(hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())));}
            return out.toString();
        }catch(SSLPeerUnverifiedException error){return "TLS peer not verified: "+clean(error.getMessage());}
        catch(Exception error){return "TLS inspection failed safely: "+error.getClass().getSimpleName()+": "+clean(error.getMessage());}
    }

    private static String http(String ip,int port){HttpURLConnection connection=null;try{
        connection=(HttpURLConnection)new URL("http",ip,port,"/").openConnection();
        connection.setConnectTimeout(1200);connection.setReadTimeout(1500);connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("HEAD");int status=connection.getResponseCode();StringBuilder out=new StringBuilder("status=").append(status);
        int count=0;for(Map.Entry<String,List<String>> header:connection.getHeaderFields().entrySet()){
            if(header.getKey()!=null&&count++<30)out.append("\n").append(header.getKey()).append(": ").append(header.getValue());}
        return out.toString();
    }catch(Exception error){return "HTTP-header inspection failed safely: "+error.getClass().getSimpleName()+": "+clean(error.getMessage());}
    finally{if(connection!=null)connection.disconnect();}}
    private static String hex(byte[] data){StringBuilder out=new StringBuilder();for(byte b:data)out.append(String.format(java.util.Locale.US,"%02x",b));return out.toString();}
    private static String clean(String value){return value==null?"":value.replace('\n',' ').replace('\r',' ').trim();}
}
'''

ACTIVITY = r'''package com.pineapple.sage;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class SageHostInspectorActivity extends Activity implements SageOperation.Listener {
    private EditText ip;private TextView status,report;private ProgressBar progress;private Button cancel;private SageOperation operation;
    @Override public void onCreate(Bundle state){super.onCreate(state);setTitle("Sage Selected Host Inspector");setContentView(build());}
    private View build(){ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(18),dp(18),dp(22));scroll.addView(root);
        root.addView(text("PRIVATE-LAN SELECTED HOST",27));root.addView(text("Enter one exact IP already present in Sage's saved private-LAN snapshot. After confirmation, Sage checks only eight conservative ports and records reverse DNS, reachability, latency, TLS certificate evidence, and HTTP headers where available.",14));
        root.addView(text("Public targets, broad port ranges, credentials, exploits, persistence, evasion, hidden scans, destructive actions, and denial of service are refused.",14));
        ip=new EditText(this);ip.setHint("Exact private IP already present in Sage's saved map");root.addView(ip);
        Button run=button("Confirm selected-host inspection");run.setOnClickListener(v->confirm());root.addView(run);
        cancel=button("Cancel selected-host inspection");cancel.setEnabled(false);cancel.setOnClickListener(v->{if(operation!=null)operation.cancel();});root.addView(cancel);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(11);root.addView(progress);status=text("Idle",13);root.addView(status);report=text("",13);report.setTextIsSelectable(true);root.addView(report);SageAppearance.apply(this,scroll,root);return scroll;}
    private void confirm(){String target=ip.getText().toString().trim();if(!SageNetworkScanner.isPrivate(target+"/32")){Toast.makeText(this,"Only an exact private IPv4 host is allowed.",Toast.LENGTH_LONG).show();return;}
        SageConfirmation.require(this,"Inspect one saved private-LAN host",target,"INTERNET; exact host must already exist in Sage's saved snapshot","Private-LAN packets only","Cancel immediately; saved network snapshots are unchanged",()->start(target));}
    private void start(String target){cancel.setEnabled(true);progress.setProgress(0);operation=new SageOperation(this,"selected private host inspection");operation.start(session->SageHostInspector.inspect(this,target,session),this);}
    @Override public void onProgress(String stage,int done,int total,long elapsed){status.setText(stage+" • "+elapsed+" ms");progress.setMax(total);progress.setProgress(done);}
    @Override public void onComplete(String result,long elapsed){cancel.setEnabled(false);status.setText("Inspection complete • "+elapsed+" ms");report.setText(result);}
    @Override public void onError(String detail,long elapsed){cancel.setEnabled(false);status.setText("Inspection stopped: "+detail+" • "+elapsed+" ms");}
    private Button button(String v){Button b=new Button(this);b.setText(v);b.setAllCaps(false);return b;}private TextView text(String v,int s){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);return t;}private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
'''


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text=path.read_text();count=text.count(old)
    if count!=1:raise SystemExit(f"expected one {label}, found {count}")
    path.write_text(text.replace(old,new,1))


def main()->None:
    if len(sys.argv)!=2:raise SystemExit("usage: network_operations_v1_28.py <reconstructed-source>")
    root=Path(sys.argv[1]);java=root/"app/src/main/java/com/pineapple/sage"
    (java/"SageHostInspector.java").write_text(INSPECTOR);(java/"SageHostInspectorActivity.java").write_text(ACTIVITY)
    manifest=root/"app/src/main/AndroidManifest.xml"
    replace_once(manifest,'        <activity android:name=".SageNetworkActivity" android:exported="false" />',
        '        <activity android:name=".SageNetworkActivity" android:exported="false" />\n        <activity android:name=".SageHostInspectorActivity" android:exported="false" />',"host inspector manifest")
    toolbelt=java/"SageToolbeltActivity.java"
    replace_once(toolbelt,'''        card(root, "Network Snapshot",
                "After owner confirmation, conservatively discover only the current private subnet; cancel, save, and compare new or missing devices.",
                SageNetworkActivity.class);''','''        card(root, "Network Snapshot",
                "After owner confirmation, conservatively discover only the current private subnet; cancel, save, and compare new or missing devices.",
                SageNetworkActivity.class);
        card(root, "Selected Host Inspector",
                "Confirm one saved private-LAN host for conservative ports, reverse DNS, latency, TLS, and HTTP-header evidence.",
                SageHostInspectorActivity.class);''',"Toolbelt host card")
    redqueen=java/"SageRedQueenActivity.java"
    replace_once(redqueen,'''        functional(root, "Network Operations", "Confirmed private-LAN snapshot only",
                SageNetworkActivity.class);''','''        functional(root, "Network Operations", "Confirmed private-LAN snapshot plus exact selected-host DNS, conservative ports, TLS, and HTTP headers",
                SageNetworkActivity.class);
        functional(root, "Selected Host Inspector", "One saved private host; conservative ports and protocol evidence",
                SageHostInspectorActivity.class);''',"Red Queen host card")
    command=java/"SageCommandEngine.java"
    replace_once(command,'''        if (isAny(lower, "show my network map", "what devices are on my network", "what changed on my network", "show unknown devices", "is my pc online")) {
            return openWorkbench(SageNetworkActivity.class, null);
        }''','''        if (isAny(lower, "show my network map", "what devices are on my network", "what changed on my network", "show unknown devices", "is my pc online")) {
            return openWorkbench(SageNetworkActivity.class, null);
        }
        if (isAny(lower, "inspect selected host", "inspect this network host", "check this private ip")) {
            return openWorkbench(SageHostInspectorActivity.class, null);
        }''',"host voice route")
    print("Applied Sage 1.28 selected-host private Network Operations")


if __name__=="__main__":main()
