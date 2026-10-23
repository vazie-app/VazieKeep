#Requires -Version 7
param(
    [ValidateSet('core', 'desktop', 'extension', 'all')]
    [string[]] $Areas = @('all')
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot

function Invoke-Step([string] $Title, [scriptblock] $Block) {
    Write-Host "`n==> $Title" -ForegroundColor Cyan
    & $Block
    if ($LASTEXITCODE -ne 0) { throw "$Title failed with exit code $LASTEXITCODE" }
}

if ($Areas -contains 'all') { $Areas = @('core', 'desktop', 'extension') }

foreach ($area in $Areas) {
    switch ($area) {
        'core' {
            Push-Location "$Root/core"
            try {
                Invoke-Step 'core: fmt' { cargo fmt --all --check }
                Invoke-Step 'core: clippy' { cargo clippy --workspace --all-targets -- -D warnings }
                Invoke-Step 'core: test' { cargo test --workspace }
            } finally { Pop-Location }
        }
        'desktop' {
            Push-Location "$Root/apps/desktop"
            try { Invoke-Step 'desktop: build' { ./gradlew.bat --console=plain build } } finally { Pop-Location }
        }
        'extension' {
            Push-Location "$Root/core"
            try {
                Invoke-Step 'wasm: cargo build' { cargo build -p keep-core-wasm --release --target wasm32-unknown-unknown }
                Invoke-Step 'wasm: bindgen' {
                    wasm-bindgen --target web --out-dir keep-core-wasm/pkg target/wasm32-unknown-unknown/release/keep_core_wasm.wasm
                }
            } finally { Pop-Location }
            # The build needs the backend addresses, which are not in git. Without them this check builds with
            # placeholders: the result proves the build, it is never a package for a store.
            if (-not $env:VAZIE_ACCOUNT_ORIGIN) { $env:VAZIE_ACCOUNT_ORIGIN = 'https://account.example.invalid' }
            if (-not $env:VAZIE_KEEP_ORIGIN) { $env:VAZIE_KEEP_ORIGIN = 'https://keep.example.invalid' }
            if (-not $env:VAZIE_SITE_ORIGIN) { $env:VAZIE_SITE_ORIGIN = 'https://site.example.invalid' }
            Push-Location "$Root/apps/browser-extension"
            try {
                Invoke-Step 'extension: install' { pnpm install --frozen-lockfile }
                Invoke-Step 'extension: check' { pnpm run check }
                Invoke-Step 'extension: build' { pnpm run build }
            } finally { Pop-Location }
        }
    }
}
Write-Host "`nAll requested checks passed" -ForegroundColor Green
