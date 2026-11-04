# 用正式密钥打两个 release 包（图形窗口输一次口令）
#
# 为什么要这个脚本而不是让他记四条环境变量：他看不到终端、也不熟命令行；
# 而口令必须由他亲手输（经我一次就等于泄露）。所以把整条链做成"双击 → 输一次 → 等它跑完"。
#
# 安全边界：
#   · 口令只进**这个进程**的环境变量（Gradle 就是这么读 KEYSTORE_PASSWORD 的），
#     进程一退就没了；不写文件、不进命令行参数、不进日志；
#   · 脚本自己**不读** E:\dev\keys 里的任何秘密，只读公开的指纹文件做核对；
#   · 最后必须拿 apksigner 回读真实签名，和指纹对得上才算成功 —— 不靠"看起来跑完了"。
#
# 自测（不打扰任何人）：-SelfTest 会跳过 gradle 构建，只验"读 APK 签名 + 比指纹 + 出报告"
# 这一段，用的就是现成那份包，跑完不留任何东西。
param(
    [string]$Keystore = 'E:\dev\keys\omniring-release.jks',
    [string]$Alias = 'omniring',
    [string]$FingerprintFile = 'E:\dev\keys\omniring-release-key-fingerprint.txt',
    [string]$Repo = 'E:\dev\hzr',
    [string]$Apksigner = 'E:\dev\android-sdk\build-tools\37.0.0\apksigner.bat',
    [string]$CheckApk = '',
    [switch]$SelfTest,
    [switch]$SkipLegacy
)

$ErrorActionPreference = 'Stop'

function Norm([string]$s) { if ($s) { ($s -replace ':', '' -replace '\s', '').ToUpper() } else { '' } }

function Read-CertInfo($apk) {
    # apksigner 是个 .bat，必须走 cmd /c；输出里 "certificate DN" 和 "SHA-256 digest" 两行是判据。
    # 注意：`(cmd /c "..." ) 2>&1 | Out-String` 在本机 PowerShell 5.1 里拿到的永远是空串（LEN=0），
    # 于是报告会写着"指纹不一致"而其实没读到东西 —— 必须用 Start-Process 重定向到临时文件再读。
    $tmp = Join-Path $env:TEMP ("hzr_cert_" + [guid]::NewGuid().ToString('N') + ".txt")
    $apArg = '/c ""{0}" verify --print-certs "{1}" > "{2}" 2>&1"' -f $Apksigner, $apk, $tmp
    $p = Start-Process -FilePath 'cmd.exe' -ArgumentList $apArg -Wait -PassThru -NoNewWindow
    $out = if (Test-Path $tmp) { Get-Content -Raw $tmp -ErrorAction SilentlyContinue } else { '' }
    if (Test-Path $tmp) { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }
    if (-not $out) { $out = '' }
    if ($p.ExitCode -ne 0 -and -not $out) { $out = '' }
    $dn = [regex]::Match($out, 'certificate DN:\s*(.+)').Groups[1].Value.Trim()
    $sha = [regex]::Match($out, 'certificate SHA-256 digest:\s*([0-9a-fA-F]+)').Groups[1].Value
    return @{ dn = $dn; sha256 = $sha; raw = $out; exit = $p.ExitCode }
}

function Invoke-Gradle([string]$Task, [string[]]$ExtraArgs) {
    # 为什么不能直接 `& cmd.exe /c ".\gradlew.bat ..." 2>&1 | Select-Object -Last 3`：
    # 本脚本开头 $ErrorActionPreference = 'Stop'，而 Gradle 会把警告写到 stderr，
    # PowerShell 5.1 把"原生命令的 stderr"当成 NativeCommandError 抛出来 —— 构建其实照跑，
    # 脚本却在这里炸掉（真机报过：sign_release.ps1:103 字符: 5 NativeCommandError）。
    # 所以把 stdout+stderr 一起重定向进临时文件，只读回尾部几行做提示，退出码自己判。
    $log = Join-Path $env:TEMP ("hzr_gradle_" + [guid]::NewGuid().ToString('N') + ".log")
    $parts = @('.\gradlew.bat', '--offline', '-q') + $ExtraArgs + @($Task)
    $line = ($parts -join ' ') + ' > "' + $log + '" 2>&1'
    $p = Start-Process -FilePath 'cmd.exe' -ArgumentList ('/c ' + $line) -Wait -PassThru -NoNewWindow
    $tail = @(Get-Content $log -ErrorAction SilentlyContinue | Select-Object -Last 3)
    foreach ($t in $tail) { Write-Host ("   " + $t) -ForegroundColor DarkGray }
    if (Test-Path $log) { Remove-Item $log -Force -ErrorAction SilentlyContinue }
    if ($p.ExitCode -ne 0) {
        throw ("gradle $Task 失败（exit=" + $p.ExitCode + "）。完整日志已删，重跑一遍或用 Android Studio 看原因。")
    }
}

