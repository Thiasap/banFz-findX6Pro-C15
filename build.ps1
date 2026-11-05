# 构建 AntiFraudBlock（Windows 版，等价于 build.sh）
# 用法:
#   .\build.ps1            -> out\AntiFraudBlock-2.2.apk（不入库）
#   .\build.ps1 release    -> release\AntiFraudBlock-2.2.apk（需 AFB_KS 正式密钥）
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $MyInvocation.MyCommand.Path)

$OutDir = if ($args.Count -ge 1) { $args[0] } else { 'out' }
$Ver = '2.4'
$SDK = if ($env:ANDROID_SDK) { $env:ANDROID_SDK } else { 'E:\Thiasap\AndroidSDK' }
$BT = Join-Path $SDK 'build-tools\36.0.0'
$AJAR = @('android-35', 'android-36', 'android-34') |
    ForEach-Object { Join-Path $SDK "platforms\$_\android.jar" } |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $AJAR) { throw 'no android.jar found under platforms/' }
$AAR = 'libs\libxposed-102.aar'
$XAPI = 'libs\aar\classes.jar'
$SAAR = 'libs\libxposed-service-102.aar'
$SXAPI = 'libs\aar-service\classes.jar'
$IAAR = 'libs\libxposed-interface-102.aar'
$IXAPI = 'libs\aar-interface\classes.jar'

New-Item -ItemType Directory -Force -Path libs, $OutDir | Out-Null

if (-not (Test-Path $XAPI)) {
    Write-Host '[0/6] extract libxposed api 102'
    if (-not (Test-Path $AAR)) {
        curl.exe -sSL -o $AAR 'https://repo1.maven.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar'
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path $AAR), (Join-Path (Resolve-Path libs) 'aar'))
}

if (-not (Test-Path $IXAPI)) {
    Write-Host '[0/6] extract libxposed interface 102'
    if (-not (Test-Path $IAAR)) {
        curl.exe -sSL -o $IAAR 'https://repo1.maven.org/maven2/io/github/libxposed/interface/102.0.0/interface-102.0.0.aar'
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path $IAAR), (Join-Path (Resolve-Path libs) 'aar-interface'))
}

if (-not (Test-Path $SXAPI)) {
    Write-Host '[0/6] extract libxposed service 102'
    if (-not (Test-Path $SAAR)) {
        curl.exe -sSL -o $SAAR 'https://repo1.maven.org/maven2/io/github/libxposed/service/102.0.0/service-102.0.0.aar'
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path $SAAR), (Join-Path (Resolve-Path libs) 'aar-service'))
}

Remove-Item -Recurse -Force build2 -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path build2\classes | Out-Null

Write-Host '[1/6] javac'
$srcs = Get-ChildItem module\src -Recurse -Filter *.java | ForEach-Object { $_.FullName.Replace('\', '/') }
$cp = "$AJAR;$XAPI;$SXAPI;$IXAPI".Replace('\', '/')
$rsp = @('-encoding', 'UTF-8', '--release', '11', '-classpath', $cp, '-d', 'build2/classes') + $srcs
[IO.File]::WriteAllLines((Join-Path (Get-Location) 'build2\javac.rsp'), $rsp, (New-Object System.Text.UTF8Encoding($false)))
cmd /c "javac @build2\javac.rsp 2>&1"
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '[2/6] d8'
$classes = Get-ChildItem build2\classes -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& (Join-Path $BT 'd8.bat') --lib $AJAR --classpath $XAPI --min-api 33 --output build2 @classes $SXAPI $IXAPI
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '[3/6] aapt2'
& (Join-Path $BT 'aapt2.exe') compile --dir module\res -o build2\res.zip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
& (Join-Path $BT 'aapt2.exe') link -o build2\base.apk -I $AJAR --manifest module\AndroidManifest.xml `
    -R build2\res.zip --min-sdk-version 33 --target-sdk-version 34 --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '[4/6] package'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open((Resolve-Path build2\base.apk), 'Update')
try {
    foreach ($entry in @('classes.dex', 'META-INF\xposed\module.prop', 'META-INF\xposed\scope.list', 'META-INF\xposed\java_init.list')) {
        if ($entry -eq 'classes.dex') { $src = Resolve-Path build2\classes.dex }
        else { $src = Resolve-Path (Join-Path 'module' $entry) }
        $name = $entry -replace '\\', '/'
        $old = $zip.GetEntry($name); if ($old) { $old.Delete() }
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $src, $name) | Out-Null
    }
} finally { $zip.Dispose() }

Copy-Item build2\base.apk build2\unsigned.apk -Force

Write-Host '[5/6] zipalign'
& (Join-Path $BT 'zipalign.exe') -f -p 4 build2\unsigned.apk build2\aligned.apk
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '[6/6] sign'
$KS = if ($env:AFB_KS) { $env:AFB_KS } else { Join-Path $SDK '.android\debug.keystore' }
$KSPASS = if ($env:AFB_KS_PASS) { $env:AFB_KS_PASS } else { 'android' }
$KSALIAS = if ($env:AFB_KS_ALIAS) { $env:AFB_KS_ALIAS } else { 'androiddebugkey' }
$KSKEYPASS = if ($env:AFB_KS_KEYPASS) { $env:AFB_KS_KEYPASS } else { 'android' }

if ($OutDir -eq 'release' -and -not $env:AFB_KS) {
    Write-Warning '发布构建没指定 AFB_KS，将用 debug 证书覆盖 release/ 里的正式包；确认请设 AFB_KS_ALLOW_DEBUG=1'
    if ($env:AFB_KS_ALLOW_DEBUG -ne '1') { exit 1 }
}

$apk = Join-Path $OutDir "AntiFraudBlock-$Ver.apk"
Remove-Item $apk -ErrorAction SilentlyContinue
& (Join-Path $BT 'apksigner.bat') sign --ks $KS --ks-pass "pass:$KSPASS" --key-pass "pass:$KSKEYPASS" `
    --ks-key-alias $KSALIAS --out $apk build2\aligned.apk
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

Get-Item $apk | Format-List FullName, Length
Get-FileHash $apk -Algorithm MD5 | Format-List
& (Join-Path $BT 'apksigner.bat') verify --print-certs $apk | Select-Object -First 3
Write-Host "OK -> $apk"
