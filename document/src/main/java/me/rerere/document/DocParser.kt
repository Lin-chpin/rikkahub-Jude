package me.rerere.document

import org.apache.poi.hwpf.extractor.WordExtractor
import java.io.File

object DocParser {
    fun parse(file: File): String {
        return WordExtractor(file.inputStream()).use { it.text.trim() }
    }
}
