"""Generate the demo databases the README and store screenshots are taken from.

    python3 -m venv .venv && .venv/bin/pip install pykeepass==4.2.0
    .venv/bin/python tooling/screenshots/generate_demo_vault.py <out-dir>

Writes `<out-dir>/Vazie Keep demo.kdbx` (English) and `<out-dir>/Vazie Keep демо.kdbx` (Russian), master
password `demo`. Every name, address and secret is invented; the addresses are under `example.*`.
Icons are standard KeePass icon numbers Vazie Keep draws.
"""

import sys
from pathlib import Path

from pykeepass import create_database

PASSWORD = "demo"

MAIL, BANK, MONEY, CHAT, MEDIA, SHOPPING, WORK = 19, 37, 66, 5, 11, 47, 59
TERMINAL, SERVER, WEB, PHONE, BOOK, CERTIFICATE, HOME, KEY = 30, 3, 1, 68, 56, 67, 60, 0

# (database name, root entries, [(group, group icon, entries)]); an entry is (title, user, password, url, icon, notes)
DEMO = {
    "en": ("Passwords", [
        ("Mail", "alex.morgan@example.com", "violet-Harbor-42-quiet", "https://mail.example.com", MAIL, ""),
        ("Northwind Bank", "alex.morgan", "Q8#tundra-Velvet-park", "https://bank.example", BANK, "Card PIN is not stored here."),
        ("Messenger", "+44 20 7946 0958", "pale-orbit-sunday-91", "https://chat.example.org", CHAT, ""),
    ], [
        ("Personal", HOME, [
            ("Cloud Photos", "alex.morgan", "Lantern#7river!Moss", "https://photos.example.net", MEDIA, ""),
            ("Home Wi-Fi", "Morgan-5G", "kettle-Copper-staircase-8", "", WEB, "Router in the hallway cupboard."),
        ]),
        ("Finance", MONEY, [
            ("Savings", "AM-204-771", "ember.Lotus.3150.drift", "https://savings.example", MONEY, ""),
        ]),
        ("Work", WORK, [
            ("Office SSO", "a.morgan@contoso.example", "Granite!Fox-2026-tide", "https://sso.contoso.example", CERTIFICATE, ""),
            ("Build server", "deploy", "n7Vq-Rk2p-Ws9x-Lm4c", "https://ci.contoso.example", SERVER, ""),
            ("SSH — staging", "alex", "copper-Meadow-17-raven", "", TERMINAL, "Host: staging.contoso.example"),
        ]),
        ("Shopping", SHOPPING, [
            ("Bookshop", "alex.morgan@example.com", "paper-Willow-88-lamp", "https://books.example.shop", BOOK, ""),
            ("Groceries", "alex.morgan@example.com", "Citrus=Atlas-604", "https://fresh.example.shop", SHOPPING, ""),
        ]),
    ]),
    "ru": ("Пароли", [
        ("Почта", "anna.smirnova@example.com", "сирень-Гавань-42-тихо", "https://mail.example.com", MAIL, ""),
        ("Северный банк", "anna.smirnova", "Q8#тундра-Бархат-парк", "https://bank.example", BANK, "ПИН-код карты здесь не хранится."),
        ("Мессенджер", "+7 900 000-00-00", "бледная-орбита-91", "https://chat.example.org", CHAT, ""),
    ], [
        ("Личное", HOME, [
            ("Облако с фото", "anna.smirnova", "Фонарь#7река!Мох", "https://photos.example.net", MEDIA, ""),
            ("Домашний Wi-Fi", "Smirnovy-5G", "чайник-Медь-лестница-8", "", WEB, "Роутер в шкафу в прихожей."),
        ]),
        ("Финансы", MONEY, [
            ("Накопления", "AS-204-771", "уголь.Лотос.3150.дрейф", "https://savings.example", MONEY, ""),
        ]),
        ("Работа", WORK, [
            ("Вход в офис", "a.smirnova@contoso.example", "Гранит!Лис-2026-прилив", "https://sso.contoso.example", CERTIFICATE, ""),
            ("Сервер сборки", "deploy", "n7Vq-Rk2p-Ws9x-Lm4c", "https://ci.contoso.example", SERVER, ""),
            ("SSH — стенд", "anna", "медь-Луг-17-ворон", "", TERMINAL, "Хост: staging.contoso.example"),
        ]),
        ("Покупки", SHOPPING, [
            ("Книжный", "anna.smirnova@example.com", "бумага-Ива-88-лампа", "https://books.example.shop", BOOK, ""),
            ("Продукты", "anna.smirnova@example.com", "Цитрус=Атлас-604", "https://fresh.example.shop", SHOPPING, ""),
        ]),
    ]),
}

FILES = {"en": "Vazie Keep demo.kdbx", "ru": "Vazie Keep демо.kdbx"}


def build(lang, out):
    name, root_entries, groups = DEMO[lang]
    path = out / FILES[lang]
    path.unlink(missing_ok=True)
    kp = create_database(str(path), password=PASSWORD)
    kp.database_name = name
    kp.root_group.name = name
    for title, user, secret, url, icon, notes in root_entries:
        kp.add_entry(kp.root_group, title, user, secret, url=url or None, notes=notes or None, icon=str(icon), force_creation=True)
    for group_name, group_icon, entries in groups:
        group = kp.add_group(kp.root_group, group_name, icon=str(group_icon))
        for title, user, secret, url, icon, notes in entries:
            kp.add_entry(group, title, user, secret, url=url or None, notes=notes or None, icon=str(icon), force_creation=True)
    kp.save()
    return path


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for lang in DEMO:
        print(build(lang, out))


if __name__ == "__main__":
    main()
