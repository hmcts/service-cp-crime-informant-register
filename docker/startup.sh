#!/usr/bin/env sh
# Add any startup requirements in here
logmsg() {
    SCRIPTNAME=$(basename $0)
    echo "$SCRIPTNAME : $1"
}

export LOCALJARFILE=$(ls ./build/libs/*.jar 2>/dev/null | grep -v 'plain' | head -n1)
export DOCKERJARFILE=$(ls /app/*.jar 2>/dev/null | grep -v 'plain' | head -n1)
# `exec` replaces this shell with the JVM, so the JVM becomes PID 1 and receives the container's
# SIGTERM directly. Without it the signal is delivered to the shell, which dies while the JVM is
# left to be killed outright a grace period later — so Spring's shutdown never runs, the consumer
# lifecycle controller is never stopped, and every delivery in flight is abandoned by a lock that
# simply expires instead of being settled. The whole point of an orderly stop is that a rolling
# deployment costs nothing.
if [ -f "$DOCKERJARFILE" ]; then
    logmsg "Running docker java jarfile $DOCKERJARFILE"
    exec java -jar "$DOCKERJARFILE"
elif [ -f "$LOCALJARFILE" ]; then
    logmsg "Running local java jarfile $LOCALJARFILE"
    exec java -jar "$LOCALJARFILE"
else
    logmsg "ERROR - No jarfile found. Unable to start application"
fi
