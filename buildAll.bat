@echo off
echo ========================================
echo Building all Maven projects...
echo ========================================

echo.
echo [1/3] Building LocalApplication...
echo ----------------------------------------
cd LocalApplication
call mvn clean package
if %ERRORLEVEL% neq 0 (
    echo ERROR: LocalApplication build failed!
    cd ..
    exit /b 1
)
cd ..

echo.
echo [2/3] Building Manager...
echo ----------------------------------------
cd Manager
call mvn clean package
if %ERRORLEVEL% neq 0 (
    echo ERROR: Manager build failed!
    cd ..
    exit /b 1
)
cd ..

echo.
echo [3/3] Building Worker...
echo ----------------------------------------
cd Worker
call mvn clean package
if %ERRORLEVEL% neq 0 (
    echo ERROR: Worker build failed!
    cd ..
    exit /b 1
)
cd ..

echo.
echo ========================================
echo All projects built successfully!
echo ========================================

cd LocalApplication