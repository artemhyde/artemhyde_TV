# tv-screen.ps1: экран ТВ в окне на ПК (scrcpy): смотреть, кликать мышкой, печатать с клавиатуры ПК. scrcpy должен уже стоять.
# Запуск: powershell -ExecutionPolicy Bypass -File tools\windows\tv-screen.ps1 [-Tv 192.168.0.10:5555] [-Scan] [-KeepIme] [-Pause]
# Мышь: левая кнопка нажимает, правая «Назад», колёсико листает, средняя «Домой». Закрыть: закрыть окно.
# Клавиатура ПК работает как клавиатура ТВ: русские и английские буквы, язык переключает Ctrl+Space. Ctrl+V вставляет текст, скопированный на ПК.
# На время сеанса включается клавиатура Google (Gboard), если она есть: с клавиатурой производителя ТВ раскладка подключённой клавиатуры может не работать.
# Прежняя клавиатура возвращается при любом выходе, и при Ctrl+C тоже (try/finally); если задана переменная среды LEDGER, смена пишется в журнал (-KeepIme: ничего не менять). Путь к scrcpy: переменная SCRCPY, PATH или .glasstv\tools\scrcpy.
param([string]$Tv = '', [switch]$Scan, [switch]$KeepIme, [switch]$Pause)

. (Join-Path $PSScriptRoot 'tv-link.ps1')

$scrcpy = $env:SCRCPY
if (-not $scrcpy) {
    $cmd = Get-Command scrcpy -ErrorAction SilentlyContinue
    if ($cmd) { $scrcpy = $cmd.Source } else { $scrcpy = Join-Path $gtHome 'scrcpy\scrcpy.exe' }
}
if (-not (Test-Path $scrcpy)) {
    Write-Host 'scrcpy не найден. Это необязательная программа (проект Genymobile/scrcpy на GitHub).' -ForegroundColor Yellow
    Write-Host 'Положите её в .glasstv\tools\scrcpy или задайте переменную среды SCRCPY.'
    if ($Pause) { Read-Host 'Нажмите Enter, чтобы закрыть' | Out-Null }
    exit 1
}

# scrcpy должен работать через тот же adb, что и всё остальное: два разных adb выбивают друг друга
$env:ADB = $adb

Write-Host 'Экран телевизора' -ForegroundColor Cyan
$dev = Connect-Tv -Tv $Tv -Scan:$Scan
$ok = [bool]$dev
if ($ok) {
    $oldIme = $null
    $imeChanged = $false
    try {
        & $adb -s $dev shell input keyevent KEYCODE_WAKEUP *> $null
        if (-not $KeepIme) {
            $oldIme = ((& $adb -s $dev shell settings get secure default_input_method) -join '').Trim()
            $gboard = ((& $adb -s $dev shell ime list -s) | Where-Object { $_ -match 'com\.google\.android\.inputmethod\.latin' } | Select-Object -First 1)
            if ($gboard) {
                $gboard = $gboard.Trim()
                # Смена клавиатуры идёт в журнал (если задан LEDGER), чтобы откат вернул прежнюю, даже если этот скрипт убили
                if ($env:LEDGER -and (Test-Path $env:LEDGER)) {
                    $inv = if ($oldIme -and $oldIme -ne 'null') { "settings put secure default_input_method $oldIme" } else { 'settings delete secure default_input_method' }
                    $row = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + "`tклавиатура ТВ временно заменена на Gboard (зеркало экрана)`time set $gboard`t$inv`n"
                    [IO.File]::AppendAllText((Resolve-Path $env:LEDGER).Path, $row, (New-Object Text.UTF8Encoding $false))
                }
                $imeChanged = $true
                & $adb -s $dev shell ime set $gboard *> $null
            }
        }
        Write-Host 'Окно с экраном открывается. Закройте его, когда закончите.' -ForegroundColor Green
        Write-Host 'Печатать можно с клавиатуры ПК, язык переключает Ctrl+Space. Ctrl+V вставляет скопированное на ПК.'
        # Звук остаётся на телевизоре; 30 кадров в секунду хватает для меню и не грузит ТВ;
        # --keyboard=uhid: телевизор видит подключённую клавиатуру, работают все буквы и Ctrl+V
        & $scrcpy --serial $dev --no-audio --max-size 1920 --max-fps 30 --window-title 'GlassTV' --shortcut-mod lalt --keyboard=uhid
    } finally {
        # Прежняя клавиатура возвращается при любом выходе: закрытие окна, Ctrl+C, ошибка
        if ($imeChanged) {
            if ($oldIme -and $oldIme -ne 'null') { & $adb -s $dev shell settings put secure default_input_method $oldIme *> $null }
            else { & $adb -s $dev shell settings delete secure default_input_method *> $null }
            Write-Host 'Прежняя клавиатура ТВ возвращена.'
        }
    }
}

# Пауза нужна, только если что-то не получилось, чтобы успеть прочитать
if ($Pause -and -not $ok) { Read-Host 'Нажмите Enter, чтобы закрыть' | Out-Null }
