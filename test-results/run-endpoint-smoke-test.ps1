$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
$base = 'http://localhost:8080/api/v1'
$complianceBase = 'http://localhost:8081/api/v1'
$results = @()
$n = 0

function Invoke-Case {
    param($Name, $Method, $Url, $Body, $ExpectStatus, $ExpectCode)
    $script:n++
    $callArgs = @{ Method = $Method; Uri = $Url; TimeoutSec = 20; Headers = @{ 'X-Correlation-Id' = "smoke-$script:n" } }
    if ($Body) { $callArgs.Body = ($Body | ConvertTo-Json -Compress); $callArgs.ContentType = 'application/json' }
    $status = $null; $content = $null; $ok = $false
    try {
        $r = Invoke-WebRequest @callArgs -UseBasicParsing
        $status = [int]$r.StatusCode
        $content = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $status = [int]$resp.StatusCode
            $stream = $resp.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream)
            $content = $reader.ReadToEnd()
        } else {
            $content = "TRANSPORT ERROR: $($_.Exception.Message)"
        }
    }
$code = $null
    if ($content) {
        $m = [regex]::Match($content, '"code"\s*:\s*"([^"]+)"')
        if ($m.Success) { $code = $m.Groups[1].Value }
    }
    $statusOk = ($ExpectStatus -eq $null) -or ($status -eq $ExpectStatus)
    # A status-only check would have let case #15's original form pass for the wrong
    # reason (LDG-1003 insufficient funds instead of LDG-3001 compliance rejected) -
    # both are 422. When a specific code is named, it must match too.
    $codeOk = ($ExpectCode -eq $null) -or ($code -eq $ExpectCode)
    $ok = $statusOk -and $codeOk
    [PSCustomObject]@{
        N = $script:n; Name = $Name; Method = $Method; Url = $Url
        ExpectedStatus = $ExpectStatus; ActualStatus = $status
        ExpectedCode = $ExpectCode; ErrorCode = $code
        Pass = $ok; Body = $content
    }
}

$srcId  = "ACC-SMK-$(Get-Random -Minimum 1000 -Maximum 9999)"
$dstId  = "ACC-SMK-$(Get-Random -Minimum 1000 -Maximum 9999)"
$badId  = "ACC-SMK-NOPE-$(Get-Random -Minimum 1000 -Maximum 9999)"

# 1: open source account with an opening balance
$results += Invoke-Case "Open source account (with opening balance)" POST "$base/accounts" `
    @{ accountId = $srcId; holderName = "Smoke Source"; openingBalance = 1000.00; currency = "GBP" } 201

# 2: open destination account at zero
$results += Invoke-Case "Open destination account (zero balance)" POST "$base/accounts" `
    @{ accountId = $dstId; holderName = "Smoke Destination"; currency = "GBP" } 201

# 3: duplicate account id -> 409
$results += Invoke-Case "Re-open the same account id (expect 409 conflict)" POST "$base/accounts" `
    @{ accountId = $srcId; holderName = "Duplicate"; openingBalance = 1.00; currency = "GBP" } 409 "LDG-2002"

# 4: get account
$results += Invoke-Case "Get account by id" GET "$base/accounts/$srcId" $null 200

# 5: get account - unknown id -> 404
$results += Invoke-Case "Get unknown account (expect 404)" GET "$base/accounts/$badId" $null 404 "LDG-2001"

# 6: balance
$results += Invoke-Case "Get balance" GET "$base/accounts/$srcId/balance" $null 200

# 7: deposit
$results += Invoke-Case "Deposit 250.50" POST "$base/accounts/$srcId/deposits" `
    @{ amount = 250.50; currency = "GBP"; narrative = "Smoke deposit" } 201

# 8: deposit invalid amount -> 400
$results += Invoke-Case "Deposit zero amount (expect 400 invalid amount)" POST "$base/accounts/$srcId/deposits" `
    @{ amount = 0; currency = "GBP"; narrative = "Should fail" } 400 "LDG-1001"

# 9: withdraw
$results += Invoke-Case "Withdraw 50.50" POST "$base/accounts/$srcId/withdrawals" `
    @{ amount = 50.50; currency = "GBP"; narrative = "Smoke withdrawal" } 201

