#!/usr/bin/env python3
from pathlib import Path
import random
import sys
import unittest

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('sage_build')
JAVA = ROOT / 'app/src/main/java/com/pineapple/sage'


def overlay_should_clear(
    *,
    event_package: str,
    event_window: int,
    event_type: str,
    numbered_package: str = 'com.google.android.youtube',
    numbered_window: int = 7,
    sage_package: str = 'com.pineapple.sagecommander.stable',
) -> bool:
    if event_package and event_package == sage_package:
        return False
    different_package = bool(
        numbered_package and event_package and numbered_package != event_package
    )
    different_window = (
        not different_package
        and numbered_window >= 0
        and event_window >= 0
        and numbered_window != event_window
    )
    deliberate_movement = event_type in {'VIEW_SCROLLED', 'VIEW_CLICKED'}
    return different_package or different_window or deliberate_movement


def target_identity_score(saved_text: str, current_text: str, same_node: bool, overlap: bool, nearby: bool) -> int:
    saved = ' '.join(saved_text.lower().split())
    current = ' '.join(current_text.lower().split())
    score = 0
    identity = same_node
    if identity:
        score += 160
    elif saved and saved == current:
        identity = True
        score += 120
    elif len(saved) >= 5 and len(current) >= 5 and (saved in current or current in saved):
        identity = True
        score += 80
    if not identity:
        return -1
    if overlap:
        score += 45
    if nearby:
        score += 35
    return score