if (-not (Test-Path $Keystore)) { Write-Host "!! 没有密钥：$Keystore" -ForegroundColor Red; exit 1 }if (-not (Test-Path $FingerprintFile)) { Write-Host "!! 没有指纹文件：$FingerprintFile" -ForegroundColor Red; exit 1 }

$want = (Get-Content -Raw $FingerprintFile).Trim()
Write-Host "记录的证书指纹：$want" -ForegroundColor Cyan

if ($SelfTest) {
    if (-not $CheckApk) {
        $CheckApk = Join-Path $Repo 'app\build\outputs\apk\release\app-release.apk'
    }
    Write-Host 'SelfTest：只验"读签名 + 比指纹 + 出报告"这一段，不跑 gradle。' -ForegroundColor Yellow
    if (-not (Test-Path $CheckApk)) {
        Write-Host ('   没有可验的包：' + $CheckApk) -ForegroundColor Red
        Write-Host '   先跑一次 assembleRelease（或直接双击 bat，跳过自测）。' -ForegroundColor Yellow
        exit 1
    }
    $c = Read-CertInfo $CheckApk
    if (-not $c.sha256) {
        Write-Host '   读不到签名 —— apksigner 没给指纹。它自己的输出：' -ForegroundColor Red
        foreach ($l in @($c.raw -split "`r?`n")) { if ($l) { Write-Host ('     | ' + $l) -ForegroundColor DarkGray } }
        exit 1
    }
    $ok = (Norm $c.sha256) -eq (Norm $want)
    Write-Host ("   读到的 DN      : " + $c.dn)
    Write-Host ("   读到的 SHA-256 : " + $c.sha256)
    Write-Host ("   与指纹一致     : " + $(if ($ok) { 'YES' } else { 'NO（SelfTest 用现成的回落调试签名包，NO 才是对的）' }))
    if (-not $ok) { Write-Host '   SelfTest 判定：比对逻辑真的在比（不是恒真）。' -ForegroundColor Green }
    else { Write-Host '   SelfTest 判定：这份包已经是正式签名。' -ForegroundColor Green }
    exit 0
}

Add-Type -AssemblyName System.Windows.Forms
$script:pw = $null
$f = New-Object System.Windows.Forms.Form
$f.Text = 'OmniRing 正式签名打包'
$f.FormBorderStyle = 'FixedDialog'; $f.MaximizeBox = $false; $f.MinimizeBox = $false
$f.TopMost = $true; $f.StartPosition = 'CenterScreen'
$f.ClientSize = New-Object System.Drawing.Size(460, 210)
Add-Type -AssemblyName System.Drawing
$lbl = New-Object System.Windows.Forms.Label
$lbl.Text = "要用这把钥匙打正式包：`r`n$Keystore`r`n`r`n输入口令（只进这个进程的内存，退出就没了）。"
$lbl.SetBounds(16, 14, 428, 86); $f.Controls.Add($lbl)
$tb = New-Object System.Windows.Forms.TextBox
$tb.UseSystemPasswordChar = $true
$tb.SetBounds(16, 108, 428, 26); $f.Controls.Add($tb)
$okBtn = New-Object System.Windows.Forms.Button
$okBtn.Text = '开始打包'; $okBtn.SetBounds(248, 152, 96, 30)
$okBtn.DialogResult = [System.Windows.Forms.DialogResult]::OK; $f.Controls.Add($okBtn)
$cancel = New-Object System.Windows.Forms.Button
$cancel.Text = '取消'; $cancel.SetBounds(348, 152, 96, 30)
$cancel.DialogResult = [System.Windows.Forms.DialogResult]::Cancel; $f.Controls.Add($cancel)
$f.AcceptButton = $okBtn
$res = $f.ShowDialog()
$pw = $tb.Text
$f.Dispose()
if ($res -ne [System.Windows.Forms.DialogResult]::OK -or [string]::IsNullOrWhiteSpace($pw)) {
    Write-Host '已取消，什么都没构建。' -ForegroundColor Yellow; exit 0
}

