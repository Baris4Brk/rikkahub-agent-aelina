[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("Probe", "Activate", "Restore")]
    [string]$Mode,
    [string]$Serial = "",
    [string]$SnapshotPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProxyPackage = "android.voiceinteraction.service"
$ProxyComponent =
    "$ProxyPackage/me.rerere.rikkahub.assistantproxy.ProxyVoiceInteractionService"
$RikkaComponent = "me.rerere.rikkahub/.assistant.RikkaVoiceInteractionService"
$MagicVoicePackage = "com.hihonor.magicvoice"
$MagicVoiceComponent =
    "$MagicVoicePackage/com.hihonor.magicvoice.voiceui.service.MagicVoiceInteractionService"
$MagicVoiceRecognizer =
    "$MagicVoicePackage/.voiceui.service.MagicVoiceRecognitionService"
$RecognitionServiceAction = "android.speech.RecognitionService"

function Invoke-Adb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $prefix = if ($Serial) { @("-s", $Serial) } else { @() }
    $output = & adb @prefix @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | ForEach-Object { $_.ToString() }) -join "`n"
    if ($exitCode -ne 0 -or $text -match "SecurityException|java\.lang\.Exception|^Error:") {
        throw "adb $($Arguments -join ' ') failed ($exitCode): $text"
    }
    return $text.Trim()
}

function Invoke-AdbShell {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    return Invoke-Adb -Arguments (@("shell") + $Arguments)
}

function Get-SecureSetting {
    param([Parameter(Mandatory = $true)][string]$Key)
    $value = Invoke-AdbShell -Arguments @("settings", "get", "--user", "0", "secure", $Key)
    if ($value -eq "null") { return "" }
    return $value
}

function Set-SecureSetting {
    param(
        [Parameter(Mandatory = $true)][string]$Key,
        [AllowEmptyString()][Parameter(Mandatory = $true)][string]$Value
    )
    if ($Value) {
        [void](Invoke-AdbShell -Arguments @(
            "settings", "put", "--user", "0", "secure", $Key, $Value
        ))
    } else {
        [void](Invoke-AdbShell -Arguments @(
            "settings", "delete", "--user", "0", "secure", $Key
        ))
    }
}

function Assert-SecureSetting {
    param(
        [Parameter(Mandatory = $true)][string]$Key,
        [AllowEmptyString()][Parameter(Mandatory = $true)][string]$ExpectedValue
    )
    $actual = Get-SecureSetting -Key $Key
    if ($actual -ne $ExpectedValue) {
        throw "$Key was '$actual', expected '$ExpectedValue'."
    }
}

function Get-RecognitionServiceCandidates {
    $output = Invoke-AdbShell -Arguments @(
        "cmd", "package", "query-services", "--brief", "--user", "0",
        "-a", $RecognitionServiceAction
    )
    return @(
        $output -split "`r?`n" |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ -match "^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$" }
    )
}

function Select-ActivationRecognitionService {
    param([AllowEmptyString()][Parameter(Mandatory = $true)][string]$Preferred)
    $candidates = @(Get-RecognitionServiceCandidates)
    $blockedPackages = @($MagicVoicePackage, "me.rerere.rikkahub", $ProxyPackage)
    $isUsable = {
        param([string]$Component)
        if (-not $Component -or $Component -notmatch "^[^/]+/.+$") { return $false }
        $packageName = $Component.Split("/")[0]
        return $packageName -notin $blockedPackages -and $Component -in $candidates
    }
    if (& $isUsable $Preferred) { return $Preferred }
    $fallback = $candidates | Where-Object { & $isUsable $_ } | Select-Object -First 1
    if (-not $fallback) {
        throw "No enabled real RecognitionService is available for Android user 0."
    }
    return $fallback
}

function Assert-RecognitionServiceResolvable {
    param([Parameter(Mandatory = $true)][string]$ExpectedComponent)
    if ($ExpectedComponent -notin @(Get-RecognitionServiceCandidates)) {
        throw "RecognitionService '$ExpectedComponent' is not enabled for Android user 0."
    }
}

