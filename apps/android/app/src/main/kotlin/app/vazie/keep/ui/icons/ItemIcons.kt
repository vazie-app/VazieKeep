package app.vazie.keep.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import app.vazie.keep.R

// The icons a group or an entry can have. Each stands for one standard KeePass icon number (`id`), so
// KeePassXC and other apps show their own drawing of the same thing. A KDBX file may name any of the
// 69 standard icons: the ones outside this set are shown as the nearest of these.
enum class ItemIcon(val id: Int, val image: ImageVector, val label: Int, val keywords: String) {
    Folder(48, VazieIcons.Folder, R.string.icon_folder, "folder group directory"),
    Key(0, VazieIcons.Key, R.string.icon_key, "key password login"),
    Web(1, VazieIcons.Globe, R.string.icon_web, "web site internet globe world browser"),
    Mail(19, VazieIcons.Mail, R.string.icon_mail, "mail email letter inbox"),
    Bank(37, VazieIcons.Bank, R.string.icon_bank, "bank finance account"),
    Money(66, VazieIcons.Card, R.string.icon_money, "money card payment pay wallet"),
    Home(60, VazieIcons.Home, R.string.icon_home, "home house family"),
    Star(61, VazieIcons.Star, R.string.icon_star, "star favorite favourite important"),
    Person(9, VazieIcons.User, R.string.icon_person, "person identity id profile user"),
    Chat(5, VazieIcons.Chat, R.string.icon_chat, "chat social message messenger forum"),
    Phone(68, VazieIcons.Phone, R.string.icon_phone, "phone mobile sim app"),
    Server(3, VazieIcons.Server, R.string.icon_server, "server hosting network cloud"),
    Computer(18, VazieIcons.Monitor, R.string.icon_computer, "computer desktop monitor pc mac"),
    Terminal(30, VazieIcons.Terminal, R.string.icon_terminal, "terminal console ssh shell code developer"),
    Settings(34, VazieIcons.Settings, R.string.icon_settings, "settings config admin router"),
    Archive(36, VazieIcons.Archive, R.string.icon_archive, "archive storage disk backup old"),
    Clock(39, VazieIcons.Clock, R.string.icon_clock, "clock time temporary"),
    Book(56, VazieIcons.Book, R.string.icon_book, "book study school library education"),
    Note(7, VazieIcons.Note, R.string.icon_note, "note document text paper"),
    Certificate(67, VazieIcons.Certificate, R.string.icon_certificate, "certificate license document official government"),
    Work(59, VazieIcons.Briefcase, R.string.icon_work, "work job office business tool"),
    Shopping(47, VazieIcons.Package, R.string.icon_shopping, "shopping shop store package delivery order"),
    Camera(11, VazieIcons.Camera, R.string.icon_camera, "camera photo video media"),
    Lock(52, VazieIcons.Lock, R.string.icon_lock, "lock secure secret safe private"),
    Warning(2, VazieIcons.Warning, R.string.icon_warning, "warning important attention"),
    Info(46, VazieIcons.Info, R.string.icon_info, "info information help"),
    Trash(43, VazieIcons.Trash, R.string.icon_trash, "trash bin deleted"),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }

        // The nearest icon of the set for every other standard KeePass number.
        private val nearest = mapOf(
            4 to Folder, 49 to Folder, 50 to Folder,
            13 to Key, 58 to Person,
            8 to Web, 16 to Web, 35 to Web,
            25 to Mail, 40 to Mail,
            12 to Chat,
            15 to Computer, 23 to Computer, 31 to Computer, 32 to Computer, 42 to Computer, 62 to Computer, 64 to Computer,
            29 to Terminal, 33 to Terminal,
            20 to Settings,
            17 to Archive, 26 to Archive, 27 to Archive, 38 to Archive,
            45 to Clock,
            65 to Book,
            10 to Note, 21 to Note, 22 to Note, 28 to Note, 41 to Note, 44 to Note, 54 to Note, 57 to Note, 63 to Note,
            53 to Certificate,
            6 to Work, 14 to Work,
            55 to Camera,
            51 to Lock,
            24 to Warning,
        )

        fun of(id: Int, fallback: ItemIcon): ItemIcon = byId[id] ?: nearest[id] ?: fallback

        fun forGroup(id: Int): ItemIcon = of(id, Folder)

        // Null for the default key: an entry without an icon of its own shows its letter.
        fun forEntry(id: Int): ItemIcon? = if (id == Key.id) null else of(id, Key)
    }
}
