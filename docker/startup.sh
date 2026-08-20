#!/usr/bin/env sh
# Add any startup requirements in here
logmsg() {
    SCRIPTNAME=$(basename $0)
    echo "$SCRIPTNAME : $1"
}

export LOCALJARFILE=$(ls ./build/libs/*.jar 2>/dev/null | grep -v 'plain' | head -n1)
export DOCKERJARFILE=$(ls /app/*.jar 2>/dev/null | grep -v 'plain' | head -n1)
if [ -f "$DOCKERJARFILE" ]; then
    logmsg "Running docker java jarfile $DOCKERJARFILE"
    java -jar $DOCKERJARFILE
elif [ -f "$LOCALJARFILE" ]; then
    logmsg "Running local java jarfile $LOCALJARFILE"
    java -jar $LOCALJARFILE
else
    logmsg "ERROR - No jarfile found. Unable to start application"
fi
