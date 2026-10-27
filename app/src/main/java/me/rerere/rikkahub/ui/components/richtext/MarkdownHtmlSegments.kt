package me.rerere.rikkahub.ui.components.richtext

internal sealed interface MarkdownHtmlSegment {
    val content: String

    data class Markdown(override val content: String) : MarkdownHtmlSegment

    data class HtmlFragment(
        override val content: String,
        val isComplete: Boolean,
    ) : MarkdownHtmlSegment
}

private data class MarkdownFence(val marker: Char, val length: Int)

private data class HtmlCandidate(
    val fragmentStart: Int,
    val rootStart: Int,
    val rootName: String?,
    val isDocument: Boolean = false,
    val incompletePrefix: Boolean = false,
)

private data class HtmlTag(
    val name: String?,
    val endExclusive: Int,
    val isClosing: Boolean,
    val isSelfClosing: Boolean,
    val isDeclaration: Boolean = false,
)

private data class HtmlScanResult(val endExclusive: Int, val isComplete: Boolean)

private val HTML_ELEMENTS = setOf(
    "a", "abbr", "address", "area", "article", "aside", "audio", "b", "base", "bdi", "bdo",
    "blockquote", "body", "br", "button", "canvas", "caption", "center", "cite", "code", "col",
    "colgroup", "data", "datalist", "dd", "del", "details", "dfn", "dialog", "dir", "div", "dl",
    "dt", "em", "embed", "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3",
    "h4", "h5", "h6", "head", "header", "hgroup", "hr", "html", "i", "iframe", "img", "input",
    "ins", "kbd", "label", "legend", "li", "link", "main", "map", "mark", "math", "menu", "meta",
    "meter", "nav", "noscript", "object", "ol", "optgroup", "option", "output", "p", "param",
    "picture", "pre", "progress", "q", "rp", "rt", "ruby", "s", "samp", "script", "search",
    "section", "select", "slot", "small", "source", "span", "strong", "style", "sub", "summary",
    "sup", "svg", "table", "tbody", "td", "template", "textarea", "tfoot", "th", "thead", "time",
    "title", "tr", "track", "u", "ul", "var", "video", "wbr",
)

private val HTML_VOID_TAGS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source",
    "track", "wbr",
)

private val HTML_RAW_TEXT_TAGS = setOf("script", "style", "textarea", "title")

/**
 * Keeps block HTML fragments intact while leaving Markdown and fenced code in their original segments.
 * Incomplete fragments at the end of a streamed response are returned as one HTML segment.
 */
internal fun splitMarkdownHtmlSegments(content: String): List<MarkdownHtmlSegment> {
    if (content.isEmpty()) return listOf(MarkdownHtmlSegment.Markdown(content))

    val segments = mutableListOf<MarkdownHtmlSegment>()
    var segmentStart = 0
    var cursor = 0
    var fence: MarkdownFence? = null

    while (cursor < content.length) {
        val lineStart = if (cursor == 0) 0 else content.lastIndexOf('\n', cursor - 1) + 1
        if (cursor != lineStart) {
            cursor = nextLineStart(content, cursor)
            continue
        }

        val lineEnd = lineEndExclusive(content, lineStart)
        val line = content.substring(lineStart, lineEnd)
        val activeFence = fence
        if (activeFence != null) {
            if (isClosingFence(line, activeFence)) fence = null
            cursor = lineEnd
            continue
        }

        fence = openingFence(line)
        if (fence != null) {
            cursor = lineEnd
            continue
        }

        val candidate = findHtmlCandidate(content, lineStart)
        if (candidate == null) {
            cursor = lineEnd
            continue
        }

        val result = scanHtmlFragment(content, candidate)
        if (candidate.fragmentStart > segmentStart) {
            segments += MarkdownHtmlSegment.Markdown(content.substring(segmentStart, candidate.fragmentStart))
        }
        if (result.endExclusive > candidate.fragmentStart) {
            segments += MarkdownHtmlSegment.HtmlFragment(
                content = content.substring(candidate.fragmentStart, result.endExclusive),
                isComplete = result.isComplete,
            )
        }
        segmentStart = result.endExclusive
        if (!result.isComplete) break
        cursor = nextLineStart(content, result.endExclusive)
    }

    if (segmentStart < content.length) {
        segments += MarkdownHtmlSegment.Markdown(content.substring(segmentStart))
    }
    return segments.ifEmpty { listOf(MarkdownHtmlSegment.Markdown(content)) }
}

