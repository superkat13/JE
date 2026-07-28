from pathlib import Path
import re
import shutil
import sys

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sage_build")
JAVA = ROOT / "app/src/main/java/com/pineapple/sage"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1))


def insert_before_once(path: Path, marker: str, content: str, label: str) -> None:
    text = path.read_text()
    count = text.count(marker)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one marker, found {count}")
    path.write_text(text.replace(marker, content + marker, 1))


voice = JAVA / "SageVoiceService.java"
commands = JAVA / "SageCommandEngine.java"
main = JAVA / "MainActivity.java"
profile_source = Path(__file__).with_name("SageWakeProfileStore.java")
profile_target = JAVA / "SageWakeProfileStore.java"
if not profile_source.is_file():
    raise SystemExit("SageWakeProfileStore.java template is missing")
shutil.copyfile(profile_source, profile_target)

# ----------------------------
# Voice service wake profiles
# ----------------------------
replace_once(
    voice,
    '''    public static final String ACTION_REFRESH_LISTENING_MODE =
            "com.pineapple.sage.REFRESH_LISTENING_MODE";
    public static final String ACTION_STATE = "com.pineapple.sage.STATE";''',
    '''    public static final String ACTION_REFRESH_LISTENING_MODE =
            "com.pineapple.sage.REFRESH_LISTENING_MODE";
    public static final String ACTION_REFRESH_WAKE_PROFILES =
            "com.pineapple.sage.REFRESH_WAKE_PROFILES";
    public static final String ACTION_STATE = "com.pineapple.sage.STATE";''',
    "wake profile refresh action",
)

replace_once(
    voice,
    '''    private boolean translationInProgress;
    private boolean brainInProgress;
    private long brainRequestGeneration;
    private boolean mediaResponseFinishing;''',
    '''    private boolean translationInProgress;
    private boolean brainInProgress;
    private long brainRequestGeneration;
    private boolean forceBrainForNextCommand;
    private boolean mediaResponseFinishing;''',
    "brain wake mode field",
)

replace_once(
    voice,
    '''        } else if (ACTION_LISTEN_NOW.equals(action)) {
            openConversationWindow();
            broadcastStatus("Getting ready to listen");
            updateNotification("Getting ready to listen");
            startCommandRecognition(550);
        } else {
            startWakeListening(250);
        }''',
    '''        } else if (ACTION_REFRESH_WAKE_PROFILES.equals(action)) {
            closeConversationWindow();
            commandEngine.cancelFollowUp();
            destroyCommandRecognizer();
            stopWakeListening();
            broadcastStatus("Wake words updated");
            updateNotification("Wake words updated — say a saved wake phrase");
            startWakeListening(250);
        } else if (ACTION_LISTEN_NOW.equals(action)) {
            openConversationWindow();
            broadcastStatus("Getting ready to listen");
            updateNotification("Getting ready to listen");
            startCommandRecognition(550);
        } else {
            startWakeListening(250);
        }''',
    "refresh active wake grammar",
)

replace_once(
    voice,
    '''        String captured = latestWakeText;
        latestWakeText = "";
        stopWakeListening();
        respondToWake(commandAfterWake(captured));''',
    '''        String captured = latestWakeText;
        latestWakeText = "";
        stopWakeListening();
        SageWakeProfileStore.Match profileMatch =
                SageWakeProfileStore.match(this, captured);
        if (profileMatch != null) {
            respondToWakeProfile(profileMatch);
        } else {
            respondToWake(commandAfterWake(captured));
        }''',
    "dispatch matched wake profile",
)

