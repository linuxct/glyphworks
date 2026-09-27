package space.linuxct.glyphworks.ui

import space.linuxct.glyphworks.matrix.MAX_BRIGHTNESS
import space.linuxct.glyphworks.matrix.MatrixCanvas
import java.net.URLEncoder

/** Carousel action only: deliberately absent from the screen registry and saved rotation. */
internal object ToyRequest {
    const val ID = "request_toy"
    const val EMAIL = "glyphworks@linuxct.space"
    const val SUBJECT = "Design request"
    const val BODY = "-- PLEASE WRITE YOUR INTERACTIVE TOY REQUEST BY FILLING THE FORM IN THE MAIL BODY BELOW --\n\n" +
        "Contact person (name and email address): \n" +
        "Design title:\n" +
        "Design description:\n" +
        "How does the design work? How does it interact with the device or the button?:"

    val mailtoUri: String
        get() = "mailto:$EMAIL?subject=${encode(SUBJECT)}&body=${encode(BODY)}"

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** A static plus for the app's previews; it never enters a GlyphScreen or hardware session. */
    fun previewFrame(size: Int): IntArray {
        val canvas = MatrixCanvas(size)
        val length = if (size >= 25) 15 else 7
        val thickness = if (size >= 25) 3 else 1
        val left = (size - length) / 2
        val middle = (size - thickness) / 2
        canvas.fillRect(left, middle, length, thickness, MAX_BRIGHTNESS)
        canvas.fillRect(middle, left, thickness, length, MAX_BRIGHTNESS)
        return canvas.copyOut()
    }
}
