# Build the SystemUIHook module APK without Gradle.
#
# Requires only the Android SDK: aapt2, d8, zipalign, apksigner and a JDK
# (javac/keytool) on PATH. Works fully offline once the SDK build-tools are
# installed, which makes it a reliable fallback when the Gradle/AGP dependency
# download is unavailable.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File build_apk.ps1 [-Sdk C:\Android\Sdk]

param(
    [string]$Sdk = 'C:\Android\Sdk',
    [int]$MinSdkVersion = 21,
    [int]$TargetSdkVersion = 33,
    [string]$BuildToolsVersion = '33.0.1',
    [string]$PlatformVersion = 'android-33'
)

$ErrorActionPreference = 'Stop'

# d8.bat/zipalign.apksigner rely on JAVA_HOME. The environment variable on some
# machines is broken (quotes or stray whitespace), so derive it from javac.
function Resolve-JdkHome {
    param([string]$Configured)
    if ($Configured) {
        $trimmed = $Configured.Trim().Trim('"').TrimEnd('\')
        if (Test-Path -LiteralPath (Join-Path $trimmed 'bin\java.exe')) { return $trimmed }
    }
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if ($javac) {
        $candidate = Split-Path -Parent (Split-Path -Parent $javac.Source)
        if (Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe')) { return $candidate }
    }
    $fallbacks = @(
        'C:\Program Files\Android\Android Studio\jbr',
        'C:\Program Files\Java\latest',
        'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
    )
    foreach ($f in $fallbacks) {
        if (Test-Path -LiteralPath (Join-Path $f 'bin\java.exe')) { return $f }
    }
    throw 'No usable JDK found; please set JAVA_HOME to a JDK directory.'
}

$env:JAVA_HOME = Resolve-JdkHome -Configured $env:JAVA_HOME
Write-Host "Using JAVA_HOME = $env:JAVA_HOME"

$root     = Split-Path -Parent $MyInvocation.MyCommand.Path
$appDir   = Join-Path $root 'app'
$srcDir   = Join-Path $appDir 'src\main'
$javaDir  = Join-Path $srcDir 'java'
$resDir   = Join-Path $srcDir 'res'
$assetsDir= Join-Path $srcDir 'assets'
$manifest = Join-Path $srcDir 'AndroidManifest.xml'
$xposedJar= Join-Path $appDir 'libs\xposed-api.jar'
$stageDir = Join-Path $env:TEMP 'SystemUIHookBuild'

$btDir       = Join-Path $Sdk "build-tools\$BuildToolsVersion"
$aapt2       = Join-Path $btDir 'aapt2.exe'
$d8          = Join-Path $btDir 'd8.bat'
$zipalign    = Join-Path $btDir 'zipalign.exe'
$apksigner   = Join-Path $btDir 'apksigner.bat'
$androidJar  = Join-Path $Sdk "platforms\$PlatformVersion\android.jar"

foreach ($tool in @($aapt2, $d8, $zipalign, $apksigner, $androidJar, $xposedJar)) {
    if (-not (Test-Path -LiteralPath $tool)) {
        throw "Missing required file: $tool"
    }
}

$workDir   = Join-Path $stageDir 'out'
$classDir  = Join-Path $workDir 'classes'
$dexDir    = Join-Path $workDir 'dex'
$unsigned  = Join-Path $workDir 'unsigned.apk'
$aligned   = Join-Path $workDir 'aligned.apk'
$stagedApk = Join-Path $workDir 'SystemUIHook.apk'

$stageSrc     = Join-Path $stageDir 'src'
$stageJava    = Join-Path $stageSrc 'java'
$stageRes     = Join-Path $stageSrc 'res'
$stageAssets  = Join-Path $stageSrc 'assets'
$stageManifest= Join-Path $stageSrc 'AndroidManifest.xml'
$stageLibs    = Join-Path $stageDir 'libs'
$stageXposedJar = Join-Path $stageLibs 'xposed-api.jar'
$stageResFlat = Join-Path $stageDir 'compiled'

# aapt2 and zipalign are native binaries that mishandle non-ASCII command line
# arguments, so every path handed to them is kept ASCII-only by staging the
# sources into %TEMP% first.
Write-Host "[0/6] Staging sources into $stageDir ..."
if (Test-Path -LiteralPath $stageDir) { Remove-Item -Recurse -Force $stageDir }
New-Item -ItemType Directory -Force -Path $stageSrc, $stageLibs, $classDir, $dexDir | Out-Null
Copy-Item -Recurse -Force -LiteralPath $javaDir -Destination (Join-Path $stageSrc 'java')
if (Test-Path -LiteralPath $resDir) { Copy-Item -Recurse -Force -LiteralPath $resDir -Destination (Join-Path $stageSrc 'res') }
if (Test-Path -LiteralPath $assetsDir) { Copy-Item -Recurse -Force -LiteralPath $assetsDir -Destination $stageAssets }
Copy-Item -Force -LiteralPath $manifest -Destination $stageManifest
Copy-Item -Force -LiteralPath $xposedJar -Destination $stageXposedJar

Write-Host '[1/6] Compiling Android resources with aapt2 ...'
$resArgs = @()
if (Test-Path -LiteralPath $stageRes) {
    New-Item -ItemType Directory -Force -Path $stageResFlat | Out-Null
    & $aapt2 compile --dir $stageRes -o $stageResFlat
    if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
    # aapt2 compile 输出 .flat 文件到目录，link 需要把每个 .flat 作为独立参数传入
    $flatFiles = Get-ChildItem -LiteralPath $stageResFlat -Filter *.flat | ForEach-Object { $_.FullName }
    $resArgs = $flatFiles
}
# link early so aapt2 --java generates R.java into $stageJava before javac runs
& $aapt2 link -o $unsigned -I $androidJar --manifest $stageManifest -A $stageAssets `
    --min-sdk-version $MinSdkVersion --target-sdk-version $TargetSdkVersion `
    --version-code 1 --version-name '1.0' --java $stageJava @($resArgs)
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '[2/6] Compiling Java sources with javac (includes generated R.java) ...'
$sources = Get-ChildItem -LiteralPath $stageJava -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$classpath = "$androidJar;$stageXposedJar"
& javac -nowarn -encoding UTF-8 -source 8 -target 8 -cp $classpath -d $classDir $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '[3/6] Converting class files to dex with d8 ...'
# Do not use d8.bat: it passes -Djava.ext.dirs, which JDK 9+ removed. Invoking
# com.android.tools.r8.D8 from lib\d8.jar works on JDK 8 through 21.
$javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
$d8Jar = Join-Path $btDir 'lib\d8.jar'
if (-not (Test-Path -LiteralPath $d8Jar)) { throw "Missing $d8Jar" }
$classFiles = Get-ChildItem -LiteralPath $classDir -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $javaExe -Xmx1024M -cp $d8Jar com.android.tools.r8.D8 `
    --min-api $MinSdkVersion --lib $androidJar --output $dexDir @($classFiles)
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '[4/6] Adding classes.dex to the APK ...'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$dexFile = Join-Path $dexDir 'classes.dex'
if (-not (Test-Path -LiteralPath $dexFile)) { throw "d8 did not produce $dexFile" }
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, 'Update')
try {
    $existing = $zip.GetEntry('classes.dex')
    if ($existing) { $existing.Delete() }
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $dexFile, 'classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
} finally {
    $zip.Dispose()
}

Write-Host '[5/6] Aligning the APK ...'
& $zipalign -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '[6/6] Signing the APK with a debug keystore ...'
# The keystore lives in the project so the signing identity is stable between
# builds; a copy is placed in the ASCII stage because it is passed to a native
# launcher.
$ksDir = Join-Path $root 'keystore'
$ksPath = Join-Path $ksDir 'debug.keystore'
$stageKs = Join-Path $stageDir 'debug.keystore'
if (-not (Test-Path -LiteralPath $ksPath)) {
    New-Item -ItemType Directory -Force -Path $ksDir | Out-Null
    & keytool -genkeypair -v -keystore $ksPath -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=Android Debug,O=Android,C=US' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}
Copy-Item -Force -LiteralPath $ksPath -Destination $stageKs

& $apksigner sign --ks $stageKs --ks-pass pass:android --key-pass pass:android `
    --out $stagedApk $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

& $apksigner verify --verbose $stagedApk | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify failed' }

$finalApk = Join-Path $root 'build\SystemUIHook.apk'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $finalApk) | Out-Null
Remove-Item -Force -LiteralPath $finalApk -ErrorAction SilentlyContinue
Copy-Item -Force -LiteralPath $stagedApk -Destination $finalApk

$size = [math]::Round((Get-Item -LiteralPath $finalApk).Length / 1KB, 1)
Write-Host "OK -> $finalApk ($size KB)"