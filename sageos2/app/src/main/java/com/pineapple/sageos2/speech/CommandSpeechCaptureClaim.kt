package com.pineapple.sageos2.speech

import java.util.concurrent.atomic.AtomicReference

/**
 * One turn's claim on the microphone, published atomically with cancellation.
 *
 * The defect this exists to close: a worker that pauses during recognizer setup can be cancelled,
 * superseded by a successor that opens its own microphone, and then resume and publish its own
 * AudioRecord over the top of the successor's. Admission then reads a claim that belongs to a turn
 * nobody owns any more, and two turns contend for one device.
 *
 * The reason a plain reference is not enough is that "am I still the live turn?" and "I am taking
 * the device" cannot be one check. Reading the answer and then acting on it leaves a window: the
 * cancel lands in between, clears the field, and the worker's unconditional write puts a stale
 * capture back after the successor was admitted.
 *
 * So the claim holds a [PENDING] placeholder from the moment a turn is admitted, before its
 * microphone exists. Every path that takes the device away from a turn stores null into the slot
 * first. A worker that was cancelled while it was still setting up therefore finds null where it
 * expected [PENDING], loses its compareAndSet, and never opens the microphone. When the worker wins
 * the compareAndSet instead, the cancelling path reads the record back out and releases it. There is
 * no interleaving that leaves a cancelled turn holding the device, because both sides contend for
 * the same reference and the sentinel makes "freed by a cancel that already happened" different from
 * "free and waiting".
 *
 * The placeholder is what lets the device be claimed before the AudioRecord exists, which is what
 * makes the claim atomic. Nothing else here needs to know about turns, cancellation or the platform.
 */
class CommandSpeechCaptureClaim {

    /**
     * What the slot holds while the turn is admitted but has not opened its microphone yet.
     *
     * A private instance, so no caller can accidentally present it as a record.
     */
    private object Pending

    private val held = AtomicReference<Any?>(Pending)

    /** True from admission until the claim is released, including while the record is still pending. */
    fun isHeld(): Boolean = held.get() != null

    /** True while the turn holds the claim but has not opened its microphone yet. */
    fun isPending(): Boolean = held.get() === Pending

    /** True when [record] is the microphone this claim published. */
    fun holds(record: Any): Boolean = held.get() === record

    /**
     * Publishes [record] as this claim's microphone.
     *
     * False when the claim is no longer pending, which is what a stop, cancel, destroy or a
     * superseded turn does to it. The caller must then release the record it just opened and take no
     * further part in the turn.
     */
    fun publish(record: Any): Boolean = held.compareAndSet(Pending, record)

    /**
     * Gives the claim up and returns whatever it was holding.
     *
     * The pending placeholder is not returned as a record, so a caller releasing a cancelled turn
     * that never opened its microphone has nothing to release. A turn that did publish gets its own
     * record back and no other turn's, because a successor could only have claimed after this slot
     * was emptied.
     */
    fun release(): Any? = held.getAndSet(null)
}
