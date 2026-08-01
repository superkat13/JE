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


write("SageForgeStore.java", r'''package com.pineapple.sage;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SageForgeStore {
    private static final String PREFS="sage_forge";
    private static final String KEY_ALIAS="sage_forge_pairing_token_v1";
    private SageForgeStore() {}

    static void savePairing(Context context,String url,String pin,String deviceId,String token)throws Exception{
        SecretKey key=key();Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);
        byte[] encrypted=cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        String protectedToken=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(encrypted,Base64.NO_WRAP);
        prefs(context).edit().putString("url",url).putString("pin",normalizePin(pin))
                .putString("device_id",deviceId).putString("protected_token",protectedToken).apply();
    }
    static boolean isPaired(Context c){return !url(c).isEmpty()&&!pin(c).isEmpty()&&!prefs(c).getString("protected_token","").isEmpty();}
    static String url(Context c){return prefs(c).getString("url","");}
    static String pin(Context c){return prefs(c).getString("pin","");}
    static String deviceId(Context c){return prefs(c).getString("device_id","");}
    static String token(Context c)throws Exception{
        String value=prefs(c).getString("protected_token","");String[] parts=value.split(":",2);if(parts.length!=2)throw new Exception("paired token is unavailable");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    static void saveJob(Context c,String jobId){prefs(c).edit().putString("active_job",jobId).apply();}
    static String activeJob(Context c){return prefs(c).getString("active_job","");}
    static void saveResult(Context c,String result){prefs(c).edit().putString("last_result",result.length()>120000?result.substring(0,120000):result).remove("active_job").apply();}
    static String lastResult(Context c){return prefs(c).getString("last_result","");}
    static void clear(Context c){prefs(c).edit().remove("url").remove("pin").remove("device_id").remove("protected_token").remove("active_job").apply();try{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias(KEY_ALIAS))store.deleteEntry(KEY_ALIAS);}catch(Exception ignored){}}
    static String normalizePin(String value){String pin=value.toLowerCase(java.util.Locale.US).replace(":","").replace(" ","").trim();if(!pin.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("certificate SHA-256 must contain exactly 64 hexadecimal characters");return pin;}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    private static SecretKey key()throws Exception{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias(KEY_ALIAS))return (SecretKey)store.getKey(KEY_ALIAS,null);KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return generator.generateKey();}
}
''')

