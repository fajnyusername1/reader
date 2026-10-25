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

class TocEntry(val title: String, val level: Int, val spineIndex: Int)

class EpubInfo(val title: String?, val spine: List<File>, val cover: File?, val toc: List<TocEntry>)

/** An EPUB is a zip of XHTML files. We unzip it once and read the chapter list from its OPF file. */
object Epub {

    private const val OPS_NS = "http://www.idpf.org/2007/ops"

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

        val toc = try {
            parseToc(doc, manifest, opfDir, spine)
        } catch (e: Exception) {
            emptyList()
        }
        return EpubInfo(title, spine, cover?.takeIf { it.exists() }, toc)
    }

    // ---------------- Table of contents ----------------

    private fun ancestorCount(el: Element, name: String): Int {
        var n = 0
        var cur = el.parentNode
        while (cur != null) {
            if (cur is Element && (cur.localName ?: cur.nodeName) == name) n++
            cur = cur.parentNode
        }
        return n
    }

    private fun childElement(parent: Element?, name: String): Element? {
        if (parent == null) return null
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val k = kids.item(i)
            if (k is Element && (k.localName ?: k.nodeName) == name) return k
        }
        return null
    }

    private fun parseToc(
        doc: Document,
        manifest: Map<String, Element>,
        opfDir: File,
        spine: List<File>
    ): List<TocEntry> {
        val canon = spine.map { it.canonicalPath }

        fun indexOf(base: File, href: String): Int {
            val path = Uri.decode(href.substringBefore('#'))
            if (path.isEmpty()) return -1
            return canon.indexOf(File(base, path).canonicalPath)
        }

        // EPUB3: <nav epub:type="toc">
        val navItem = manifest.values.firstOrNull {
            it.getAttribute("properties").split(" ").contains("nav")
        }
        if (navItem != null) {
            val navFile = File(opfDir, Uri.decode(navItem.getAttribute("href")))
            if (navFile.exists()) {
                val navDoc = parseXml(navFile)
                val navs = navDoc.getElementsByTagNameNS("*", "nav")
                var tocNav: Element? = null
                for (i in 0 until navs.length) {
                    val n = navs.item(i) as Element
                    val type = n.getAttributeNS(OPS_NS, "type").ifEmpty { n.getAttribute("epub:type") }
                    if (type.split(" ").contains("toc")) {
                        tocNav = n
                        break
                    }
                }
                if (tocNav == null && navs.length > 0) tocNav = navs.item(0) as Element
                if (tocNav != null) {
                    val out = ArrayList<TocEntry>()
                    val links = tocNav.getElementsByTagNameNS("*", "a")
                    for (i in 0 until links.length) {
                        val a = links.item(i) as Element
                        val idx = indexOf(navFile.parentFile ?: opfDir, a.getAttribute("href"))
                        if (idx < 0) continue
                        val label = a.textContent.replace(Regex("\\s+"), " ").trim()
                        if (label.isEmpty()) continue
                        out.add(TocEntry(label, (ancestorCount(a, "ol") - 1).coerceAtLeast(0), idx))
                    }
                    if (out.isNotEmpty()) return out
                }
            }
        }

        // EPUB2: toc.ncx
        val spineEl = doc.getElementsByTagNameNS("*", "spine").item(0) as? Element
        val ncxId = spineEl?.getAttribute("toc")
        val byId = if (!ncxId.isNullOrEmpty()) manifest[ncxId] else null
        val ncxItem = byId ?: manifest.values.firstOrNull {
            it.getAttribute("media-type") == "application/x-dtbncx+xml"
        }
        if (ncxItem != null) {
            val ncxFile = File(opfDir, Uri.decode(ncxItem.getAttribute("href")))
            if (ncxFile.exists()) {
                val ncx = parseXml(ncxFile)
                val points = ncx.getElementsByTagNameNS("*", "navPoint")
                val out = ArrayList<TocEntry>()
                for (i in 0 until points.length) {
                    val p = points.item(i) as Element
                    val label = childElement(childElement(p, "navLabel"), "text")
                        ?.textContent?.replace(Regex("\\s+"), " ")?.trim() ?: ""
                    val src = childElement(p, "content")?.getAttribute("src") ?: ""
                    val idx = indexOf(ncxFile.parentFile ?: opfDir, src)
                    if (idx < 0 || label.isEmpty()) continue
                    out.add(TocEntry(label, ancestorCount(p, "navPoint"), idx))
                }
                return out
            }
        }
        return emptyList()
    }
}
