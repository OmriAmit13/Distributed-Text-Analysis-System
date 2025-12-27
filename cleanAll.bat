@echo off
echo ========================================
echo Cleaning all Maven projects...
echo ========================================

echo.
echo [1/3] Cleaning LocalApplication...
echo ----------------------------------------
cd LocalApplication
call mvn clean
if %ERRORLEVEL% neq 0 (
    echo ERROR: LocalApplication clean failed!
    cd ..
    exit /b 1
)
cd ..

echo.
echo [2/3] Cleaning Manager...
echo ----------------------------------------
cd Manager
call mvn clean
if %ERRORLEVEL% neq 0 (
    echo ERROR: Manager clean failed!
    cd ..
    exit /b 1
)
cd ..

echo.
echo [3/3] Cleaning Worker...
echo ----------------------------------------
cd Worker
call mvn clean
if %ERRORLEVEL% neq 0 (
    echo ERROR: Worker clean failed!
    cd ..
    exit /b 1
)
cd ..

echo.
echo ========================================
echo All projects cleaned successfully!
echo ========================================
