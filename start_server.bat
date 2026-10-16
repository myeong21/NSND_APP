@echo off
echo ==========================================
echo Starting NSND FastAPI Backend Server...
echo ==========================================
if not defined NSND_BACKEND_DIR set "NSND_BACKEND_DIR=%USERPROFILE%\Documents\NSND\backend"
if not exist "%NSND_BACKEND_DIR%\.venv\Scripts\python.exe" (
    echo Backend virtual environment not found. Set NSND_BACKEND_DIR to the backend directory.
    exit /b 1
)
cd /d "%NSND_BACKEND_DIR%"
if errorlevel 1 exit /b 1
".venv\Scripts\python.exe" -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
