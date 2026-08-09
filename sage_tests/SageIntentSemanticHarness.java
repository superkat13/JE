package com.pineapple.sage;

import java.util.Arrays;

/** Executable host regression for the compiled intent and semantic action policies. */
public final class SageIntentSemanticHarness {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        SageIntentActionPolicy.Decision youtube = SageIntentActionPolicy.decide(
                "Open YouTube", "", "");
        require(youtube.action == SageIntentActionPolicy.Action.OPEN_YOUTUBE
                && youtube.capability.equals("android.launch"), "YouTube route was not direct");

        SageIntentActionPolicy.Decision downloads = SageIntentActionPolicy.decide(
                "Open Downloads", "", "");
        require(downloads.action == SageIntentActionPolicy.Action.OPEN_DOWNLOADS
                && downloads.capability.equals("android.documents"), "Downloads route was not Android documents");

        SageIntentActionPolicy.Decision adobe = SageIntentActionPolicy.decide(
                "Open Adobe Express", "", "");
        require(adobe.action == SageIntentActionPolicy.Action.OPEN_ADOBE_EXPRESS
                && adobe.capability.equals("trusted.adobe.launch"), "Adobe launch was not trusted");

        SageIntentActionPolicy.Decision edit = SageIntentActionPolicy.decide(
                "Edit this in Adobe", "selected_file=content://owner/video", "adobe");
        require(edit.mayExecute() && edit.capability.equals("trusted.adobe.edit"),
                "selected Adobe edit was not approved");

        SageIntentActionPolicy.Decision play = SageIntentActionPolicy.decide(
                "Tap Play", "subject=this video", "");
        require(play.capability.equals("media.session_then_semantic"),
                "Play did not prefer direct media");

        SageSemanticTargetPolicy.Candidate first = candidate("First item", "", "row_1", "button", true, true);
        SageSemanticTargetPolicy.Candidate second = candidate("Second item", "", "row_2", "button", true, true);
        require(SageSemanticTargetPolicy.selectOrdinal(Arrays.asList(first, second), "item", 2) == second,
                "second semantic item was not selected");

        SageSemanticTargetPolicy.Candidate described = candidate("", "Play", "media_play", "ImageButton", true, true);
        require(SageSemanticTargetPolicy.score(described, "play") >= 55,
                "content description/resource/role semantic match failed");
        SageSemanticTargetPolicy.Candidate childWithAncestor = new SageSemanticTargetPolicy.Candidate(
                "Play", "", "media_play", "TextView", true, true, false, true);
        require(SageSemanticTargetPolicy.score(childWithAncestor, "play") >= 55,
                "clickable ancestor semantic match failed");

        require(SageSemanticTargetPolicy.revalidate(second,
                candidate("Second item", "", "row_2", "button", true, true)),
                "stable target failed revalidation");
        require(!SageSemanticTargetPolicy.revalidate(second,
                candidate("Different item", "", "row_2", "button", true, true)),
                "mismatched target passed revalidation");

        require(SageIntentActionPolicy.decide("please help", "", "").action
                        == SageIntentActionPolicy.Action.NONE
                && SageIntentActionPolicy.decide("Show numbers", "", "").action
                        == SageIntentActionPolicy.Action.SHOW_NUMBERS,
                "numbers were available without explicit final fallback request");

        SageIntentActionPolicy.Decision destructive = SageIntentActionPolicy.decide(
                "Delete it", "subject=current item", "");
        require(!destructive.mayExecute() && !destructive.clarification.isEmpty(),
                "ambiguous destructive target executed");

        require(!SageSemanticTargetPolicy.safe(candidate("Delete", "", "delete", "button", false, true))
                && !SageSemanticTargetPolicy.safe(candidate("Delete", "", "delete", "button", true, false)),
                "stale hidden or disabled target was actionable");

        SageIntentActionPolicy.Decision radio = SageIntentActionPolicy.decide(
                "Find a wiring diagram for this radio", "subject=Yaesu FT-60R", "");
        require(radio.mayExecute() && radio.context.contains("Yaesu FT-60R"),
                "this radio did not resolve from context");

        System.out.println("PASS 1: Open YouTube uses direct launch/deep-link policy");
        System.out.println("PASS 2: Open Downloads uses Android document policy");
        System.out.println("PASS 3: Adobe Express uses trusted installed-app policy");
        System.out.println("PASS 4: selected Adobe edit is an approved content-URI route");
        System.out.println("PASS 5: Tap Play prefers direct media before semantic accessibility");
        System.out.println("PASS 6: second item uses semantic match ordering");
        System.out.println("PASS 7: semantic fields and clickable ancestor are matched");
        System.out.println("PASS 8: target identity is revalidated and mismatch rejected");
        System.out.println("PASS 9: numbered overlay requires explicit final-fallback route");
        System.out.println("PASS 10: ambiguous destructive request clarifies without action");
        System.out.println("PASS 11: hidden and disabled targets cannot execute");
        System.out.println("PASS 12: natural this-subject follow-up uses retained context");
    }

    private static SageSemanticTargetPolicy.Candidate candidate(
            String text, String description, String id, String role,
            boolean visible, boolean enabled) {
        return new SageSemanticTargetPolicy.Candidate(text, description, id, role,
                visible, enabled, true, false);
    }
}
