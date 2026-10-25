#!/bin/bash
# Behavioral verification of the RuntimeEffectRecoveryProof change at the SQL level.
# Reproduces the wedged BOCHK journal state (UNKNOWN prepareApplicationLaunch,
# detail=BROKER_RECOVERY, no activity entry, dead sessions) and runs the OLD vs NEW
# discharge query plus negative/positive guard cases. No device, no Android.
set -e
DB=$(mktemp /tmp/recovery-proof-XXXX.db)
trap "rm -f $DB" EXIT

OWNER="space/default/package/com.bochk.bn.aos"
OP="a0fd0751-f63b-46c0-a600-1c17b471094c"

seed() {
  rm -f "$DB"
  sqlite3 "$DB" <<SQL
CREATE TABLE operations(id TEXT, state TEXT, detail TEXT, caller TEXT, digest TEXT, created INT, updated INT);
CREATE TABLE operation_scopes(operation TEXT, scope TEXT, command TEXT, dispatch TEXT);
CREATE TABLE sessions(generation TEXT, space TEXT, package TEXT, state TEXT, installGeneration TEXT, processName TEXT, closeRequested INT);
CREATE TABLE activity_launches(operation TEXT, candidate TEXT);
$1
SQL
}

# The exact prepared predicate shared by both variants (admitted=false => the `live` guard is present).
PREPARED_OLD="FROM operations original JOIN operation_scopes own ON own.operation=original.id WHERE original.id='$OP' AND original.state='UNKNOWN' AND original.detail='LAUNCH_PREPARATION_UNCONFIRMED' AND own.scope='$OWNER' AND own.command IN ('prepareApplicationLaunch','prepareLaunch','agentPrepareLaunch') AND NOT EXISTS(SELECT 1 FROM activity_launches l WHERE l.operation=original.id) AND NOT EXISTS(SELECT 1 FROM sessions s WHERE 'space/'||s.space||'/package/'||s.package=own.scope AND s.state NOT IN ('DEAD','FAILED'))"
PREPARED_NEW="FROM operations original JOIN operation_scopes own ON own.operation=original.id WHERE original.id='$OP' AND original.state='UNKNOWN' AND original.detail IN ('LAUNCH_PREPARATION_UNCONFIRMED','BROKER_RECOVERY') AND own.scope='$OWNER' AND own.command IN ('prepareApplicationLaunch','prepareLaunch','agentPrepareLaunch') AND NOT EXISTS(SELECT 1 FROM activity_launches l WHERE l.operation=original.id) AND NOT EXISTS(SELECT 1 FROM sessions s WHERE 'space/'||s.space||'/package/'||s.package=own.scope AND s.state NOT IN ('DEAD','FAILED'))"

# variant B = dead-session discharge; variant A = explicit-stop discharge
DEAD_B=" AND EXISTS(SELECT 1 FROM sessions s WHERE 'space/'||s.space||'/package/'||s.package=own.scope AND s.state='DEAD') LIMIT 1"
STOP_A=" AND EXISTS(SELECT 1 FROM operations stopped JOIN operation_scopes ss ON ss.operation=stopped.id WHERE stopped.state='SUCCEEDED' AND ss.scope=own.scope AND ss.command IN ('stopApplication','agentStopApplication') AND stopped.created>=original.updated) LIMIT 1"

q() { sqlite3 "$DB" "SELECT COUNT(*) FROM (SELECT 1 $1$2);"; }
expect() { # $1=label $2=got $3=want
  if [ "$2" = "$3" ]; then echo "PASS  $1 (got $2)"; else echo "FAIL  $1 (got $2, want $3)"; FAILED=1; fi
}
FAILED=0

# --- The wedged BOCHK state: UNKNOWN prepareApplicationLaunch, BROKER_RECOVERY, no activity entry, dead sessions ---
WEDGED="INSERT INTO operations VALUES('$OP','UNKNOWN','BROKER_RECOVERY','trusted-workbench','d',100,100);
INSERT INTO operation_scopes VALUES('$OP','$OWNER','prepareApplicationLaunch','NOT_DISPATCHED');
INSERT INTO sessions VALUES('g1','default','com.bochk.bn.aos','DEAD','ig1','com.bochk.bn.aos',0);
INSERT INTO sessions VALUES('g2','default','com.bochk.bn.aos','DEAD','ig1','com.bochk.bn.aos',0);"

