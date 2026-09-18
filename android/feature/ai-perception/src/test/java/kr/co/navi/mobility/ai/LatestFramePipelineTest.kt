package kr.co.navi.mobility.ai

import kotlinx.coroutines.*
import kr.co.navi.mobility.guidance.contract.*
import org.junit.Assert.*
import org.junit.Test

class LatestFramePipelineTest {
    private class Lease(val id: Long): PerceptionFrameLease {
        override val stamp=FrameStamp(id,id)
        override val width=1;override val height=1;override val rotationDegrees=0
        override val pixelFormat=PixelFormat.RGBA_8888;override val planes=emptyList<FramePlane>()
        var closes=0
        override fun close(){closes++}
    }
    private fun spatial(f: Lease)=SpatialFrameContext(f.stamp,null,null,null,TrackingQuality.UNAVAILABLE,false)
    @Test fun dropsOldPendingFrameAndClosesEveryLease()=runBlocking {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val processed=mutableListOf<Long>()
        val engine=object: PerceptionEngine {
            override suspend fun analyze(frame: PerceptionFrameLease): PerceptionResult {
                if(frame.stamp.frameId==1L){started.complete(Unit);release.await()}
                return PerceptionResult(frame.stamp,"test",0,emptyList())
            }
            override fun close(){}
        }
        val pipeline=LatestFramePipeline(this,engine,{_,f,_->processed+=f.stamp.frameId},{throw it})
        val frames=(1L..3L).map(::Lease)
        pipeline.submit(spatial(frames[0]),frames[0]);withTimeout(2000){started.await()}
        pipeline.submit(spatial(frames[1]),frames[1]);pipeline.submit(spatial(frames[2]),frames[2]);release.complete(Unit)
        withTimeout(2000){pipeline.finish()}
        assertEquals(listOf(1L,3L),processed);assertTrue(frames.all{it.closes==1});assertEquals(0,pipeline.openLeases.get())
    }
    @Test fun cancellationClosesProcessingAndPendingFrames()=runBlocking {
        val started=CompletableDeferred<Unit>()
        val engine=object: PerceptionEngine {
            override suspend fun analyze(frame: PerceptionFrameLease): PerceptionResult {started.complete(Unit);awaitCancellation()}
            override fun close(){}
        }
        val pipeline=LatestFramePipeline(this,engine,{_,_,_->},{throw it})
        val a=Lease(1);val b=Lease(2)
        pipeline.submit(spatial(a),a);withTimeout(2000){started.await()};pipeline.submit(spatial(b),b);pipeline.close()
        withTimeout(2000){while(pipeline.openLeases.get()!=0)delay(10)}
        assertEquals(1,a.closes);assertEquals(1,b.closes)
    }
}