replace_once(
    voice,
    '''    private void respondToWake(String command) {
        openConversationWindow();
        if (!command.isEmpty()) {
            handleCommand(command);
        } else {
            listenForCommandAfterSpeech = true;
            broadcastLine("Sage", "Yes?");
            speak("Yes?");
        }
    }

    private String buildWakeGrammar() {''',
    '''    private void respondToWake(String command) {
        openConversationWindow();
        if (!command.isEmpty()) {
            handleCommand(command);
        } else {
            listenForCommandAfterSpeech = true;
            broadcastLine("Sage", "Yes?");
            speak("Yes?");
        }
    }

    private void respondToWakeProfile(SageWakeProfileStore.Match match) {
        SageWakeProfileStore.Profile profile = match.profile;
        SageDiagnostics.appendEvent(
                this,
                "WAKE PROFILE",
                profile.phrase + " → " + SageWakeProfileStore.modeLabel(profile)
        );
        if (SageWakeProfileStore.MODE_NORMAL.equals(profile.mode)) {
            respondToWake(match.remainder);
            return;
        }

        openConversationWindow();
        commandEngine.cancelFollowUp();
        if (SageWakeProfileStore.MODE_RED_QUEEN.equals(profile.mode)) {
            handleCommand("red queen mode");
            return;
        }
        if (SageWakeProfileStore.MODE_COMMAND.equals(profile.mode)) {
            handleCommand(profile.command);
            return;
        }
        if (SageWakeProfileStore.MODE_BRAIN.equals(profile.mode)) {
            if (brainManager == null || !brainManager.canAnswer()) {
                listenForCommandAfterSpeech = true;
                String message = "Sage Brain is off. Listening in normal mode.";
                broadcastLine("Sage", message);
                speak(message);
                return;
            }
            forceBrainForNextCommand = true;
            if (!match.remainder.isEmpty()) {
                handleCommand(match.remainder);
            } else {
                listenForCommandAfterSpeech = true;
                broadcastLine("Sage", "Brain mode.");
                speak("Brain mode.");
            }
            return;
        }
        respondToWake(match.remainder);
    }

    private String buildWakeGrammar() {''',
    "wake profile mode dispatcher",
)

replace_once(
    voice,
    '''        Set<String> aliases = preferences.getStringSet("wake_aliases", null);
        if (aliases != null) {
            for (String alias : aliases) {
                String wake = normalizeForEcho(alias);
                if (!wake.isEmpty()) {
                    phrases.add(wake);
                    phrases.add("hey " + wake);
                    phrases.add("okay " + wake);
                    phrases.add("ok " + wake);
                }
            }
        }''',
    '''        for (String phrase : SageWakeProfileStore.allWakePhrases(this)) {
            phrases.add(phrase);
        }''',
    "custom wake grammar",
)

replace_once(
    voice,
    '''        Set<String> aliases = preferences.getStringSet("wake_aliases", null);
        if (aliases != null) {
            for (String alias : aliases) {
                String wake = normalizeForEcho(alias);
                if (!wake.isEmpty() && (normalized.equals(wake) || normalized.startsWith(wake + " "))) {
                    return true;
                }
            }
        }
        return false;''',
    '''        return SageWakeProfileStore.match(this, normalized) != null;''',
    "custom wake recognition",
)

replace_once(
    voice,
    '''        Set<String> aliases = preferences.getStringSet("wake_aliases", null);
        if (aliases != null) {
            for (String alias : aliases) {
                String wake = normalizeForEcho(alias);
                if (normalized.equals(wake)) {
                    return "";
                }
                if (!wake.isEmpty() && normalized.startsWith(wake + " ")) {
                    return cleanWakeRemainder(normalized.substring(wake.length()));
                }
            }
        }
        return "";''',
    '''        SageWakeProfileStore.Match profileMatch =
                SageWakeProfileStore.match(this, normalized);
        return profileMatch == null ? "" : cleanWakeRemainder(profileMatch.remainder);''',
    "custom wake remainder",
)

replace_once(
    voice,
    '''        Set<String> aliases = preferences.getStringSet("wake_aliases", null);
        if (aliases != null) {
            for (String alias : aliases) {
                if (normalized.equals(normalizeForEcho(alias))) {
                    return true;
                }
            }
        }
        return false;''',
    '''        SageWakeProfileStore.Match profileMatch =
                SageWakeProfileStore.match(this, normalized);
        return profileMatch != null && profileMatch.remainder.isEmpty();''',
    "custom exact wake phrase",
)

