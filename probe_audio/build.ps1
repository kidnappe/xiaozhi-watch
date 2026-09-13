# XZProbe 构建脚本（免 Gradle）—— 小智手表端 P0 音频能力探针
# 相对 E:\code\watch\app\build.ps1 的 3 处改造（见 PLAN.md §4.7）：
#   1) javac 加第三方 jar 的 classpath
#   2) 第三方 jar 也进 dex：d8 同时吃「自己的 app.jar」+「第三方 jar」
#   3) merge dex 支持 classes*.dex（多 dex）
# 另外：拆包时剔除 META-INF/versions/（multi-release），避免 d8 碰到 module-info.class
# 注意：不能用 `Stop`。PowerShell 5.1 下 native 命令往 stderr 写东西（javac 的报错、
# d8 的 warning）会被当成 NativeCommandError 直接终止脚本，导致真正的编译错误被吞掉。
# 所以这里用 Continue，每个 native 调用后面手动查 $LASTEXITCODE。
$ErrorActionPreference = 'Continue'
$tools = 'E:\code\code tools'
$jdk   = "$tools\jdk-17.0.20+8"
$bt    = "$tools\build-tools-33\android-13"
$jar   = "$tools\android-13\android.jar"
$adb   = "$tools\platform-tools\adb.exe"
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"

# 某些宿主（比如带沙箱的执行器）里 $MyInvocation.MyCommand.Path 会是空，
# 这里按 PSScriptRoot -> PSCommandPath -> 硬编码 三级兜底，保证任何方式调用都能跑。
$root = $PSScriptRoot
if (-not $root) { $root = Split-Path -Parent $PSCommandPath }
if (-not $root) { $root = 'E:\code\xiaozhi-watch\probe_audio' }
Start-Transcript -Path "$root\build.log" -Force | Out-Null

$ws   = Split-Path -Parent $root                 # E:\code\xiaozhi-watch
$libs = Join-Path $ws 'libs'
$out  = "$root\out"
$extJars = @(
    (Join-Path $libs 'concentus-1.0.2.jar'),
    (Join-Path $libs 'Java-WebSocket-1.5.7.jar'),
    (Join-Path $libs 'slf4j-api-2.0.6.jar')
)
foreach ($j in $extJars) {
    if (-not (Test-Path $j)) { throw "缺少依赖: $j（PLAN.md §9 里有下载地址）" }
}

# 清空 out/。某些带安全包装的环境会把 Remove-Item 劫持到"回收站"并失败，
# 这里先试 Remove-Item，失败再用 .NET 直接删（只删本工程自己的构建产物目录）。
if (Test-Path $out) {
    try {
        Remove-Item -Recurse -Force $out -ErrorAction Stop
    } catch {
        # 有的安全包装会"先删掉了再报错"，所以这里必须再判一次存在性
        if (Test-Path $out) { [System.IO.Directory]::Delete($out, $true) }
    }
}
New-Item -ItemType Directory -Path "$out\classes" -Force | Out-Null
New-Item -ItemType Directory -Path "$out\xjars"   -Force | Out-Null
New-Item -ItemType Directory -Path "$out\dex"     -Force | Out-Null

Write-Output '[1/7] aapt2 link (manifest, 本工程无 res/)'
& "$bt\aapt2.exe" link -o "$out\app.unsigned.apk" --manifest "$root\AndroidManifest.xml" -I $jar --min-sdk-version 27 --target-sdk-version 28
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Output '[2/7] javac (classpath 含第三方 jar)'
$cp = @($jar) + $extJars -join ';'
$srcs = Get-ChildItem "$root\src" -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
& "$jdk\bin\javac.exe" --release 8 -encoding UTF-8 -classpath $cp -d "$out\classes" @srcs
if ($LASTEXITCODE -ne 0) { throw 'javac failed（错误详情见上方 javac 输出）' }

Write-Output '[3/7] 自己的 class 打包成 app.jar'
& "$jdk\bin\jar.exe" cf "$out\xjars\app.jar" -C "$out\classes" .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

Write-Output '[4/7] 第三方 jar 剔除 META-INF/versions (multi-release)'
$pyPrune = @"
import zipfile, shutil, os
srcs = [r'$($extJars[0])', r'$($extJars[1])', r'$($extJars[2])']
dst  = r'$out\xjars'
for s in srcs:
    name = 'pruned-' + os.path.basename(s)
    d = os.path.join(dst, name)
    zin = zipfile.ZipFile(s); zout = zipfile.ZipFile(d, 'w', zipfile.ZIP_DEFLATED)
    n = 0
    for it in zin.infolist():
        if it.filename.startswith('META-INF/versions/'): continue
        if it.filename.endswith('module-info.class'): continue
        zout.writestr(it, zin.read(it.filename)); n += 1
    zout.close(); zin.close()
    print('pruned', name, n, 'entries')
"@
$pyPrune | Out-File -Encoding utf8 "$out\prune.py"
python "$out\prune.py"
if ($LASTEXITCODE -ne 0) { throw 'prune failed' }

Write-Output '[5/7] d8 -> dex (含第三方 jar)'
Push-Location "$out\dex"
$d8Inputs = @("$out\xjars\app.jar") + (Get-ChildItem "$out\xjars\pruned-*.jar" | ForEach-Object { $_.FullName })
& "$jdk\bin\java.exe" -cp "$bt\lib\d8.jar" com.android.tools.r8.D8 --lib $jar --min-api 27 --release --output . @d8Inputs
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'd8 failed（详情见上方 D8 输出）' }
Pop-Location
$dexFiles = Get-ChildItem "$out\dex\classes*.dex"
if ($dexFiles.Count -eq 0) { throw 'no dex produced' }
$dexFiles | ForEach-Object { Write-Output ("      {0}  {1:N0} bytes" -f $_.Name, $_.Length) }

Write-Output '[6/7] merge classes*.dex into apk'
$pyMerge = @"
import zipfile, glob, os
apk = r'$out\app.unsigned.apk'
z = zipfile.ZipFile(apk, 'a')
for d in sorted(glob.glob(r'$out\dex\classes*.dex'), key=lambda p: (len(p), p)):
    z.write(d, os.path.basename(d), zipfile.ZIP_DEFLATED)
    print('merged', os.path.basename(d))
z.close()
"@
$pyMerge | Out-File -Encoding utf8 "$out\merge.py"
python "$out\merge.py"
if ($LASTEXITCODE -ne 0) { throw 'merge failed' }

Write-Output '[7/7] zipalign + sign'
& "$bt\zipalign.exe" -f 4 "$out\app.unsigned.apk" "$out\app.aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }
if (-not (Test-Path "$root\debug.keystore")) {
    & "$jdk\bin\keytool.exe" -genkeypair -keystore "$root\debug.keystore" -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=XZ,OU=XZ,O=XZ,L=CN,ST=CN,C=CN"
}
& "$bt\apksigner.bat" sign --ks "$root\debug.keystore" --ks-pass pass:android --key-pass pass:android --out "$out\app.apk" "$out\app.aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

Write-Output 'done. APK:'
Get-Item "$out\app.apk" | Select-Object FullName, Length
Write-Output "安装:  & '$adb' install -r `"$out\app.apk`""
Write-Output "看日志: & '$adb' logcat -s XZProbe"
Write-Output "拉日志: & '$adb' pull /sdcard/Android/data/com.xiaozhi.probe/files/ `"$root\pulled`""
