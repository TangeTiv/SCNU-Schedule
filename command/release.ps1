<#
.SYNOPSIS
    师陶学程 (SCNU-Schedule) 一键发版脚本

.DESCRIPTION
    按 command/RELEASE_PROMPT.md 定义的流程，自动完成一次发版：
      第0步 前置校验（分支 / 版本一致性 / 工作区）
      第1步 升 versionCode(+1) 与 versionName 并提交
      第2步 打 tag 并推送 tag
      第3步 打包 :app:assembleProdRelease
      第4步 计算 APK 的 SHA-256
      第5步 创建 Release 并上传 APK（Gitee 用 curl.exe + PAT；GitHub 用已登录的 gh）
      第6步 生成 update.json（含 checksums）并提交
      第7步 推送 main
      第8步 验证线上清单 + 下载回来自校验哈希

    兼容 Windows PowerShell 5.1：Gitee API 走系统自带 curl.exe（PS5.1 的
    Invoke-RestMethod 没有 -Form，无法直接发 multipart）。

.PARAMETER Version
    新版本号，格式 X.Y.Z，例如 1.4.2

.PARAMETER Changelog
    更新说明（多行用反引号 n）。不传则用默认一句。

.PARAMETER GiteePat
    Gitee 私人令牌（建 Release / 传附件用）。也可用环境变量 GITEE_PAT。

.PARAMETER PackageMode
    universal（默认）上传单个 SCNU-Schedule.apk；
    abi 上传 3 个按 ABI 的包（arm64-v8a / armeabi-v7a / x86_64）。

.EXAMPLE
    .\release.ps1 -Version 1.4.2 -Changelog "🚀 v1.4.2 更新`n- 修复若干问题" -GiteePat "xxxx"

.EXAMPLE
    .\release.ps1 -Version 1.4.2 -GiteePat "xxxx" -PackageMode abi

.EXAMPLE
    .\release.ps1 -Version 1.4.2 -GiteePat "xxxx" -SkipRelease -SkipPush
    只做本地部分（改文件 + 打包 + 提交），不建 Release、不推送
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Changelog = "",
    [string]$GiteePat = $env:GITEE_PAT,
    [string]$GiteeSlug = "TangeTiw/scnu-schedule",
    [string]$GithubSlug = "TangeTiv/SCNU-Schedule",
    [string]$GiteeRemote = "gitee",
    [string]$GithubRemote = "origin",
    [string]$MainBranch = "main",
    [ValidateSet("universal", "abi")][string]$PackageMode = "universal",
    [switch]$SkipBuild,
    [switch]$SkipRelease,
    [switch]$SkipPush,
    [switch]$SkipVerify
)

$ErrorActionPreference = "Stop"
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Info([string]$m) { Write-Host "[release] $m" -ForegroundColor Cyan }
function Warn([string]$m) { Write-Host "[release] $m" -ForegroundColor Yellow }
function Die([string]$m) { Write-Host "[release] $m" -ForegroundColor Red; exit 1 }

# 推送单个 ref，并以**退出码**判定成败。
#
# 不要写成 `git push ... 2>&1 | Select-String ...`：
# PS 5.1 会把原生命令往 stderr 写的任何内容包成 ErrorRecord，配合
# $ErrorActionPreference = "Stop" 直接抛错终止 —— 而 `git push` 的远程横幅
# （`remote: Powered by GITEE.COM ...`）正是写在 stderr 的，
# 于是「推送其实已经成功」也会把脚本打断。v1.5.0 / v1.6.0 都栽在这里。
#
# 这里临时把 EAP 降级、只用退出码判成败，是 RELEASE_PROMPT 坑 #13 里
# 标注「最可靠」的那种修法。
function PushRef([string]$remote, [string]$ref) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = & git push $remote $ref 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    $out | Select-String -Pattern 'error|fatal|rejected|up-to-date|new tag|->' | ForEach-Object { Write-Host "        $_" }
    if ($code -ne 0) { Die "推送 $ref 到 $remote 失败（exit $code）" }
}