private fun findHtmlCandidate(source: String, lineStart: Int): HtmlCandidate? {
    var cursor = lineStart
    var fragmentStart: Int? = null

    while (cursor < source.length) {
        val lineEnd = lineEndExclusive(source, cursor)
        val lineContentEnd = when {
            lineEnd >= 2 && source[lineEnd - 2] == '\r' && source[lineEnd - 1] == '\n' -> lineEnd - 2
            lineEnd >= 1 && source[lineEnd - 1] == '\n' -> lineEnd - 1
            else -> lineEnd
        }
        val tokenStart = skipHorizontalWhitespace(source, cursor, lineContentEnd)
        if (tokenStart == lineContentEnd) {
            cursor = lineEnd
            continue
        }

        val indentation = tokenStart - cursor
        if (fragmentStart == null && indentation > 3) return null
        if (fragmentStart == null) fragmentStart = tokenStart

        if (source.startsWith("<!--", tokenStart)) {
            val commentEnd = source.indexOf("-->", startIndex = tokenStart + 4)
            if (commentEnd < 0) {
                return HtmlCandidate(fragmentStart, tokenStart, null, incompletePrefix = true)
            }
            cursor = commentEnd + 3
            continue
        }

        if (source.startsWithIgnoreCase("<!doctype", tokenStart)) {
            val doctype = readHtmlTag(source, tokenStart)
            if (doctype == null) return HtmlCandidate(fragmentStart, tokenStart, "html", true, true)
            val htmlStart = findHtmlRootAfter(source, doctype.endExclusive)
                ?: return HtmlCandidate(fragmentStart, tokenStart, "html", true, true)
            return HtmlCandidate(fragmentStart, htmlStart, "html", isDocument = true)
        }

        val tag = readHtmlTag(source, tokenStart)
        if (tag == null) {
            val partialName = partialHtmlTagName(source, tokenStart)
            if (partialName != null && isBlockHtmlCandidate(partialName, source.substring(tokenStart))) {
                return HtmlCandidate(fragmentStart, tokenStart, partialName, incompletePrefix = true)
            }
            return null
        }
        val name = tag.name ?: return null
        if (tag.isClosing || !isBlockHtmlCandidate(name, source.substring(tokenStart, tag.endExclusive))) {
            return null
        }
        return HtmlCandidate(
            fragmentStart = fragmentStart,
            rootStart = tokenStart,
            rootName = name,
            isDocument = name == "html",
        )
    }

    return null
}