def target_order_key(top: int, left: int, bottom: int, row_height: int):
    center_y = (top + bottom) // 2
    return (max(0, center_y) // row_height, left, top)


class BehaviorRegressionTests(unittest.TestCase):
    def test_youtube_window_churn_does_not_clear_numbers(self):
        for event_type in ('WINDOW_STATE_CHANGED', 'WINDOWS_CHANGED', 'WINDOW_CONTENT_CHANGED'):
            self.assertFalse(
                overlay_should_clear(
                    event_package='com.google.android.youtube',
                    event_window=7,
                    event_type=event_type,
                ),
                event_type,
            )

    def test_real_navigation_still_clears_numbers(self):
        self.assertTrue(overlay_should_clear(
            event_package='com.google.android.youtube', event_window=7, event_type='VIEW_SCROLLED'))
        self.assertTrue(overlay_should_clear(
            event_package='com.google.android.youtube', event_window=7, event_type='VIEW_CLICKED'))
        self.assertTrue(overlay_should_clear(
            event_package='com.google.android.youtube', event_window=9, event_type='WINDOWS_CHANGED'))
        self.assertTrue(overlay_should_clear(
            event_package='com.android.settings', event_window=1, event_type='WINDOW_STATE_CHANGED'))

    def test_sage_overlay_events_do_not_erase_its_own_numbers(self):
        self.assertFalse(overlay_should_clear(
            event_package='com.pineapple.sagecommander.stable',
            event_window=99,
            event_type='WINDOWS_CHANGED',
        ))

    def test_geometry_alone_cannot_select_a_refreshed_card(self):
        self.assertEqual(target_identity_score('', '', False, True, True), -1)
        self.assertEqual(target_identity_score('Old video', 'New unrelated card', False, True, True), -1)

    def test_text_or_node_identity_can_revalidate_a_choice(self):
        self.assertGreaterEqual(target_identity_score('My Video', 'My Video', False, True, True), 80)
        self.assertGreaterEqual(target_identity_score('', '', True, False, False), 80)

    def test_row_sort_key_is_transitive(self):
        random.seed(121)
        row_height = 40
        points = [
            (random.randint(0, 1000), random.randint(0, 500), random.randint(20, 120))
            for _ in range(500)
        ]
        keys = [target_order_key(top, left, top + height, row_height) for top, left, height in points]
        ordered = sorted(keys)
        for a, b, c in zip(ordered, ordered[1:], ordered[2:]):
            self.assertLessEqual(a, b)
            self.assertLessEqual(b, c)
            self.assertLessEqual(a, c)


class SourceRegressionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.access = (JAVA / 'SageAccessibilityService.java').read_text()
        cls.commands = (JAVA / 'SageCommandEngine.java').read_text()
        cls.voice = (JAVA / 'SageVoiceService.java').read_text()
        cls.gradle = (ROOT / 'app/build.gradle.kts').read_text()
        cls.manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()

    def test_permanent_update_identity(self):
        self.assertIn('applicationId = "com.pineapple.sagecommander.stable"', self.gradle)
        self.assertIn('versionCode = 40', self.gradle)
        self.assertIn('versionName = "1.28.0"', self.gradle)
        self.assertIn('sagePermanentSigning', self.gradle)
        self.assertIn('android.permission.REQUEST_INSTALL_PACKAGES', self.manifest)

    def test_overlay_lifetime_and_event_policy(self):
        self.assertIn('DEFAULT_NUMBER_OVERLAY_TIMEOUT_MS = 60000L', self.access)
        self.assertIn('"number_overlay_timeout_ms"', self.access)
        self.assertIn('NUMBER_OVERLAY_EVENT_GRACE_MS = 2500L', self.access)
        self.assertIn('eventPackage.equals(getPackageName())', self.access)
        event_method = self.access.split('public void onAccessibilityEvent', 1)[1].split('@Override', 1)[0]
        self.assertIn('"ignored_content_or_window_churn"', event_method)
        self.assertIn('TYPE_VIEW_SCROLLED', event_method)
        self.assertIn('TYPE_VIEW_CLICKED', event_method)

    def test_number_followup_does_not_clear_itself(self):
        self.assertIn('Numbers ready. Say the red number.', self.commands)
        self.assertNotIn('The numbers clear automatically', self.commands)
        self.assertIn('"play", "video", "item", "choice"', self.commands)
        pending = self.commands.split('if (pendingAction.equals("screen_number"))', 1)[1].split('if (!pendingAction.isEmpty())', 1)[0]
        self.assertIn('SageAccessibilityService.hasNumberOverlay()', pending)
        self.assertNotIn('Those screen numbers already cleared', pending)

    def test_stale_card_and_coordinate_safety(self):
        self.assertIn('Geometry alone must never', self.access)
        self.assertIn('bestScore >= 80', self.access)
        self.assertIn('clickNodeOrTap(currentTarget.node, currentTarget.bounds)', self.access)
        self.assertIn('return tapBounds(fallbackBounds);', self.access)

    def test_wake_soundalikes_require_final_result(self):
        method = self.voice.split('private boolean startsWithWakePhrase', 1)[1].split(
            'private String commandAfterWake', 1)[0]
        self.assertLess(method.index('if (!finalResult)'), method.index('SAFE_PREFIXED_WAKE_SOUNDALIKES'))

    def test_command_engine_runs_before_generic_brain_fallback(self):
        method = self.voice.split('private void handleCommand', 1)[1].split(
            'private boolean isOpenEndedBrainRequest', 1)[0]
        self.assertIn('if (forceBrainForNextCommand)', method)
        execute_index = method.index('SageCommandEngine.Result result = commandEngine.execute(executionCommand)')
        fallback_index = method.index('if (!result.matched && brainManager != null && brainManager.canAnswer())')
        self.assertLess(execute_index, fallback_index)

    def test_custom_voice_response_cancels_stale_followup(self):
        cancel = self.voice.index('commandEngine.cancelFollowUp();',
                self.voice.index('if (voiceResponse != null)'))
        play = self.voice.index('playVoiceResponse(voiceResponse);', cancel)
        self.assertLess(cancel, play)

    def test_current_nonzero_speech_silence_thresholds(self):
        self.assertIn('MINIMUM_LENGTH_MILLIS, 1300L', self.voice)
        self.assertIn('COMPLETE_SILENCE_LENGTH_MILLIS, 1100L', self.voice)
        self.assertIn('POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 750L', self.voice)


if __name__ == '__main__':
    sys.argv = [sys.argv[0]]
    unittest.main(verbosity=2)
