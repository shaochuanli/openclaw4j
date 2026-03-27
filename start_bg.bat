@echo off
chcp 65001 > nul
subst Z: d:\E盘\李少川\work-ai\workbuddy\openclaw4j 2>nul
start "OpenClaw4j" java -jar Z:\target\openclaw4j-1.0.0.jar
