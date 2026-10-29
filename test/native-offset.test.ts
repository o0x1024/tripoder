import assert from "node:assert/strict";
import test from "node:test";
import { definePlan, preparePlan } from "@abox/hook";
import type { EvidenceSnapshot, NativeFunction } from "@abox/hook";
import { HookSimulator } from "@abox/hook/simulator";
import { snapshotEvidence } from "../src/evidence.js";

const target = { packageName: "com.example.stripped", versionCode: "100", apkSha256: "b".repeat(64) };

const strippedEvidence: EvidenceSnapshot = {
  id: "evidence-stripped-001",
  source: "fixture",
  target,
  processInstanceId: "proc-stripped-001",
  abi: "arm64-v8a",
  capabilities: ["native-hook"],
  symbols: [
    {
      id: "sub_12a0",
      kind: "native",
      loaded: true,
      moduleName: "libstripped.so",
      symbol: "sub_12a0",
      offset: "0x12a0",
      buildId: "build-stripped-001",
      argumentTypes: ["int32", "int32"],
      returnType: "int32",
      signatureSource: "confirmed",
    },
    {
      id: "crypto_decrypt",
      kind: "native",
      loaded: true,
      moduleName: "libstripped.so",
      symbol: "internal_decrypt",
      offset: 0x2400,
      buildId: "build-stripped-001",
      argumentTypes: ["uint64", "uint32"],
      returnType: "int32",
      signatureSource: "confirmed",
    },
  ],
};

test("native offset hook resolves by hex string, sub_ prefix, and numeric offset", () => {
  const planByOffsetHex = definePlan({
    id: "plan-hex",
    target,
    params: {},
    setup(ctx) {
      ctx.native.offset("libstripped.so", "0x12a0", {
        argumentTypes: ["int32", "int32"],
        returnType: "int32",
        buildId: "build-stripped-001",
      }).replace(({ args }) => (Number(args[0]) + Number(args[1])) * 10);
    },
  });

  const preparedHex = preparePlan(planByOffsetHex, strippedEvidence);
  assert.equal(preparedHex.rules.length, 1);
  assert.equal(preparedHex.rules[0]?.symbol.id, "sub_12a0");

  const simHex = new HookSimulator(strippedEvidence);
  simHex.activate(preparedHex);
  const resHex = simHex.invoke("sub_12a0", [3, 4], () => 0);
  assert.equal(resHex.value, 70);

  // By numeric offset
  const planByOffsetNumber = definePlan({
    id: "plan-num",
    target,
    params: {},
    setup(ctx) {
      ctx.native.offset("libstripped.so", 0x12a0, {
        argumentTypes: ["int32", "int32"],
        returnType: "int32",
        buildId: "build-stripped-001",
      }).replace(({ args }) => Number(args[0]) * 100);
    },
  });
  const preparedNum = preparePlan(planByOffsetNumber, strippedEvidence);
  const simNum = new HookSimulator(strippedEvidence);
  simNum.activate(preparedNum);
  const resNum = simNum.invoke("sub_12a0", [5, 0], () => 0);
  assert.equal(resNum.value, 500);

  // By sub_ identifier via ctx.native.function
  const planBySubName = definePlan({
    id: "plan-sub",
    target,
    params: {},
    setup(ctx) {
      ctx.native.function("libstripped.so", "sub_12a0", {
        argumentTypes: ["int32", "int32"],
        returnType: "int32",
        buildId: "build-stripped-001",
      }).replace(({ args }) => Number(args[0]) + 1);
    },
  });
  const preparedSub = preparePlan(planBySubName, strippedEvidence);
  const simSub = new HookSimulator(strippedEvidence);
  simSub.activate(preparedSub);
  const resSub = simSub.invoke("sub_12a0", [41, 0], () => 0);
  assert.equal(resSub.value, 42);

  // By offset matching named symbol with numeric offset
  const planByNameAndOffset = definePlan({
    id: "plan-name-offset",
    target,
    params: {},
    setup(ctx) {
      ctx.native.offset("libstripped.so", "0x2400", {
        argumentTypes: ["uint64", "uint32"],
        returnType: "int32",
        buildId: "build-stripped-001",
      }).replace(() => 999);
    },
  });
  const preparedNameOffset = preparePlan(planByNameAndOffset, strippedEvidence);
  const simNameOffset = new HookSimulator(strippedEvidence);
  simNameOffset.activate(preparedNameOffset);
  const resNameOffset = simNameOffset.invoke("crypto_decrypt", [0n, 0], () => 0);
  assert.equal(resNameOffset.value, 999);
});

test("native offset hook rejects unknown target or ambiguous targets", () => {
  const missingPlan = definePlan({
    id: "plan-missing",
    target,
    params: {},
    setup(ctx) {
      ctx.native.offset("libstripped.so", "0x9999", {
        argumentTypes: ["int32", "int32"],
        returnType: "int32",
        buildId: "build-stripped-001",
      }).replace(() => 1);
    },
  });
  assert.throws(() => preparePlan(missingPlan, strippedEvidence), { code: "TARGET_NOT_FOUND" });

  const ambiguousEvidence: EvidenceSnapshot = {
    ...strippedEvidence,
    symbols: [
      strippedEvidence.symbols[0]!,
      {
        ...strippedEvidence.symbols[0]!,
        id: "sub_12a0_dup",
      },
    ],
  };
  const dupPlan = definePlan({
    id: "plan-dup",
    target,
    params: {},
    setup(ctx) {
      ctx.native.offset("libstripped.so", "0x12a0", {
        argumentTypes: ["int32", "int32"],
        returnType: "int32",
        buildId: "build-stripped-001",
      }).replace(() => 1);
    },
  });
  assert.throws(() => preparePlan(dupPlan, ambiguousEvidence), { code: "TARGET_AMBIGUOUS" });
});

test("snapshotEvidence validates offset format", () => {
  // Valid offsets
  const ok1 = snapshotEvidence(strippedEvidence);
  assert.equal(ok1.symbols.length, 2);

  // Invalid offset format
  const sym = strippedEvidence.symbols[0]! as NativeFunction;
  assert.throws(
    () =>
      snapshotEvidence({
        ...strippedEvidence,
        symbols: [
          {
            ...sym,
            offset: "invalid_hex",
          } as unknown as NativeFunction,
        ],
      }),
    { code: "INVALID_EVIDENCE" }
  );

  assert.throws(
    () =>
      snapshotEvidence({
        ...strippedEvidence,
        symbols: [
          {
            ...sym,
            offset: -10,
          } as unknown as NativeFunction,
        ],
      }),
    { code: "INVALID_EVIDENCE" }
  );
});
