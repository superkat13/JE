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
    @Test fun rejectsUnknownCommand() = assertNull(parser.parse("explain gravity"))
}