# ==================== 第 0 步：前置校验 ====================
if ($Version -notmatch '^\d+\.\d+\.\d+$') { Die "版本号格式应为 X.Y.Z（例如 1.4.2）" }
$tag = "v$Version"
$relTitle = "华师课表 $tag 发布"

$cur = (git rev-parse --abbrev-ref HEAD).Trim()
if ($cur -ne $MainBranch) { Die "当前分支是 '$cur'，发版必须在 '$MainBranch' 上进行" }
Info "分支 OK：$cur"

# 忽略仓库里长期存在的噪声（daemon.pid / .claude / command 等）
$noise = '\.codegraph/|\.claude/|command/'
$dirty = @(git status --porcelain) | Where-Object { $_ -notmatch $noise }
if ($dirty.Count -gt 0) {
    Warn "工作区存在其他未提交改动（脚本仍继续，只会提交 build.gradle.kts 与 update.json）："
    $dirty | ForEach-Object { Write-Host "        $_" }
}

$gradlePath = "app/build.gradle.kts"
$gtext = [System.IO.File]::ReadAllText($gradlePath, $utf8)
$mc = [regex]::Match($gtext, 'versionCode\s*=\s*(\d+)')
if (-not $mc.Success) { Die "在 $gradlePath 中未找到 versionCode" }
$oldCode = [int]$mc.Groups[1].Value
$newCode = $oldCode + 1
$oldName = ([regex]::Match($gtext, 'versionName\s*=\s*"([^"]*)"')).Groups[1].Value
Info "版本：$oldName (code $oldCode)  ->  $Version (code $newCode)"

if ([string]::IsNullOrWhiteSpace($Changelog)) {
    $Changelog = "🚀 $tag 更新"
    Warn "未提供 -Changelog，使用默认：$Changelog"
}

$apkDir = "app/build/outputs/apk/prod/release"
$abis = @("arm64-v8a", "armeabi-v7a", "x86_64")
$baseUrl = "https://gitee.com/$GiteeSlug/releases/download/$tag"

