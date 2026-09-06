package com.pineapple.sageos2.runtime

import com.pineapple.sageos2.action.*
import com.pineapple.sageos2.brain.*
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
        f.speech.listener!!.onWakeDetected(f.runtime.snapshot().recognizerGeneration)
        assertEquals(SageRuntimeState.ACKNOWLEDGING_WAKE, f.runtime.snapshot().state)
    }

    @Test fun recognitionErrorReturnsToUsableConversationPath() {
        val f = Fixture(); f.runtime.start(); f.speech.listener!!.onWakeDetected(f.runtime.snapshot().recognizerGeneration)
        f.speech.completeLastSpeech(); val s = f.runtime.snapshot()
        f.speech.listener!!.onRecognitionError(s.activeTurnId, s.recognizerGeneration, 7)
        assertEquals(SageRuntimeState.SPEAKING, f.runtime.snapshot().state)
        assertEquals("I didn't catch that.", f.speech.spoken.last().second)
    }

    @Test fun fastDeviceCommandBypassesBrain() {
        val f = Fixture(); f.runtime.start(); f.runtime.submit(SageEvent.TextSubmitted("open youtube"))
        assertEquals(1, f.fast.requests.size); assertEquals(0, f.brain.requests.size)
    }

    @Test fun ownerWorkflowTriggerIsSilent() {
        val f = Fixture(); f.runtime.start(); f.runtime.submit(SageEvent.TextSubmitted("Do you feel like chicken tonight?"))
        assertEquals(listOf("chicken_tonight"), f.workflows.launched); assertTrue(f.speech.spoken.isEmpty())
    }

    private class Fixture {
        val speech = FakeSpeech(); val brain = FakeBrain(); val fast = FakeFastActions(); val workflows = FakeWorkflows(); val scheduler = FakeScheduler()
        val runtime = SageRuntime(SageTurnCoordinator(), speech, brain, fast, workflows, scheduler)
    }
    private class FakeSpeech : SpeechPort {
        var listener: SpeechInputListener? = null; val spoken = mutableListOf<Pair<Long,String>>(); private var completion:(()->Unit)?=null
        override fun attach(listener: SpeechInputListener){this.listener=listener}
        override fun setListening(mode:SageListeningMode,generation:Long,turnId:Long)=Unit
        override fun speak(turnId:Long,text:String,onComplete:()->Unit){spoken+=turnId to text; completion=onComplete}
        override fun speakTransient(text:String)=Unit
        fun completeLastSpeech(){val c=completion?:error("no speech"); completion=null; c()}
    }
    private class FakeBrain:BrainEngine{override val name="fake"; val requests=mutableListOf<BrainRequest>(); override fun health()=BrainHealth(true,"ready"); override fun start(request:BrainRequest,callback:(Result<BrainResponse>)->Unit):BrainJob{requests+=request;return object:BrainJob{override val turnId=request.turnId;override fun cancel()=Unit}}}
    private class FakeFastActions:FastActionEngine{val requests=mutableListOf<FastActionRequest>();override fun start(request:FastActionRequest,callback:(Result<FastActionResponse>)->Unit):FastActionJob{requests+=request;return object:FastActionJob{override val turnId=request.turnId;override fun cancel()=Unit}}}
    private class FakeWorkflows:WorkflowEngine{val launched=mutableListOf<String>();override fun launch(turnId:Long,workflowId:String){launched+=workflowId}}
    private class FakeScheduler:RuntimeScheduler{override fun schedule(delayMs:Long,task:()->Unit)=object:ScheduledHandle{override fun cancel()=Unit}}
}
