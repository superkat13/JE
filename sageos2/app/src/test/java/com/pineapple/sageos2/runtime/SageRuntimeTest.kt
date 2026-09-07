package com.pineapple.sageos2.runtime

import com.pineapple.sageos2.action.*
import com.pineapple.sageos2.brain.*
import com.pineapple.sageos2.capability.*
import com.pineapple.sageos2.core.*
import com.pineapple.sageos2.speech.*
import com.pineapple.sageos2.workflow.WorkflowEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SageRuntimeTest {
    @Test fun speechInputIsAttachedDirectlyToSingleRuntimeCoordinator() {
        val f = Fixture(); f.runtime.start(); assertNotNull(f.speech.listener)
        f.speech.listener!!.onWakeDetected(WakeHit(f.runtime.snapshot().recognizerGeneration, "sage", null, "Yes"))
        assertEquals(SageRuntimeState.ACKNOWLEDGING_WAKE, f.runtime.snapshot().state)
    }

    @Test fun recognitionErrorReturnsToUsableConversationPath() {
        val f = Fixture(); f.runtime.start()
        f.speech.listener!!.onWakeDetected(WakeHit(f.runtime.snapshot().recognizerGeneration, "sage", null, "Yes"))
        f.speech.completeLastSpeech(); val s = f.runtime.snapshot()
        f.speech.listener!!.onRecognitionError(s.activeTurnId, s.recognizerGeneration, 7)
        assertEquals(SageRuntimeState.SPEAKING, f.runtime.snapshot().state)
        assertEquals("I didn't catch that.", f.speech.spoken.last().second)
    }

    @Test fun customWakeProfileActivatesModeInsideSameRuntime() {
        val f = Fixture(); f.runtime.start()
        f.speech.listener!!.onWakeDetected(WakeHit(f.runtime.snapshot().recognizerGeneration, "sage_glitch", "red_queen", "Yes"))
        assertEquals("red_queen", com.pineapple.sageos2.mode.DefaultSageModeController.current().modeId)
    }

    @Test fun deepBrainReceivesRenderedVirtualTwinContext() {
        val f = Fixture(); f.runtime.start(); f.runtime.submit(SageEvent.TextSubmitted("tell me something useful"))
        assertEquals(1, f.brain.requests.size)
        val request = f.brain.requests.single()
        assertTrue(request.twinContextText?.contains("virtual twin") == true)
        assertTrue(request.twinContextText?.contains("Self restrictions: (none)") == true)
        assertTrue(request.twinContextText?.contains("SAGE TOOL CONTRACT") == true)
    }

    @Test fun fastDeviceCommandBypassesBrain() {
        val f = Fixture(); f.runtime.start(); f.runtime.submit(SageEvent.TextSubmitted("open youtube"))
        assertEquals(1, f.fast.requests.size); assertEquals(0, f.brain.requests.size)
    }

    @Test fun ownerWorkflowTriggerIsSilent() {
        val f = Fixture(); f.runtime.start(); f.runtime.submit(SageEvent.TextSubmitted("Do you feel like chicken tonight?"))
        assertEquals(listOf("chicken_tonight"), f.workflows.launched); assertTrue(f.speech.spoken.isEmpty())
    }

    @Test fun exactBrainToolDirectiveExecutesCapabilityAndContinuesSameTurn() {
        val capability = FakeCapabilityBroker(rootActive = true)
        val f = Fixture(capability)
        f.runtime.start()
        f.runtime.submit(SageEvent.TextSubmitted("check your root identity"))
        val turn = f.runtime.snapshot().activeTurnId

        f.brain.respond(
            0,
            """
            <SAGE_TOOL>
            name=root.exec
            executable=/system/bin/id
            arg.0=-u
            timeout_ms=5000
            </SAGE_TOOL>
            """.trimIndent()
        )

        waitUntil { capability.actions.size == 1 && f.brain.requests.size == 2 }
        assertEquals("root.exec", capability.actions.single().name)
        assertEquals("/system/bin/id", capability.actions.single().arguments["executable"])
        assertEquals(turn, f.brain.requests[1].turnId)
        assertTrue(f.brain.requests[1].prompt.contains("SAGE_TOOL_RESULT"))
        assertTrue(f.brain.requests[1].prompt.contains("success=true"))

        f.brain.respond(1, "Root is alive and answering through the broker.")
        waitUntil { f.observer.textResponses.isNotEmpty() }
        assertEquals("Root is alive and answering through the broker.", f.observer.textResponses.last().second)
        assertTrue(f.speech.spoken.isEmpty())
    }

    @Test fun normalBrainProseMentioningToolNameNeverExecutesCapability() {
        val capability = FakeCapabilityBroker(rootActive = true)
        val f = Fixture(capability)
        f.runtime.start(); f.runtime.submit(SageEvent.TextSubmitted("explain the root path"))
        f.brain.respond(0, "The root.exec tool uses executable and argv fields.")
        waitUntil { f.observer.textResponses.isNotEmpty() }
        assertTrue(capability.actions.isEmpty())
    }

    @Test fun fifthToolCallIsBlockedByPerTurnLimit() {
        val capability = FakeCapabilityBroker(rootActive = true)
        val f = Fixture(capability)
        f.runtime.start(); f.runtime.submit(SageEvent.TextSubmitted("run a bounded diagnostic chain"))

        repeat(4) { index ->
            f.brain.respond(index, "<SAGE_TOOL>\nname=root.health\n</SAGE_TOOL>")
            waitUntil { capability.actions.size == index + 1 && f.brain.requests.size == index + 2 }
        }
        f.brain.respond(4, "<SAGE_TOOL>\nname=root.health\n</SAGE_TOOL>")
        Thread.sleep(80)
        assertEquals(4, capability.actions.size)
        assertEquals(5, f.brain.requests.size)
    }

    private class Fixture(capability: CapabilityBroker = EmptyCapabilityBroker) {
        val speech = FakeSpeech(); val brain = FakeBrain(); val fast = FakeFastActions(); val workflows = FakeWorkflows(); val scheduler = FakeScheduler(); val observer = FakeObserver()
        val runtime = SageRuntime(SageTurnCoordinator(), speech, brain, fast, workflows, scheduler, observer = observer, capabilities = capability)
    }

    private class FakeSpeech : SpeechPort {
        var listener: SpeechInputListener? = null; val spoken = mutableListOf<Pair<Long,String>>(); private var completion:(()->Unit)?=null
        override fun attach(listener: SpeechInputListener){this.listener=listener}
        override fun setListening(mode:SageListeningMode,generation:Long,turnId:Long)=Unit
        override fun speak(turnId:Long,text:String,onComplete:()->Unit){spoken+=turnId to text; completion=onComplete}
        override fun speakTransient(text:String)=Unit
        fun completeLastSpeech(){val c=completion?:error("no speech"); completion=null; c()}
    }

    private class FakeBrain:BrainEngine {
        override val name="fake"
        val requests=mutableListOf<BrainRequest>()
        private val callbacks=mutableListOf<(Result<BrainResponse>)->Unit>()
        override fun health()=BrainHealth(true,"ready")
        override fun start(request:BrainRequest,callback:(Result<BrainResponse>)->Unit):BrainJob {
            requests += request; callbacks += callback
            return object:BrainJob{override val turnId=request.turnId;override fun cancel()=Unit}
        }
        fun respond(index:Int,text:String) {
            val request=requests[index]
            callbacks[index](Result.success(BrainResponse(request.turnId,text,name)))
        }
    }

    private class FakeFastActions:FastActionEngine{val requests=mutableListOf<FastActionRequest>();override fun start(request:FastActionRequest,callback:(Result<FastActionResponse>)->Unit):FastActionJob{requests+=request;return object:FastActionJob{override val turnId=request.turnId;override fun cancel()=Unit}}}
    private class FakeWorkflows:WorkflowEngine{val launched=mutableListOf<String>();override fun launch(turnId:Long,workflowId:String){launched+=workflowId}}
    private class FakeScheduler:RuntimeScheduler{override fun schedule(delayMs:Long,task:()->Unit)=object:ScheduledHandle{override fun cancel()=Unit}}
    private class FakeObserver:RuntimeObserver {
        val textResponses=mutableListOf<Pair<Long,String>>()
        override fun onTextResponse(turnId:Long,text:String){textResponses += turnId to text}
    }
    private class FakeCapabilityBroker(private val rootActive:Boolean):CapabilityBroker {
        val actions=java.util.Collections.synchronizedList(mutableListOf<DeviceAction>())
        override fun snapshot()=CapabilitySnapshot(mapOf(Capability.SAGEOS_ROOT_BROKER to if(rootActive) CapabilityStatus.ACTIVE else CapabilityStatus.UNAVAILABLE))
        override fun execute(action:DeviceAction):CapabilityResult { actions += action; return CapabilityResult(true,"uid=0") }
    }

    private fun waitUntil(timeoutMs:Long=1_500, condition:()->Boolean) {
        val deadline=System.nanoTime()+timeoutMs*1_000_000
        while(!condition()) {
            if(System.nanoTime()>=deadline) error("condition was not met within ${timeoutMs}ms")
            Thread.sleep(10)
        }
    }
}
