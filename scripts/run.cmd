@echo off
REM SKYFIX launcher (Windows). Builds on first use, then runs the CLI.
REM   scripts\run.cmd validate
setlocal
cd /d "%~dp0.."

set JAR=target\skyfix-1.0-SNAPSHOT.jar
if not exist "%JAR%" (
  echo [skyfix] building %JAR% ...
  call mvnw.cmd -q -B -DskipTests package || exit /b 1
)

java -jar "%JAR%" %*
