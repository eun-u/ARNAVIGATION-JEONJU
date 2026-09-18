package kr.co.navi.mobility.ai.cone

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opt-in evaluation of real captured pixels. Outputs candidates, never ground truth. */
class ConeFieldAuditTest {
    @Test fun auditRecordedPixelsWithProductionDetector() {
        val root=System.getenv("NAVI_CONE_EVAL_DIR")
        assumeTrue("Set NAVI_CONE_EVAL_DIR for local field audit",root!=null)
        val directory=File(requireNotNull(root));val detector=ConeShapeDetector()
        var processed=0
        File(directory,"cone_predictions.tsv").bufferedWriter().use { out->
            out.appendLine("run\tframe_id\telapsed_ms\tlabel\tscore\tleft\ttop\tright\tbottom")
            File(directory,"frames.tsv").forEachLine {line->
                if(line.startsWith("run\t"))return@forEachLine
                val f=line.split('\t');val width=f[4].toInt();val height=f[5].toInt()
                val buffer=ByteBuffer.wrap(File(f[3]).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                require(buffer.remaining()==width*height*4)
                val upright=IntArray(width*height){buffer.int}
                val detections=detector.detect(upright,width,height)
                processed++
                if(detections.isEmpty())out.appendLine("${f[0]}\t${f[1]}\t${f[2]}\tnone\t0\t0\t0\t0\t0")
                detections.forEach {d->out.appendLine("${f[0]}\t${f[1]}\t${f[2]}\t${d.label}\t${d.confidence}\t${d.bounds.left}\t${d.bounds.top}\t${d.bounds.right}\t${d.bounds.bottom}")}
            }
        }
        assertTrue("Field audit must not pass on an empty input",processed>0)
    }
}
