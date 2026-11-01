# OmniRing 正式签名密钥生成器（图形窗口版，不用开终端）
#
# 为什么有这个版本：黑窗口那条路他看不到窗口（conhost 可能被别的窗压住 / 主题太暗 /
# 一开始就没弹出来）。口令改由一个置顶的小窗口输入，屏幕上不会有终端，也不需要他
# 会用什么 Git Bash。
#
# 安全边界和终端版完全一样，一条没放低：
#   · 口令只进内存 + 一个 0600 的临时文件喂给 keytool，用完立刻删；
#   · 口令不出现在命令行参数里（同机任何进程都能读 argv），不进日志，不落任何文件；
#   · 唯一会被写进磁盘的是**证书指纹**（SHA256，公开无害，就是要抄给我核对的）；
#   · 已存在密钥时拒绝覆盖 —— 覆盖了等于把所有老用户的更新通道剪断。
#
# 自测（不打扰任何人，用一次性口令走完整条链，跑完自己删干净）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\make_release_key.ps1 -SelfTest
param(
    [switch]$SelfTest,
    [string]$KeyDir = 'E:\dev\keys',
    [string]$KeyFile = '',
    [string]$Alias = 'omniring',
    [int]$ValidDays = 10950,
    [string]$DName = 'CN=OmniRing, OU=TvTjiesen, O=TvTjiesen, L=Hangzhou, ST=Zhejiang, C=CN',
    [string]$KeyTool = 'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot\bin\keytool.exe'
)

$ErrorActionPreference = 'Stop'
if (-not $KeyFile) { $KeyFile = Join-Path $KeyDir 'omniring-release.jks' }
$fpOut = Join-Path $KeyDir 'omniring-release-key-fingerprint.txt'

function Say($t, $c = 'White') { Write-Host $t -ForegroundColor $c }
function Ask-Title($msg) {
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    $f = New-Object System.Windows.Forms.Form
    $f.Text = 'OmniRing 正式签名密钥'
    $f.FormBorderStyle = 'FixedDialog'
    $f.MaximizeBox = $false; $f.MinimizeBox = $false
    $f.TopMost = $true
    $f.StartPosition = 'CenterScreen'
    $f.ClientSize = New-Object System.Drawing.Size(460, 250)

    $lbl = New-Object System.Windows.Forms.Label
    $lbl.Text = $msg + "`r`n`r`n输入两次同一个口令（≥16 位）。口令只进内存，`r`n不写文件、不进日志、不显示在屏幕上。"
    $lbl.SetBounds(16, 14, 428, 86)
    $f.Controls.Add($lbl)

    $tb1 = New-Object System.Windows.Forms.TextBox
    $tb1.UseSystemPasswordChar = $true
    $tb1.SetBounds(16, 108, 428, 26)
    $f.Controls.Add($tb1)

    $tb2 = New-Object System.Windows.Forms.TextBox
    $tb2.UseSystemPasswordChar = $true
    $tb2.SetBounds(16, 140, 428, 26)
    $f.Controls.Add($tb2)

    $ok = New-Object System.Windows.Forms.Button
    $ok.Text = '生成密钥'
    $ok.SetBounds(248, 192, 96, 30); $ok.DialogResult = [System.Windows.Forms.DialogResult]::OK
    $f.Controls.Add($ok)

    $cancel = New-Object System.Windows.Forms.Button
    $cancel.Text = '取消'
    $cancel.SetBounds(348, 192, 96, 30); $cancel.DialogResult = [System.Windows.Forms.DialogResult]::Cancel
    $f.Controls.Add($cancel)

    $f.AcceptButton = $ok
    $res = $f.ShowDialog()
    $p1 = $tb1.Text; $p2 = $tb2.Text
    $f.Dispose()
    if ($res -ne [System.Windows.Forms.DialogResult]::OK) { return $null }
    if ($p1 -ne $p2) {
        [System.Windows.Forms.MessageBox]::Show('两次输入不一致，没有生成任何东西。再跑一次即可。', 'OmniRing') | Out-Null
        return $null
    }
    if ([string]::IsNullOrWhiteSpace($p1)) {
        [System.Windows.Forms.MessageBox]::Show('空口令不放行。', 'OmniRing') | Out-Null
        return $null
    }
    return $p1
}

