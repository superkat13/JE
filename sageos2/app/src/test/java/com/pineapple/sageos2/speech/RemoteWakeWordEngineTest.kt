package com.pineapple.sageos2.speech

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class RemoteWakeWordEngineTest {
    private lateinit var context: WakeContext
    private lateinit var engine: RemoteWakeWordEngine

    @Before fun setup() {
        context = WakeContext(RuntimeEnvironment.getApplication())
        engine = RemoteWakeWordEngine(context)
    }
    @After fun cleanup() { engine.close(); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun start() { engine.start(1L) {}; idle() }

    @Test fun configurationAcknowledgementIsNotListening() {
        start()
        assertFalse(engine.health().ready)
        assertEquals(1, context.binds)
    }

    @Test fun repeatedFailedStartsStopAfterThreeRetriesDespiteConfigurationAcks() {
        context.failStarts = true
        start()
        advance(750)
        advance(2_000)
        advance(5_000)
        assertEquals(4, context.binds)
        assertFalse(engine.health().ready)
        assertTrue(engine.health().detail.contains("paused"))
        engine.start(2L) {}; idle()
        advance(60_000)
        assertEquals(4, context.binds)
        assertTrue(context.stops >= 4)
    }

    @Test fun missingAudioHealthTriggersRecovery() {
        start()
        advance(30_000)
        assertFalse(engine.health().ready)
        assertTrue(engine.health().detail.contains("timed out"))
        advance(750)
        assertEquals(2, context.binds)
    }

    @Test fun liveStatusExpiresWhenAudioHeartbeatDisappears() {
        start()
        context.status(true)
        idle()
        assertTrue(engine.health().ready)
        advance(10_000)
        assertFalse(engine.health().ready)
        assertTrue(engine.health().detail.contains("timed out"))
    }

    @Test fun callbacksFromDiscardedConnectionCannotPoisonRecovery() {
        start()
        val oldConnection = context.connections.first()
        val oldReply = context.reply!!
        context.status(false); idle()
        advance(750)
        context.status(true); idle()
        assertTrue(engine.health().ready)
        oldConnection.onServiceDisconnected(context.component)
        oldReply.send(Message.obtain(null, RemoteWakeProtocol.MSG_STATUS).apply {
            data = Bundle().apply {
                putLong(RemoteWakeProtocol.KEY_GENERATION, 1L)
                putBoolean(RemoteWakeProtocol.KEY_READY, false)
            }
        })
        idle()
        assertTrue(engine.health().ready)
        assertEquals(2, context.binds)
    }

    @Test fun intentionalStopCancelsHealthDeadlineAndRetry() {
        start()
        engine.stop(); idle()
        advance(60_000)
        assertEquals(1, context.binds)
        assertTrue(engine.health().detail.contains("intentionally stopped"))
    }

    private class WakeContext(base: Context) : ContextWrapper(base) {
        val component = ComponentName(packageName, "FakeWakeService")
        val connections = mutableListOf<ServiceConnection>()
        var binds = 0
        var stops = 0
        var failStarts = false
        var reply: Messenger? = null
        var generation = 0L
        override fun getApplicationContext(): Context = this
        override fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED
        override fun startForegroundService(intent: Intent): ComponentName = component
        override fun stopService(intent: Intent): Boolean { stops++; return true }
        override fun unbindService(connection: ServiceConnection) {}
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            binds++
            connections.add(connection)
            val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
                reply = message.replyTo
                generation = message.data.getLong(RemoteWakeProtocol.KEY_GENERATION)
                when (message.what) {
                    RemoteWakeProtocol.MSG_CONFIGURE -> reply?.send(Message.obtain(null, RemoteWakeProtocol.MSG_ACKNOWLEDGED))
                    RemoteWakeProtocol.MSG_START -> if (failStarts) status(false)
                }
                true
            })
            Handler(Looper.getMainLooper()).post { connection.onServiceConnected(component, messenger.binder) }
            return true
        }
        fun status(ready: Boolean) {
            reply!!.send(Message.obtain(null, RemoteWakeProtocol.MSG_STATUS).apply {
                data = Bundle().apply {
                    putLong(RemoteWakeProtocol.KEY_GENERATION, generation)
                    putBoolean(RemoteWakeProtocol.KEY_READY, ready)
                    putString(RemoteWakeProtocol.KEY_DETAIL, if (ready) "audio advancing" else "fake audio failure")
                }
            })
        }
    }
}
