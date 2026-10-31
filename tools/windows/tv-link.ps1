# tv-link.ps1: общая часть Windows-скриптов: найти adb, найти ТВ в сети и подключиться (функция Connect-Tv).
# Не запускается сам: его подключают точкой из tv-screen.ps1 и tv-paste.ps1 (. .\tv-link.ps1).
# Откуда берётся адрес ТВ, по порядку: параметр -Tv, переменная среды TV, прошлый удачный адрес (файл .glasstv\tv-address.txt в вашей домашней папке),
# затем mDNS (adb mdns services), затем с ключом -Scan перебор вашей домашней подсети по порту 5555, и наконец вопрос вам.
# Перебор подсети трогает чужие устройства (на телефоны с включённой отладкой придёт окно подтверждения), поэтому он только по ключу -Scan.
# Переменные среды: ADB (путь к adb), GLASSTV_TOOLS (папка инструментов мастера, по умолчанию .glasstv\tools в домашней папке), TV_NAME (имя ТВ в Cast, необязательно).

$gtHome = if ($env:GLASSTV_TOOLS) { $env:GLASSTV_TOOLS } else { Join-Path $HOME '.glasstv\tools' }
$adb = $env:ADB
if (-not $adb) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source } else { $adb = Join-Path $gtHome 'platform-tools\adb.exe' }
}
if (-not (Test-Path $adb)) {
    Write-Host "adb не найден: $adb" -ForegroundColor Red
    Write-Host 'Поставьте platform-tools (мастер предложит) или задайте переменную среды ADB.'
    throw 'adb not found'
}
$ipDir = Join-Path $HOME '.glasstv'
$ipFile = Join-Path $ipDir 'tv-address.txt'   # лежит вне репозитория, в git не попадёт

function Test-Port([string]$ip, [int]$port, [int]$ms = 600) {
    $c = New-Object System.Net.Sockets.TcpClient
    try { return ($c.ConnectAsync($ip, $port).Wait($ms) -and $c.Connected) } catch { return $false } finally { $c.Dispose() }
}

function Ready($addr) {
    return ((& $adb -s $addr get-state 2>$null) -eq 'device')
}

# Все адреса в ваших домашних подсетях, у которых открыт порт: перебор параллельно, около секунды на подсеть
function Find-Open([int]$port) {
    $found = @()
    $nets = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -match '^(192\.168|10\.|172\.(1[6-9]|2\d|3[01]))\.' -and $_.PrefixLength -ge 16 } |
        ForEach-Object { ($_.IPAddress -split '\.')[0..2] -join '.' } | Select-Object -Unique
    foreach ($net in $nets) {
        $socks = foreach ($i in 1..254) {
            $c = New-Object System.Net.Sockets.TcpClient
            ,@("$net.$i", $c, $c.ConnectAsync("$net.$i", $port))
        }
        Start-Sleep -Milliseconds 900
        foreach ($s in $socks) {
            if ($s[2].Status -eq 'RanToCompletion' -and $s[1].Connected) { $found += $s[0] }
            $s[1].Dispose()
        }
    }
    return $found
}

# Телевизор ли это: на Android TV есть возможность leanback
function Test-IsTv($addr) {
    $f = & $adb -s $addr shell pm list features 2>$null
    return [bool]($f -match 'android\.software\.leanback')
}

function Save-Address([string]$ip) {
    if (-not (Test-Path $ipDir)) { New-Item -ItemType Directory -Path $ipDir -Force | Out-Null }
    Set-Content -Path $ipFile -Value $ip -Encoding ASCII
}

# Фиксированный порт 5555 до перезагрузки ТВ (команда adb tcpip меняет режим службы отладки на ТВ, поэтому только по ключу -PinPort)
function Pin-Port([string]$addr) {
    $ip = ($addr -split ':')[0]
    & $adb -s $addr tcpip 5555 *> $null
    Start-Sleep -Seconds 4
    & $adb connect "${ip}:5555" *> $null
    if (Ready "${ip}:5555") { Save-Address $ip; return "${ip}:5555" }
    return $null
}

# Возвращает адрес вида 192.168.0.10:5555 или $null
function Connect-Tv([string]$Tv = '', [switch]$Scan, [switch]$PinPort) {
    $cands = @()
    foreach ($c in @($Tv, $env:TV, (Get-Content $ipFile -ErrorAction SilentlyContinue))) {
        if ($c) { $cands += $c }
    }
    foreach ($c in ($cands | Select-Object -Unique)) {
        $addr = if ($c -match ':\d+$') { $c } else { "${c}:5555" }
        $ip = ($addr -split ':')[0]; $port = [int](($addr -split ':')[1])
        if (Test-Port $ip $port) {
            & $adb connect $addr *> $null
            if (Ready $addr) { Save-Address $ip; return $addr }
        }
    }

    # mDNS: ТВ с беспроводной отладкой сам объявляет адрес и порт (сопряжение должно быть сделано раньше)
    $m = (& $adb mdns services 2>$null) | Select-String '_adb-tls-connect\._tcp\s+(\d+\.\d+\.\d+\.\d+):(\d+)' | Select-Object -First 1
    if ($m) {
        $ip = $m.Matches[0].Groups[1].Value
        $addr = "${ip}:$($m.Matches[0].Groups[2].Value)"
        & $adb connect $addr *> $null
        if (Ready $addr) {
            if ($PinPort) { $p = Pin-Port $addr; if ($p) { return $p } }
            Save-Address $ip
            return $addr
        }
    }

    # Перебор подсети только по явной просьбе
    if ($Scan) {
        Write-Host 'Ищу устройства с открытой отладкой в вашей сети (трогает все такие устройства)...'
        foreach ($ip in (Find-Open 5555)) {
            & $adb connect "${ip}:5555" *> $null
            if ((Ready "${ip}:5555") -and (Test-IsTv "${ip}:5555")) { Save-Address $ip; return "${ip}:5555" }
        }
    }

    # Имя ТВ в Cast: только если вы задали TV_NAME. Так видно, что ТВ в сети, но отладка на нём выключена
    $castFound = $null
    if ($env:TV_NAME) {
        foreach ($ip in (Find-Open 8008)) {
            try {
                $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 "http://${ip}:8008/setup/eureka_info?params=name"
                $info = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()) | ConvertFrom-Json
                if ($info.name -eq $env:TV_NAME) { $castFound = $ip; break }
            } catch { }
        }
    }

    Write-Host ''
    if ($castFound) { Write-Host "Телевизор найден ($castFound), но отладка на нём выключена." -ForegroundColor Yellow }
    else { Write-Host 'Телевизор в этой сети не найден.' -ForegroundColor Yellow }
    Write-Host 'На телевизоре: Настройки, Система, Для разработчиков, Беспроводная отладка: включить.'
    Write-Host 'На том же экране написан адрес, например 192.168.0.10:41234. ПК и телевизор должны быть в одной сети.'
    $addr = (Read-Host 'Адрес с экрана (или Enter, чтобы выйти)').Trim()
    if (-not $addr) { return $null }
    & $adb connect $addr *> $null
    if (-not (Ready $addr)) {
        Write-Host "Не подключилось к $addr. Если на ТВ появилось окно «Разрешить», нажмите «Разрешить» и повторите." -ForegroundColor Red
        return $null
    }
    if ($PinPort) { return (Pin-Port $addr) }
    Save-Address (($addr -split ':')[0])
    return $addr
}
