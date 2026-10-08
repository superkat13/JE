from pathlib import Path
import sys

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
voice = (ROOT / "app/src/main/java/com/pineapple/sage/SageVoiceService.java").read_text()

bad = '''"I heard "" + cleaned
                                        + "" but my local brain could not answer it."'''
good = '''"I heard " + cleaned
                                        + " but my local brain could not answer it."'''

if bad in voice:
    raise SystemExit("Broken generated Java quote sequence is still present")
if good not in voice:
    raise SystemExit("Repaired brain fallback sentence is missing")

print("Sage 1.23 compile repair check passed.")
