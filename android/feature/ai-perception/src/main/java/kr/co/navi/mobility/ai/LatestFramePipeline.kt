package kr.co.navi.mobility.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kr.co.navi.mobility.guidance.contract.*
import java.util.concurrent.atomic.AtomicInteger

/** At most one processing frame and one pending copy; all termination paths release ownership. */
class LatestFramePipeline(
    scope: CoroutineScope,
    private val engine: PerceptionEngine,
    private val consume: suspend (SpatialFrameContext,PerceptionFrameLease,PerceptionResult)->Unit,
    private val failure: (Throwable)->Unit,
) : AutoCloseable {
    private data class Input(val spatial: SpatialFrameContext,val frame: PerceptionFrameLease)
    val openLeases=AtomicInteger(0)
    private fun release(input: Input) {try{input.frame.close()}finally{openLeases.decrementAndGet()}}
    private val channel=Channel<Input>(1,BufferOverflow.DROP_OLDEST,onUndeliveredElement=::release)
    private val job=scope.launch(Dispatchers.Default) {
        try {
            for(input in channel) {
                try { consume(input.spatial,input.frame,engine.analyze(input.frame)) }
                catch(e: CancellationException){throw e}
                catch(e: Throwable){failure(e)}
                finally {release(input)}
            }
        } finally {engine.close()}
    }
    fun submit(spatial: SpatialFrameContext,frame: PerceptionFrameLease) {
        val input=Input(spatial,frame);openLeases.incrementAndGet()
        if(channel.trySend(input).isFailure) release(input)
    }
    suspend fun finish() {channel.close();job.join()}
    override fun close() {channel.cancel();job.cancel()}
}
