# Bootstrap the OCC EC2 CD deployment from a workstation with AWS and GitHub
# access. The tool never prints, logs or forwards secret values: the imported
# environment file is read locally, written to a temporary JSON payload and
# passed to the AWS CLI by file reference only.
#
# Usage (review change set only; nothing is applied without -ApplyChangeSet):
#   pwsh scripts/cd/bootstrap.ps1
#   pwsh scripts/cd/bootstrap.ps1 -Profile occ-admin -StackName occ-ec2-cd -Region us-east-1
#   pwsh scripts/cd/bootstrap.ps1 -ApplyChangeSet
#
# Requirements: AWS CLI v2, gh CLI authenticated for pslimmm/OCC, and the
# existing root .env with real settings. The local .env is never modified.

[CmdletBinding()]
param(
    [string]$Profile = "",
    [string]$StackName = "occ-ec2-cd",
    [ValidateSet("us-east-1")]
    [string]$Region = "us-east-1",
    [string]$Repository = "pslimmm/OCC",
    [ValidateSet("t3.small")]
    [string]$InstanceType = "t3.small",
    [string]$EnvFile = ".env",
    [switch]$ApplyChangeSet
)

$ErrorActionPreference = "Stop"

function Invoke-Aws {
    param([Parameter(Mandatory)][object[]]$Arguments)
    # Flatten nested arrays so repeated parameter groups reach the CLI as
    # separate argv entries instead of one space-joined string.
    $flat = @($Arguments | ForEach-Object { $_ })
    if ($Profile) {
        & aws @flat --profile $Profile --region $Region
    }
    else {
        & aws @flat --region $Region
    }
    if ($LASTEXITCODE -ne 0) {
        throw "aws $($flat -join ' ') failed with exit code $LASTEXITCODE"
    }
}

function Test-Stack {
    param([Parameter(Mandatory)][string]$Name)
    # list-stacks succeeds whether or not the name exists, so no expected
    # "not found" stderr can become a PowerShell NativeCommandError.
    $arguments = @(
        "cloudformation", "list-stacks",
        "--query", "StackSummaries[?StackName=='$Name' && StackStatus!='DELETE_COMPLETE'].StackName",
        "--output", "text"
    )
    if ($Profile) { $arguments += @("--profile", $Profile) }
    $arguments += @("--region", $Region)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    try {
        $result = & aws @arguments 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($exitCode -ne 0) {
        throw "aws cloudformation list-stacks failed with exit code $exitCode"
    }
    $text = (($result | ForEach-Object { "$_" }) -join "").Trim()
    return ($text -ne "" -and $text -ne "None")
}

# Verify both identities before any mutation so a missing session fails fast
# and clearly instead of half-applying.
if (-not (Get-Command aws -ErrorAction SilentlyContinue)) {
    throw "The AWS CLI is not installed or not on PATH."
}
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw "The gh CLI is not installed or not on PATH."
}
Write-Output "Verifying AWS identity..."
$identity = Invoke-Aws @("sts", "get-caller-identity")
Write-Output "Verifying GitHub authentication..."
& gh auth status
if ($LASTEXITCODE -ne 0) {
    throw "gh is not authenticated; run 'gh auth login' first."
}
Write-Output "Verifying repository access..."
& gh repo view $Repository --json name | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "gh cannot read $Repository; check the authenticated account."
}

$repoRoot = Split-Path -Parent $PSScriptRoot | Split-Path -Parent
$envPath = Join-Path $repoRoot $EnvFile
if (-not (Test-Path $envPath)) {
    throw "Environment file not found: $envPath"
}

