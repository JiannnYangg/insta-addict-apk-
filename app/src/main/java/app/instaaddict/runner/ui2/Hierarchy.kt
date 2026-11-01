package app.instaaddict.runner.ui2

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** One element on screen, as reported by the UI automation server. */
data class UiNode(
    val id: String,
    val text: String,
    val desc: String,
    val cls: String,
    val pkg: String,
    val clickable: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val cx: Int get() = (left + right) / 2
    val cy: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** "com.instagram.android:id/like_button" -> "like_button" */
    val shortId: String get() = id.substringAfter(":id/", id)
}

object Hierarchy {
    private val boundsRegex = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    fun parse(xml: String): List<UiNode> {
        val out = ArrayList<UiNode>()
        val p = Xml.newPullParser()
        p.setInput(StringReader(xml))
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name == "node") {
                val b = boundsRegex.find(p.getAttributeValue(null, "bounds") ?: "")
                if (b != null) {
                    val g = b.groupValues
                    out.add(
                        UiNode(
                            id = p.getAttributeValue(null, "resource-id") ?: "",
                            text = p.getAttributeValue(null, "text") ?: "",
                            desc = p.getAttributeValue(null, "content-desc") ?: "",
                            cls = p.getAttributeValue(null, "class") ?: "",
                            pkg = p.getAttributeValue(null, "package") ?: "",
                            clickable = p.getAttributeValue(null, "clickable") == "true",
                            left = g[1].toInt(),
                            top = g[2].toInt(),
                            right = g[3].toInt(),
                            bottom = g[4].toInt(),
                        )
                    )
                }
            }
            event = p.next()
        }
        return out
    }
}
