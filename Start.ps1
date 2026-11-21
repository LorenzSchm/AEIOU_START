#requires -Version 7.1
[CmdletBinding(DefaultParameterSetName='Demo')]
param(
    [Parameter(Mandatory, ParameterSetName='Live')][switch]$Live,
    [Parameter(Mandatory, ParameterSetName='Live')][ValidateRange(0,[long]::MaxValue)][long]$ExpectedCursor,
    [Parameter(Mandatory, ParameterSetName='Check')][switch]$CheckDatabase,
    [ValidateRange(1,65535)][int]$Port
)
$ErrorActionPreference = 'Stop'
$demo = $PSCmdlet.ParameterSetName -eq 'Demo'
if (!$PSBoundParameters.ContainsKey('Port')) { $Port = if ($demo) { 18092 } else { 18091 } }

# Keep application settings and credentials in the child process.
function Invoke-Java([string[]]$Arguments, [hashtable]$Settings) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $java
    $info.WorkingDirectory = $PSScriptRoot
    $info.UseShellExecute = $false
    foreach ($argValue in $Arguments) { $info.ArgumentList.Add($argValue) }
    foreach ($name in @($info.Environment.Keys)) {
        if ($name -match '^(SPRING_|FLYWAY_|FORWARDER_|DATABASE_|JAVA_TOOL_OPTIONS$|_JAVA_OPTIONS$|JDK_JAVA_OPTIONS$)' -or
            ($demo -and $name -match '^(TEAM_API_KEY$|GATEWAY_|WEBHOOK_|OPENAI_|UI_TOKEN$)')) {
            [void]$info.Environment.Remove($name)
        }
    }
    foreach ($name in $Settings.Keys) { $info.Environment[$name] = $Settings[$name] }
    $child = [Diagnostics.Process]::Start($info)
    try {
        $child.WaitForExit()
        if ($child.ExitCode -ne 0) { throw "Java wurde mit Exitcode $($child.ExitCode) beendet." }
    } finally {
        if (!$child.HasExited) { $child.Kill(); $child.WaitForExit() }
        $child.Dispose()
    }
}

Push-Location -LiteralPath $PSScriptRoot
try {
    $java = if ($env:JAVA_HOME) {
        (Get-Command (Join-Path $env:JAVA_HOME 'bin/java') -CommandType Application).Source
    } else { (Get-Command java -CommandType Application).Source }

    $version = [xml](Get-Content -LiteralPath pom.xml -Raw)
    $jar = Join-Path $PSScriptRoot "target/forwarder-$($version.project.version).jar"
    $wrapper = if ($IsWindows) { '.\mvnw.cmd' } else { './mvnw' }
    & $wrapper -B -ntp '-Dmaven.test.skip=true' package
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $jar)) { throw 'Build fehlgeschlagen. Benötigt wird ein JDK ab Version 21.' }

    $settings = @{}
    if (!$demo) {
        $names = @('DATABASE_URL','DATABASE_USER','DATABASE_PASSWORD')
        if ($Live) { $names += @('TEAM_API_KEY','GATEWAY_MODEL','UI_TOKEN') }
        foreach ($name in $names) {
            $value = [Environment]::GetEnvironmentVariable($name)
            if ([string]::IsNullOrWhiteSpace($value)) {
                $value = if ($name -in 'DATABASE_PASSWORD','TEAM_API_KEY','UI_TOKEN') { Read-Host $name -MaskInput } else { Read-Host $name }
            }
            if ([string]::IsNullOrWhiteSpace($value)) { throw "Fehlende Einstellung: $name" }
            $settings[$name] = $value
        }
        if ($settings.DATABASE_URL -notmatch '^jdbc:postgresql://') { throw 'DATABASE_URL muss die übergebene PostgreSQL-Datenbank bezeichnen.' }
        $driverPath = Join-Path ([IO.Path]::GetTempPath()) ("aeiou-pg-" + [guid]::NewGuid() + ".jar")
        try {
            $archive = [IO.Compression.ZipFile]::OpenRead($jar)
            try {
                $driver = @($archive.Entries | Where-Object FullName -match '^BOOT-INF/lib/postgresql-[^/]+\.jar$')
                if ($driver.Count -ne 1) { throw 'PostgreSQL-Treiber fehlt im Build.' }
                [IO.Compression.ZipFileExtensions]::ExtractToFile($driver[0], $driverPath)
            } finally { $archive.Dispose() }
            $check = @('--class-path',$driverPath,'tools/StandPruefen.java')
            if ($Live) { $check += $ExpectedCursor.ToString() }
            Invoke-Java $check $settings
        } finally {
            if (Test-Path -LiteralPath $driverPath) { Remove-Item -LiteralPath $driverPath }
        }
        if ($CheckDatabase) { return }
    }
    $profile = if ($demo) { 'demo' } else { 'live' }
    $arguments = @('-jar',$jar,"--spring.profiles.active=$profile",'--server.address=127.0.0.1',"--server.port=$Port",'--forwarder.kit=start-kit')
    if ($demo) {
        $arguments += @('--spring.datasource.url=jdbc:h2:mem:aeiou_demo;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE',
            '--spring.datasource.username=sa','--spring.datasource.password=','--forwarder.ui-token=demo',
            '--forwarder.api-root=http://127.0.0.1:1')
    }
    Write-Host "$profile auf http://127.0.0.1:$Port/health (Strg+C zum Beenden)"
    Invoke-Java $arguments $settings
} finally { Pop-Location }
