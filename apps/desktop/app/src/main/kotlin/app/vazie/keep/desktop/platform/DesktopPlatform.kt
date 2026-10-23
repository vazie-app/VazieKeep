package app.vazie.keep.desktop.platform

enum class DesktopPlatform(val displayName: String) {
    Windows("Windows"),
    Linux("Linux"),
    MacOsDevelopment("macOS (development build)"),
    Unknown("Desktop"),
    ;

    companion object {
        fun current(): DesktopPlatform = fromOsName(System.getProperty("os.name").orEmpty())

        fun fromOsName(osName: String): DesktopPlatform = when {
            osName.startsWith("Windows", ignoreCase = true) -> Windows
            osName.startsWith("Linux", ignoreCase = true) -> Linux
            osName.startsWith("Mac", ignoreCase = true) -> MacOsDevelopment
            else -> Unknown
        }
    }
}
