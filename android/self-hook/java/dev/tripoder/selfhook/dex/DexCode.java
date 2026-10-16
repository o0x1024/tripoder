package dev.tripoder.selfhook.dex;

import java.util.ArrayList;
import java.util.List;

/** Instruction boundaries follow the DEX 039 specification; unused opcodes reject. */
public final class DexCode {
    public static final class Call {
        public final int opcode, codeUnit;
        public final DexFile.MethodRef target;
        Call(int opcode, int codeUnit, DexFile.MethodRef target) { this.opcode = opcode; this.codeUnit = codeUnit; this.target = target; }
        public boolean staticDispatch() { return opcode == 0x71 || opcode == 0x77; }
    }
    private static final int[] WIDTHS = widths();
    private static int[] widths() {
        int[] w = new int[256];
        fill(w,0x00,0x12,1);
        for (int i : new int[]{0x02,0x05,0x08}) w[i] = 2;
        for (int i : new int[]{0x03,0x06,0x09}) w[i] = 3;
        fill(w,0x13,0x26,2);
        for (int i : new int[]{0x14,0x17,0x1b,0x24,0x25,0x26}) w[i] = 3;
        w[0x18]=5; w[0x1d]=1; w[0x1e]=1; w[0x21]=1;
        w[0x27]=1; w[0x28]=1; w[0x29]=2; w[0x2a]=3; w[0x2b]=3; w[0x2c]=3;
        fill(w,0x2d,0x3d,2); fill(w,0x44,0x6d,2); fill(w,0x6e,0x72,3); fill(w,0x74,0x78,3);
        fill(w,0x7b,0x8f,1); fill(w,0x90,0xaf,2); fill(w,0xb0,0xcf,1); fill(w,0xd0,0xe2,2);
        w[0xfa]=4; w[0xfb]=4; w[0xfc]=3; w[0xfd]=3; w[0xfe]=2; w[0xff]=2;
        return w;
    }
    private static void fill(int[] values, int start, int end, int value) { for (int i=start;i<=end;i++) values[i]=value; }
    static List<Call> read(DexFile file, int start, int units) {
        List<Call> calls = new ArrayList<>();
        for (int pc = 0; pc < units;) {
            int word = file.u16(start + pc * 2), opcode = word & 255;
            long width = WIDTHS[opcode];
            if (opcode == 0 && word != 0) {
                DexFile.need((pc & 1) == 0 && pc + 2 <= units, "DEX_PAYLOAD_ALIGNMENT");
                int size = file.u16(start + pc * 2 + 2);
                if (word == 0x0100) width = 4L + 2L * size;
                else if (word == 0x0200) width = 2L + 4L * size;
                else if (word == 0x0300) {
                    DexFile.need(pc + 4 <= units && (size == 1 || size == 2 || size == 4 || size == 8), "DEX_ARRAY_PAYLOAD");
                    width = 4L + (file.u32(start + pc * 2 + 4) * size + 1) / 2;
                } else throw DexFile.invalid("DEX_PAYLOAD_KIND");
            }
            DexFile.need(width > 0 && width <= units - pc, "DEX_OPCODE_OR_TRUNCATED_INSTRUCTION");
            if ((opcode >= 0x6e && opcode <= 0x72) || (opcode >= 0x74 && opcode <= 0x78) || opcode == 0xfa || opcode == 0xfb) {
                int target = file.u16(start + pc * 2 + 2);
                DexFile.need(target < file.methods.size(), "DEX_INVOKE_METHOD_INDEX");
                calls.add(new Call(opcode, pc, file.methods.get(target)));
            } else if (opcode == 0xfc || opcode == 0xfd || opcode == 0xfe) {
                // Dynamic call sites/handles cannot be represented as exact method edges.
                calls.add(new Call(opcode, pc, null));
            }
            DexFile.need(calls.size() <= 250000, "DEX_CALL_BUDGET");
            pc += (int)width;
        }
        return calls;
    }
}
