param(
    [ValidatePattern('^[A-Za-z]:$')][string]$Drive = 'S:',
    [string[]]$Tasks = @(':app:assembleDebug', ':app:testDebugUnitTest'),
    [string]$DeviceSerial = ''
)

$ErrorActionPreference = 'Stop'
$projectPath = [System.IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\')
$driveRoot = $Drive.ToUpperInvariant() + '\'
if (-not ('Sprint2DriveLookup' -as [type])) {
    Add-Type @'
using System.Text;
using System.Runtime.InteropServices;
public static class Sprint2DriveLookup {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    public static extern uint QueryDosDevice(string name, StringBuilder target, int length);
}
'@
}
if (Test-Path -LiteralPath $driveRoot) {
    $targetBuffer = New-Object System.Text.StringBuilder 4096
    [void][Sprint2DriveLookup]::QueryDosDevice($Drive, $targetBuffer, $targetBuffer.Capacity)
    $devicePath = $targetBuffer.ToString()
    $mappedPath = if ($devicePath.StartsWith('\??\')) { $devicePath.Substring(4).TrimEnd('\') } else { '' }
    if (![string]::Equals($mappedPath, $projectPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Drive is already in use. Choose a free letter with -Drive T:."
    }
} else {
    & subst $Drive $projectPath
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the ASCII build drive.' }
}

$previousJavaHome = $env:JAVA_HOME
$previousAndroidHome = $env:ANDROID_HOME
$previousGradleHome = $env:GRADLE_USER_HOME
$previousSerial = $env:ANDROID_SERIAL
try {
    $env:JAVA_HOME = 'D:\Android\Android Studio\jbr'
    $env:ANDROID_HOME = 'D:\Android\Sdk'
    $env:GRADLE_USER_HOME = 'D:\Android\.gradle'
    if ($DeviceSerial) { $env:ANDROID_SERIAL = $DeviceSerial }
    foreach ($requiredPath in @($env:JAVA_HOME, $env:ANDROID_HOME)) {
        if (!(Test-Path -LiteralPath $requiredPath)) { throw "Missing Android toolchain: $requiredPath" }
    }
    Write-Host "Building $projectPath through $driveRoot"
    Push-Location -LiteralPath $driveRoot
    try {
        & ($driveRoot + 'gradlew.bat') -p $driveRoot @Tasks --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:ANDROID_HOME = $previousAndroidHome
    $env:GRADLE_USER_HOME = $previousGradleHome
    $env:ANDROID_SERIAL = $previousSerial
}

# The drive is kept for Android Studio to open the same working copy.
# This script never switches branches, resets a repository, or modifies the Sprint1 project.