# Read the environment file by name only. Values stay in memory and are
# written straight to the temporary payload; nothing is echoed.
$envNames = @{}
$requiredNames = @(
    "POSTGRES_PASSWORD", "POSTGRES_DB", "POSTGRES_USER",
    "SUPABASE_PROJECT_URL", "SUPABASE_JWKS_URL",
    "DATAMALL_API_KEY",
    "OCC_INGESTION_DB_PASSWORD", "OCC_DISRUPTION_DB_PASSWORD", "OCC_PLANNING_DB_PASSWORD",
    "OCC_INGESTION_DB_MIGRATOR_PASSWORD", "OCC_DISRUPTION_DB_MIGRATOR_PASSWORD",
    "OCC_PLANNING_DB_MIGRATOR_PASSWORD",
    "OCC_INGESTION_TO_DISRUPTION_PEER_TOKEN", "OCC_INGESTION_TO_PLANNING_PEER_TOKEN",
    "OCC_DISRUPTION_TO_INGESTION_PEER_TOKEN", "OCC_DISRUPTION_TO_PLANNING_PEER_TOKEN",
    "OCC_PLANNING_TO_INGESTION_PEER_TOKEN", "OCC_PLANNING_TO_DISRUPTION_PEER_TOKEN",
    "OCC_INGESTION_TO_DISRUPTION_PEER_TOKEN", "PGADMIN_DEFAULT_PASSWORD"
)
$placeholderPattern = "^(change-?me|placeholder|synthetic|example|xxx+|<.*>)$"
foreach ($line in Get-Content $envPath) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or $trimmed.StartsWith("#")) { continue }
    $separator = $trimmed.IndexOf("=")
    if ($separator -lt 1) { continue }
    $name = $trimmed.Substring(0, $separator).Trim()
    $value = $trimmed.Substring($separator + 1).Trim()
    $envNames[$name] = $value
}
$missing = $requiredNames | Where-Object { -not $envNames.ContainsKey($_) }
if ($missing) {
    throw "Environment file is missing required settings (names only): $($missing -join ', ')"
}
$placeholders = $envNames.Keys | Where-Object { $envNames[$_] -match $placeholderPattern }
if ($placeholders) {
    Write-Output "WARNING: these settings look like placeholders (names only): $($placeholders -join ', ')"
}

# Obsolete overrides that silently fight the tuned defaults: report names
# only and require an explicit migration decision.
$obsoletePatterns = @(
    "^OCC_[A-Z]+_JAVA_TOOL_OPTIONS$",
    "^OCC_[A-Z]+_MEMORY_LIMIT$",
    "GATEWAY",
    "^OCC_[A-Z]+_DB_INTERNAL_URL$"
)
$obsolete = $envNames.Keys | Where-Object {
    $name = $_
    ($obsoletePatterns | Where-Object { $name -match $_ }).Count -gt 0
}
if ($obsolete) {
    Write-Output "WARNING: obsolete gateway/JVM-flag overrides detected (names only; they will NOT be imported because they override optimized defaults): $($obsolete -join ', ')"
}

# Image references are release-managed: the pipeline pins them from the
# verified manifest. Placeholder values in .env must never be imported into
# the deployment secret, where the shell would outrank the manifest pins.
$releaseManaged = $envNames.Keys | Where-Object { $_ -match "^OCC_[A-Z]+_IMAGE$" }
if ($releaseManaged) {
    Write-Output "WARNING: release-managed image settings are ignored (the pipeline pins them): $($releaseManaged -join ', ')"
}

# Resolve the reused OIDC provider and the AWS-managed CloudFront prefix list.
Write-Output "Resolving the existing GitHub OIDC provider..."
$providers = Invoke-Aws @("iam", "list-open-id-connect-providers") | ConvertFrom-Json
$oidcArn = $null
foreach ($provider in $providers.OpenIDConnectProviderList) {
    $detail = Invoke-Aws @("iam", "get-open-id-connect-provider", "--open-id-connect-provider-arn", $provider.Arn) | ConvertFrom-Json
    if ($detail.Url -eq "token.actions.githubusercontent.com") {
        $oidcArn = $provider.Arn
        break
    }
}
if (-not $oidcArn) {
    throw "No GitHub Actions OIDC provider exists in this account; create it once outside this stack and rerun."
}
Write-Output "Resolving the CloudFront origin-facing managed prefix list..."
$prefixLists = Invoke-Aws @("ec2", "describe-managed-prefix-lists", "--filters", "Name=prefix-list-name,Values=com.amazonaws.global.cloudfront.origin-facing") | ConvertFrom-Json
$prefixListId = $prefixLists.PrefixLists | Select-Object -First 1 -ExpandProperty PrefixListId
if (-not $prefixListId) {
    throw "The com.amazonaws.global.cloudfront.origin-facing managed prefix list was not found in $Region."
}

