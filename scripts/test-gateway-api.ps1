<#
.SYNOPSIS
    Live smoke tests for the Android SMS Gateway REST API.

.DESCRIPTION
    Validates every documented endpoint against a running gateway device.
    Real SMS/MMS sends are OPT-IN so the script is safe to run read-only.

    Reachability:
      - USB:  adb forward tcp:8080 tcp:8080   then use -HostIp 127.0.0.1
      - LAN:  use the phone's IP shown on the Gateway screen

.EXAMPLE
    .\scripts\test-gateway-api.ps1 -HostIp 127.0.0.1 -ApiKey "gw_abcd..."

.EXAMPLE
    .\scripts\test-gateway-api.ps1 -HostIp 192.168.1.20 -ApiKey "gw_..." -SendTestSms -To "+989121234567"
#>
[CmdletBinding()]
param(
    [string]$HostIp = "127.0.0.1",
    [int]$Port = 8080,
    [Parameter(Mandatory = $true)]
    [string]$ApiKey,
    [switch]$SendTestSms,
    [string]$To,
    [string]$TestImageUrl = "https://raw.githubusercontent.com/github/explore/main/github/github-icon.png"
)

$ErrorActionPreference = "Stop"
$Base = "http://${HostIp}:${Port}"
$script:Pass = 0
$script:Fail = 0

function Invoke-Api {
    param(
        [string]$Method = "GET",
        [string]$Path,
        [object]$Body,
        [string]$Key = $ApiKey
    )
    $headers = @{ "X-API-Key" = $Key }
    $splat = @{
        Method      = $Method
        Uri         = "$Base$Path"
        Headers     = $headers
        ContentType = "application/json; charset=utf-8"
        ErrorAction = "SilentlyContinue"
        UseBasicParsing = $true
    }
    if ($null -ne $Body) { $splat.Body = ($Body | ConvertTo-Json -Compress) }

    try {
        $resp = Invoke-WebRequest @splat
        [pscustomobject]@{
            Status = [int]$resp.StatusCode
            Body   = ($resp.Content | ConvertFrom-Json)
        }
    } catch {
        $r = $_.Exception.Response
        if ($null -ne $r) {
            $reader = New-Object System.IO.StreamReader($r.GetResponseStream())
            $content = $reader.ReadToEnd()
            $parsed = $null
            try { $parsed = $content | ConvertFrom-Json } catch { $parsed = $content }
            [pscustomobject]@{
                Status = [int]$r.StatusCode
                Body   = $parsed
            }
        } else { throw }
    }
}

