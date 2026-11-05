package com.noop

import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * English `values/` resources for plain-JVM unit tests, which have no Application to resolve
 * `uiString` / `uiPlural` against. [install] routes [NoopApplication.localizedString] here; call
 * [uninstall] in `@After`. Resource ids map to names by reflection on the generated `R`.
 */
object EnglishTestStrings : NoopApplication.Companion.DetachedStrings {
    private val idToName: Map<Int, String> by lazy {
        (R.string::class.java.fields + R.plurals::class.java.fields).associate { (it.get(null) as Int) to it.name }
    }
    private val strings = HashMap<String, Pair<String, Boolean>>()
    private val plurals = HashMap<String, Map<String, String>>()

    init {
        val dir = listOf("src/main/res/values", "app/src/main/res/values").map(::File).first { it.isDirectory }
        dir.listFiles { f -> f.name.endsWith(".xml") }!!.forEach { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val s = doc.getElementsByTagName("string")
            for (i in 0 until s.length) {
                val e = s.item(i) as Element
                strings[e.getAttribute("name")] = unescape(e.textContent) to (e.getAttribute("formatted") != "false")
            }
            val p = doc.getElementsByTagName("plurals")
            for (i in 0 until p.length) {
                val e = p.item(i) as Element
                val items = e.getElementsByTagName("item")
                plurals[e.getAttribute("name")] = (0 until items.length).associate {
                    val item = items.item(it) as Element
                    item.getAttribute("quantity") to unescape(item.textContent)
                }
            }
        }
    }

    private fun unescape(t: String) =
        t.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\@", "@").replace("\\?", "?")

    override fun string(id: Int, vararg formatArgs: Any): String {
        val (text, formatted) = strings.getValue(idToName.getValue(id))
        // Matches the device: getString(id) with no args returns the raw text without formatting.
        return if (formatArgs.isEmpty() || !formatted) text else String.format(Locale.US, text, *formatArgs)
    }

    override fun plural(id: Int, count: Int, vararg formatArgs: Any): String {
        val forms = plurals.getValue(idToName.getValue(id))
        return String.format(Locale.US, forms[if (count == 1) "one" else "other"] ?: forms.getValue("other"), *formatArgs)
    }

    fun install() { NoopApplication.detachedStrings = this }
    fun uninstall() { NoopApplication.detachedStrings = null }
}