# Outbound destinations are an explicit, fail-closed allow-list. The list is
# created once with the deployment's exact required ranges (see
# docs/DEPLOYMENT.md); bootstrap refuses to build a change set without it.
Write-Output "Resolving the deployment egress allow-list..."
$egressLists = Invoke-Aws @("ec2", "describe-managed-prefix-lists", "--filters", "Name=prefix-list-name,Values=occ-ec2-cd-egress") | ConvertFrom-Json
$egressPrefixListId = $egressLists.PrefixLists | Select-Object -First 1 -ExpandProperty PrefixListId
if (-not $egressPrefixListId) {
    throw "The customer-managed prefix list occ-ec2-cd-egress was not found in $Region; create it with the required outbound ranges (see docs/DEPLOYMENT.md) and rerun."
}
# Refuse an empty list or one that reintroduces unrestricted egress through the
# back door: the prefix list, not the template, carries the destinations.
$egressEntries = @()
$nextToken = $null
do {
    $entryArguments = @("ec2", "get-managed-prefix-list-entries", "--prefix-list-id", $egressPrefixListId)
    if ($nextToken) { $entryArguments += @("--next-token", $nextToken) }
    $page = Invoke-Aws $entryArguments | ConvertFrom-Json
    $egressEntries += $page.Entries
    $nextToken = $page.NextToken
} while ($nextToken)
if (-not $egressEntries) {
    throw "The occ-ec2-cd-egress prefix list has no entries; add the deployment's required outbound ranges and rerun."
}
$unrestricted = $egressEntries | Where-Object { $_.Cidr -in @("0.0.0.0/0", "::/0") }
if ($unrestricted) {
    throw "The occ-ec2-cd-egress prefix list contains an unrestricted entry; remove it and list only required destinations."
}

# Create (or reuse) the change set. Nothing is applied without -ApplyChangeSet.
$parameters = @(
    "ParameterKey=RepositoryName,ParameterValue=$Repository",
    "ParameterKey=InstanceType,ParameterValue=$InstanceType",
    "ParameterKey=OidcProviderArn,ParameterValue=$oidcArn",
    "ParameterKey=CloudFrontOriginPrefixListId,ParameterValue=$prefixListId",
    "ParameterKey=EgressAllowedPrefixListId,ParameterValue=$egressPrefixListId"
)
Write-Output "Creating change set for stack $StackName..."
$changeSet = "occ-bootstrap-" + (Get-Date -Format "yyyyMMddHHmmss")
$stackExists = Test-Stack $StackName
$changeSetType = if ($stackExists) { "UPDATE" } else { "CREATE" }
$templatePath = Join-Path $repoRoot "infra/aws/ec2-cd.yaml"
$templateUri = "file://" + ($templatePath -replace '\\', '/')
$changeSetArguments = @(
    "cloudformation", "create-change-set", "--stack-name", $StackName,
    "--change-set-name", $changeSet, "--change-set-type", $changeSetType,
    "--template-body", $templateUri,
    "--capabilities", "CAPABILITY_NAMED_IAM", "--parameters"
) + $parameters
Invoke-Aws $changeSetArguments
Write-Output "Change set $changeSet ($changeSetType) created. Review it with:"
Write-Output "  aws cloudformation describe-change-set --stack-name $StackName --change-set-name $changeSet"
if (-not $ApplyChangeSet) {
    Write-Output "Apply it with:  pwsh scripts/cd/bootstrap.ps1 -ApplyChangeSet"
    exit 0
}
Invoke-Aws @("cloudformation", "execute-change-set", "--stack-name", $StackName, "--change-set-name", $changeSet)
Write-Output "Executing change set $changeSet; waiting for the stack..."
if ($stackExists) {
    Invoke-Aws @("cloudformation", "wait", "stack-update-complete")
}
else {
    Invoke-Aws @("cloudformation", "wait", "stack-create-complete")
}

$stack = Invoke-Aws @("cloudformation", "describe-stacks", "--stack-name", $StackName) | ConvertFrom-Json
$outputs = @{}
foreach ($output in $stack.Stacks[0].Outputs) {
    $outputs[$output.OutputKey] = $output.OutputValue
}

