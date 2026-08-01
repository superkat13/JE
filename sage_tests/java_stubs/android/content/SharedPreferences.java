package android.content;

import java.util.Set;

public interface SharedPreferences {
    String getString(String key, String fallback);
    Set<String> getStringSet(String key, Set<String> fallback);
    boolean getBoolean(String key, boolean fallback);
    long getLong(String key, long fallback);
    Editor edit();

    interface Editor {
        Editor putString(String key, String value);
        Editor putStringSet(String key, Set<String> value);
        Editor putBoolean(String key, boolean value);
        Editor putLong(String key, long value);
        Editor remove(String key);
        void apply();
        boolean commit();
    }
}
