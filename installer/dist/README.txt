HYDE TV {VERSION}: установщик для телевизора на Google TV или Android TV
(English below)

КАК НАЧАТЬ ({PLATFORM})
@@windows
1. Распакуйте архив целиком: правой кнопкой по архиву, «Извлечь всё». Из самого архива не запускайте.
2. В распакованной папке запустите hyde-install.exe. Откроются чёрное окно и окно мастера в браузере. Чёрное окно не закрывайте, пока идёт работа.
3. Windows может сказать «Система Windows защитила ваш компьютер»: программа не подписана платным сертификатом. Нажмите «Подробнее», затем «Выполнить в любом случае». Сомневаетесь: сверьте контрольную сумму архива с файлом SHA256SUMS на странице выпуска (PowerShell: Get-FileHash <архив>).
@@macos
1. Распакуйте архив двойным щелчком и откройте папку.
2. Дважды щёлкните hyde-install: откроются Терминал и окно мастера в браузере. Терминал не закрывайте, пока идёт работа.
3. macOS скажет, что программу неустановленного разработчика открыть нельзя: она не подписана сертификатом Apple. Откройте «Системные настройки», «Конфиденциальность и безопасность», внизу нажмите «Всё равно открыть» и запустите снова. Сомневаетесь: сверьте контрольную сумму архива с файлом SHA256SUMS на странице выпуска (Терминал: shasum -a 256 <архив>).
@@linux
1. Распакуйте: tar -xzf {ARCHIVE}
2. В распакованной папке запустите ./hyde-install. Откроется окно мастера в браузере; если браузера нет, адрес окна будет в терминале (./hyde-install --no-browser).
3. На Linux для ARM у Google нет adb: поставьте его пакетом системы (например, sudo apt install adb).
@@end

ЧТО ПОНАДОБИТСЯ
Телевизор на Google TV или Android TV 12 и новее. Проверено на TCL 65C6K (Android 14) и на виртуальном Google TV; на других моделях часть функций может не заработать, мастер проверит это сам и скажет честно.
Компьютер и телевизор в одной домашней сети, пульт под рукой: на телевизоре один раз включается «Беспроводная отладка», мастер подскажет, где.

ЧТО СДЕЛАЕТ МАСТЕР
Найдёт телевизор, прочитает, что он умеет, и покажет план: что поставит, какие права выдаст и как всё вернуть. До кнопки «Установить» на телевизоре ничего не меняется. Каждое изменение пишется в журнал (папка .glasstv/backup в вашей домашней папке), кнопка «Откатить всё» возвращает телевизор как было.
Для связи с телевизором нужна программа adb от Google. Раздавать её вместе с HYDE TV нельзя, поэтому мастер предложит скачать её с сайта Google (около 8 МБ), когда вы примете условия Google. Скачанное сверяется с официальной контрольной суммой.
Key Mapper (кнопки пульта) мастер скачивает с GitHub автора, тоже только с вашего согласия, и сверяет подпись.

С CLAUDE
Если у вас есть Claude Code: откройте его в этой папке и напишите «давай начнём». Claude поведёт по шагам тем же установщиком (инструкция для него в файле CLAUDE.md).

ЧТО В ПАПКЕ
hyde-install: установщик (без параметров открывает окно мастера; команды: hyde-install help)
release: приложения HYDE TV, опись manifest.json и контрольные суммы SHA256SUMS
licenses: лицензии HYDE TV и сторонних частей (HYDE Browser сделан из TV Bro, плеер «Волны» из Aura Music по GPL-3.0, его исходники лежат в release)
CLAUDE.md: инструкция для Claude

ПРОВЕРКА ФАЙЛОВ
Все приложения подписаны ключом выпуска. Отпечаток сертификата (SHA-256):
{CERT}

----------------------------------------------------------------------

HYDE TV {VERSION}: installer for Google TV and Android TV sets

HOW TO START ({PLATFORM})
@@windows
Extract the whole archive (right click, "Extract All"), then run hyde-install.exe in the extracted folder. A console window and the wizard in your browser will open; keep the console open while it works. If Windows shows "Windows protected your PC" (the program is not signed with a paid certificate), click "More info", then "Run anyway".
@@macos
Unzip the archive and double click hyde-install: Terminal and the wizard in your browser will open. macOS will refuse to open a program from an unidentified developer (it is not signed by Apple): open System Settings, Privacy & Security, click "Open Anyway" at the bottom and run it again.
@@linux
Extract with tar -xzf {ARCHIVE} and run ./hyde-install in the extracted folder (./hyde-install --no-browser prints the wizard address instead of opening a browser). On ARM Linux Google ships no adb: install it with your package manager.
@@end

The wizard finds the TV, reads what it supports and shows a plan: what will be installed, which permissions are granted and how everything is reverted. Nothing on the TV changes before you press "Install". Every change is written to a journal (.glasstv/backup in your home folder), and "Revert everything" returns the TV to how it was. The wizard needs adb from Google; it may not be redistributed with HYDE TV, so the wizard downloads it from Google after you accept Google's terms and checks it against Google's official checksum. The interface of the apps on the TV is in Russian.
With Claude Code: open it in this folder and say "let's start"; it drives the same installer (see CLAUDE.md).
Release signing certificate (SHA-256): {CERT}
