@echo off
REM TeleCrypt URL Protocol Handler
REM This script is called by Windows when a com.zendev.telecrypt:// URL is opened
REM It forwards the URL to the running TeleCrypt instance via TCP socket

setlocal enabledelayedexpansion

REM Get the URL passed as argument
set "URL=%~1"

REM Send URL to running instance via PowerShell TCP client
powershell -Command "$client = New-Object System.Net.Sockets.TcpClient('localhost', 47823); $stream = $client.GetStream(); $writer = New-Object System.IO.StreamWriter($stream); $writer.WriteLine('%URL%'); $writer.Flush(); $client.Close()"

exit /b 0