private fun isBlockHtmlCandidate(name: String, tagSource: String): Boolean =
    name in HTML_ELEMENTS || name.contains('-') ||
        Regex("\\bstyle\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(tagSource)

private fun partialHtmlTagName(source: String, start: Int): String? {
    if (start >= source.length || source[start] != '<' || start + 1 >= source.length) return null
    if (source[start + 1] == '!' || source[start + 1] == '/' || source[start + 1] == '?') return null
    var end = start + 1
    while (end < source.length && isHtmlNameCharacter(source[end])) end++
    if (end == start + 1) return null
    return source.substring(start + 1, end).lowercase()
}

private fun scanHtmlFragment(source: String, candidate: HtmlCandidate): HtmlScanResult {
    if (candidate.incompletePrefix || candidate.rootName == null) {
        return HtmlScanResult(source.length, isComplete = false)
    }

    val rootResult = scanHtmlRoot(source, candidate.rootStart, candidate.rootName)
    if (!rootResult.isComplete || candidate.isDocument) return rootResult

    var fragmentEnd = rootResult.endExclusive
    while (true) {
        val nextStart = skipHtmlTrivia(source, fragmentEnd) ?: return HtmlScanResult(fragmentEnd, true)
        val nextTag = readHtmlTag(source, nextStart) ?: return HtmlScanResult(fragmentEnd, true)
        val nextName = nextTag.name ?: return HtmlScanResult(fragmentEnd, true)
        if (nextTag.isClosing || !isBlockHtmlCandidate(nextName, source.substring(nextStart, nextTag.endExclusive))) {
            return HtmlScanResult(fragmentEnd, true)
        }

        val sibling = scanHtmlRoot(source, nextStart, nextName)
        if (!sibling.isComplete) return sibling
        fragmentEnd = sibling.endExclusive
    }
}

private fun scanHtmlRoot(source: String, rootStart: Int, rootName: String): HtmlScanResult {
    val root = readHtmlTag(source, rootStart) ?: return HtmlScanResult(source.length, false)
    if (root.isClosing || root.name == null) return HtmlScanResult(source.length, false)
    if (root.isSelfClosing || root.name in HTML_VOID_TAGS) return HtmlScanResult(root.endExclusive, true)

    val openTags = mutableListOf(root.name)
    var cursor = root.endExclusive
    while (cursor < source.length) {
        val rawTextTag = openTags.lastOrNull()?.takeIf { it in HTML_RAW_TEXT_TAGS }
        if (rawTextTag != null) {
            val closingStart = source.indexOf("</$rawTextTag", startIndex = cursor, ignoreCase = true)
            if (closingStart < 0) return HtmlScanResult(source.length, false)
            cursor = closingStart
        }

        if (source.startsWith("<!--", cursor)) {
            val commentEnd = source.indexOf("-->", startIndex = cursor + 4)
            if (commentEnd < 0) return HtmlScanResult(source.length, false)
            cursor = commentEnd + 3
            continue
        }

        if (source[cursor] != '<') {
            cursor++
            continue
        }

        val tag = readHtmlTag(source, cursor)
        if (tag == null) {
            cursor++
            continue
        }
        cursor = tag.endExclusive
        val name = tag.name ?: continue
        if (tag.isClosing) {
            val matchingIndex = openTags.indexOfLast { it.equals(name, ignoreCase = true) }
            if (matchingIndex >= 0) {
                while (openTags.size > matchingIndex) openTags.removeAt(openTags.lastIndex)
                if (openTags.isEmpty() && name.equals(rootName, ignoreCase = true)) {
                    return HtmlScanResult(cursor, true)
                }
            }
        } else if (!tag.isSelfClosing && name !in HTML_VOID_TAGS) {
            openTags += name
        }
    }

    return HtmlScanResult(source.length, false)
}

private fun findHtmlRootAfter(source: String, start: Int): Int? {
    var cursor = start
    while (cursor < source.length) {
        if (source.startsWith("<!--", cursor)) {
            val commentEnd = source.indexOf("-->", startIndex = cursor + 4)
            if (commentEnd < 0) return null
            cursor = commentEnd + 3
            continue
        }
        if (source[cursor] != '<') {
            cursor++
            continue
        }
        val tag = readHtmlTag(source, cursor)
        if (tag == null) {
            cursor++
            continue
        }
        if (tag.name == "html" && !tag.isClosing) return cursor
        cursor = tag.endExclusive
    }
    return null
}

private fun skipHtmlTrivia(source: String, start: Int): Int? {
    var cursor = start
    while (cursor < source.length) {
        while (cursor < source.length && source[cursor].isWhitespace()) cursor++
        if (cursor >= source.length) return null
        if (!source.startsWith("<!--", cursor)) return cursor
        val commentEnd = source.indexOf("-->", startIndex = cursor + 4)
        if (commentEnd < 0) return null
        cursor = commentEnd + 3
    }
    return null
}

private fun readHtmlTag(source: String, start: Int): HtmlTag? {
    if (start >= source.length || source[start] != '<' || start + 1 >= source.length) return null
    var cursor = start + 1
    if (source[cursor] == '!' || source[cursor] == '?') {
        val end = findTagEnd(source, cursor + 1) ?: return null
        return HtmlTag(null, end + 1, false, false, isDeclaration = true)
    }

    val closing = source[cursor] == '/'
    if (closing) cursor++
    while (cursor < source.length && source[cursor].isWhitespace()) cursor++
    val nameStart = cursor
    while (cursor < source.length && isHtmlNameCharacter(source[cursor])) cursor++
    if (cursor == nameStart) return null
    val name = source.substring(nameStart, cursor).lowercase()
    val end = findTagEnd(source, cursor) ?: return null
    val selfClosing = source.substring(cursor, end).trimEnd().endsWith('/')
    return HtmlTag(name, end + 1, closing, selfClosing)
}

private fun findTagEnd(source: String, start: Int): Int? {
    var quote: Char? = null
    for (index in start until source.length) {
        val char = source[index]
        if (quote != null) {
            if (char == quote) quote = null
        } else when (char) {
            '\'', '"' -> quote = char
            '>' -> return index
        }
    }
    return null
}

private fun openingFence(line: String): MarkdownFence? {
    val marker = fenceMarker(line) ?: return null
    return MarkdownFence(marker.first, marker.second)
}

private fun isClosingFence(line: String, active: MarkdownFence): Boolean {
    val marker = fenceMarker(line) ?: return false
    if (marker.first != active.marker || marker.second < active.length) return false
    val lineEnd = line.trimEnd('\r', '\n')
    val markerStart = lineEnd.indexOf(active.marker)
    val afterMarker = markerStart + marker.second
    return lineEnd.substring(afterMarker).all { it == ' ' || it == '\t' }
}

private fun fenceMarker(line: String): Pair<Char, Int>? {
    val lineEnd = line.trimEnd('\r', '\n')
    var cursor = 0
    while (cursor < lineEnd.length && lineEnd[cursor] == ' ' && cursor < 4) cursor++
    if (cursor > 3 || cursor >= lineEnd.length) return null
    val marker = lineEnd[cursor]
    if (marker != '`' && marker != '~') return null
    var end = cursor + 1
    while (end < lineEnd.length && lineEnd[end] == marker) end++
    return if (end - cursor >= 3) marker to (end - cursor) else null
}

private fun skipHorizontalWhitespace(source: String, start: Int, limit: Int): Int {
    var cursor = start
    while (cursor < limit && (source[cursor] == ' ' || source[cursor] == '\t')) cursor++
    return cursor
}

private fun lineEndExclusive(source: String, start: Int): Int {
    val newline = source.indexOf('\n', start)
    return if (newline < 0) source.length else newline + 1
}

private fun nextLineStart(source: String, offset: Int): Int {
    if (offset >= source.length) return source.length
    val newline = source.indexOf('\n', offset)
    return if (newline < 0) source.length else newline + 1
}

private fun isHtmlNameCharacter(char: Char): Boolean =
    char.isLetterOrDigit() || char == '-' || char == '_' || char == ':'

private fun String.startsWithIgnoreCase(prefix: String, startIndex: Int): Boolean =
    regionMatches(startIndex, prefix, 0, prefix.length, ignoreCase = true)

private fun prepareHtmlElement(source: String): String {
    val htmlOpen = Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE)
    if (htmlOpen.containsMatchIn(source)) return source

    val doctype = Regex("<!doctype\\b[^>]*>", RegexOption.IGNORE_CASE).find(source)
    val hasDocumentChildren = Regex("</?(head|body)\\b", RegexOption.IGNORE_CASE).containsMatchIn(source)
    if (hasDocumentChildren) {
        if (doctype == null) return "<html>$source</html>"
        val offset = doctype.range.last + 1
        return source.substring(0, offset) + "<html>" + source.substring(offset) + "</html>"
    }
    if (doctype != null) {
        val offset = doctype.range.last + 1
        return source.substring(0, offset) + "<html><head></head><body>" +
            source.substring(offset) + "</body></html>"
    }
    return "<!doctype html><html><head></head><body>$source</body></html>"
}