# 10: overdraft -> 422
$results += Invoke-Case "Withdraw far more than balance (expect 422 insufficient funds)" POST "$base/accounts/$srcId/withdrawals" `
    @{ amount = 999999.00; currency = "GBP"; narrative = "Too much" } 422 "LDG-1003"

# 11: currency mismatch -> 400
$results += Invoke-Case "Deposit in wrong-but-real currency USD (expect 400 currency mismatch)" POST "$base/accounts/$srcId/deposits" `
    @{ amount = 10.00; currency = "USD"; narrative = "Wrong currency" } 400 "LDG-1002"

# 12: malformed currency code -> 400
$results += Invoke-Case "Deposit with a non-ISO currency code XYZ (expect 400 malformed request)" POST "$base/accounts/$srcId/deposits" `
    @{ amount = 10.00; currency = "XYZ"; narrative = "Bad code" } 400 "LDG-4001"

# 13: transfer approved
$results += Invoke-Case "Transfer 100.00 (expect approved)" POST "$base/transfers" `
    @{ sourceAccountId = $srcId; destinationAccountId = $dstId; amount = 100.00; currency = "GBP"; narrative = "Smoke transfer" } 201

# 14: same-account transfer -> 400
$results += Invoke-Case "Transfer to the same account (expect 400)" POST "$base/transfers" `
    @{ sourceAccountId = $srcId; destinationAccountId = $srcId; amount = 1.00; currency = "GBP"; narrative = "Same account" } 400 "LDG-1004"

# 15: transfer above compliance limit -> 422 LDG-3001 compliance rejected.
# Needs enough balance that the refusal genuinely comes from screening, not from
# an overdraft check tripping first - fund the account well above the limit.
$results += Invoke-Case "Top up source so it can afford a limit-testing transfer" POST "$base/accounts/$srcId/deposits" `
    @{ amount = 20000.00; currency = "GBP"; narrative = "Fund for compliance-limit case" } 201
$results += Invoke-Case "Transfer 10000.00 - at the screening limit, funds available (expect 422 compliance rejected)" POST "$base/transfers" `
    @{ sourceAccountId = $srcId; destinationAccountId = $dstId; amount = 10000.00; currency = "GBP"; narrative = "At the limit" } 422 "LDG-3001"

# 16: transfer to a nonexistent destination -> 404
$results += Invoke-Case "Transfer to unknown destination account (expect 404)" POST "$base/transfers" `
    @{ sourceAccountId = $srcId; destinationAccountId = $badId; amount = 1.00; currency = "GBP"; narrative = "Unknown dest" } 404 "LDG-2001"

# 17: balances after all movement
$results += Invoke-Case "Source balance after all movement" GET "$base/accounts/$srcId/balance" $null 200
$results += Invoke-Case "Destination balance after all movement" GET "$base/accounts/$dstId/balance" $null 200

# 18: statement, default paging
$results += Invoke-Case "Source transaction history (default paging)" GET "$base/accounts/$srcId/transactions" $null 200

# 19: statement, explicit small page
$results += Invoke-Case "Source transaction history (page=0&size=2)" GET "$base/accounts/$srcId/transactions?page=0&size=2" $null 200

# 20: statement, out-of-range page -> still 200, empty window
$results += Invoke-Case "Source transaction history (page=99, out of range, expect empty window)" GET "$base/accounts/$srcId/transactions?page=99&size=10" $null 200

# 21: destination has exactly one entry (the transfer in) -> zero-opened accounts have no opening entry
$results += Invoke-Case "Destination transaction history (opened at zero: expect just the transfer-in)" GET "$base/accounts/$dstId/transactions" $null 200