write("SageForgeClient.java", r'''package com.pineapple.sage;

import android.content.Context;import android.os.Handler;import android.os.Looper;import org.json.JSONObject;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.security.*;import java.security.cert.*;import java.util.Locale;import java.util.UUID;import java.util.concurrent.*;import javax.net.ssl.*;

final class SageForgeClient {
    interface Callback{void complete(JSONObject value);void failed(String detail);}
    private static final ExecutorService IO=Executors.newCachedThreadPool();private final Context context;private final Handler main=new Handler(Looper.getMainLooper());
    SageForgeClient(Context context){this.context=context.getApplicationContext();}
    void pair(String url,String pin,String code,String deviceName,Callback callback){JSONObject body=new JSONObject();try{body.put("pairing_code",code);body.put("device_name",deviceName);}catch(Exception e){fail(callback,e);return;}send(url,pin,null,"POST","/v1/pair",body,callback);}
    void startSystemInfo(Callback callback){try{JSONObject approval=new JSONObject().put("surface","sage_commander").put("action","Read approved Dell system information");JSONObject body=new JSONObject().put("tool_id","system.info").put("input",new JSONObject()).put("owner_approved",true).put("approval_context",approval);authenticated("POST","/v1/jobs",body,callback);}catch(Exception e){fail(callback,e);}}
    void job(String jobId,Callback callback){authenticated("GET","/v1/jobs/"+safeId(jobId),null,callback);}
    void cancel(String jobId,Callback callback){authenticated("POST","/v1/jobs/"+safeId(jobId)+"/cancel",new JSONObject(),callback);}
    void revoke(Callback callback){authenticated("POST","/v1/devices/current/revoke",new JSONObject(),callback);}
    private void authenticated(String method,String path,JSONObject body,Callback callback){try{send(SageForgeStore.url(context),SageForgeStore.pin(context),SageForgeStore.token(context),method,path,body,callback);}catch(Exception e){fail(callback,e);}}
    private void send(String base,String pin,String token,String method,String path,JSONObject body,Callback callback){IO.submit(()->{HttpsURLConnection connection=null;try{URL origin=new URL(base);if(!"https".equals(origin.getProtocol())||!origin.getPath().matches("/?"))throw new Exception("Forge address must be an HTTPS origin with no path");String normalized=SageForgeStore.normalizePin(pin);SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,new TrustManager[]{new PinTrust(normalized)},new SecureRandom());URL endpoint=new URL(base.replaceAll("/$","")+path);connection=(HttpsURLConnection)endpoint.openConnection();connection.setSSLSocketFactory(tls.getSocketFactory());connection.setConnectTimeout(7000);connection.setReadTimeout(12000);connection.setRequestMethod(method);connection.setRequestProperty("Accept","application/json");connection.setUseCaches(false);if(token!=null){connection.setRequestProperty("Authorization","SageToken "+token);connection.setRequestProperty("X-Sage-Timestamp",String.valueOf(System.currentTimeMillis()/1000L));connection.setRequestProperty("X-Sage-Nonce",UUID.randomUUID().toString().replace("-",""));}if(body!=null){byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setDoOutput(true);connection.setFixedLengthStreamingMode(bytes.length);connection.setRequestProperty("Content-Type","application/json");try(OutputStream out=connection.getOutputStream()){out.write(bytes);}}int status=connection.getResponseCode();InputStream stream=status>=200&&status<300?connection.getInputStream():connection.getErrorStream();String response=read(stream);JSONObject value=new JSONObject(response);if(status<200||status>=300)throw new Exception("Forge rejected request ("+status+"): "+value.optString("message","unknown error"));main.post(()->callback.complete(value));}catch(Exception error){fail(callback,error);}finally{if(connection!=null)connection.disconnect();}});}
    private void fail(Callback callback,Exception error){String message=error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage());main.post(()->callback.failed(message));}
    private static String read(InputStream stream)throws Exception{if(stream==null)return "{}";ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n,total=0;while((n=stream.read(b))>=0){total+=n;if(total>262144)throw new Exception("Forge response exceeded limit");out.write(b,0,n);}return out.toString("UTF-8");}
    private static String safeId(String value){if(value==null||!value.matches("job_[0-9a-f]{24}"))throw new IllegalArgumentException("invalid Forge job ID");return value;}
    private static final class PinTrust implements X509TrustManager{private final String pin;PinTrust(String pin){this.pin=pin;}public void checkClientTrusted(X509Certificate[] chain,String auth)throws CertificateException{throw new CertificateException("client certificates not accepted");}public void checkServerTrusted(X509Certificate[] chain,String auth)throws CertificateException{if(chain==null||chain.length==0)throw new CertificateException("Forge sent no certificate");try{MessageDigest digest=MessageDigest.getInstance("SHA-256");StringBuilder actual=new StringBuilder();for(byte value:digest.digest(chain[0].getEncoded()))actual.append(String.format(Locale.US,"%02x",value));if(!MessageDigest.isEqual(actual.toString().getBytes(StandardCharsets.US_ASCII),pin.getBytes(StandardCharsets.US_ASCII)))throw new CertificateException("Forge certificate pin mismatch");}catch(NoSuchAlgorithmException error){throw new CertificateException(error);}}public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}}
}
''')