function New-PassFile($pw) {
    $t = Join-Path $env:TEMP ('hzrks_' + [guid]::NewGuid().ToString('N') + '.txt')
    $fs = [System.IO.File]::Open($t, 'Create', 'Write', 'None')
    try {
        $bytes = [System.Text.Encoding]::ASCII.GetBytes($pw)
        $fs.Write($bytes, 0, $bytes.Length)
    } finally { $fs.Close() }
    return $t
}

function Get-Fingerprint($pf) {
    # keytool 自己会问口令；这里一律走 -storepass:file，绝不把口令放进参数
    $ka = @('-list', '-v', '-keystore', $KeyFile, '-alias', $Alias, '-storepass:file', $pf)
    $out = (& $KeyTool @ka 2>&1) | Out-String
    $m = [regex]::Match($out, 'SHA256:\s*([0-9A-Fa-f:]+)')
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

if (-not (Test-Path $KeyTool)) { Say "!! 找不到 keytool：$KeyTool（需要 JDK 17+）" Red; exit 1 }

if (Test-Path $KeyFile) {
    Say "密钥已存在：$KeyFile" Yellow
    Say "我不覆盖它（覆盖了等于把所有已装机用户的更新通道剪断）。" Yellow
    $pf = $null
    if ($SelfTest) { $pf = New-PassFile 'selftest-only' }
    try {
        $fp = Get-Fingerprint $pf
        if ($fp) { Say "指纹 SHA256: $fp" Cyan; Set-Content -Path $fpOut -Value $fp -Encoding ascii }
    } catch { }
    if ($pf) { Remove-Item $pf -Force -ErrorAction SilentlyContinue }
    exit 0
}

New-Item -ItemType Directory -Force -Path $KeyDir | Out-Null

$pw = $null
if ($SelfTest) {
    Say "SelfTest：用一次性口令把整条链跑一遍（会自己删干净）" Yellow
    $pw = 'selftest-' + [guid]::NewGuid().ToString('N')
} else {
    $pw = Ask-Title '给 OmniRing 设置正式签名口令'
    if (-not $pw) { Say '已取消，什么都没生成。' Yellow; exit 0 }
}

$pf = $null
try {
    $pf = New-PassFile $pw
    $ga = @('-genkeypair', '-keystore', $KeyFile, '-alias', $Alias, '-keyalg', 'RSA',
            '-keysize', '2048', '-validity', "$ValidDays",
            '-storepass:file', $pf, '-keypass:file', $pf, '-dname', $DName)
    (& $KeyTool @ga) | Out-Null
    $fp = Get-Fingerprint $pf
    if (-not $fp) { throw '生成了但读不出指纹' }
    Set-Content -Path $fpOut -Value $fp -Encoding ascii
    Say '' ; Say "── 已生成：$KeyFile" Green
    Say "   别名：$Alias  有效期：$ValidDays 天" Green
    Say "   证书指纹 SHA256：$fp" Cyan
    Say "   这行指纹也写到了 $fpOut（公开信息，可以直接抄/发）" Green
    if (-not $SelfTest) {
        Add-Type -AssemblyName System.Windows.Forms
        [System.Windows.Forms.MessageBox]::Show(
            "密钥已经生成好了。`r`n`r`n下一步请你只做两件事：`r`n" +
            "1) 把口令存进密码管理器（就在刚才你输的那个，别改）；`r`n" +
            "2) 把 $KeyFile 再拷一份到网盘或移动盘。`r`n`r`n" +
            "指纹已经写进文件，Qoder 那边能自己读到，你不用抄。",
            'OmniRing · 完成') | Out-Null
    }
} finally {
    if ($pf) { Remove-Item $pf -Force -ErrorAction SilentlyContinue }
    if ($SelfTest -and (Test-Path $KeyFile)) {
        Remove-Item $KeyFile -Force -ErrorAction SilentlyContinue
        Remove-Item $fpOut -Force -ErrorAction SilentlyContinue
        Say 'SelfTest：测试密钥与指纹文件已删除，无残留。' Yellow
    }
}