echo "== Case 1: wedged BOCHK (BROKER_RECOVERY launch, dead sessions) =="
seed "$WEDGED"
expect "OLD query fences it (regression repro)" "$(q "$PREPARED_OLD" "$DEAD_B")" 0
expect "NEW query discharges it (THE FIX)"     "$(q "$PREPARED_NEW" "$DEAD_B")" 1

echo "== Case 2 (neg): BROKER_RECOVERY but command=install -> stays fenced =="
seed "INSERT INTO operations VALUES('$OP','UNKNOWN','BROKER_RECOVERY','trusted-workbench','d',100,100);
INSERT INTO operation_scopes VALUES('$OP','$OWNER','install','NOT_DISPATCHED');
INSERT INTO sessions VALUES('g1','default','com.bochk.bn.aos','DEAD','ig1','com.bochk.bn.aos',0);"
expect "NEW query keeps non-launch BROKER_RECOVERY fenced" "$(q "$PREPARED_NEW" "$DEAD_B")" 0

echo "== Case 3 (neg): a live session in scope -> stays fenced =="
seed "$WEDGED
INSERT INTO sessions VALUES('g3','default','com.bochk.bn.aos','RUNNING','ig1','com.bochk.bn.aos',0);"
expect "NEW query respects the live-session guard" "$(q "$PREPARED_NEW" "$DEAD_B")" 0

echo "== Case 4 (neg): an activity entry exists for the op -> stays fenced =="
seed "$WEDGED
INSERT INTO activity_launches VALUES('$OP','ticket1');"
expect "NEW query respects the no-activity-entry guard" "$(q "$PREPARED_NEW" "$DEAD_B")" 0

echo "== Case 5 (neg): no dead session, no stop -> nothing proves inertness =="
seed "INSERT INTO operations VALUES('$OP','UNKNOWN','BROKER_RECOVERY','trusted-workbench','d',100,100);
INSERT INTO operation_scopes VALUES('$OP','$OWNER','prepareApplicationLaunch','NOT_DISPATCHED');"
expect "NEW variant-B (dead-session) does not fire" "$(q "$PREPARED_NEW" "$DEAD_B")" 0
expect "NEW variant-A (explicit-stop) does not fire" "$(q "$PREPARED_NEW" "$STOP_A")" 0

echo "== Case 6 (pos): BROKER_RECOVERY launch cleared by an explicit SUCCEEDED stop =="
seed "INSERT INTO operations VALUES('$OP','UNKNOWN','BROKER_RECOVERY','trusted-workbench','d',100,100);
INSERT INTO operations VALUES('stop1','SUCCEEDED','','trusted-workbench','d',200,200);
INSERT INTO operation_scopes VALUES('$OP','$OWNER','prepareApplicationLaunch','NOT_DISPATCHED');
INSERT INTO operation_scopes VALUES('stop1','$OWNER','stopApplication','NOT_DISPATCHED');"
expect "NEW variant-A discharges after explicit stop" "$(q "$PREPARED_NEW" "$STOP_A")" 1

echo "== Case 7 (regression-safety): the originally-fixed LAUNCH_PREPARATION_UNCONFIRMED still works =="
seed "INSERT INTO operations VALUES('$OP','UNKNOWN','LAUNCH_PREPARATION_UNCONFIRMED','trusted-workbench','d',100,100);
INSERT INTO operation_scopes VALUES('$OP','$OWNER','prepareApplicationLaunch','NOT_DISPATCHED');
INSERT INTO sessions VALUES('g1','default','com.bochk.bn.aos','DEAD','ig1','com.bochk.bn.aos',0);"
expect "NEW query still discharges the previously-fixed case" "$(q "$PREPARED_NEW" "$DEAD_B")" 1

echo
[ "$FAILED" = 0 ] && echo "ALL CASES PASSED" || echo "SOME CASES FAILED"
exit ${FAILED:-0}