write("SageForgeActivity.java", r'''package com.pineapple.sage;

import android.app.Activity;import android.os.*;import android.view.View;import android.widget.*;import org.json.*;

public class SageForgeActivity extends Activity{
    private final Handler handler=new Handler(Looper.getMainLooper());private EditText url,pin,code;private TextView status,logs,result;private ProgressBar progress;private Button pair,run,cancel,revoke;private SageForgeClient client;private String activeJob="";private boolean polling;
    @Override public void onCreate(Bundle state){super.onCreate(state);setTitle("Sage Forge");client=new SageForgeClient(this);setContentView(build());load();}
    private View build(){ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(18),dp(18),dp(18));scroll.addView(root);root.addView(text("Sage Forge",28));root.addView(text("Pair this tablet with the owner's Dell over pinned TLS. The Dell grants only locally registered tools; every remote job requires owner approval.",14));url=input("Forge HTTPS address (example: https://forge.example.invalid:8743)");root.addView(url);pin=input("Certificate SHA-256 shown on the Dell");root.addView(pin);code=input("One-time pairing code shown on the Dell");code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);root.addView(code);pair=button("Review and pair this tablet");pair.setOnClickListener(v->confirmPair());root.addView(pair);status=text("Not paired",15);root.addView(status);progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);root.addView(progress);run=button("Approve Dell system-information job");run.setOnClickListener(v->confirmSystemInfo());root.addView(run);cancel=button("Cancel active Forge job");cancel.setOnClickListener(v->cancelJob());root.addView(cancel);revoke=button("Revoke this Dell pairing");revoke.setOnClickListener(v->confirmRevoke());root.addView(revoke);logs=text("",13);logs.setTextIsSelectable(true);root.addView(logs);result=text("",13);result.setTextIsSelectable(true);root.addView(result);SageAppearance.apply(this,scroll,root);return scroll;}
    private void load(){boolean paired=SageForgeStore.isPaired(this);if(paired){url.setText(SageForgeStore.url(this));pin.setText(SageForgeStore.pin(this));status.setText("Paired with Forge device "+SageForgeStore.deviceId(this));}String saved=SageForgeStore.lastResult(this);if(!saved.isEmpty())result.setText("Last stored structured result\n"+saved);activeJob=SageForgeStore.activeJob(this);setPaired(paired);if(paired&&!activeJob.isEmpty()){polling=true;poll();}}
    private void setPaired(boolean paired){pair.setEnabled(!paired);run.setEnabled(paired);revoke.setEnabled(paired);cancel.setEnabled(paired&&!activeJob.isEmpty());url.setEnabled(!paired);pin.setEnabled(!paired);code.setEnabled(!paired);}
    private void confirmPair(){String target=url.getText().toString().trim(),fingerprint=pin.getText().toString().trim(),pairCode=code.getText().toString().trim();try{SageForgeStore.normalizePin(fingerprint);if(!target.startsWith("https://"))throw new Exception("HTTPS address required");if(!pairCode.matches("[0-9]{6,32}"))throw new Exception("pairing code must contain 6 to 32 digits");}catch(Exception e){status.setText("Pairing input error: "+e.getMessage());return;}SageConfirmation.require(this,"Pair Sage Commander with the owner's Dell Forge",target,"INTERNET; Dell pairing window; Android Keystore","Device label and one-time pairing proof go only to this pinned Dell","Revoke from this screen or on the Dell",()->pairNow(target,fingerprint,pairCode));}
    private void pairNow(String target,String fingerprint,String pairCode){status.setText("Pairing over encrypted pinned TLS…");client.pair(target,fingerprint,pairCode,"Sage Commander tablet",new Callback(){public void complete(JSONObject value){try{SageForgeStore.savePairing(SageForgeActivity.this,target,fingerprint,value.getString("device_id"),value.getString("device_token"));code.setText("");status.setText("Pairing approved and stored in Android Keystore");SageDiagnostics.appendEvent(SageForgeActivity.this,"FORGE","paired device="+value.optString("device_id"));setPaired(true);}catch(Exception e){failed("Secure storage failed: "+e.getMessage());}}public void failed(String detail){status.setText("Pairing failed: "+detail);SageDiagnostics.recordError(SageForgeActivity.this,"Forge pairing failed: "+detail);}});}
    private void confirmSystemInfo(){SageConfirmation.require(this,"Read harmless system information on paired Dell",SageForgeStore.url(this)+" / tool system.info","Paired Forge trust and local system-metadata read","OS, hostname, CPU, storage and local address facts return to this tablet","Job can be cancelled; result can be removed with Sage app data",this::startSystemInfo);}
    private void startSystemInfo(){status.setText("Submitting allowlisted job…");progress.setProgress(0);logs.setText("");client.startSystemInfo(new Callback(){public void complete(JSONObject value){activeJob=value.optString("job_id");SageForgeStore.saveJob(SageForgeActivity.this,activeJob);polling=true;cancel.setEnabled(true);SageDiagnostics.appendEvent(SageForgeActivity.this,"FORGE","system.info submitted job="+activeJob);poll();}public void failed(String detail){failJob("Submit failed: "+detail);}});}
    private void poll(){if(!polling||activeJob.isEmpty())return;client.job(activeJob,new Callback(){public void complete(JSONObject value){String jobStatus=value.optString("status");progress.setProgress(value.optInt("progress"));status.setText("Forge job: "+jobStatus+" — "+value.optString("stage"));logs.setText(renderLogs(value.optJSONArray("logs")));if("completed".equals(jobStatus)){polling=false;cancel.setEnabled(false);JSONObject structured=value.optJSONObject("result");String shown=structured==null?"{}":structured.toString();SageForgeStore.saveResult(SageForgeActivity.this,shown);result.setText("Stored structured Dell result\n"+shown);SageDiagnostics.appendEvent(SageForgeActivity.this,"FORGE","system.info completed job="+activeJob);activeJob="";}else if("failed".equals(jobStatus)||"cancelled".equals(jobStatus)||"interrupted".equals(jobStatus)){polling=false;cancel.setEnabled(false);status.setText("Forge job "+jobStatus+": "+value.optString("error"));activeJob="";}else handler.postDelayed(SageForgeActivity.this::poll,750);}public void failed(String detail){failJob("Status failed: "+detail);}});}
    private void cancelJob(){if(activeJob.isEmpty())return;client.cancel(activeJob,new Callback(){public void complete(JSONObject value){status.setText("Cancellation requested; waiting for Forge…");polling=true;poll();}public void failed(String detail){status.setText("Cancellation failed: "+detail);}});}
    private void confirmRevoke(){SageConfirmation.require(this,"Revoke this tablet's Dell Forge trust",SageForgeStore.url(this),"Paired Forge trust","One signed revocation request goes to the paired Dell","New pairing requires a new Dell-approved one-time code",this::revokeNow);}
    private void revokeNow(){client.revoke(new Callback(){public void complete(JSONObject value){polling=false;handler.removeCallbacksAndMessages(null);SageForgeStore.clear(SageForgeActivity.this);activeJob="";status.setText("Dell trust revoked; local pairing secret deleted");SageDiagnostics.appendEvent(SageForgeActivity.this,"FORGE","device trust revoked");setPaired(false);}public void failed(String detail){status.setText("Revocation not confirmed: "+detail+". Trust was not claimed revoked.");}});}
    private void failJob(String detail){polling=false;cancel.setEnabled(false);status.setText(detail);SageDiagnostics.recordError(this,"Forge job: "+detail);}
    private String renderLogs(JSONArray values){StringBuilder out=new StringBuilder("Structured Forge activity log\n");if(values!=null)for(int i=0;i<values.length();i++){JSONObject item=values.optJSONObject(i);if(item!=null)out.append(item.optLong("timestamp")).append(" ").append(item.optString("level")).append(" — ").append(item.optString("message")).append('\n');}return out.toString();}
    @Override protected void onDestroy(){polling=false;handler.removeCallbacksAndMessages(null);super.onDestroy();}
    private abstract class Callback implements SageForgeClient.Callback{public void failed(String detail){status.setText(detail);}}
    private EditText input(String hint){EditText v=new EditText(this);v.setHint(hint);v.setSingleLine(true);return v;}private Button button(String value){Button b=new Button(this);b.setText(value);b.setAllCaps(false);return b;}private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);return t;}private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
''')

