# tv-paste.ps1: вставить на ТВ то, что скопировано на ПК (любой текст, русский тоже), и нажать Enter.
# Запуск: powershell -ExecutionPolicy Bypass -File tools\windows\tv-paste.ps1 [-Tv 192.168.0.10:5555] [-NoEnter] [-Scan]
# Сначала встаньте на ТВ в поле ввода (поиск, логин, адрес сайта). Подойдёт ярлык на рабочем столе с этой командой.
# Текст кладёт в буфер ТВ мини-приложение «Вставка с ПК» (app.glasstv.clip), потом нажимается «Вставить».
param([string]$Tv = '', [switch]$NoEnter, [switch]$Scan)

. (Join-Path $PSScriptRoot 'tv-link.ps1')

$text = Get-Clipboard -Raw
if (-not $text) {
    Write-Host 'В буфере обмена нет текста: сначала скопируйте его на ПК.' -ForegroundColor Yellow
    Start-Sleep -Seconds 3
    exit 1
}
$text = $text.TrimEnd("`r", "`n")   # перенос строки в конце скопированного не нужен

$dev = Connect-Tv -Tv $Tv -Scan:$Scan
if (-not $dev) { Read-Host 'Нажмите Enter, чтобы закрыть' | Out-Null; exit 1 }

# Текст едет в base64: так через adb проходят кириллица, кавычки и любые знаки
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($text))
$r = & $adb -s $dev shell am start -W -n app.glasstv.clip/.Paste --es b64 $b64 2>&1
if ($r -match 'Error') {
    Write-Host 'На телевизоре нет приложения «Вставка с ПК» (app.glasstv.clip). Его ставит мастер из папки apps\clip.' -ForegroundColor Red
    Read-Host 'Нажмите Enter, чтобы закрыть' | Out-Null
    exit 1
}
Start-Sleep -Milliseconds 400
& $adb -s $dev shell input keyevent KEYCODE_PASTE *> $null
if (-not $NoEnter) {
    Start-Sleep -Milliseconds 300
    & $adb -s $dev shell input keyevent KEYCODE_ENTER *> $null
}
Write-Host "Вставлено на телевизор: $text" -ForegroundColor Green
Start-Sleep -Seconds 1
