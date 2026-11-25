#Requires -Version 7.2
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $engine = docker info --format '{{.OSType}}' 2>$null
    if ($LASTEXITCODE -ne 0 -or $engine -ne 'linux') {
        throw 'Docker Desktop 的 Linux 引擎未就绪，请启动后重试'
    }

    if (!(Test-Path -LiteralPath '.env')) {
        docker compose config --quiet 2>$null
        if ($LASTEXITCODE -ne 0) {
            $layout = docker compose config --no-interpolate --format json
            if ($LASTEXITCODE -ne 0) { throw '无法读取 Compose 配置' }
            $volumeName = ($layout | ConvertFrom-Json).volumes.'mysql-data'.name
            if (!$volumeName) { throw 'Compose 配置缺少 mysql-data 数据卷' }

            $volumes = docker volume ls --quiet --filter "name=$volumeName"
            if ($LASTEXITCODE -ne 0) { throw '无法确认 MySQL 数据卷状态' }
            if ($volumes -contains $volumeName) {
                throw "已存在 MySQL 数据卷 $volumeName。请恢复与该卷对应的 .env 后重试"
            }

            $keys = 'RAG_DB_ROOT_PASSWORD', 'RAG_DOC_DB_PASSWORD', 'RAG_SEARCH_DB_PASSWORD',
                'RAG_GATEWAY_DOC_TOKEN', 'RAG_SEARCH_DOC_TOKEN', 'RAG_GATEWAY_AI_TOKEN',
                'RAG_AI_SEARCH_TOKEN', 'RAG_AI_DOC_TOKEN', 'RAG_METRIC_TOKEN', 'RAG_NACOS_SERVER_ID',
                'RAG_NACOS_SIGNING_KEY', 'RAG_KEYCLOAK_ADMIN_PASSWORD'
            $settings = foreach ($key in $keys) {
                $bytes = [byte[]]::new(32)
                [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
                if ($key -eq 'RAG_NACOS_SIGNING_KEY') {
                    "$key=$([Convert]::ToBase64String($bytes))"
                } else {
                    "$key=$([Convert]::ToHexString($bytes).ToLowerInvariant())"
                }
            }
            $settings += 'RAG_KEYCLOAK_ADMIN_USER=rag-admin'
            New-Item -Path '.env' -ItemType File -Value ($settings -join "`n") | Out-Null
        }
    }

    if (Test-Path -LiteralPath '.env') {
        foreach ($key in 'RAG_SEARCH_DOC_TOKEN', 'RAG_GATEWAY_AI_TOKEN', 'RAG_AI_SEARCH_TOKEN', 'RAG_AI_DOC_TOKEN', 'RAG_KEYCLOAK_ADMIN_PASSWORD') {
            if (![Environment]::GetEnvironmentVariable($key) -and
                    !(Select-String -LiteralPath '.env' -Pattern "^\s*$key\s*=" -Quiet)) {
                $bytes = [byte[]]::new(32)
                [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
                Add-Content -LiteralPath '.env' -Value @('', "$key=$([Convert]::ToHexString($bytes).ToLowerInvariant())")
            }
        }
        if (![Environment]::GetEnvironmentVariable('RAG_KEYCLOAK_ADMIN_USER') -and
                !(Select-String -LiteralPath '.env' -Pattern '^\s*RAG_KEYCLOAK_ADMIN_USER\s*=' -Quiet)) {
            Add-Content -LiteralPath '.env' -Value @('', 'RAG_KEYCLOAK_ADMIN_USER=rag-admin')
        }
    }

    $config = docker compose config --format json
    if ($LASTEXITCODE -ne 0) { throw 'Compose 配置无效，请检查环境变量' }
    $gateway = ($config | ConvertFrom-Json).services.gateway.environment
    $localIssuer = $gateway.RAG_OIDC_ISSUER -ceq 'http://127.0.0.1:8180/realms/rag-local'
    $localJwks = $gateway.RAG_OIDC_JWKS_URI -ceq 'http://keycloak:8080/realms/rag-local/protocol/openid-connect/certs'
    if ($localIssuer -xor $localJwks) {
        throw 'OIDC 的 RAG_OIDC_ISSUER 与 RAG_OIDC_JWKS_URI 必须同时指向本地 Keycloak 或同时使用企业 IdP 配置'
    }

    npm --prefix admin-web ci --include=dev
    if ($LASTEXITCODE -ne 0) { throw '管理员网页依赖安装失败' }
    npm --prefix admin-web run build
    if ($LASTEXITCODE -ne 0) { throw '管理员网页构建失败' }
    mvn -B -ntp verify
    if ($LASTEXITCODE -ne 0) { throw 'Maven 构建失败' }

    docker compose up -d --build --wait --wait-timeout 240
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose 启动失败' }
} finally {
    Pop-Location
}
