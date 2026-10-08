from pathlib import Path
import sys

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
VOICE = ROOT / "app/src/main/java/com/pineapple/sage/SageVoiceService.java"

text = VOICE.read_text()
old = '''                                "I heard "" + cleaned
                                        + "" but my local brain could not answer it."'''
new = '''                                "I heard " + cleaned
                                        + " but my local brain could not answer it."'''
count = text.count(old)
if count != 1:
    raise SystemExit(f"Sage 1.23 generated fallback repair: expected one match, found {count}")
VOICE.write_text(text.replace(old, new, 1))
print("Repaired Sage 1.23 generated brain fallback sentence.")