# ----------------------------
# Command recognition accuracy
# ----------------------------
replace_once(
    voice,
    '''        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 650L);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 400L);''',
    '''        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1300L);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 750L);''',
    "complete spoken command window",
)

replace_once(
    voice,
    '''                    ArrayList<String> choices = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String candidate = chooseBestCandidate(choices);
                    if (candidate.isEmpty()) {
                        candidate = chooseBestCandidate(new ArrayList<>(partialChoices));
                    }''',
    '''                    ArrayList<String> choices =
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    ArrayList<String> combinedChoices = new ArrayList<>();
                    if (choices != null) {
                        for (String choice : choices) {
                            if (choice != null && !combinedChoices.contains(choice)) {
                                combinedChoices.add(choice);
                            }
                        }
                    }
                    for (String partial : partialChoices) {
                        if (partial != null && !combinedChoices.contains(partial)) {
                            combinedChoices.add(partial);
                        }
                    }
                    String candidate = chooseBestCandidate(combinedChoices);''',
    "combine final and partial recognition candidates",
)

old_choose = '''    private String chooseBestCandidate(ArrayList<String> choices) {
        if (choices == null || choices.isEmpty()) {
            return "";
        }
        if (commandEngine != null && commandEngine.isAwaitingFollowUp()) {
            for (String choice : choices) {
                if (choice != null && !choice.trim().isEmpty() && !isLikelySelfEcho(choice)) {
                    return choice.trim();
                }
            }
        }
        String best = "";
        int bestScore = Integer.MIN_VALUE;
        for (int index = 0; index < choices.size(); index++) {
            String choice = choices.get(index);
            if (choice != null && !choice.trim().isEmpty() && !isLikelySelfEcho(choice)) {
                int score = scoreCommandCandidate(choice, index);
                if (score > bestScore) {
                    best = choice.trim();
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private int scoreCommandCandidate(String choice, int index) {
        String normalized = normalizeForEcho(choice);
        int score = 100 - (index * 5);
        if (containsCommandCue(normalized)) {
            score += 40;
        }
        if (normalized.startsWith("sage ") || normalized.startsWith("hey sage ")) {
            score += 15;
        }
        int wordCount = normalized.isEmpty() ? 0 : normalized.split(" ").length;
        score += Math.min(12, wordCount * 2);
        return score;
    }'''
new_choose = '''    private String chooseBestCandidate(ArrayList<String> choices) {
        if (choices == null || choices.isEmpty()) {
            return "";
        }
        String best = "";
        int bestScore = Integer.MIN_VALUE;
        for (int index = 0; index < choices.size(); index++) {
            String choice = choices.get(index);
            if (choice != null && !choice.trim().isEmpty() && !isLikelySelfEcho(choice)) {
                int score = scoreCommandCandidate(choice, index);
                if (score > bestScore) {
                    best = choice.trim();
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private int scoreCommandCandidate(String choice, int index) {
        String normalized = normalizeForEcho(choice);
        int score = 100 - (index * 5);
        if (containsCommandCue(normalized)) {
            score += 40;
        }
        if (normalized.startsWith("sage ") || normalized.startsWith("hey sage ")) {
            score += 15;
        }
        int wordCount = normalized.isEmpty() ? 0 : normalized.split(" ").length;
        score += Math.min(36, wordCount * 9);
        score += Math.min(12, normalized.length() / 4);
        if (wordCount == 1 && isIncompleteCommandStem(normalized)) {
            score -= 45;
        }
        return score;
    }

    private boolean isIncompleteCommandStem(String normalized) {
        String[] stems = {
                "open", "show", "search", "find", "look", "tap", "click", "type",
                "write", "go", "play", "scroll", "turn", "number", "select", "choose"
        };
        for (String stem : stems) {
            if (normalized.equals(stem)) {
                return true;
            }
        }
        return false;
    }'''
replace_once(voice, old_choose, new_choose, "prefer complete command candidates")