function Get-VoiceSnapshot {
    $roleHolders = Invoke-AdbShell -Arguments @(
        "cmd", "role", "get-role-holders", "android.app.role.ASSISTANT", "--user", "0"
    )
    return [ordered]@{
        capturedAt = (Get-Date).ToString("o")
        device = Invoke-AdbShell -Arguments @("getprop", "ro.product.model")
        assistant = Get-SecureSetting -Key "assistant"
        voiceInteractionService = Get-SecureSetting -Key "voice_interaction_service"
        voiceRecognitionService = Get-SecureSetting -Key "voice_recognition_service"
        assistantRoleHolders = @($roleHolders -split "`r?`n" | Where-Object { $_ })
    }
}

function Save-VoiceSnapshot {
    param([Parameter(Mandatory = $true)]$Snapshot)
    $path = $SnapshotPath
    if (-not $path) {
        $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
        $path = Join-Path $PSScriptRoot "..\..\rikkahub-agent-backups\voice-baseline-$stamp.json"
    }
    $parent = Split-Path -Parent $path
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        [void](New-Item -ItemType Directory -Path $parent)
    }
    $Snapshot | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $path -Encoding utf8
    return (Resolve-Path -LiteralPath $path).Path
}

function Assert-ActiveVoiceService {
    param([Parameter(Mandatory = $true)][string]$ExpectedComponent)
    $actual = ""
    $dump = ""
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        $actual = Get-SecureSetting -Key "voice_interaction_service"
        $dump = Invoke-AdbShell -Arguments @("dumpsys", "voiceinteraction")
        $serviceSectionMatch = [regex]::Match(
            $dump,
            "(?ms)^\s*mComponent=(?<component>[^\r\n]+)\s*$.*?(?=^\s*Active session:|\z)"
        )
        $component = if ($serviceSectionMatch.Success) {
            $serviceSectionMatch.Groups["component"].Value.Trim()
        } else {
            ""
        }
        $bound = $serviceSectionMatch.Success -and
            $serviceSectionMatch.Value -match "(?m)^\s*mBound=true(?:\s|$)"
        if ($actual -eq $ExpectedComponent -and
            $component -eq $ExpectedComponent -and
            $bound) {
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw "VoiceInteraction did not bind exact component $ExpectedComponent. " +
        "voice_interaction_service='$actual'. dumpsys:`n$dump"
}

function Restore-VoiceSnapshot {
    param(
        [Parameter(Mandatory = $true)]$Snapshot,
        [switch]$EnableMagicVoice,
        [AllowEmptyString()][string]$AssistantOverride = ""
    )
    if ($EnableMagicVoice) {
        [void](Invoke-AdbShell -Arguments @(
            "pm", "default-state", "--user", "0", $MagicVoicePackage
        ))
        Start-Sleep -Milliseconds 500
        $enabledPackages = Invoke-AdbShell -Arguments @(
            "pm", "list", "packages", "-e", "--user", "0", $MagicVoicePackage
        )
        if ($enabledPackages -notmatch [regex]::Escape("package:$MagicVoicePackage")) {
            throw "MagicVoice was not enabled for Android user 0."
        }
    }
    $assistantValue = if ($AssistantOverride) { $AssistantOverride } else { $Snapshot.assistant }
    Set-SecureSetting -Key "voice_recognition_service" -Value $Snapshot.voiceRecognitionService
    Set-SecureSetting -Key "assistant" -Value $assistantValue
    Set-SecureSetting -Key "voice_interaction_service" -Value $Snapshot.voiceInteractionService
    Start-Sleep -Milliseconds 2500
    Assert-ActiveVoiceService -ExpectedComponent $Snapshot.voiceInteractionService
    Assert-SecureSetting -Key "assistant" -ExpectedValue $assistantValue
    Assert-SecureSetting `
        -Key "voice_recognition_service" `
        -ExpectedValue $Snapshot.voiceRecognitionService
    Assert-RecognitionServiceResolvable -ExpectedComponent $Snapshot.voiceRecognitionService
}

[void](Invoke-Adb -Arguments @("get-state"))

if ($Mode -eq "Restore") {
    if (-not $SnapshotPath) {
        throw "Restore requires -SnapshotPath from a successful Probe or Activate run."
    }
    if (-not (Test-Path -LiteralPath $SnapshotPath)) {
        throw "Restore snapshot does not exist: $SnapshotPath"
    }
    $snapshot = Get-Content -Raw -LiteralPath $SnapshotPath | ConvertFrom-Json
    if ($snapshot.voiceInteractionService -ne $MagicVoiceComponent) {
        throw "Restore snapshot is not a MagicVoice baseline: $($snapshot.voiceInteractionService)"
    }
    Restore-VoiceSnapshot `
        -Snapshot $snapshot `
        -EnableMagicVoice `
        -AssistantOverride $MagicVoiceComponent
    Write-Output "RESTORED: $MagicVoiceComponent"
    exit 0
}

$baseline = Get-VoiceSnapshot
if ($Mode -eq "Activate" -and
    $baseline.voiceInteractionService -ne $MagicVoiceComponent) {
    throw "Activate requires a MagicVoice baseline for rollback. Current service: " +
        $baseline.voiceInteractionService
}
$activationRecognizer = if ($Mode -eq "Activate") {
    Select-ActivationRecognitionService -Preferred $baseline.voiceRecognitionService
} else {
    ""
}
$savedSnapshotPath = Save-VoiceSnapshot -Snapshot $baseline
$succeeded = $false
$magicVoiceDisabled = $false
try {
    $proxyPath = Invoke-AdbShell -Arguments @("pm", "path", "--user", "0", $ProxyPackage)
    if ($proxyPath -notmatch "^package:") {
        throw "The whitelist proxy package is not installed for user 0."
    }

    Set-SecureSetting -Key "voice_interaction_service" -Value $ProxyComponent
    Start-Sleep -Milliseconds 2500
    Assert-ActiveVoiceService -ExpectedComponent $ProxyComponent

    if ($Mode -eq "Probe") {
        Write-Output "PROBE_OK: $ProxyComponent"
        $succeeded = $true
    } else {
        $disableResult = Invoke-AdbShell -Arguments @(
            "pm", "disable-user", "--user", "0", $MagicVoicePackage
        )
        if ($disableResult -notmatch "disabled-user|new state: disabled") {
            throw "MagicVoice was not disabled for user 0: $disableResult"
        }
        $magicVoiceDisabled = $true

        Set-SecureSetting -Key "assistant" -Value $RikkaComponent
        Set-SecureSetting -Key "voice_interaction_service" -Value $RikkaComponent
        Start-Sleep -Milliseconds 2500
        Assert-ActiveVoiceService -ExpectedComponent $RikkaComponent
        Set-SecureSetting -Key "voice_recognition_service" -Value $activationRecognizer
        Assert-SecureSetting -Key "voice_recognition_service" -ExpectedValue $activationRecognizer
        Assert-RecognitionServiceResolvable -ExpectedComponent $activationRecognizer
        Write-Output "ACTIVATED: $RikkaComponent"
        Write-Output "VOICE_RECOGNIZER: $activationRecognizer"
        Write-Output "ROLLBACK_SNAPSHOT: $savedSnapshotPath"
        $succeeded = $true
    }
} finally {
    if ($Mode -eq "Probe" -or -not $succeeded) {
        Restore-VoiceSnapshot `
            -Snapshot ([pscustomobject]$baseline) `
            -EnableMagicVoice:$magicVoiceDisabled
        if ($Mode -eq "Probe" -and $succeeded) {
            Write-Output "PROBE_ROLLBACK_OK: $($baseline.voiceInteractionService)"
        }
    }
}
