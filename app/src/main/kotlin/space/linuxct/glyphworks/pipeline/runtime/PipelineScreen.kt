package space.linuxct.glyphworks.pipeline.runtime

import space.linuxct.glyphworks.core.GlyphScreen
import space.linuxct.glyphworks.core.ScreenContext

class PipelineScreen(
    override val id: String,
    override val interactive: Boolean,
    override val instantAction: Boolean,
    private val controller: PipelineController,
) : GlyphScreen {
    override fun onActivate(ctx: ScreenContext) = controller.activate(id, ctx)
    override fun suspendForPreview(): Boolean { controller.suspendPreview(); return true }
    override fun resumeFromPreview() { controller.resumePreview() }
    override fun onDeactivate() = controller.deactivate(id)
    override fun onEvent(event: String) { controller.glyphEvent(event) }
}