# Import the deployment settings. The payload travels by file reference, the
# local .env stays untouched, and only the secret version is recorded.
Write-Output "Importing deployment settings into Secrets Manager (values are never displayed)..."
$deployment = [ordered]@{}
foreach ($name in $envNames.Keys) {
    if ($obsolete -contains $name -or $releaseManaged -contains $name) { continue }
    $deployment[$name] = $envNames[$name]
}
$deployment["OCC_ORIGIN_PUBLIC_URL"] = $outputs["CloudFrontUrl"]
$deployment["OCC_NGINX_ORIGIN_PORT"] = "8080"
$deployment["OCC_COMPOSE_PROJECT"] = "occ-production"
$payloadPath = Join-Path ([System.IO.Path]::GetTempPath()) ("occ-secret-" + [guid]::NewGuid().ToString("N") + ".json")
$payloadUri = "file://" + ($payloadPath -replace '\\', '/')
try {
    $payload = @{ SecretId = $outputs["DeploymentSecretName"]; SecretString = ($deployment | ConvertTo-Json -Depth 4) } |
        ConvertTo-Json -Depth 4
    [System.IO.File]::WriteAllText($payloadPath, $payload, (New-Object System.Text.UTF8Encoding($false)))
    Invoke-Aws @("secretsmanager", "put-secret-value", "--cli-input-json", $payloadUri) | Out-Null
}
finally {
    Remove-Item -Force $payloadPath -ErrorAction SilentlyContinue
}
$secretVersion = Invoke-Aws @("secretsmanager", "describe-secret", "--secret-id", $outputs["DeploymentSecretName"]) | ConvertFrom-Json
Write-Output "Deployment secret version recorded: $($secretVersion.VersionIdsToStages.Keys -join ', ')"

# Configure the main-only production environment without per-push reviewers,
# then scope the role ARNs to it as environment secrets.
Write-Output "Configuring the GitHub production environment..."
& gh api "repos/$Repository/environments/production" -X PUT `
    --input - -H "Content-Type: application/json" `
    -d '{"deployment_branch_policy":{"protected_branches":true,"custom_branch_policies":false}}'
if ($LASTEXITCODE -ne 0) {
    throw "Failed to configure the GitHub production environment."
}
& gh secret set OCC_PUBLISH_ROLE_ARN --body $outputs["PublishRoleArn"] --repo $Repository --env production
& gh secret set OCC_DEPLOY_ROLE_ARN --body $outputs["DeployRoleArn"] --repo $Repository --env production
& gh secret set OCC_RELEASE_BUCKET --body $outputs["ReleaseBucketName"] --repo $Repository --env production
& gh secret set OCC_REGION --body $Region --repo $Repository --env production
& gh secret set OCC_INSTANCE_ID --body $outputs["InstanceId"] --repo $Repository --env production
foreach ($component in @("ingestion", "disruption", "planning", "db-setup", "web")) {
    $key = "OCC_" + ($component.ToUpper() -replace "-", "_") + "_REPOSITORY"
    # CloudFormation outputs are PascalCase without the hyphen (DbSetupRepositoryUri).
    $camel = ($component -split '-' | ForEach-Object { $_.Substring(0, 1).ToUpper() + $_.Substring(1) }) -join ''
    $outputKey = $camel + "RepositoryUri"
    if (-not $outputs.ContainsKey($outputKey)) {
        throw "The stack has no $outputKey output; cannot configure $key."
    }
    & gh secret set $key --body $outputs[$outputKey] --repo $Repository --env production
}
& gh variable set OCC_GITHUB_REPOSITORY --body $Repository --repo $Repository --env production

Write-Output ""
Write-Output "Bootstrap complete (change set applied)."
Write-Output "CloudFront URL: $($outputs['CloudFrontUrl'])"
Write-Output "Instance: $($outputs['InstanceId'])  Bucket: $($outputs['ReleaseBucketName'])"
Write-Output "Next: run scripts/cd/bootstrap-host.sh through SSM to prepare the host, then deploy the first release."
