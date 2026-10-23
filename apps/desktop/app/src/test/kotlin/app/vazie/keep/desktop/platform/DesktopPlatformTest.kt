package app.vazie.keep.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPlatformTest {
    @Test
    fun mapsOsNames() {
        assertEquals(DesktopPlatform.Windows, DesktopPlatform.fromOsName("Windows 11"))
        assertEquals(DesktopPlatform.Linux, DesktopPlatform.fromOsName("Linux"))
        assertEquals(DesktopPlatform.MacOsDevelopment, DesktopPlatform.fromOsName("Mac OS X"))
        assertEquals(DesktopPlatform.Unknown, DesktopPlatform.fromOsName("FreeBSD"))
    }
}
