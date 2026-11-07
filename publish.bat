@echo off
rem Makes this folder a git repo (first time) and commits + pushes everything. Build the jar with: gradlew build
setlocal
cd /d "%~dp0"
where git >nul 2>nul || (echo git not found. & goto :fail)
if not exist .git git init -b main
git add -A
git commit -m "Restyler 4.0.0 - shader looks, AI removed" || echo (nothing new to commit, or set: git config --global user.name / user.email)
git remote get-url origin >nul 2>nul && (
    echo Pushing to GitHub...
    git push || echo Push failed - run "git push" yourself and read the message.
) || (
    echo No GitHub remote yet:  gh repo create SwiPixel/restyler --public --source . --push
)
echo.
echo Done.
pause
exit /b 0

:fail
pause
exit /b 1
