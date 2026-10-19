@echo off
setlocal
set "GRADLE_VERSION=8.14.5"
set "DIST_NAME=gradle-%GRADLE_VERSION%"
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "CACHE_ROOT=%GRADLE_USER_HOME%\cleanProjectProgram"
set "GRADLE_BIN=%CACHE_ROOT%\%DIST_NAME%\bin\gradle.bat"
set "APP_HOME=%~dp0"
if exist "%GRADLE_BIN%" goto run_gradle
where powershell >nul 2>nul || (echo ERROR: PowerShell es necesario para descargar Gradle.& exit /b 1)
if not exist "%CACHE_ROOT%" mkdir "%CACHE_ROOT%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $v='%GRADLE_VERSION%'; $n='gradle-'+$v; $cache='%CACHE_ROOT%'; $tmp=Join-Path $env:TEMP ('cpp-gradle-'+[guid]::NewGuid().ToString('N')); New-Item -ItemType Directory -Path $tmp | Out-Null; try { $url='https://services.gradle.org/distributions/'+$n+'-bin.zip'; $zip=Join-Path $tmp ($n+'-bin.zip'); Invoke-WebRequest -Uri $url -OutFile $zip; $sumText=(Invoke-WebRequest -Uri ($url+'.sha256')).Content.Trim(); $expected=($sumText -split '\s+')[0].ToLowerInvariant(); $actual=(Get-FileHash -Algorithm SHA256 $zip).Hash.ToLowerInvariant(); if ($expected -ne $actual) { throw 'SHA-256 no coincide; se cancela la instalación.' }; Expand-Archive -Path $zip -DestinationPath $tmp; $dest=Join-Path $cache ($n+'.new'); if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }; Move-Item -Path (Join-Path $tmp $n) -Destination $dest; $final=Join-Path $cache $n; if (Test-Path $final) { Remove-Item -Recurse -Force $final }; Move-Item -Path $dest -Destination $final } finally { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }"
if errorlevel 1 exit /b 1
:run_gradle
pushd "%APP_HOME%"
call "%GRADLE_BIN%" %*
set "EXIT_CODE=%ERRORLEVEL%"
popd
exit /b %EXIT_CODE%
