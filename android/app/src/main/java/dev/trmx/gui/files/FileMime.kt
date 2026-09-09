package dev.trmx.gui.files

/*
 * Phase 8: extension → MIME mapping for open/share intents.
 *
 * Deliberately our own table instead of android.webkit.MimeTypeMap:
 * - testable on the JVM (no android.jar),
 * - deterministic across OEMs (MimeTypeMap is a device database and
 *   regularly lacks e.g. video/x-matroska),
 * - fail-loud: unknown extensions map to application/octet-stream and the
 *   UI says so honestly instead of guessing.
 */

object FileMime {

    private val BY_EXT: Map<String, String> = mapOf(
        // video
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska",
        "webm" to "video/webm", "avi" to "video/x-msvideo", "mov" to "video/quicktime",
        "ts" to "video/mp2t", "3gp" to "video/3gpp",
        // audio
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac",
        "flac" to "audio/flac", "wav" to "audio/x-wav", "ogg" to "audio/ogg",
        "opus" to "audio/opus",
        // image
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        // documents
        "pdf" to "application/pdf",
        "txt" to "text/plain", "md" to "text/plain", "log" to "text/plain",
        "csv" to "text/csv", "srt" to "text/plain", "vtt" to "text/vtt",
        "json" to "application/json", "xml" to "text/xml", "yaml" to "text/yaml",
        "yml" to "text/yaml", "toml" to "text/plain", "ini" to "text/plain",
        "conf" to "text/plain",
        // source code (plain text viewers)
        "sh" to "text/x-shellscript", "py" to "text/x-python",
        "kt" to "text/x-kotlin", "java" to "text/x-java-source",
        "js" to "text/javascript", "ts" to "text/plain", "c" to "text/x-c",
        "cpp" to "text/x-c++", "h" to "text/x-c", "rs" to "text/x-rust",
        "go" to "text/x-go", "sql" to "text/x-sql",
        // archives (viewers are rare; octet-stream-ish is honest)
        "zip" to "application/zip", "tar" to "application/x-tar",
        "gz" to "application/gzip", "bz2" to "application/x-bzip2",
        "xz" to "application/x-xz", "7z" to "application/x-7z-compressed",
        "apk" to "application/vnd.android.package-archive",
    )

    const val FALLBACK = "application/octet-stream"

    /** "lecture.MKV" → "video/x-matroska"; unknown → [FALLBACK]. */
    fun of(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (ext.isEmpty()) FALLBACK else BY_EXT[ext] ?: FALLBACK
    }
}
