package com.shuangka.punch.core.engine

import android.graphics.Rect
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** 把 `uiautomator dump` 出来的 XML 解析成 UiNode 树（Shizuku 通道用） */
object SnapshotParser {

    private val boundsRegex = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")

    fun parse(xml: String?): UiNode? {
        if (xml.isNullOrBlank() || !xml.contains("<node")) return null
        return try {
            val parser = android.util.Xml.newPullParser()
            parser.setInput(StringReader(xml))

            var root: Raw? = null
            val stack = ArrayList<Raw>()
            var event = parser.eventType

            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "node") {
                    val text = (parser.getAttributeValue(null, "text") ?: "")
                        .ifBlank { parser.getAttributeValue(null, "content-desc") ?: "" }
                    val node = Raw(
                        text = text.trim(),
                        bounds = parseBounds(parser.getAttributeValue(null, "bounds")),
                        clickable = parser.getAttributeValue(null, "clickable") == "true",
                        className = parser.getAttributeValue(null, "class")
                    )
                    if (stack.isEmpty()) root = node else stack[stack.size - 1].children.add(node)
                    stack.add(node)
                } else if (event == XmlPullParser.END_TAG && parser.name == "node") {
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                }
                event = parser.next()
            }
            root?.toUiNode()
        } catch (e: Exception) {
            null
        }
    }

    private fun parseBounds(raw: String?): Rect {
        val match = raw?.let { boundsRegex.find(it) } ?: return Rect()
        val g = match.groupValues
        return Rect(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt())
    }

    private class Raw(
        val text: String,
        val bounds: Rect,
        val clickable: Boolean,
        val className: String?
    ) {
        val children = ArrayList<Raw>()

        fun toUiNode(): UiNode =
            UiNode(text, bounds, clickable, className, children.map { it.toUiNode() })
    }
}
