#!/usr/bin/env python3
from pathlib import Path
import sys
if len(sys.argv)!=2:raise SystemExit("usage: test_network_operations_v1_28.py <source>")
root=Path(sys.argv[1]);java=root/"app/src/main/java/com/pineapple/sage"
inspector=(java/"SageHostInspector.java").read_text();activity=(java/"SageHostInspectorActivity.java").read_text();command=(java/"SageCommandEngine.java").read_text();manifest=(root/"app/src/main/AndroidManifest.xml").read_text()
checks={
 "private scope enforced":"SageNetworkScanner.isPrivate" in inspector and "public or invalid selected host refused" in inspector,
 "saved exact host enforced":"savedHost" in inspector and "saved private-LAN snapshot" in inspector,
 "conservative ports":all(v in inspector for v in ("22,53,80,443,445,631,8080,8443","CONNECT_TIMEOUT_MS")),
 "dns reachability latency":all(v in inspector for v in ("dns_reverse","reachable","latency_ms")),
 "TLS evidence":all(v in inspector for v in ("SSLSocket","cert_sha256","getPeerCertificates")),
 "HTTP headers":all(v in inspector for v in ("HttpURLConnection","setRequestMethod(\"HEAD\")","getHeaderFields")),
 "MAC vendor honesty":"unavailable through ordinary Android app authority; not claimed" in inspector,
 "owner confirmation":"SageConfirmation.require" in activity,
 "cancellation":"session.checkCancelled" in inspector and "operation.cancel" in activity,
 "forbidden behavior absent":all(v in activity for v in ("Public targets","credentials","exploits","denial of service")),
 "voice route":"inspect selected host" in command and "SageHostInspectorActivity.class" in command,
 "non-exported":'<activity android:name=".SageHostInspectorActivity" android:exported="false" />' in manifest,
}
failed=[k for k,v in checks.items() if not v]
for k,v in checks.items():print(f"{'PASS' if v else 'FAIL'}: {k}")
if failed:raise SystemExit("Network Operations failures: "+", ".join(failed))
