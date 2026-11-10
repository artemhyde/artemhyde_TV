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
Телевизор на Google TV или Android TV 9 и новее (TCL, Sony, Philips, Hisense, Xiaomi и другие) или приставка с Google TV. Samsung и LG не подойдут: у них своя система. Проверено на TCL 65C6K (Android 14) и на виртуальных Google TV 14 и 12 и Android TV 9, 10, 11 и 13; на других моделях часть функций может не заработать, мастер проверит это сам и скажет честно.
Компьютер и телевизор в одной домашней сети Wi-Fi, пульт под рукой.

КАК ПОДКЛЮЧИТЬ ТЕЛЕВИЗОР (один раз, то же показывает окно мастера со снимками)
1. На телевизоре: Настройки, Система, «Об устройстве». На строке «Сборка ОС Android TV» нажмите OK семь раз подряд, появится «Вы стали разработчиком!».
2. Назад в «Система»: появился пункт «Для разработчиков». В нём откройте «Отладка по Wi-Fi» (на некоторых телевизорах «Беспроводная отладка»).
3. Выберите «Включено». Спросит, разрешить ли отладку в этой сети: отметьте «Всегда» и нажмите «Разрешить». Затем «Подключить устройство с помощью кода подключения». Отладке нужен Wi-Fi, даже если телевизор подключён кабелем.
4. Введите шесть цифр с экрана телевизора в окне мастера. Окно на телевизоре не закрывайте, пока не подключится.
Телевизор на Android 9, 10 или 11: пункта «Отладка по Wi-Fi» и кода там нет. В «Для разработчиков» включите «Отладка по USB» (и «Отладка по сети», если такой пункт есть) и введите в окне мастера IP-адрес телевизора из настроек сети. Спросит «Разрешить отладку по USB?»: отметьте «Всегда разрешать с этого компьютера» и нажмите OK. Кабель не нужен.
Подробная инструкция со снимками: https://github.com/artemhyde/artemhyde_TV/blob/main/docs/install/windows.md

ЧТО СДЕЛАЕТ МАСТЕР
Найдёт телевизор, прочитает, что он умеет, и покажет план: что поставит, какие права выдаст и как всё вернуть. Главным экраном HYDE станет, только если вы так выберете; можно сначала попробовать его как обычное приложение. До кнопки «Установить» на телевизоре ничего не меняется. Каждое изменение пишется в журнал (папка .glasstv/backup в вашей домашней папке), кнопка «Вернуть как было» возвращает телевизор к прежнему виду.
Для связи с телевизором нужна программа adb от Google. Раздавать её вместе с HYDE TV нельзя, поэтому мастер предложит скачать её с сайта Google (около 8 МБ), когда вы примете условия Google. Скачанное сверяется с официальной контрольной суммой.
Key Mapper (кнопки пульта) и приложения по желанию (SmartTube, Lampa, TorrServe, LocalSend, Material Files) мастер скачивает со страниц их авторов на GitHub, только с вашего согласия, и ставит, только если сумма файла и подпись совпали со списком. Откат удаляет всё, что поставил мастер, кроме Key Mapper (у него права администратора, их снимают руками).
В начале мастера две галочки, обе включены: «Обновлять HYDE автоматически» (новые версии ставит «Сторож» на телевизоре) и «Отправлять автору технические отчёты» (модель телевизора, версии Android и HYDE, что поставилось, ошибки и случайный номер устройства; без имени, адресов и названий сетей). Отчёт уходит в конце установки, обновления и возврата, а с телевизора раз в сутки короткая сводка. Не нужно: снимите галочку. Номер отчёта и номер устройства мастер показывает после отправки: напишите их автору, и он удалит записи.

С CLAUDE
Если у вас есть Claude Code: откройте его в этой папке и напишите «давай начнём». Claude поведёт по шагам тем же установщиком (инструкция для него в файле CLAUDE.md).

ЧТО В ПАПКЕ
hyde-install: установщик (без параметров открывает окно мастера; команды: hyde-install help)
release: приложения HYDE TV, опись manifest.json и контрольные суммы SHA256SUMS
licenses: лицензии HYDE TV и сторонних частей (HYDE Browser сделан из TV Bro, плеер «Волны» из Aura Music по GPL-3.0, его исходники лежат в release)
CLAUDE.md: инструкция для Claude
VERSION.txt: номер выпуска и коммиты, из которых собраны приложения и установщик

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

Connect the TV once: on the TV open Settings, System, About and press OK seven times on "Android TV OS build"; then in System, Developer options, open "Wi-Fi debugging" (on some TVs "Wireless debugging"), turn it on, choose "Pair device with pairing code" and type the six digits into the wizard. On Android 9, 10 or 11 there is no Wi-Fi debugging: turn on "USB debugging" (and "Network debugging" if present) and type the TV's IP address into the wizard; allow the computer when the TV asks, no cable needed. Android 9 and newer is supported. Samsung and LG TVs are not supported (different system); a Google TV streaming box works with any TV.
The wizard finds the TV, reads what it supports and shows a plan: what will be installed, which permissions are granted and how everything is reverted. You choose whether HYDE becomes the home screen or is tried as a regular app first. Nothing on the TV changes before you press "Install". Every change is written to a journal (.glasstv/backup in your home folder), and "Revert everything" returns the TV to how it was. The wizard needs adb from Google; it may not be redistributed with HYDE TV, so the wizard downloads it from Google after you accept Google's terms and checks it against Google's official checksum. The interface of the apps on the TV is in Russian.
At the start the wizard shows two boxes, both ticked: «Обновлять HYDE автоматически» (update HYDE automatically: Guard installs new versions on the TV) and «Отправлять автору технические отчёты» (send technical reports to the author: TV model, Android and HYDE versions, what was installed, errors and a random device number; no name, addresses or network names). The report goes out at the end of an install, update or revert, and the TV sends a short daily summary. Untick a box if you do not want it. The wizard shows the report and device numbers after sending: send them to the author to have the records deleted.
With Claude Code: open it in this folder and say "let's start"; it drives the same installer (see CLAUDE.md).
Release signing certificate (SHA-256): {CERT}