/** Adds one shared viewport, responsive width rules and the restricted preview policy. */
internal fun prepareHtmlPreviewDocument(html: String, allowInlineScripts: Boolean): String {
    val document = prepareHtmlElement(html)
    val scriptSource = if (allowInlineScripts) "'unsafe-inline'" else "'none'"
    val csp = """<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src $scriptSource; style-src 'unsafe-inline' data:; img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; frame-src 'none'; worker-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'">"""
    val viewport = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
    val responsiveStyle = """<style>html,body{width:100%;max-width:100%;min-height:0;margin:0;overflow-wrap:anywhere}*,*::before,*::after{box-sizing:border-box;max-width:100%}img,video,iframe{height:auto}</style>"""
    val previewHead = csp + viewport + responsiveStyle
    val headOpen = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE).find(document)
    if (headOpen == null) {
        val htmlOpen = Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE).find(document)
            ?: return "<html><head>$previewHead</head><body>$document</body></html>"
        val offset = htmlOpen.range.last + 1
        return document.substring(0, offset) + "<head>$previewHead</head>" + document.substring(offset)
    }

    val contentStart = headOpen.range.last + 1
    val headClose = Regex("</head\\s*>", RegexOption.IGNORE_CASE).find(document, contentStart)
    val contentEnd = headClose?.range?.first ?: document.length
    val oldHeadContent = document.substring(contentStart, contentEnd)
    val metaTag = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    val viewportName = Regex("\\bname\\s*=\\s*(['\"]?)viewport\\1", RegexOption.IGNORE_CASE)
    val normalizedHead = metaTag.replace(oldHeadContent) { match ->
        if (viewportName.containsMatchIn(match.value)) "" else match.value
    }
    return document.substring(0, contentStart) + previewHead + normalizedHead + document.substring(contentEnd)
}
