@echo off
cd /d C:\Android\myapp
call "%USERPROFILE%\.gradle\wrapper\dists\gradle-8.14-all\c2qonpi39x1mddn7hk5gh9iqj\gradle-8.14\bin\gradle.bat" :app:assembleRelease :app:testDebugUnitTest --console=plain --no-daemon > C:\Android\myapp\build.log 2>&1
echo EXITCODE=%ERRORLEVEL% >> C:\Android\myapp\build.log
