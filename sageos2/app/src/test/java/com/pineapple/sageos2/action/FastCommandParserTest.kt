package com.pineapple.sageos2.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FastCommandParserTest {
    private val parser = FastCommandParser()

    @Test fun parsesOpenApp() = assertEquals(FastCommand.OpenApp("youtube"), parser.parse("Open YouTube"))
    @Test fun parsesNavigation() {
        assertEquals(FastCommand.Back, parser.parse("go back"))
        assertEquals(FastCommand.Home, parser.parse("home"))
        assertEquals(FastCommand.Recents, parser.parse("show recents"))
        assertEquals(FastCommand.Notifications, parser.parse("show notifications"))
        assertEquals(FastCommand.QuickSettings, parser.parse("quick settings"))
    }
    @Test fun parsesGestures() {
        assertEquals(FastCommand.Scroll(Direction.DOWN), parser.parse("scroll down"))
        assertEquals(FastCommand.Swipe(Direction.LEFT), parser.parse("swipe left"))
        assertEquals(FastCommand.Tap(420f, 815f), parser.parse("tap 420, 815"))
    }
    @Test fun parsesVolume() {
        assertEquals(FastCommand.Volume(VolumeDirection.UP), parser.parse("volume up"))
        assertEquals(FastCommand.Volume(VolumeDirection.MUTE), parser.parse("mute"))
    }
    @Test fun parsesTimersAlarmsAndScreenshot() {
        assertEquals(FastCommand.SetTimer(600), parser.parse("set a timer for 10 minutes"))
        assertEquals(FastCommand.SetTimer(30), parser.parse("timer for 30 seconds"))
        assertEquals(FastCommand.SetAlarm(7, 0), parser.parse("set an alarm for 7 am"))
        assertEquals(FastCommand.SetAlarm(19, 30), parser.parse("set alarm for 7:30 pm"))
        assertEquals(FastCommand.TakeScreenshot, parser.parse("take a screenshot"))
    }

    @Test fun parsesSemanticTapWithoutCapturingCoordinateTap() {
        assertEquals(FastCommand.TapLabel("submit"), parser.parse("tap Submit"))
        assertEquals(FastCommand.TapLabel("continue"), parser.parse("press Continue"))
        assertEquals(FastCommand.Tap(420f, 815f), parser.parse("tap 420, 815"))
    }
    @Test fun parsesNotificationSummaryAndMediaControls() {
        assertEquals(FastCommand.ReadNotifications, parser.parse("read notifications"))
        assertEquals(FastCommand.ReadNotifications, parser.parse("what are my notifications"))
        assertEquals(FastCommand.Media(MediaAction.PLAY), parser.parse("play music"))
        assertEquals(FastCommand.Media(MediaAction.PAUSE), parser.parse("pause media"))
        assertEquals(FastCommand.Media(MediaAction.NEXT), parser.parse("next track"))
        assertEquals(FastCommand.Media(MediaAction.PREVIOUS), parser.parse("previous song"))
    }
    @Test fun parsesDiagnosticShareWithoutBrain() {
        assertEquals(FastCommand.ShareDiagnosticReport, parser.parse("share diagnostic report"))
        assertEquals(FastCommand.ShareDiagnosticReport, parser.parse("share Sage diagnostic report"))
        assertEquals(FastCommand.ShareDiagnosticReport, parser.parse("send diagnostic report"))
    }
    @Test fun parsesBrainModelImportWithoutBrain() {
        assertEquals(FastCommand.ImportBrainModel, parser.parse("import brain model"))
        assertEquals(FastCommand.ImportBrainModel, parser.parse("choose brain model"))
        assertEquals(FastCommand.ImportBrainModel, parser.parse("load brain model"))
    }
    @Test fun rejectsUnknownCommand() = assertNull(parser.parse("explain gravity"))
}