$env:KEYSTORE_FILE = $Keystore
$env:KEYSTORE_PASSWORD = $pw
$env:KEY_ALIAS = $Alias
$env:KEY_PASSWORD = $pw
$pw = $null

Push-Location $Repo
try {
    Write-Host '构建 1/2：双声明那份 …' -ForegroundColor Cyan
    Invoke-Gradle 'assembleRelease' @()
    $dual = Join-Path $Repo 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path $dual)) { throw '没产出 app-release.apk' }

    $built = Get-Item $dual
    $vname = (Select-String -Path (Join-Path $Repo 'app\build.gradle.kts') -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
    $outDual = Join-Path $Repo ("build\OmniRing-$vname-signed.apk")
    Copy-Item $dual $outDual -Force
    Write-Host ("   → " + $outDual) -ForegroundColor Green

    $outLegacy = ''
    if (-not $SkipLegacy) {
        Write-Host '构建 2/2：只留传统入口那份（发酷友 / 上架）…' -ForegroundColor Cyan
        Invoke-Gradle 'assembleRelease' @('-Phzr.legacyOnly=true')
        $outLegacy = Join-Path $Repo ("build\OmniRing-$vname-signed-legacy.apk")
        Copy-Item $dual $outLegacy -Force
        Write-Host ("   → " + $outLegacy) -ForegroundColor Green
    }
} finally {
    Pop-Location
    Remove-Item Env:KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:KEY_PASSWORD -ErrorAction SilentlyContinue
}

# 用 apksigner 回读真实签名 —— 这才是"打上了正式签名"的正面证据
$lines = @()
$allOk = $true
foreach ($apk in @($outDual, $outLegacy | Where-Object { $_ })) {
    $c = Read-CertInfo $apk
    $ok = (Norm $c.sha256) -eq (Norm $want)
    if (-not $ok) { $allOk = $false }
    $lines += ("{0}`r`n   DN={1}`r`n   SHA256={2}`r`n   与正式指纹一致={3}" -f (Split-Path $apk -Leaf), $c.dn, $c.sha256, $(if ($ok) { 'YES' } else { 'NO' }))
    $lines += ("   大小={0} B  md5={1}" -f (Get-Item $apk).Length, ((Get-FileHash -Algorithm MD5 $apk).Hash.ToLower()))
}
$report = Join-Path $Repo 'build\release_sign_report.txt'
($lines -join "`r`n") | Set-Content -Path $report -Encoding utf8
Write-Host ''
Write-Host ($lines -join "`r`n")
Write-Host ''

# 把成品拷到桌面按版本号命名的目录 —— 他找不到 build\ 里的文件，所以这一步必须在脚本里做，
# 别指望那个 .bat 再解析一遍 versionName（bat 的 for /f 转义太容易写坏，已经坏过一次）。
$deskDir = Join-Path ([Environment]::GetFolderPath('Desktop')) ("OmniRing " + $vname)
if (-not (Test-Path $deskDir)) { New-Item -ItemType Directory -Path $deskDir | Out-Null }
# 只拷本次版本的包，别把 build\ 里历次留下的旧包一起糊到桌面
foreach ($f in @(Get-ChildItem (Join-Path $Repo 'build') -Filter ("OmniRing-$vname-signed*.apk") -ErrorAction SilentlyContinue)) {
    Copy-Item $f.FullName (Join-Path $deskDir $f.Name) -Force
}
if (Test-Path $report) { Copy-Item $report (Join-Path $deskDir 'release_sign_report.txt') -Force }
Write-Host ("── 已拷到桌面：" + $deskDir) -ForegroundColor Green
Write-Host ''
Start-Process explorer.exe -ArgumentList $deskDir
if ($allOk) {
    Write-Host '── 两份包都用正式密钥签好了，指纹对得上。' -ForegroundColor Green
} else {
    Write-Host '!! 回读的签名和记录的指纹不一致 —— 别把这些包发出去，先查哪一步走岔了。' -ForegroundColor Red
}
Add-Type -AssemblyName System.Windows.Forms
[System.Windows.Forms.MessageBox]::Show(
    $(if ($allOk) { "正式签名的包打好了，指纹核对一致。`r`n报告在 build\release_sign_report.txt`r`n告诉 Qoder 一声，它自己会读到。" }
      else { "签名核对不一致，先别发。`r`n报告在 build\release_sign_report.txt" }),
    'OmniRing · 打包结果') | Out-Null
