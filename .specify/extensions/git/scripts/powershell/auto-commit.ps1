#!/usr/bin/env pwsh
# Git extension: auto-commit.ps1
#
# SPECKIT-LOCAL-PATCH: auto-commit is UNSUPPORTED on PowerShell in this repository.
#
# Bash is the canonical toolchain here, and the bash twin
# (.specify/extensions/git/scripts/bash/auto-commit.sh) carries a local patch that stages only the
# artefacts a Spec Kit command actually writes. The upstream PowerShell implementation staged the
# entire working tree with `git add .`, which on this repo would sweep unrelated in-flight work —
# and, worse, could commit files that were never meant to be committed — into an automatic commit.
#
# Rather than maintain a second copy of the scoped-staging logic, this script does nothing. Run the
# bash twin under WSL, Git Bash, or any POSIX shell, or commit by hand with a Conventional Commits
# message.
#
# Usage: auto-commit.ps1 <event_name>
#   e.g.: auto-commit.ps1 after_specify
param(
    [Parameter(Position = 0, Mandatory = $false)]
    [string]$EventName
)
$ErrorActionPreference = 'Stop'

Write-Warning "[specify] auto-commit is not supported on PowerShell in this repository; skipped$(if ($EventName) { " for $EventName" })."
Write-Host "[specify] Use .specify/extensions/git/scripts/bash/auto-commit.sh, or commit manually." -ForegroundColor DarkGray
exit 0
