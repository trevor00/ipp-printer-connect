package kr.ac.hit.networkprinterstudio

/** Automatic resolution for app-rendered jobs, separate from the printer's engine resolution. */
internal object AutomaticRasterPolicy {
    const val STANDARD_DPI = 300

    fun outputDpi(printer: PrinterRecord?, outputMime: String?): Int? {
        if (outputMime == null || PrintFormatSupport.normalize(outputMime) == PrintFormatSupport.PDF) return null
        val format = PrintFormatSupport.normalize(outputMime)
        val advertised = when (format) {
            PrintFormatSupport.PWG_RASTER -> printer?.pwgResolutions.orEmpty()
                .filter { it.units == 3 && it.x == it.y && it.x > 0 }
                .map { it.x }
            PrintFormatSupport.URF -> printer?.urfSupported.orEmpty()
                .flatMap { it.split(',') }
                .map(String::trim)
                .filter { it.startsWith("RS", ignoreCase = true) }
                .flatMap { it.drop(2).split('-') }
                .mapNotNull(String::toIntOrNull)
                .filter { it > 0 }
            else -> emptyList()
        }.distinct().sorted()

        // Use a standard print resolution; for PWG/URF choose only a resolution
        // the printer explicitly advertised.
        return if (format in setOf(PrintFormatSupport.PWG_RASTER, PrintFormatSupport.URF)) {
            advertised.lastOrNull { it <= STANDARD_DPI } ?: advertised.firstOrNull() ?: STANDARD_DPI
        } else STANDARD_DPI
    }
}

/** Legacy wrapper for older photo-resolution call sites. */
@Deprecated("Use AutomaticRasterPolicy")
internal object PhotoRasterPolicy {
    fun outputDpi(printer: PrinterRecord?, sourceMime: String?, outputMime: String?): Int? =
        AutomaticRasterPolicy.outputDpi(printer, outputMime)
}