replace_once(
    voice,
    '''                "open", "search", "find", "look", "youtube", "home", "back", "recent",
                "scroll", "tap", "click", "press", "pick", "choose", "select", "type",''',
    '''                "open", "show", "search", "find", "look", "youtube", "home", "back", "recent",
                "scroll", "tap", "click", "press", "pick", "choose", "select", "type",
                "number", "numbers", "go",''',
    "complete-command scoring cues",
)

# Force direct local-brain interpretation only for a wake profile that selected Brain mode.
insert_before_once(
    voice,
    '''        SageCommandEngine.Result result = commandEngine.execute(cleaned);''',
    '''        if (forceBrainForNextCommand) {
            forceBrainForNextCommand = false;
            if (brainManager != null && brainManager.canAnswer()) {
                beginBrainRequest(
                        cleaned,
                        SageCommandEngine.Result.unmatched(
                                "I heard \"" + cleaned
                                        + "\" but my local brain could not answer it."
                        )
                );
                return;
            }
        }
''',
    "direct brain wake mode routing",
)

replace_once(
    voice,
    '''        conversationExpiresAtMs = 0L;
        listenForCommandAfterSpeech = false;
        handler.removeCallbacks(conversationExpiryRunnable);''',
    '''        conversationExpiresAtMs = 0L;
        listenForCommandAfterSpeech = false;
        forceBrainForNextCommand = false;
        handler.removeCallbacks(conversationExpiryRunnable);''',
    "clear one-shot brain mode",
)

# ----------------------------
# Escape a mistaken short follow-up
# ----------------------------
replace_once(
    commands,
    '''        if (!pendingAction.isEmpty()) {
            String action = pendingAction;''',
    '''        if (shouldReplaceActionFollowUp(pendingAction, lower)) {
            pendingAction = "";
        }

        if (!pendingAction.isEmpty()) {
            String action = pendingAction;''',
    "fresh command escapes stale action follow-up",
)

insert_before_once(
    commands,
    '''    private static String normalize(String text) {''',
    '''    private static boolean shouldReplaceActionFollowUp(String pending, String lower) {
        if (!(pending.equals("open")
                || pending.equals("tap")
                || pending.equals("type")
                || pending.equals("youtube_search")
                || pending.equals("web_search")
                || pending.equals("say"))) {
            return false;
        }
        String[] commandStarts = {
                "open ", "show ", "search ", "find ", "look for ", "tap ", "click ",
                "type ", "write ", "go back", "go home", "scroll ", "number ",
                "play ", "pause ", "turn ", "read ", "translate ", "remember ",
                "red queen", "clear numbers"
        };
        for (String start : commandStarts) {
            if (lower.startsWith(start)) {
                return true;
            }
        }
        return isAny(lower, "back", "home", "recent apps", "notifications", "help", "sleep");
    }

''',
    "stale follow-up escape helper",
)

# ----------------------------
# Wake Profiles controls in the app
# ----------------------------
replace_once(
    main,
    '''    private static final String APK_MIME = "application/vnd.android.package-archive";

    private TextView statusText;''',
    '''    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String[] WAKE_MODE_KEYS = {
            SageWakeProfileStore.MODE_NORMAL,
            SageWakeProfileStore.MODE_RED_QUEEN,
            SageWakeProfileStore.MODE_BRAIN,
            SageWakeProfileStore.MODE_COMMAND
    };
    private static final String[] WAKE_MODE_LABELS = {
            "Normal Sage",
            "Red Queen",
            "Sage Brain",
            "Run a command"
    };

    private TextView statusText;''',
    "wake profile mode constants",
)

replace_once(
    main,
    '''    private Button conversationModeButton;
    private Button brainToggleButton;''',
    '''    private Button conversationModeButton;
    private EditText wakeProfilePhrase;
    private EditText wakeProfileCommand;
    private Button wakeProfileModeButton;
    private TextView wakeProfileModeHelp;
    private TextView wakeProfileSummary;
    private int wakeProfileModeIndex;
    private Button brainToggleButton;''',
    "wake profile interface fields",
)

