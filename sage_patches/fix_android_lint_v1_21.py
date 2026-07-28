from pathlib import Path
import sys

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('sage_build')
JAVA = ROOT / 'app/src/main/java/com/pineapple/sage'


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one match, found {count}')
    path.write_text(text.replace(old, new, 1))


main = JAVA / 'MainActivity.java'
commands = JAVA / 'SageCommandEngine.java'

replace_once(
    commands,
'''    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }''',
'''    private static String encode(String value) {
        try {
            // The Charset overload requires Android 13. The named-charset overload works
            // on Sage's full Android 8+ support range.
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException impossible) {
            return value;
        }
    }''',
    'Android 8 compatible URL encoding',
)

replace_once(
    main,
'''    private void registerStateReceiver() {
        IntentFilter filter = new IntentFilter(SageVoiceService.ACTION_STATE);''',
'''    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerStateReceiver() {
        IntentFilter filter = new IntentFilter(SageVoiceService.ACTION_STATE);''',
    'pre-Android-13 receiver registration annotation',
)

replace_once(
    main,
'''        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {
        }
        pendingUpdateUri = uri;''',
'''        try {
            if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            }
        } catch (Exception ignored) {
        }
        pendingUpdateUri = uri;''',
    'update APK persisted read grant',
)

replace_once(
    main,
'''        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (flags != 0) {
                getContentResolver().takePersistableUriPermission(uri, flags);
            }
        } catch (Exception ignored) {
        }''',
'''        try {
            if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            }
        } catch (Exception ignored) {
        }''',
    'voice response persisted read grant',
)

print('Applied Android compatibility fixes required by lint.')
