package com.pineapple.sageos2.capability

import com.pineapple.sageos2.action.Direction
import com.pineapple.sageos2.action.FastCommand
import com.pineapple.sageos2.action.MediaAction
import com.pineapple.sageos2.action.VolumeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceCapabilityMapperTest {
    @Test fun mapsOwnerAppStartupToolsOntoExistingDeterministicCommands() {
        assertEquals(
            FastCommand.OpenApp("Firefox"),
            DeviceCapabilityMapper.map(DeviceAction("device.open_app", mapOf("app" to "Firefox")))
        )
        assertEquals(
            FastCommand.TapLabel("Private browsing"),
            DeviceCapabilityMapper.map(DeviceAction("device.tap_label", mapOf("label" to "Private browsing")))
        )
        assertEquals(
            FastCommand.Scroll(Direction.DOWN),
            DeviceCapabilityMapper.map(DeviceAction("device.scroll", mapOf("direction" to "down")))
        )
        assertEquals(
            FastCommand.Volume(VolumeDirection.UP),
            DeviceCapabilityMapper.map(DeviceAction("device.volume", mapOf("direction" to "up")))
        )
        assertEquals(
            FastCommand.Media(MediaAction.NEXT),
            DeviceCapabilityMapper.map(DeviceAction("device.media", mapOf("action" to "next")))
        )
        assertEquals(
            FastCommand.SetAlarm(7, 30),
            DeviceCapabilityMapper.map(
                DeviceAction("device.set_alarm", mapOf("hour24" to "7", "minute" to "30"))
            )
        )
    }

    @Test fun unsupportedDeviceToolDoesNotInventACommand() {
        assertNull(DeviceCapabilityMapper.map(DeviceAction("device.imaginary", emptyMap())))
    }

    @Test fun malformedStructuredDeviceArgumentsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceCapabilityMapper.map(DeviceAction("device.tap", mapOf("x" to "not-a-number", "y" to "2")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceCapabilityMapper.map(DeviceAction("device.scroll", mapOf("direction" to "diagonal")))
        }
    }
}