workbench = JAVA / "SageWorkbenchActivity.java"
replace_once(workbench,
'''card(r,"Repair Center","Diagnose, review evidence and theories, prepare and explicitly export repair packets",SageRepairActivity.class);''',
'''card(r,"Sage Forge","Pair with the owner's Dell, run approved jobs, see live logs, store results, and revoke trust",SageForgeActivity.class);card(r,"Repair Center","Diagnose, review evidence and theories, prepare and explicitly export repair packets",SageRepairActivity.class);''',
"Forge Workbench entry")

manifest = ROOT / "app/src/main/AndroidManifest.xml"
replace_once(manifest,
'''        <activity android:name=".SageWorkbenchActivity" android:exported="false" />''',
'''        <activity android:name=".SageForgeActivity" android:exported="false" />
        <activity android:name=".SageWorkbenchActivity" android:exported="false" />''',
"Forge activity")

commands = JAVA / "SageCommandEngine.java"
replace_once(commands,
'''        if (isAny(lower, "open the workbench", "open workbench", "sage workbench")) {''',
'''        if (isAny(lower, "open sage forge", "open forge", "ask forge for system information")) {
            return openWorkbench(SageForgeActivity.class, null);
        }
        if (isAny(lower, "open the workbench", "open workbench", "sage workbench")) {''',
"Forge voice entry")

print("Applied Sage Forge pairing and approved system-information vertical slice")
