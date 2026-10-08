package com.pineapple.sageos2.capability

import android.os.Looper
import com.pineapple.sageos2.action.DeviceControlResult
import com.pineapple.sageos2.action.DeviceController
import com.pineapple.sageos2.action.FastCommand
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidDeviceDispatchTest {
    @Test fun timedOutQueuedActionCannotExecuteLater() = checkAbandonedAction(false)

    @Test fun interruptedQueuedActionCannotExecuteLater() = checkAbandonedAction(true)

    private fun checkAbandonedAction(interrupt: Boolean) {
        val calls = AtomicInteger()
        val broker = AndroidCapabilityBroker(
            RuntimeEnvironment.getApplication(),
            deviceController = object : DeviceController {
                override fun execute(command: FastCommand): DeviceControlResult {
                    calls.incrementAndGet()
                    return DeviceControlResult(true, "opened")
                }
            },
            deviceTimeoutMs = 20L
        )
        val result = AtomicReference<CapabilityResult>()
        val interrupted = AtomicReference(false)
        val worker = Thread {
            if (interrupt) Thread.currentThread().interrupt()
            result.set(broker.execute(DeviceAction("device.home", emptyMap())))
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        worker.start()
        worker.join(2_000L)
        assertFalse("Worker must finish within its bounded wait", worker.isAlive)
        assertFalse(result.get().success)
        assertTrue(result.get().detail.contains("cancelled"))
        assertEquals(interrupt, interrupted.get())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Draining the UI queue must not execute abandoned work", 0, calls.get())
    }
}
