// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WearTAK core CotXml.kt, CotMessageParser.kt and SensitiveEnrollment.kt
// at 00290203f9a6bb09870c53f2cdac0e110ed88125.
package com.weartak.plugin.ditto.document

import java.io.IOException
import java.io.StringReader
import java.util.ArrayDeque
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

internal object DittoCotXml {
    private val sensitiveEnrollment = Regex(
        "#weartakcommand[\\s\\p{Z}\\p{javaWhitespace}]*enrollserver",
        RegexOption.IGNORE_CASE,
    )

    fun parseDetail(xml: String): Element {
        val root = parse(xml)
        require(root.tagName == "detail" &&
            root.getElementsByTagName("event").length == 0 &&
            root.getElementsByTagName("detail").length == 0) { "Malformed Ditto detail" }
        return root
    }

    fun requireValidEvent(xml: String) {
        parseEvent(xml)
    }

    fun parseEvent(xml: String): Element =
        parse(xml).also { require(it.tagName == "event") { "Malformed Ditto CoT" } }

    private fun parse(xml: String): Element {
        require(!xml.contains("<!DOCTYPE", ignoreCase = true) &&
            !xml.contains("<!ENTITY", ignoreCase = true)) { "DTD/entity in Ditto XML" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
            optionalFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            optionalFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            optionalFeature("http://xml.org/sax/features/external-general-entities", false)
            optionalFeature("http://xml.org/sax/features/external-parameter-entities", false)
            optionalAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            optionalAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        val root = try {
            factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
                setErrorHandler(DefaultHandler())
            }.parse(InputSource(StringReader(xml.trimStart { it <= ' ' || it == '\uFEFF' })))
                .documentElement
        } catch (_: SAXException) {
            throw IllegalArgumentException("Malformed Ditto detail")
        } catch (_: IOException) {
            throw IllegalArgumentException("Malformed Ditto detail")
        } catch (_: ParserConfigurationException) {
            throw IllegalStateException("Cannot configure Ditto XML parser")
        }
        return root ?: throw IllegalArgumentException("Malformed Ditto XML")
    }

    fun containsSensitiveEnrollment(xml: String, root: Element): Boolean {
        if (sensitiveEnrollment.containsMatchIn(xml)) return true
        val text = StringBuilder()
        val nodes = ArrayDeque<Node>()
        nodes.add(root)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeLast()
            val attributes = node.attributes
            if (attributes != null) {
                for (i in 0 until attributes.length) {
                    if (sensitiveEnrollment.containsMatchIn(attributes.item(i).nodeValue.orEmpty())) return true
                }
            }
            if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                text.append(node.nodeValue)
            }
            val children = node.childNodes
            for (i in children.length - 1 downTo 0) nodes.add(children.item(i))
        }
        return sensitiveEnrollment.containsMatchIn(text)
    }

    fun containsSensitiveEnrollmentText(text: String): Boolean = sensitiveEnrollment.containsMatchIn(text)

    fun escape(value: String): String = buildString(value.length + 16) {
        value.forEach {
            append(when (it) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&apos;"
                else -> it
            })
        }
    }

    fun element(
        name: String,
        attributes: List<Pair<String, Any?>> = emptyList(),
        content: String? = null,
    ): String = buildString {
        append('<').append(name)
        attributes.forEach { (key, value) ->
            append(' ').append(key).append("='").append(value).append('\'')
        }
        if (content == null) append("/>")
        else append('>').append(content).append("</").append(name).append('>')
    }

    private fun DocumentBuilderFactory.optionalFeature(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: ParserConfigurationException) {
            // Android XML providers do not all expose every hardening feature.
        }
    }

    private fun DocumentBuilderFactory.optionalAttribute(name: String, value: String) {
        try {
            setAttribute(name, value)
        } catch (_: IllegalArgumentException) {
            // Android XML providers do not all expose every hardening attribute.
        }
    }
}
