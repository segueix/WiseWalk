@ECHO OFF
SET DIR=%~dp0
IF EXIST "%DIR%gradle\wrapper\gradle-wrapper.jar" (
  java -jar "%DIR%gradle\wrapper\gradle-wrapper.jar" %*
  EXIT /B %ERRORLEVEL%
)

where gradle >NUL 2>NUL
IF %ERRORLEVEL% EQU 0 (
  ECHO gradle-wrapper.jar not found; using installed Gradle from PATH. 1>&2
  gradle %*
  EXIT /B %ERRORLEVEL%
)

ECHO Error: gradle-wrapper.jar is missing and Gradle is not installed. 1>&2
ECHO Install Gradle locally or open the project in Android Studio to sync/build. 1>&2
EXIT /B 1
