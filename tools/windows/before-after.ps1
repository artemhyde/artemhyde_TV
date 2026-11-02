# before-after.ps1: открывает в браузере интерактивное сравнение «До и После» со шторкой (docs\before-after.html).
# Запуск: powershell -ExecutionPolicy Bypass -File tools\windows\before-after.ps1. Нужен только браузер: ни ТВ, ни интернет, ни инструменты сборки.
$page = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'docs\before-after.html'
if (-not (Test-Path $page)) {
    Write-Host "Нет файла ${page}: репозиторий скачан неполно." -ForegroundColor Red
    exit 1
}
Start-Process $page