function Assert-Test {
    param([string]$Name, [bool]$Condition, [string]$Detail = "")
    if ($Condition) {
        $script:Pass++
        Write-Host ("  PASS  {0} {1}" -f $Name, $Detail) -ForegroundColor Green
    } else {
        $script:Fail++
        Write-Host ("  FAIL  {0} {1}" -f $Name, $Detail) -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "SMS Gateway API smoke tests -> $Base" -ForegroundColor Cyan
Write-Host ""

# -- 1. Status probe --------------------------------------------------------
$r = Invoke-Api -Method GET -Path "/api/v1/status"
Assert-Test "GET  /api/v1/status returns 200" ($r.Status -eq 200)
if ($r.Status -eq 200) {
    Assert-Test "  status=online, has ip/port/battery" (
        $r.Body.status -eq "online" -and
        $null -ne $r.Body.ip -and [int]$r.Body.port -gt 0 -and $null -ne $r.Body.batteryLevel
    ) "(ip=$($r.Body.ip), battery=$($r.Body.batteryLevel))"
}

# -- 2. Auth enforcement ----------------------------------------------------
$r = Invoke-Api -Method GET -Path "/api/v1/sms/inbox" -Key "gw_wrong_key_deliberately"
Assert-Test "GET  /api/v1/sms/inbox wrong key -> 401" ($r.Status -eq 401) "(got $($r.Status))"

# NOTE: run the no-key case LAST; it counts toward the per-IP brute-force lockout.
$noKey = Invoke-Api -Method GET -Path "/api/v1/sms/inbox" -Key ""
Assert-Test "GET  /api/v1/sms/inbox no key -> 401" ($noKey.Status -eq 401) "(got $($noKey.Status))"

# -- 3. Inbox (read-only) ----------------------------------------------------
$r = Invoke-Api -Method GET -Path "/api/v1/sms/inbox"
Assert-Test "GET  /api/v1/sms/inbox valid key -> 200" ($r.Status -eq 200)
if ($r.Status -eq 200) {
    Assert-Test "  count<=50 and messages array present" (
        [int]$r.Body.count -le 50 -and $null -ne $r.Body.messages
    ) "(count=$($r.Body.count))"
}

# -- 4. Filtered query -------------------------------------------------------
$r = Invoke-Api -Method GET -Path "/api/v1/sms?limit=5&offset=0&type=received"
Assert-Test "GET  /api/v1/sms?limit=5&type=received -> 200" ($r.Status -eq 200)
if ($r.Status -eq 200) {
    $types = @($r.Body.messages | ForEach-Object { $_.type })
    Assert-Test "  all rows type=received, count<=5" (
        ($types -notcontains "sent") -and ([int]$r.Body.count -le 5)
    ) "(count=$($r.Body.count))"
}

$fromMs = [DateTimeOffset]::UtcNow.AddDays(-30).ToUnixTimeMilliseconds()
$toMs   = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$r = Invoke-Api -Method GET -Path "/api/v1/sms?from_date=$fromMs&to_date=$toMs"
Assert-Test "GET  /api/v1/sms epoch date-range -> 200" ($r.Status -eq 200)

$r = Invoke-Api -Method GET -Path "/api/v1/sms?from_date=2026-01-01&to_date=2026-12-31"
Assert-Test "GET  /api/v1/sms yyyy-MM-dd date-range -> 200" ($r.Status -eq 200)

# -- 5. Validation errors ----------------------------------------------------
$r = Invoke-Api -Method POST -Path "/api/v1/sms/send" -Body @{ phone = ""; message = "" }
Assert-Test "POST /api/v1/sms/send blank fields -> 400" ($r.Status -eq 400)

$r = Invoke-Api -Method POST -Path "/api/v1/mms/send" -Body @{ phone = "+989120000000"; imageUrl = "http://insecure.example/x.png" }
Assert-Test "POST /api/v1/mms/send http scheme -> 400" ($r.Status -eq 400)

$r = Invoke-Api -Method GET -Path "/api/v1/unknown"
Assert-Test "GET  /api/v1/unknown -> 404" ($r.Status -eq 404)

# -- 6. Optional live sends (destructive: costs a real SMS/MMS) --------------
if ($SendTestSms) {
    if (-not $To) { throw "-SendTestSms requires -To <number>" }
    Write-Host ""
    Write-Host "[destructive] sending real SMS to $To" -ForegroundColor Yellow

    $r = Invoke-Api -Method POST -Path "/api/v1/sms/send" `
         -Body @{ phone = $To; message = "Gateway API smoke test $(Get-Date -Format o)" }
    Assert-Test "POST /api/v1/sms/send live -> 200 status=success" (
        $r.Status -eq 200 -and $r.Body.status -eq "success"
    )

    $r = Invoke-Api -Method POST -Path "/api/v1/mms/send" `
         -Body @{ phone = $To; imageUrl = $TestImageUrl }
    Assert-Test "POST /api/v1/mms/send live https image -> 200" ($r.Status -eq 200) "(got $($r.Status))"
}

# -- Summary -----------------------------------------------------------------
Write-Host ""
Write-Host ("Results: {0} passed, {1} failed" -f $script:Pass, $script:Fail) -ForegroundColor $(if ($script:Fail -eq 0) { "Green" } else { "Red" })
exit $(if ($script:Fail -eq 0) { 0 } else { 1 })
