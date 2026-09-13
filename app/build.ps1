# 小智手表端客户端 com.xiaozhi.watch —— 免 Gradle 构建脚本
# 构建链与 probe_audio/build.ps1 一致（比 E:\code\watch\app\build.ps1 多 4 处改造：jar 进 classpath、
# 第三方 jar 一起进 dex、多 dex 合并、剔除 META-INF/versions）。
# 踩过的坑见文件末尾注释，改脚本前先读。
$ErrorActionPreference = 'Continue'   # 不能用 Stop：PS 5.1 下 native 写 stderr 会终止脚本、吞掉 javac 真实报错
$tools = 'E:\code\code tools'
$jdk   = "$tools\jdk-17.0.20+8"
$bt    = "$tools\build-tools-33\android-13"
$jar   = "$tools\android-13\android.jar"
$adb   = "$tools\platform-tools\adb.exe"
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"

$root = $PSScriptRoot
if (-not $root) { $root = Split-Path -Parent $PSCommandPath }
if (-not $root) { $root = 'E:\code\xiaozhi-watch\app' }
Start-Transcript -Path "$root\build.log" -Force | Out-Null

$ws   = Split-Path -Parent $root
$libs = Join-Path $ws 'libs'
$out  = "$root\out"
$extJars = @(
    (Join-Path $libs 'concentus-1.0.2.jar'),
    (Join-Path $libs 'Java-WebSocket-1.5.7.jar'),
    (Join-Path $libs 'slf4j-api-2.0.6.jar')
)
foreach ($j in $extJars) {
    if (-not (Test-Path $j)) { throw "缺少依赖: $j" }
}

if (Test-Path $out) {
    try { Remove-Item -Recurse -Force $out -ErrorAction Stop }
    catch { if (Test-Path $out) { [System.IO.Directory]::Delete($out, $true) } }
}
New-Item -ItemType Directory -Path "$out\classes" -Force | Out-Null
New-Item -ItemType Directory -Path "$out\xjars"   -Force | Out-Null
New-Item -ItemType Directory -Path "$out\dex"     -Force | Out-Null

Write-Output '[1/7] aapt2 link'
& "$bt\aapt2.exe" link -o "$out\app.unsigned.apk" --manifest "$root\AndroidManifest.xml" -I $jar --min-sdk-version 27 --target-sdk-version 28
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Output '[2/7] javac'
$cp = @($jar) + $extJars -join ';'
$srcs = Get-ChildItem "$root\src" -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
& "$jdk\bin\javac.exe" --release 8 -encoding UTF-8 -classpath $cp -d "$out\classes" @srcs
if ($LASTEXITCODE -ne 0) { throw 'javac failed（详情见上方 javac 输出）' }

Write-Output '[3/7] 打包 app.jar'
& "$jdk\bin\jar.exe" cf "$out\xjars\app.jar" -C "$out\classes" .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

Write-Output '[4/7] 剔除第三方 jar 的 META-INF/versions'
$pyPrune = @"
import zipfile, os
srcs = [r'$($extJars[0])', r'$($extJars[1])', r'$($extJars[2])']
dst  = r'$out\xjars'
for s in srcs:
    d = os.path.join(dst, 'pruned-' + os.path.basename(s))
    zi = zipfile.ZipFile(s); zo = zipfile.ZipFile(d, 'w', zipfile.ZIP_DEFLATED)
    n = 0
    for it in zi.infolist():
        if it.filename.startswith('META-INF/versions/'): continue
        if it.filename.endswith('module-info.class'): continue
        zo.writestr(it, zi.read(it.filename)); n += 1
    zo.close(); zi.close()
    print('pruned', os.path.basename(d), n)
"@
$pyPrune | Out-File -Encoding utf8 "$out\prune.py"
python "$out\prune.py"
if ($LASTEXITCODE -ne 0) { throw 'prune failed' }

Write-Output '[5/7] d8 -> dex'
Push-Location "$out\dex"
$d8Inputs = @("$out\xjars\app.jar") + @(Get-ChildItem "$out\xjars\pruned-*.jar" | ForEach-Object { $_.FullName })
& "$jdk\bin\java.exe" -cp "$bt\lib\d8.jar" com.android.tools.r8.D8 --lib $jar --min-api 27 --release --output . @d8Inputs
$rc = $LASTEXITCODE
Pop-Location
if ($rc -ne 0) { throw 'd8 failed（详情见上方 D8 输出）' }
$dex = @(Get-ChildItem "$out\dex\classes*.dex")
if ($dex.Count -eq 0) { throw 'no dex produced' }
$dex | ForEach-Object { Write-Output ("      {0}  {1:N0} bytes" -f $_.Name, $_.Length) }

Write-Output '[6/7] merge dex'
$pyMerge = @"
import zipfile, glob, os
z = zipfile.ZipFile(r'$out\app.unsigned.apk', 'a')
for d in sorted(glob.glob(r'$out\dex\classes*.dex')):
    z.write(d, os.path.basename(d), zipfile.ZIP_DEFLATED); print('merged', os.path.basename(d))
z.close()
"@
$pyMerge | Out-File -Encoding utf8 "$out\merge.py"
python "$out\merge.py"
if ($LASTEXITCODE -ne 0) { throw 'merge failed' }

Write-Output '[7/7] zipalign + sign'
& "$bt\zipalign.exe" -f 4 "$out\app.unsigned.apk" "$out\app.aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }
$ks = "$root\debug.keystore"
if (-not (Test-Path $ks)) {
    & "$jdk\bin\keytool.exe" -genkeypair -keystore $ks -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=XZW,OU=XZW,O=XZW,L=CN,ST=CN,C=CN"
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out "$out\app.apk" "$out\app.aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

Get-Item "$out\app.apk" | Select-Object FullName, Length
Write-Output "安装:  & '$adb' install -r `"$out\app.apk`""
Write-Output "启动:  & '$adb' shell am start -n com.xiaozhi.watch/.MainActivity"
Write-Output "日志:  & '$adb' logcat -s XZW"
Stop-Transcript | Out-Null

# ── 已知坑（2026-09-12 实测，血泪） ──────────────────────────────────────────
# 1) 本 .ps1 含中文，必须存成 UTF-8 **with BOM**。无 BOM 时 PS 5.1 按 GBK 解码 → 解析期失败：
#    脚本一行都不执行、不建目录、无输出（症状极具迷惑性）。用 Write/Edit 工具改完要复查 BOM。
# 2) 不要用 $ErrorActionPreference='Stop'：native 命令写 stderr 会被当 NativeCommandError 终止脚本，
#    javac 的真实报错被吞掉。用 'Continue' + 手动查 $LASTEXITCODE。
# 3) Remove-Item -Recurse 可能被安全包装劫持到回收站并失败 → 已加 .NET 兜底（且要再判一次存在性，
#    因为有的包装会"先删掉再报错"）。
# 4) 清单里不要写 @mipmap/ic_launcher —— 本工程没有 res/，aapt2 会直接报 resource not found。
