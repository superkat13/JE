import pathlib
import sys
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
BUILD = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT / "sage_build_129"
sys.argv[:] = sys.argv[:1]
SOURCE = BUILD / "app/src/main/java/com/pineapple/sage/MainActivity.java"


class OwnerHomeCategoriesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = SOURCE.read_text(encoding="utf-8")

    def test_five_stable_functional_categories_replace_the_giant_home_scroll(self):
        for label in (
            "Voice & Wake",
            "Brain & Memory",
            "Create & Discover",
            "Tools & Settings",
            "Diagnostics & Repair",
        ):
            self.assertIn(f'addCategoryButton(', self.main)
            self.assertIn(f'"{label}"', self.main)
        self.assertIn('panel.setVisibility(View.GONE)', self.main)
        self.assertIn('for (LinearLayout panel : categoryPanels)', self.main)
        self.assertIn('selectedPanel.setVisibility(View.VISIBLE)', self.main)

    def test_push_to_talk_and_text_fallback_remain_permanently_visible(self):
        talk = self.main.index('root.addView(listenButton, spaced())')
        typed = self.main.index('root.addView(typedCommand, spaced())')
        categories = self.main.index('addCategoryNavigation(root, voicePanel')
        self.assertLess(talk, categories)
        self.assertLess(typed, categories)
        self.assertNotIn('voicePanel.addView(listenButton', self.main)
        self.assertNotIn('voicePanel.addView(typedCommand', self.main)

    def test_existing_real_tools_are_reachable_without_placeholder_cards(self):
        for route in (
            'new Intent(this, SageWorkbenchActivity.class)',
            'new Intent(this, SageToolbeltActivity.class)',
            'new Intent(this, SageBrainTestActivity.class)',
            'new Intent(this, SageRepairActivity.class)',
            'new Intent(this, SageAuthorityActivity.class)',
        ):
            self.assertIn(route, self.main)
        self.assertNotIn('coming soon', self.main.lower())

    def test_fun_controls_execute_existing_command_engine_routes(self):
        for command in (
            '"surprise me on YouTube"',
            '"find me something weird"',
            '"send me down a rabbit hole"',
            '"give me a video idea"',
            '"give me an image prompt"',
            '"give me a music idea"',
            '"give me a project idea"',
        ):
            self.assertIn(command, self.main)
        self.assertIn('dispatchTypedCommand(command)', self.main)

    def test_adobe_is_visible_only_for_a_real_launchable_trusted_install(self):
        self.assertIn('SageTrustedAppRegistry.installedAdobeApps(this)', self.main)
        self.assertIn('if (app.launch) launchable.add(app)', self.main)
        self.assertIn('if (launchable.isEmpty()) return', self.main)
        self.assertIn('getLaunchIntentForPackage(app.packageName)', self.main)
        self.assertIn('ADOBE LAUNCH', self.main)
        self.assertNotIn('Install Adobe', self.main)


if __name__ == "__main__":
    unittest.main()
