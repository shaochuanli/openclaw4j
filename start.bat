@echo off
chcp 65001 > nul
echo.
echo  🦞 OpenClaw4j — Starting...
echo.

set JAR=target\openclaw4j-1.0.0.jar

if not exist "%JAR%" (
    echo [ERROR] JAR not found. Run: mvn package -DskipTests
    pause
    exit /b 1
)

java -jar "%JAR%" %*
