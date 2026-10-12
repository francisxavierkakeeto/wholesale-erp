@echo off
if exist out rmdir /s /q out
mkdir out
javac -d out -cp lib\mariadb-java-client.jar src\erp\*.java
if errorlevel 1 (echo BUILD FAILED & exit /b 1)
copy /y sql\schema.sql out\schema.sql >nul
echo Build OK. Now run run.bat (or run.bat --selftest)
