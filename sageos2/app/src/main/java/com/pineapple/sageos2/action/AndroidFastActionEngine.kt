package com.pineapple.sageos2.action

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

class AndroidFastActionEngine(
    private val controller: DeviceController,
    private val parser: FastCommandParser = FastCommandParser(),
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) : FastActionEngine {
    override fun start(request: FastActionRequest, callback: (Result<FastActionResponse>) -> Unit): FastActionJob {
        val cancelled = AtomicBoolean(false)
        val job = object : FastActionJob {
            override val turnId: Long = request.turnId
            override fun cancel() { cancelled.set(true) }
        }
        val command = parser.parse(request.command)
        if (command == null) {
            callback(Result.failure(IllegalArgumentException("Unsupported fast command: ${request.command}")))
            return job
        }
        mainHandler.post {
            if (cancelled.get()) return@post
            try {
                val result = controller.execute(command)
                if (!cancelled.get()) {
                    if (result.success) callback(Result.success(FastActionResponse(request.turnId, result.message)))
                    else callback(Result.failure(IllegalStateException(result.message)))
                }
            } catch (t: Throwable) {
                if (!cancelled.get()) callback(Result.failure(t))
            }
        }
        return job
    }
}
