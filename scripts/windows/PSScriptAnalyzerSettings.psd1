# PSScriptAnalyzer settings for marvel.ps1 (used by CI). Write-Host is deliberate: this is an interactive console
# tool whose messages are for the person at the terminal, not output for a pipeline.
@{
    Severity     = @('Error', 'Warning')
    ExcludeRules = @('PSAvoidUsingWriteHost')
}
