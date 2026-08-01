package com.pineapple.sage;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Executable host harness for goal planning, safe follow-ups, and Memory 2.0 migration. */
public final class SageIntelligenceMemoryHarness {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        FakeContext context = new FakeContext();

        SageIntentCoordinator.Plan open = SageIntentCoordinator.understand(
                context, "Please open Downloads", true, false);
        require("device_action".equals(open.intent), "open intent was not device_action");
        require("command engine".equals(open.routeHint), "open route was not command engine");
        SageIntentCoordinator.recordOutcome(context, open, "command engine", true, "opened");

        SageIntentCoordinator.Plan repeat = SageIntentCoordinator.understand(
                context, "do that again", true, false);
        require("Please open Downloads".equals(repeat.executionRequest),
                "safe follow-up did not recover the previous command");

        SageIntentCoordinator.Plan unsafe = SageIntentCoordinator.understand(
                context, "type private words", true, false);
        SageIntentCoordinator.recordOutcome(context, unsafe, "command engine", true, "typed");
        SageIntentCoordinator.Plan refusedRepeat = SageIntentCoordinator.understand(
                context, "do that again", true, false);
        require("do that again".equals(refusedRepeat.executionRequest),
                "unsafe typing command was repeated");

        require(SageMemoryStore.save(context, "This project is Sage 1.27")
                        == SageMemoryStore.SaveResult.SAVED,
                "project memory did not save");
        require(SageMemoryStore.save(context, "This project is Sage 1.27")
                        == SageMemoryStore.SaveResult.DUPLICATE,
                "duplicate memory was not rejected");
        require(SageMemoryStore.inspectAll(context).get(0).contains("[project • confidence 1.00")
                        && SageMemoryStore.inspectAll(context).get(0).contains("source owner_statement"),
                "memory metadata was not visible");

        SharedPreferences memory = context.getSharedPreferences("sage_state", Context.MODE_PRIVATE);
        Set<String> migrated = new HashSet<>(memory.getStringSet("memory_items", new HashSet<>()));
        migrated.add("legacy key\tpreference\tI prefer dark mode");
        memory.edit().putStringSet("memory_items", migrated).commit();
        require(SageMemoryStore.recallAll(context).contains("I prefer dark mode"),
                "legacy memory was not decoded");
        require(SageMemoryStore.edit(context, "I prefer dark mode", "I prefer midnight mode")
                        == SageMemoryStore.EditResult.UPDATED,
                "memory edit failed");
        require(SageMemoryStore.delete(context, "I prefer midnight mode")
                        == SageMemoryStore.DeleteResult.DELETED,
                "memory delete failed");

        String summary = SageIntentCoordinator.lastSummary(context);
        require(summary.startsWith("I understand what you want to accomplish:"),
                "goal summary did not use the required user-facing wording");

        System.out.println("PASS: deterministic goal, tool, specialist, and route planning");
        System.out.println("PASS: safe contextual repeat and unsafe-repeat rejection");
        System.out.println("PASS: Memory 2.0 metadata, duplicate prevention, migration, edit, delete");
    }

    private static final class FakeContext extends Context {
        private final Map<String, FakePreferences> stores = new HashMap<>();
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return stores.computeIfAbsent(name, ignored -> new FakePreferences());
        }
    }

    private static final class FakePreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();
        @Override public String getString(String key, String fallback) {
            Object value = values.get(key); return value instanceof String ? (String) value : fallback;
        }
        @SuppressWarnings("unchecked")
        @Override public Set<String> getStringSet(String key, Set<String> fallback) {
            Object value = values.get(key);
            return value instanceof Set ? new HashSet<>((Set<String>) value) : new HashSet<>(fallback);
        }
        @Override public boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key); return value instanceof Boolean ? (Boolean) value : fallback;
        }
        @Override public long getLong(String key, long fallback) {
            Object value = values.get(key); return value instanceof Long ? (Long) value : fallback;
        }
        @Override public Editor edit() { return new FakeEditor(this); }
    }

    private static final class FakeEditor implements SharedPreferences.Editor {
        private final FakePreferences target;
        private final Map<String, Object> updates = new HashMap<>();
        private final Set<String> removals = new HashSet<>();
        FakeEditor(FakePreferences target) { this.target = target; }
        @Override public SharedPreferences.Editor putString(String key, String value) {
            updates.put(key, value); return this;
        }
        @Override public SharedPreferences.Editor putStringSet(String key, Set<String> value) {
            updates.put(key, new HashSet<>(value)); return this;
        }
        @Override public SharedPreferences.Editor putBoolean(String key, boolean value) {
            updates.put(key, value); return this;
        }
        @Override public SharedPreferences.Editor putLong(String key, long value) {
            updates.put(key, value); return this;
        }
        @Override public SharedPreferences.Editor remove(String key) {
            removals.add(key); return this;
        }
        @Override public void apply() { commit(); }
        @Override public boolean commit() {
            for (String key : removals) target.values.remove(key);
            target.values.putAll(updates);
            return true;
        }
    }
}
