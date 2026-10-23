package app.vazie.keep.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

object VazieIcons {
    val Back = stroke("Back", "M15 5L8 12L15 19")
    val ChevronRight = stroke("ChevronRight", "M9 5L16 12L9 19")
    val Mail = stroke("Mail", "M3.5 6.5H20.5V17.5H3.5Z", "M3.5 7L12 13L20.5 7")
    val Bank = stroke("Bank", "M4 10L12 4.5L20 10Z", "M6.5 10V16.5", "M10.2 10V16.5", "M13.8 10V16.5", "M17.5 10V16.5", "M4 16.5H20", "M3.5 19.5H20.5")
    val Card = stroke("Card", "M3.5 6.5H20.5V17.5H3.5Z", "M3.5 10.5H20.5", "M7 14.5H10.5")
    val Home = stroke("Home", "M4 11L12 4.5L20 11", "M6 9.5V19.5H18V9.5", "M10 19.5V14.5H14V19.5")
    val Chat = stroke("Chat", "M4.5 5.5H19.5V15.5H11L7 19V15.5H4.5Z")
    val Phone = stroke("Phone", "M8 3.5H16V20.5H8Z", "M11 17.5H13")
    val Server = stroke("Server", "M4.5 5H19.5V11H4.5Z", "M4.5 13H19.5V19H4.5Z", "M7.5 8H8", "M7.5 16H8")
    val Monitor = stroke("Monitor", "M3.5 5H20.5V16H3.5Z", "M9 20H15", "M12 16V20")
    val Terminal = stroke("Terminal", "M3.5 5H20.5V19H3.5Z", "M7 9.5L10 12L7 14.5", "M12 15H16")
    val Book = stroke("Book", "M5 4.5H17A2 2 0 0 1 19 6.5V19.5H7A2 2 0 0 1 5 17.5Z", "M5 17.5A2 2 0 0 1 7 15.5H19")
    val Note = stroke("Note", "M6 3.5H14.5L18 7V20.5H6Z", "M14.5 3.5V7H18", "M9 12H15", "M9 15.5H15")
    val Certificate = stroke("Certificate", "M12 4A5 5 0 1 1 12 14A5 5 0 1 1 12 4Z", "M9 13L7.5 20.5L12 18L16.5 20.5L15 13")
    val Briefcase = stroke("Briefcase", "M4 8H20V19H4Z", "M9 8V5.5H15V8", "M4 13H20")
    val Package = stroke("Package", "M4 8L12 4L20 8V16L12 20L4 16Z", "M4 8L12 12L20 8", "M12 12V20")
    val Camera = stroke("Camera", "M4 8H7.5L9 6H15L16.5 8H20V18.5H4Z", "M12 10.5A3 3 0 1 1 12 16.5A3 3 0 1 1 12 10.5Z")
    val Lock = stroke("Lock", "M6 11H18V20H6Z", "M8.5 11V8A3.5 3.5 0 0 1 15.5 8V11")
    val Eye = stroke("Eye", "M2.5 12C2.5 12 6 5.5 12 5.5S21.5 12 21.5 12S18 18.5 12 18.5S2.5 12 2.5 12Z", "M12 9A3 3 0 1 1 12 15A3 3 0 1 1 12 9Z")
    val EyeOff = stroke("EyeOff", "M2.5 12C2.5 12 6 5.5 12 5.5S21.5 12 21.5 12S18 18.5 12 18.5S2.5 12 2.5 12Z", "M12 9A3 3 0 1 1 12 15A3 3 0 1 1 12 9Z", "M4 4L20 20")
    val Search = stroke("Search", "M10.5 4A6.5 6.5 0 1 1 10.5 17A6.5 6.5 0 1 1 10.5 4Z", "M15.5 15.5L20 20")
    val Close = stroke("Close", "M6 6L18 18", "M18 6L6 18")
    val Folder = stroke("Folder", "M3.5 7A1.5 1.5 0 0 1 5 5.5H9.5L11.5 7.5H19A1.5 1.5 0 0 1 20.5 9V17A1.5 1.5 0 0 1 19 18.5H5A1.5 1.5 0 0 1 3.5 17Z")
    val Key = stroke("Key", "M7.5 8.5A3.5 3.5 0 1 1 7.5 15.5A3.5 3.5 0 1 1 7.5 8.5Z", "M11 12H20.5", "M17.5 12V15", "M20.5 12V14")
    val Cpu = stroke("Cpu", "M7 7H17V17H7Z", "M10 10H14V14H10Z", "M9 4V7", "M15 4V7", "M9 17V20", "M15 17V20", "M4 9H7", "M4 15H7", "M17 9H20", "M17 15H20")
    val Copy = stroke("Copy", "M9 9H19.5V20H9Z", "M5 15.5V4.5H15")
    val Settings = stroke(
        "Settings",
        "M4 6H13", "M17 6H20", "M15 4A2 2 0 1 1 15 8A2 2 0 1 1 15 4Z",
        "M4 12H7", "M11 12H20", "M9 10A2 2 0 1 1 9 14A2 2 0 1 1 9 10Z",
        "M4 18H14", "M18 18H20", "M16 16A2 2 0 1 1 16 20A2 2 0 1 1 16 16Z",
    )
    val Biometric = stroke(
        "Biometric",
        "M5 15V12A7 7 0 0 1 17.6 7.8", "M19 11.5V13.5",
        "M8.5 17.5V12A3.5 3.5 0 0 1 15.5 12V14.5A6 6 0 0 1 14 18.8",
        "M12 11.5V15A4.5 4.5 0 0 1 10.6 18.5",
    )
    val Add = stroke("Add", "M12 5V19", "M5 12H19")
    val Edit = stroke("Edit", "M4 20H8L19 9L15 5L4 16Z", "M13 7L17 11")
    val More = stroke("More", "M12 5.5A0.6 0.6 0 1 1 12 6.7A0.6 0.6 0 1 1 12 5.5Z", "M12 11.4A0.6 0.6 0 1 1 12 12.6A0.6 0.6 0 1 1 12 11.4Z", "M12 17.3A0.6 0.6 0 1 1 12 18.5A0.6 0.6 0 1 1 12 17.3Z")
    val Trash = stroke("Trash", "M4.5 7H19.5", "M9 7V4.5H15V7", "M6.5 7L7.5 20H16.5L17.5 7", "M10 11V16", "M14 11V16")
    val Dice = stroke(
        "Dice",
        "M5 5H19V19H5Z", "M8.5 8.5A0.5 0.5 0 1 1 8.5 9.5A0.5 0.5 0 1 1 8.5 8.5Z",
        "M15.5 8.5A0.5 0.5 0 1 1 15.5 9.5A0.5 0.5 0 1 1 15.5 8.5Z", "M12 11.5A0.5 0.5 0 1 1 12 12.5A0.5 0.5 0 1 1 12 11.5Z",
        "M8.5 14.5A0.5 0.5 0 1 1 8.5 15.5A0.5 0.5 0 1 1 8.5 14.5Z", "M15.5 14.5A0.5 0.5 0 1 1 15.5 15.5A0.5 0.5 0 1 1 15.5 14.5Z",
    )
    val FolderAdd = stroke("FolderAdd", "M3.5 7A1.5 1.5 0 0 1 5 5.5H9.5L11.5 7.5H19A1.5 1.5 0 0 1 20.5 9V17A1.5 1.5 0 0 1 19 18.5H5A1.5 1.5 0 0 1 3.5 17Z", "M12 10.5V15.5", "M9.5 13H14.5")
    val Warning = stroke("Warning", "M12 4L21 19.5H3Z", "M12 10V14", "M12 16.8A0.4 0.4 0 1 1 12 17.6A0.4 0.4 0 1 1 12 16.8Z")
    val Check = stroke("Check", "M5 12.5L10 17.5L19 7")
    val Info = stroke("Info", "M12 3.5A8.5 8.5 0 1 1 12 20.5A8.5 8.5 0 1 1 12 3.5Z", "M12 11V16", "M12 7.6A0.4 0.4 0 1 1 12 8.4A0.4 0.4 0 1 1 12 7.6Z")
    val Star = stroke("Star", "M12 3.8L14.4 9L20 9.7L15.9 13.6L17 19.2L12 16.4L7 19.2L8.1 13.6L4 9.7L9.6 9Z")
    val StarFilled = filled("StarFilled", "M12 3.8L14.4 9L20 9.7L15.9 13.6L17 19.2L12 16.4L7 19.2L8.1 13.6L4 9.7L9.6 9Z")
    val Clock = stroke("Clock", "M12 4A8 8 0 1 1 12 20A8 8 0 1 1 12 4Z", "M12 8V12L15 14")
    val Archive = stroke("Archive", "M4 5.5H20V9H4Z", "M5.5 9V19H18.5V9", "M9.5 13H14.5")
    val Swap = stroke("Swap", "M5 8H18", "M15 5L18 8L15 11", "M19 16H6", "M9 13L6 16L9 19")
    val Offline = stroke("Offline", "M2.5 9.9A13.5 13.5 0 0 1 21.5 9.9", "M5.3 12.8A9.5 9.5 0 0 1 18.7 12.8", "M8.1 15.6A5.5 5.5 0 0 1 15.9 15.6", "M12 19.4L12 19.5", "M4 3.5L20 20.5")
    val User = stroke("User", "M12 4A4 4 0 1 1 12 12A4 4 0 1 1 12 4Z", "M4.5 20A7.5 7.5 0 0 1 19.5 20")
    val External = stroke("External", "M13 5H19V11", "M19 5L11 13", "M17 14V19H5V7H10")
    val Minus = stroke("Minus", "M5 12H19")
    val Globe = stroke("Globe", "M12 3.5A8.5 8.5 0 1 1 12 20.5A8.5 8.5 0 1 1 12 3.5Z", "M3.5 12H20.5", "M12 3.5C9 7 9 17 12 20.5", "M12 3.5C15 7 15 17 12 20.5")
    val Tag = stroke("Tag", "M4 4H12L20 12L12 20L4 12Z", "M8 8A0.5 0.5 0 1 1 8 9A0.5 0.5 0 1 1 8 8Z")
    val Paperclip = stroke("Paperclip", "M16.5 9.5L10 16A2.5 2.5 0 0 1 6.5 12.5L13.5 5.5A4 4 0 0 1 19 11L12 18")
    val Calendar = stroke("Calendar", "M4.5 6H19.5V19.5H4.5Z", "M4.5 10H19.5", "M8 3.5V7", "M16 3.5V7")
    val Download = stroke("Download", "M12 4V15", "M7.5 10.5L12 15L16.5 10.5", "M5 19H19")
    val Unlock = stroke("Unlock", "M6 11H18V20H6Z", "M8.5 11V8A3.5 3.5 0 0 1 15 6.2")
    val ArrowForward = stroke("ArrowForward", "M5 12H19", "M13 6L19 12L13 18")
    val Pencil = stroke("Pencil", "M4 20H8L19 9L15 5L4 16Z", "M13 7L17 11")
    val Fingerprint = Biometric
    val Shield = stroke("Shield", "M12 3.5L19 6V12C19 16 16 19 12 20.5C8 19 5 16 5 12V6Z", "M9 12L11 14L15 10")
    val History = stroke("History", "M4.5 12A7.5 7.5 0 1 0 7 6.5", "M4 4V8H8", "M12 8V12L14.5 13.5")
    val Palette = stroke("Palette", "M12 4A8 8 0 1 0 12 20C13.5 20 14 19 13.4 18C12.7 16.8 13.5 15.5 15 15.5H17A3 3 0 0 0 20 12.5A8 8 0 0 0 12 4Z")
    val File = stroke("File", "M6 3.5H14L18.5 8V20.5H6Z", "M14 3.5V8H18.5")

    private fun stroke(name: String, vararg paths: String): ImageVector = build(name, fill = false, paths = paths)

    private fun filled(name: String, vararg paths: String): ImageVector = build(name, fill = true, paths = paths)

    private fun build(name: String, fill: Boolean, paths: Array<out String>): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                paths.forEach { data ->
                    addPath(
                        pathData = addPathNodes(data),
                        fill = if (fill) SolidColor(Color.Black) else null,
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = 1.8f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            .build()
}