ui_marker = '''        TextView brainTitle = new TextView(this);'''
ui_content = '''        TextView wakeProfilesTitle = new TextView(this);
        wakeProfilesTitle.setText("Custom wake profiles");
        wakeProfilesTitle.setTextSize(23);
        wakeProfilesTitle.setTextColor(Color.rgb(31, 41, 55));
        wakeProfilesTitle.setPadding(4, 18, 4, 4);
        root.addView(wakeProfilesTitle, spaced());

        TextView wakeProfilesHelp = new TextView(this);
        wakeProfilesHelp.setText("Add a distinctive wake word or short phrase, then choose what it activates. A profile can open normal Sage, trigger Red Queen, open direct Sage Brain mode, or run any saved command.");
        wakeProfilesHelp.setTextSize(15);
        wakeProfilesHelp.setTextColor(Color.DKGRAY);
        wakeProfilesHelp.setPadding(8, 2, 8, 8);
        root.addView(wakeProfilesHelp, matchWrap());

        wakeProfilePhrase = new EditText(this);
        wakeProfilePhrase.setHint("Wake word or phrase, such as computer or red queen");
        wakeProfilePhrase.setSingleLine(true);
        wakeProfilePhrase.setTextSize(17);
        root.addView(wakeProfilePhrase, spacedSmall());

        wakeProfileModeButton = makeButton("");
        wakeProfileModeButton.setOnClickListener(v -> {
            wakeProfileModeIndex = (wakeProfileModeIndex + 1) % WAKE_MODE_KEYS.length;
            refreshWakeProfileMode();
        });
        root.addView(wakeProfileModeButton, spacedSmall());

        wakeProfileModeHelp = new TextView(this);
        wakeProfileModeHelp.setTextSize(14);
        wakeProfileModeHelp.setTextColor(Color.DKGRAY);
        wakeProfileModeHelp.setPadding(8, 2, 8, 4);
        root.addView(wakeProfileModeHelp, matchWrap());

        wakeProfileCommand = new EditText(this);
        wakeProfileCommand.setHint("Command to run, such as open YouTube");
        wakeProfileCommand.setSingleLine(false);
        wakeProfileCommand.setTextSize(17);
        root.addView(wakeProfileCommand, spacedSmall());

        Button saveWakeProfile = makeButton("Save wake profile");
        saveWakeProfile.setOnClickListener(v -> saveWakeProfile());
        root.addView(saveWakeProfile, spacedSmall());

        Button removeWakeProfile = makeButton("Remove typed wake phrase");
        removeWakeProfile.setOnClickListener(v -> removeWakeProfile());
        root.addView(removeWakeProfile, spacedSmall());

        Button clearWakeProfiles = makeButton("Clear custom wake profiles");
        clearWakeProfiles.setOnClickListener(v -> confirmClearWakeProfiles());
        root.addView(clearWakeProfiles, spacedSmall());

        wakeProfileSummary = new TextView(this);
        wakeProfileSummary.setTextSize(15);
        wakeProfileSummary.setTextColor(Color.rgb(55, 65, 81));
        wakeProfileSummary.setPadding(8, 10, 8, 4);
        root.addView(wakeProfileSummary, matchWrap());
        refreshWakeProfileMode();
        refreshWakeProfileSummary();

'''
insert_before_once(main, ui_marker, ui_content, "wake profile controls")

