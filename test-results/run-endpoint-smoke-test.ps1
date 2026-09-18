# Manual, black-box endpoint smoke test for ledger-service.
# Run with the service already started (see README "Running it").
# Usage: powershell -ExecutionPolicy Bypass -File test-results\run-endpoint-smoke-test.ps1

$ProgressPreference = 'SilentlyContinue'
$ErrorActionPreference = 'Stop'

$base = "http://localhost:8080/api/v1"
$results = New-Object System.Collections.Generic.List[object]

function Check($name, $ok, $detail) {
    $results.Add([pscustomobject]@{ Case = $name; Result = if ($ok) {"PASS"} else {"FAIL"}; Detail = $detail })
}

function AsText($content) {
    if ($content -is [byte[]]) { return [System.Text.Encoding]::UTF8.GetString($content) }
    return $content
}

function Post($path, $body) {
    try {
        $r = Invoke-WebRequest -Uri "$base$path" -Method POST -ContentType "application/json" -Body $body -UseBasicParsing
        return @{ Status = $r.StatusCode; Body = AsText $r.Content }
    } catch {
        $resp = $_.Exception.Response
        $sc = [int]$resp.StatusCode
        $stream = $resp.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        $content = $reader.ReadToEnd()
        return @{ Status = $sc; Body = $content }
    }
}

function Get($path) {
    try {
        $r = Invoke-WebRequest -Uri "$base$path" -Method GET -UseBasicParsing
        return @{ Status = $r.StatusCode; Body = AsText $r.Content }
    } catch {
        $resp = $_.Exception.Response
        $sc = [int]$resp.StatusCode
        $stream = $resp.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        $content = $reader.ReadToEnd()
        return @{ Status = $sc; Body = $content }
    }
}

# Fresh, timestamp-suffixed account ids, so this run cannot collide with accounts left over
# by an earlier run against the same long-lived in-memory database.
$suffix = Get-Date -Format "HHmmss"
$src = "ACC-5${suffix}"
$dst = "ACC-6${suffix}"

# 1. Open source account with an opening balance
$r = Post "/accounts" "{`"accountId`":`"$src`",`"holderName`":`"Ada Lovelace`",`"openingBalance`":500.00,`"currency`":`"GBP`"}"
Check "01 Open $src with 500.00 opening balance" ($r.Status -eq 201) $r.Body

# 2. Open destination account with no opening balance (defaults to zero)
$r = Post "/accounts" "{`"accountId`":`"$dst`",`"holderName`":`"Grace Hopper`"}"
Check "02 Open $dst with no opening balance" ($r.Status -eq 201) $r.Body

# 3. Duplicate account id is rejected
$r = Post "/accounts" "{`"accountId`":`"$src`",`"holderName`":`"Someone Else`",`"openingBalance`":10.00}"
Check "03 Duplicate account id -> 409 LDG-2002" (($r.Status -eq 409) -and ($r.Body -match "LDG-2002")) $r.Body

# 4. Get account by id
$r = Get "/accounts/$src"
Check "04 Get $src" (($r.Status -eq 200) -and ($r.Body -match "500")) $r.Body

# 5. Get balance
$r = Get "/accounts/$src/balance"
Check "05 Balance of $src = 500.00" (($r.Status -eq 200) -and ($r.Body -match "500")) $r.Body

# 6. Unknown account -> 404
$r = Get "/accounts/ACC-9999"
Check "06 Unknown account -> 404 LDG-2001" (($r.Status -eq 404) -and ($r.Body -match "LDG-2001")) $r.Body

# 7. Deposit
$r = Post "/accounts/$src/deposits" '{"amount":250.50,"currency":"GBP","narrative":"Salary"}'
Check "07 Deposit 250.50 into $src" (($r.Status -eq 201) -and ($r.Body -match "DEPOSIT")) $r.Body

# 8. Zero-amount deposit rejected
$r = Post "/accounts/$src/deposits" '{"amount":0,"currency":"GBP"}'
Check "08 Zero deposit -> 400 LDG-1001" (($r.Status -eq 400) -and ($r.Body -match "LDG-1001")) $r.Body

# 9. Withdrawal
$r = Post "/accounts/$src/withdrawals" '{"amount":100.00,"currency":"GBP","narrative":"Cash machine"}'
Check "09 Withdraw 100.00 from $src" (($r.Status -eq 201) -and ($r.Body -match "WITHDRAWAL")) $r.Body

# 10. Overdraft refused
$r = Post "/accounts/$src/withdrawals" '{"amount":999999.00,"currency":"GBP"}'
Check "10 Overdraft refused -> 422 LDG-1003" (($r.Status -eq 422) -and ($r.Body -match "LDG-1003")) $r.Body

