@echo off
echo ==========================================
echo Starting NSND FastAPI Backend Server...
echo ==========================================
cd /d "C:\Users\dudrj\NPNS\backend"
".venv\Scripts\uvicorn.exe" app.main:app --host 0.0.0.0 --port 8000 --reload
