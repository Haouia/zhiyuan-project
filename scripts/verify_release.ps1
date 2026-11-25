#Requires -Version 7.2
# 本地发布门禁：与 .github/workflows/ci.yml 的校验项保持一致。
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $engine = docker info --format '{{.OSType}}' 2>$null
    if ($LASTEXITCODE -ne 0 -or $engine -ne 'linux') { throw 'Docker Desktop 的 Linux 引擎未就绪' }

    function Invoke-Step([string] $name, [scriptblock] $action) {
        Write-Host "== $name ==" -ForegroundColor Cyan
        & $action
        if ($LASTEXITCODE -ne 0) { throw "$name 失败（exit $LASTEXITCODE）" }
    }

    Invoke-Step 'Compose 配置校验' { docker compose config --quiet }
    Invoke-Step 'Maven 构建与单测' { mvn -B -ntp verify }
    Invoke-Step '前端构建' { npm --prefix admin-web ci --include=dev; npm --prefix admin-web run build }
    Invoke-Step 'K8s 配置校验' { python scripts/validate_k8s.py }
    Invoke-Step '契约测试' { python scripts/contract_tests.py }
    Invoke-Step '阶段 1-4 回归' {
        python scripts/smoke.py
        if ($LASTEXITCODE -eq 0) { python scripts/smoke_phase2.py }
        if ($LASTEXITCODE -eq 0) { python scripts/smoke_phase3.py }
        if ($LASTEXITCODE -eq 0) { python scripts/smoke_phase4.py }
    }
    Invoke-Step '阶段 5 验收' { python scripts/smoke_phase5.py }
    Write-Host 'RELEASE GATE PASSED' -ForegroundColor Green
} finally {
    Pop-Location
}