methods_marker = '''    private void openRecommendedBrainModel() {'''
methods_content = '''    private void refreshWakeProfileMode() {
        if (wakeProfileModeButton == null || wakeProfileCommand == null) {
            return;
        }
        String mode = WAKE_MODE_KEYS[wakeProfileModeIndex];
        wakeProfileModeButton.setText("Wake mode: " + WAKE_MODE_LABELS[wakeProfileModeIndex]);
        boolean commandMode = SageWakeProfileStore.MODE_COMMAND.equals(mode);
        wakeProfileCommand.setVisibility(commandMode ? View.VISIBLE : View.GONE);
        if (wakeProfileModeHelp != null) {
            if (SageWakeProfileStore.MODE_RED_QUEEN.equals(mode)) {
                wakeProfileModeHelp.setText("Saying this wake phrase immediately runs Red Queen mode, including a linked Red Queen audio clip if one is saved.");
            } else if (SageWakeProfileStore.MODE_BRAIN.equals(mode)) {
                wakeProfileModeHelp.setText("Saying this phrase opens a one-question direct local-brain turn. Built-in commands are bypassed for that one question.");
            } else if (commandMode) {
                wakeProfileModeHelp.setText("Saying this phrase immediately runs the command typed below.");
            } else {
                wakeProfileModeHelp.setText("Saying this phrase works like saying Sage and opens normal conversation.");
            }
        }
    }

    private void saveWakeProfile() {
        String phrase = wakeProfilePhrase.getText().toString();
        String command = wakeProfileCommand.getText().toString();
        String mode = WAKE_MODE_KEYS[wakeProfileModeIndex];
        String problem = SageWakeProfileStore.save(this, phrase, mode, command);
        if (!problem.isEmpty()) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show();
            return;
        }
        wakeProfilePhrase.setText("");
        if (SageWakeProfileStore.MODE_COMMAND.equals(mode)) {
            wakeProfileCommand.setText("");
        }
        refreshWakeProfileSummary();
        refreshWakeProfilesInService();
        Toast.makeText(this, "Wake profile saved.", Toast.LENGTH_LONG).show();
    }

    private void removeWakeProfile() {
        String phrase = wakeProfilePhrase.getText().toString();
        if (!SageWakeProfileStore.remove(this, phrase)) {
            Toast.makeText(this, "I could not find that saved wake phrase.", Toast.LENGTH_LONG).show();
            return;
        }
        wakeProfilePhrase.setText("");
        refreshWakeProfileSummary();
        refreshWakeProfilesInService();
        Toast.makeText(this, "Wake profile removed.", Toast.LENGTH_LONG).show();
    }

    private void confirmClearWakeProfiles() {
        new AlertDialog.Builder(this)
                .setTitle("Clear custom wake profiles?")
                .setMessage("Built-in Sage wake words will remain. This removes only the wake profiles you added.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    if (!SageWakeProfileStore.clear(this)) {
                        Toast.makeText(this, "Android could not clear the wake profiles.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    refreshWakeProfileSummary();
                    refreshWakeProfilesInService();
                    Toast.makeText(this, "Custom wake profiles cleared.", Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private void refreshWakeProfileSummary() {
        if (wakeProfileSummary != null) {
            wakeProfileSummary.setText(SageWakeProfileStore.summary(this));
        }
    }

    private void refreshWakeProfilesInService() {
        if (!SageVoiceService.isRunning()) {
            return;
        }
        Intent refresh = new Intent(this, SageVoiceService.class)
                .setAction(SageVoiceService.ACTION_REFRESH_WAKE_PROFILES);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(refresh);
        } else {
            startService(refresh);
        }
    }

'''
insert_before_once(main, methods_marker, methods_content, "wake profile interface methods")

# ----------------------------
# Permanent in-place version
# ----------------------------
build_gradle = ROOT / "app/build.gradle.kts"
text = build_gradle.read_text()
text, code_count = re.subn(r'versionCode\s*=\s*32', 'versionCode = 33', text, count=1)
text, name_count = re.subn(r'versionName\s*=\s*"1\.22"', 'versionName = "1.23"', text, count=1)
if code_count != 1 or name_count != 1:
    raise SystemExit(f"Sage 1.23 version identity: code={code_count}, name={name_count}")
build_gradle.write_text(text)

for xml in (ROOT / "app/src/main").rglob("*.xml"):
    xml_text = xml.read_text()
    updated = re.sub(
        r"Sage Commander(?:\s+\d+\.\d+)?",
        "Sage Commander 1.23",
        xml_text,
    )
    if updated != xml_text:
        xml.write_text(updated)

print("Applied Sage 1.23 complete-command recognition and custom wake profiles.")
