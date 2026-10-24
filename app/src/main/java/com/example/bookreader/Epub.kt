package com.example.bookreader

import android.net.Uri
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class EpubInfo(val title: String?, val spine: List<File>, val cover: File?)

/** An EPUB is a zip of XHTML files. We unzip it once and read the chapter list from its OPF file. */
object Epub {

    fun unzip(input: InputStream, dest: File) {
        dest.mkdirs()
        val root = dest.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(input)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(root)) throw SecurityException("Bad zip entry")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun parseXml(f: File): Document {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val builder = factory.newDocumentBuilder()
        // Never fetch external DTDs from the network.
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return f.inputStream().use { builder.parse(it) }
    }

    fun parse(dir: File): EpubInfo {
        val container = parseXml(File(dir, "META-INF/container.xml"))
        val rootfile = container.getElementsByTagNameNS("*", "rootfile").item(0) as Element
        val opf = File(dir, Uri.decode(rootfile.getAttribute("full-path")))
        val opfDir = opf.parentFile ?: dir
        val doc = parseXml(opf)

        val manifest = HashMap<String, Element>()
        val items = doc.getElementsByTagNameNS("*", "item")
        for (i in 0 until items.length) {
            val el = items.item(i) as Element
            manifest[el.getAttribute("id")] = el
        }
        fun fileOf(el: Element) = File(opfDir, Uri.decode(el.getAttribute("href")))

        val spine = ArrayList<File>()
        val refs = doc.getElementsByTagNameNS("*", "itemref")
        for (i in 0 until refs.length) {
            val idref = (refs.item(i) as Element).getAttribute("idref")
            val el = manifest[idref] ?: continue
            val f = fileOf(el)
            if (f.exists()) spine.add(f)
        }

        val title = doc.getElementsByTagNameNS("*", "title").item(0)?.textContent?.trim()

        // Cover: EPUB3 uses properties="cover-image", EPUB2 uses <meta name="cover" content="id">
        var cover: File? = null
        for (el in manifest.values) {
            if (el.getAttribute("properties").split(" ").contains("cover-image")) {
                cover = fileOf(el)
                break
            }
        }
        if (cover == null) {
            val metas = doc.getElementsByTagNameNS("*", "meta")
            for (i in 0 until metas.length) {
                val m = metas.item(i) as Element
                if (m.getAttribute("name") == "cover") {
                    val el = manifest[m.getAttribute("content")]
                    if (el != null) cover = fileOf(el)
                    break
                }
            }
        }
        return EpubInfo(title, spine, cover?.takeIf { it.exists() })
    }
}
