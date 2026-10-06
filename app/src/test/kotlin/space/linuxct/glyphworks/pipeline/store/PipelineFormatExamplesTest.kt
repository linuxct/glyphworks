package space.linuxct.glyphworks.pipeline.store

import org.junit.Assert.*
import org.junit.Test
import space.linuxct.pipeline.PipelineCodec
import java.io.File

class PipelineFormatExamplesTest {
    @Test fun documentedPortableExamplesDecodeAndRoundTrip() {
        val directory = File("../docs/examples")
        val examples = directory.listFiles { file -> file.name.endsWith(".glyph.pipeline.json") }.orEmpty()
        assertTrue("Portable examples missing", examples.size >= 2)
        for (file in examples) {
            val decoded = file.inputStream().use(PipelineCodec::decode)
            assertTrue("${file.name}: $decoded", decoded is PipelineCodec.Result.Ok)
            val document = (decoded as PipelineCodec.Result.Ok).document
            assertEquals(document, (PipelineCodec.decode(PipelineCodec.encode(document)) as PipelineCodec.Result.Ok).document)
        }
    }
}