# 11. Wrong-but-real currency rejected by domain
$r = Post "/accounts/$src/withdrawals" '{"amount":10.00,"currency":"USD"}'
Check "11 Wrong real currency -> 400 LDG-1002" (($r.Status -eq 400) -and ($r.Body -match "LDG-1002")) $r.Body

# 12. Unrecognised currency code rejected at the edge
$r = Post "/accounts/$src/withdrawals" '{"amount":10.00,"currency":"ZZZ"}'
Check "12 Unknown currency code -> 400 LDG-4001" (($r.Status -eq 400) -and ($r.Body -match "LDG-4001")) $r.Body

# 13. Transfer, approved end to end
$r = Post "/transfers" "{`"sourceAccountId`":`"$src`",`"destinationAccountId`":`"$dst`",`"amount`":100.00,`"currency`":`"GBP`",`"narrative`":`"Rent`"}"
Check "13 Transfer 100.00 $src -> $dst" (($r.Status -eq 201) -and ($r.Body -match "TRANSFER_OUT") -and ($r.Body -match "TRANSFER_IN")) $r.Body

# 14. Same-account transfer rejected
$r = Post "/transfers" "{`"sourceAccountId`":`"$src`",`"destinationAccountId`":`"$src`",`"amount`":10.00}"
Check "14 Same-account transfer -> 400 LDG-1004" (($r.Status -eq 400) -and ($r.Body -match "LDG-1004")) $r.Body

# 15. Transfer to unknown destination
$r = Post "/transfers" "{`"sourceAccountId`":`"$src`",`"destinationAccountId`":`"ACC-9999`",`"amount`":10.00}"
Check "15 Transfer to unknown destination -> 404 LDG-2001" (($r.Status -eq 404) -and ($r.Body -match "LDG-2001")) $r.Body

# 16. Balances after the transfer settle correctly
# 500.00 opening + 250.50 deposit - 100.00 withdrawal - 100.00 transfer = 550.50
$rSource = Get "/accounts/$src/balance"
$rDest = Get "/accounts/$dst/balance"
Check "16 Balances reconcile after transfer" (($rSource.Body -match "550.50") -and ($rDest.Body -match "100")) "source=$($rSource.Body) dest=$($rDest.Body)"

# 17. Statement, default paging
$r = Get "/accounts/$src/transactions"
Check "17 Statement default paging" (($r.Status -eq 200) -and ($r.Body -match "Opening balance")) $r.Body

# 18. Statement, explicit small page
$r = Get "/accounts/$src/transactions?page=0&size=2"
Check "18 Statement explicit small page" ($r.Status -eq 200) $r.Body

# 19. Statement, out-of-range page -> empty window, not an error
$r = Get "/accounts/$src/transactions?page=40&size=10"
Check "19 Out-of-range page -> empty window" (($r.Status -eq 200) -and ($r.Body -match '"transactions":\[\]')) $r.Body

# 20. Account opened at zero shows only the transfer-in entry, no opening entry
$r = Get "/accounts/$dst/transactions"
Check "20 $dst statement shows only the transfer-in, no opening entry" (($r.Status -eq 200) -and ($r.Body -match '"totalTransactions":1') -and ($r.Body -match "TRANSFER_IN")) $r.Body

# 21. Health endpoints
# Actuator responds with a vendor content type PowerShell 5.1 does not recognise as text, so
# Content comes back as a byte[] rather than a string unless decoded explicitly.
function GetText($uri) {
    $r = Invoke-WebRequest -Uri $uri -UseBasicParsing
    $text = if ($r.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($r.Content) } else { $r.Content }
    return @{ Status = $r.StatusCode; Body = $text }
}

$r = GetText "http://localhost:8080/actuator/health/liveness"
Check "21 Liveness probe UP" (($r.Status -eq 200) -and ($r.Body -match "UP")) $r.Body

$r = GetText "http://localhost:8080/actuator/health/readiness"
Check "22 Readiness probe UP" (($r.Status -eq 200) -and ($r.Body -match "UP")) $r.Body

# --- Output ---
$passed = ($results | Where-Object { $_.Result -eq "PASS" }).Count
$total = $results.Count

$lines = New-Object System.Collections.Generic.List[string]
foreach ($row in $results) {
    $lines.Add("[$($row.Result)] $($row.Case)")
    $lines.Add("       $($row.Detail)")
}
$lines.Add("")
$lines.Add("$passed/$total passed")

$lines | Write-Output

$results | Export-Csv -Path "test-results\endpoint-smoke-test-normal.csv" -NoTypeInformation
$lines | Set-Content -Path "test-results\endpoint-smoke-test-normal.txt"