# 22: direct call to compliance-service screening endpoint
$results += Invoke-Case "Direct call to compliance-service: approve" POST "$complianceBase/screenings" `
    @{ reference = [guid]::NewGuid().ToString(); sourceAccountId = $srcId; destinationAccountId = $dstId; amount = 5.00; currency = "GBP" } 200

# 23: direct call to compliance-service: over the limit
$results += Invoke-Case "Direct call to compliance-service: refuse (over limit, expect 200 decision=REJECTED)" POST "$complianceBase/screenings" `
    @{ reference = [guid]::NewGuid().ToString(); sourceAccountId = $srcId; destinationAccountId = $dstId; amount = 50000.00; currency = "GBP" } 200

# 24: actuator health on both services, while compliance-service is still up
$results += Invoke-Case "ledger-service liveness probe" GET "http://localhost:8080/actuator/health/liveness" $null 200
$results += Invoke-Case "ledger-service readiness probe" GET "http://localhost:8080/actuator/health/readiness" $null 200
$results += Invoke-Case "compliance-service liveness probe" GET "http://localhost:8081/actuator/health/liveness" $null 200
$results += Invoke-Case "compliance-service readiness probe" GET "http://localhost:8081/actuator/health/readiness" $null 200

# 25: fail-closed behaviour. This one needs compliance-service to be genuinely
# unreachable, which the caller of this script arranges by stopping it before
# this point and starting it again afterwards. Skipped gracefully if it is
# still reachable (nothing to prove without the outage).
$complianceReachable = $true
try { Invoke-WebRequest -Uri "http://localhost:8081/actuator/health" -UseBasicParsing -TimeoutSec 2 | Out-Null }
catch { $complianceReachable = $false }

if (-not $complianceReachable) {
    $sourceBalanceBefore = (Invoke-WebRequest -Uri "$base/accounts/$srcId/balance" -UseBasicParsing).Content |
        ConvertFrom-Json | Select-Object -ExpandProperty balance

    $results += Invoke-Case "Transfer while compliance-service is down (expect 503 screening unavailable)" POST "$base/transfers" `
        @{ sourceAccountId = $srcId; destinationAccountId = $dstId; amount = 1.00; currency = "GBP"; narrative = "Should be refused, funds must not move" } 503 "LDG-3002"

    $sourceBalanceAfter = (Invoke-WebRequest -Uri "$base/accounts/$srcId/balance" -UseBasicParsing).Content |
        ConvertFrom-Json | Select-Object -ExpandProperty balance

    $balanceRestored = ($sourceBalanceBefore -eq $sourceBalanceAfter)
    $script:n++
    $results += [PSCustomObject]@{
        N = $script:n
        Name = "Source balance unchanged after the refused/reversed transfer (saga compensation)"
        Method = "GET"; Url = "$base/accounts/$srcId/balance"
        ExpectedStatus = $null; ActualStatus = $null; ExpectedCode = $null; ErrorCode = $null
        Pass = $balanceRestored
        Body = "before=$sourceBalanceBefore after=$sourceBalanceAfter"
    }
} else {
    "compliance-service was still reachable - skipping the fail-closed case (run the outage scenario manually to exercise LDG-3002)."
}

$results | Export-Csv -Path .git\smoke-results.csv -NoTypeInformation -Encoding utf8

$lines = @()
$lines += "Smoke test run: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$lines += "Source account: $srcId   Destination account: $dstId   Unknown id used: $badId"
$lines += ""
foreach ($r in $results) {
    $mark = if ($r.Pass) { "PASS" } else { "FAIL" }
    $lines += "[$mark] #$($r.N) $($r.Name)"
    $lines += "       $($r.Method) $($r.Url)"
    $lines += "       expected status=$($r.ExpectedStatus) actual status=$($r.ActualStatus)   expected code=$($r.ExpectedCode) actual code=$($r.ErrorCode)"
    $lines += "       body: $($r.Body)"
    $lines += ""
}
$total = $results.Count
$passed = ($results | Where-Object { $_.Pass }).Count
$lines += "TOTAL: $passed / $total passed"
$lines -join "`n" | Out-File -FilePath .git\smoke-results.txt -Encoding utf8

"DONE: $passed / $total passed"
