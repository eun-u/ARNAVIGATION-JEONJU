package kr.co.navi.mobility.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReferenceCaptureTest {
    @Test fun `empty form reports a specific missing field instead of a numeric exception`() {
        assertEquals("기준점 이름을 입력하세요.",runCatching{parseReferenceInput("","","","")}.exceptionOrNull()?.message)
        assertEquals("위도 값을 입력하세요.",runCatching{parseReferenceInput("","127.132","0.1","A")}.exceptionOrNull()?.message)
        assertEquals("경도 값을 입력하세요.",runCatching{parseReferenceInput("35.846","","0.1","A")}.exceptionOrNull()?.message)
        assertEquals("위치 오차 값을 입력하세요.",runCatching{parseReferenceInput("35.846","127.132","","A")}.exceptionOrNull()?.message)
    }

    @Test fun `malformed and nonfinite coordinates never become registered positions`() {
        for(value in listOf("abc","NaN","Infinity","-Infinity","89","-89","91")) {
            assertTrue(runCatching{parseReferenceInput(value,"127.132","0.1","A")}.isFailure)
        }
        assertTrue(runCatching{parseReferenceInput("35.846","181","0.1","A")}.isFailure)
        for(value in listOf("NaN","Infinity","0","0.009","0.501")) {
            assertTrue(runCatching{parseReferenceInput("35.846","127.132",value,"A")}.isFailure)
        }
    }

    @Test fun `measured values are preserved without inventing accuracy`() {
        val input=parseReferenceInput(" 35.8463514 ","127.1319861","0.05"," A ")
        assertEquals(35.8463514,input.coordinate.latitude,0.0)
        assertEquals(127.1319861,input.coordinate.longitude,0.0)
        assertEquals(0.05,input.accuracyMeters,0.0)
        assertEquals("A",input.label)
    }

    @Test fun `A registration can succeed before a two point calibration exists`() = runTest {
        assertNull(awaitReferenceCapture<String?> { callback -> callback(Result.success(null)) })
    }

    @Test fun `missing renderer callback expires and a late result cannot revive it`() = runTest {
        var callback: ((Result<String?>)->Unit)?=null
        val result=runCatching{awaitReferenceCapture<String?>(100){callback=it}}
        assertTrue(result.exceptionOrNull() is ReferenceCaptureTimeout)
        callback!!(Result.success("late calibration"))
        assertTrue(result.isFailure)
    }

    @Test fun `renderer error is retained for the visible registration feedback`() = runTest {
        val error=IllegalStateException("카메라 추적을 기다리세요.")
        val result=runCatching{awaitReferenceCapture<String?>{it(Result.failure(error))}}
        assertEquals(error.message,result.exceptionOrNull()?.message)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `leaving the screen cancels the pending capture and ignores its late callback`() = runTest {
        var callback: ((Result<String?>)->Unit)?=null
        val pending=async{awaitReferenceCapture<String?>{callback=it}}
        runCurrent()
        pending.cancel(CancellationException("screen paused"))
        runCurrent()
        callback!!(Result.success("stale calibration"))
        assertTrue(pending.isCancelled)
    }
}