# ==================== 第 1 步：升版本号 ====================
$gtext = [regex]::Replace($gtext, 'versionCode\s*=\s*\d+', "versionCode = $newCode", 1)
$gtext = [regex]::Replace($gtext, 'versionName\s*=\s*"[^"]*"', "versionName = `"$Version`"", 1)
[System.IO.File]::WriteAllText($gradlePath, $gtext, $utf8)
git add $gradlePath
git commit -m "chore: bump version to $Version (versionCode $newCode)" | Out-Host
if ($LASTEXITCODE -ne 0) { Die "提交失败" }
Info "已提升版本号并提交"

# ==================== 第 2 步：打 tag 并推送 ====================
git tag $tag 2>$null
if (-not $SkipPush) {
    # 用显式 refs，避免与同名分支歧义（raw/<名字> 也会优先解析 tag）
    PushRef $GiteeRemote "refs/tags/$tag"
    PushRef $GithubRemote "refs/tags/$tag"
}
Info "tag $tag 就绪"

# ==================== 第 3 步：打包 ====================
if (-not $SkipBuild) {
    Info "打包 :app:assembleProdRelease ..."
    & .\gradlew.bat :app:assembleProdRelease --console=plain
    if ($LASTEXITCODE -ne 0) { Die "打包失败" }
} else { Warn "已跳过打包（-SkipBuild）" }

# ==================== 第 4 步：计算 SHA-256 ====================
$hashes = @{}
foreach ($abi in $abis) {
    $p = Join-Path $apkDir "app-prod-$abi-release.apk"
    if (Test-Path $p) {
        $hashes[$abi] = (Get-FileHash $p -Algorithm SHA256).Hash.ToLower()
        Info "$abi  SHA256 = $($hashes[$abi])"
    } else { Warn "缺少 $p" }
}
if ($hashes.Count -eq 0) { Die "没有任何 APK 产物" }

# 决定要上传的文件清单
$uploads = @()      # 绝对路径
if ($PackageMode -eq "universal") {
    $src = Join-Path $apkDir "app-prod-arm64-v8a-release.apk"
    if (-not (Test-Path $src)) { Die "universal 模式需要 arm64 包，但找不到：$src" }
    $dst = Join-Path $apkDir "SCNU-Schedule.apk"
    Copy-Item $src $dst -Force
    $uploads += (Resolve-Path $dst).Path
} else {
    foreach ($abi in $abis) {
        $p = Join-Path $apkDir "app-prod-$abi-release.apk"
        if (Test-Path $p) { $uploads += (Resolve-Path $p).Path }
    }
}

# ==================== 第 5 步：建 Release + 上传 ====================
if (-not $SkipRelease) {
    $nameFile = Join-Path $env:TEMP "_scnu_rel_name.txt"
    $bodyFile = Join-Path $env:TEMP "_scnu_rel_body.txt"
    [System.IO.File]::WriteAllText($nameFile, $relTitle, $utf8)
    [System.IO.File]::WriteAllText($bodyFile, $Changelog, $utf8)

    # ---- Gitee（curl.exe + PAT）----
    if ([string]::IsNullOrWhiteSpace($GiteePat)) {
        Warn "未提供 GiteePat（或环境变量 GITEE_PAT），跳过 Gitee Release"
    } else {
        Info "Gitee：创建 Release ..."
        $a1 = @('-s', '-X', 'POST', "https://gitee.com/api/v5/repos/$GiteeSlug/releases",
                '-F', "access_token=$GiteePat", '-F', "tag_name=$tag",
                '-F', "name=<$nameFile", '-F', "body=<$bodyFile", '-F', "target_commitish=$MainBranch")
        $resp = (& curl.exe @a1 | Out-String)
        $rel = $null
        try { $rel = $resp | ConvertFrom-Json } catch { }
        if (-not $rel -or -not $rel.id) {
            $safe = $resp -replace [regex]::Escape($GiteePat), '***'
            Warn "Gitee 建 Release 失败（可能已存在）：$safe"
        } else {
            Info "Gitee release id = $($rel.id)"
            foreach ($f in $uploads) {
                $a2 = @('-s', '-X', 'POST', "https://gitee.com/api/v5/repos/$GiteeSlug/releases/$($rel.id)/attach_files",
                        '-F', "access_token=$GiteePat", '-F', "file=@$f")
                $r2 = (& curl.exe @a2 | Out-String)
                $u = $null
                try { $u = $r2 | ConvertFrom-Json } catch { }
                if ($u -and $u.name) { Info "Gitee 已上传: $($u.name)  $($u.browser_download_url)" }
                else { Warn "Gitee 上传失败: $($r2 -replace [regex]::Escape($GiteePat), '***')" }
            }
        }
    }

    # ---- GitHub（gh CLI，无需 token）----
    if (Get-Command gh -ErrorAction SilentlyContinue) {
        Info "GitHub：创建 Release + 上传（gh）..."
        $ghArgs = @('release', 'create', $tag) + $uploads + @('--repo', $GithubSlug, '--title', $relTitle, '--notes-file', $bodyFile, '--latest')
        & gh @ghArgs 2>&1 | ForEach-Object { Write-Host "        $_" }
    } else {
        Warn "未找到 gh CLI，跳过 GitHub Release"
    }

    Remove-Item $nameFile, $bodyFile -Force -ErrorAction SilentlyContinue
} else { Warn "已跳过建 Release（-SkipRelease）" }

# ==================== 第 6 步：生成 update.json ====================
# changelog 里的真实换行要转成 JSON 的 \n
$cl = $Changelog.Replace('\', '\\').Replace('"', '\"').Replace("`r`n", '\n').Replace("`n", '\n')

if ($PackageMode -eq "universal") {
    $uniSha = (Get-FileHash (Join-Path $apkDir "SCNU-Schedule.apk") -Algorithm SHA256).Hash.ToLower()
    $prodLinksBlock = '      "universal": "{0}/SCNU-Schedule.apk"' -f $baseUrl
    $prodSumsBlock  = '      "universal": "{0}"' -f $uniSha
    $devSumsBlock   = '      "universal": ""'
} else {
    $prodLinksBlock = ($abis | ForEach-Object { '      "{0}": "{1}/app-prod-{0}-release.apk"' -f $_, $baseUrl }) -join ",`n"
    $prodSumsBlock  = ($abis | ForEach-Object {
        $h = ""
        if ($hashes.ContainsKey($_)) { $h = $hashes[$_] }
        '      "{0}": "{1}"' -f $_, $h
    }) -join ",`n"
    $devSumsBlock   = ($abis | ForEach-Object { '      "{0}": ""' -f $_ }) -join ",`n"
}

# dev 段暂与 prod 用同一份下载链接
$devLinksBlock = $prodLinksBlock

# 用单引号 here-string 做模板 + 占位符替换，避免大量反引号转义
$jsonTemplate = @'
{
  "prod": {
    "latestVersionCode": __CODE__,
    "latestVersionName": "__VERSION__",
    "changelog": "__CHANGELOG__",
    "downloadLinks": {
__PROD_LINKS__
    },
    "checksums": {
__PROD_SUMS__
    }
  },
  "dev": {
    "latestVersionCode": __CODE__,
    "latestVersionName": "__VERSION__-dev",
    "changelog": "__CHANGELOG__",
    "downloadLinks": {
__DEV_LINKS__
    },
    "checksums": {
__DEV_SUMS__
    }
  }
}
'@

$json = $jsonTemplate.Replace('__CODE__', "$newCode").Replace('__VERSION__', $Version).Replace('__CHANGELOG__', $cl).Replace('__PROD_LINKS__', $prodLinksBlock).Replace('__DEV_LINKS__', $devLinksBlock).Replace('__PROD_SUMS__', $prodSumsBlock).Replace('__DEV_SUMS__', $devSumsBlock)

# 落盘前先校验 JSON 合法
$null = $json | ConvertFrom-Json
[System.IO.File]::WriteAllText("update.json", $json, $utf8)
git add update.json
git commit -m "chore: release $tag manifest" | Out-Host
Info "update.json 已生成并提交"

# ==================== 第 7 步：推 main ====================
if (-not $SkipPush) {
    PushRef $GiteeRemote $MainBranch
    PushRef $GithubRemote $MainBranch
    Info "main 已推送（App 从 Gitee $MainBranch 读清单）"
} else { Warn "已跳过推送（-SkipPush）" }

# ==================== 第 8 步：验证 ====================
if (-not $SkipVerify) {
    if ($SkipPush) { Warn "未推送，跳过线上验证" }
    else {
        Info "等待 65 秒（Gitee raw 缓存 max-age=60）..."
        Start-Sleep -Seconds 65
        $manifestUrl = "https://gitee.com/$GiteeSlug/raw/$MainBranch/update.json"
        try {
            $r = Invoke-WebRequest $manifestUrl -UseBasicParsing -TimeoutSec 30
            $o = $r.Content | ConvertFrom-Json
            Info "线上清单：code=$($o.prod.latestVersionCode)  name=$($o.prod.latestVersionName)"
            Info "          link=$($o.prod.downloadLinks.universal)$($o.prod.downloadLinks.'arm64-v8a')"

            # 下载回来自校验
            $dl = $o.prod.downloadLinks.universal
            if (-not $dl) { $dl = $o.prod.downloadLinks.'arm64-v8a' }
            $tmp = Join-Path $env:TEMP "_scnu_verify.apk"
            Invoke-WebRequest $dl -OutFile $tmp -TimeoutSec 300
            $actual = (Get-FileHash $tmp -Algorithm SHA256).Hash.ToLower()
            Remove-Item $tmp -Force -ErrorAction SilentlyContinue
            $expect = $o.prod.checksums.universal
            if (-not $expect) { $expect = $o.prod.checksums.'arm64-v8a' }
            if ($actual -eq $expect) { Info "✅ 哈希一致，发布完成" }
            else { Warn "❌ 哈希不一致！实际=$actual 清单=$expect" }
        } catch {
            Warn "验证失败：$($_.Exception.Message)"
        }
    }
}

Write-Host ""
Info "发版流程结束。"
