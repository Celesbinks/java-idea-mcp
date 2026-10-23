param(
    [string]$Url = 'http://127.0.0.1:64123/mcp',
    [string]$Token = $env:IDEA_MCP_TOKEN,
    [string]$Project,
    [string]$File,
    [int]$Line = 1,
    [int]$Column = 1
)
$ErrorActionPreference = 'Stop'
if (-not $Token) { throw 'Set IDEA_MCP_TOKEN to the copied token, without the Bearer prefix.' }
$endpoint = [Uri]$Url
if ($endpoint.Scheme -ne 'http' -or $endpoint.Host -notin @('127.0.0.1','localhost')) { throw 'Use a loopback HTTP endpoint.' }
$headers = @{ Authorization = "Bearer $Token"; Accept = 'application/json, text/event-stream'; 'MCP-Protocol-Version' = '2025-11-25' }
$script:nextId = 0
function Send-Rpc([string]$Method, [hashtable]$Params) {
    $script:nextId++
    $body = @{ jsonrpc = '2.0'; id = $script:nextId; method = $Method; params = $Params } | ConvertTo-Json -Depth 12 -Compress
    $response = Invoke-RestMethod -Uri $Url -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
    if ($response.PSObject.Properties.Name -contains 'error') { throw ($response.error | ConvertTo-Json -Compress) }
    return $response.result
}
$initial = Send-Rpc 'initialize' @{ protocolVersion = '2025-11-25'; capabilities = @{}; clientInfo = @{ name = 'idea-mcp-smoke'; version = '0.1.0' } }
if ($initial.protocolVersion -ne '2025-11-25') { throw 'Unexpected negotiated protocol version.' }
$notification = @{ jsonrpc = '2.0'; method = 'notifications/initialized' } | ConvertTo-Json -Compress
Invoke-WebRequest -UseBasicParsing -Uri $Url -Method Post -Headers $headers -ContentType 'application/json' -Body $notification | Out-Null
$catalog = Send-Rpc 'tools/list' @{}
Write-Output ('Available tools: ' + (($catalog.tools | ForEach-Object name) -join ', '))
$projects = Send-Rpc 'tools/call' @{ name = 'list_projects'; arguments = @{} }
if ($projects.isError) { throw ($projects.content | ConvertTo-Json -Compress) }
Write-Output ($projects.structuredContent | ConvertTo-Json -Depth 8)
if ($File) {
    foreach ($name in @('read_symbols', 'find_definition', 'find_references')) {
        $arguments = @{ file = $File }
        if ($Project) { $arguments.project = $Project }
        if ($name -ne 'read_symbols') { $arguments.line = $Line; $arguments.column = $Column }
        $result = Send-Rpc 'tools/call' @{ name = $name; arguments = $arguments }
        if ($result.isError) { throw "$name failed: $($result.content | ConvertTo-Json -Compress)" }
        Write-Output "$name passed"
        Write-Output ($result.structuredContent | ConvertTo-Json -Depth 12)
    }
}
Write-Output 'MCP smoke test passed.'
