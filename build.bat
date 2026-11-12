@echo off
setlocal

set "JAVAC=javac"
set "JAVA=java"
if exist "C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.4\jbr\bin\javac.exe" (
    set "JAVAC=C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.4\jbr\bin\javac.exe"
    set "JAVA=C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.4\jbr\bin\java.exe"
)

echo [1/4] Building injector and header converter...
"%JAVAC%" --release 8 -Xlint:-options injector-src\ForgeInjector.java
if errorlevel 1 exit /b 1
"%JAVAC%" --release 8 -Xlint:-options header-converter-src\HeaderConverter.java
if errorlevel 1 exit /b 1

echo [2/4] Converting ForgeInjector.class to native-src\classes\injector.h...
"%JAVA%" -cp header-converter-src HeaderConverter injector injector-src\ForgeInjector.class native-src\classes\injector.h
if errorlevel 1 exit /b 1

echo [3/4] Converting input.jar to native-src\classes\jar.h...
"%JAVA%" -cp header-converter-src HeaderConverter input-jar input.jar native-src\classes\jar.h
if errorlevel 1 exit /b 1

echo [4/4] Building output.dll...
set "VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
if exist "%VCVARS%" (
    call "%VCVARS%"
    cl /nologo /std:c++17 /O2 /MT /EHsc /LD /DUNICODE /D_UNICODE native-src\main.cpp native-src\injector.cpp native-src\utils.cpp user32.lib /Fe:output.dll
    del /q main.obj injector.obj utils.obj output.exp output.lib 2>nul
) else (
    cd native-src && x86_64-w64-mingw32-g++ -O2 main.cpp injector.cpp utils.cpp -shared -Wl,--dll -Wl,--exclude-all-symbols -static -static-libgcc -static-libstdc++ -luser32 -o ../output.dll
    cd ..
)

del /q injector-src\*.class header-converter-src\*.class 2>nul
echo Build complete: output.dll

