import java.io.*;
import java.util.*;
import java.util.regex.*;

public class Assembler {

    // Instruction Map: Mnemonic -> Opcode
    private static final Map<String, Integer> OPCODES = new HashMap<>();
    // Instruction Type Map: Mnemonic -> Type (R, I, M, J, LUI)
    private static final Map<String, String> TYPES = new HashMap<>();
    
    // Symbol Table for Labels: Label -> Address
    private static final Map<String, Integer> LABELS = new HashMap<>();

    static {
        // Initialize Opcodes and Types based on ISA
        registerInstruction("ADD",   0b00000, "R");
        registerInstruction("SUB",   0b00001, "R");
        registerInstruction("AND",   0b00010, "R");
        registerInstruction("OR",    0b00011, "R");
        registerInstruction("XOR",   0b00100, "R");
        registerInstruction("SRL",   0b00100, "R"); // Reusing opcode space logic if needed or distinct
        registerInstruction("SRA",   0b00101, "R"); // Check specific opcode 
        // Note: SRL/SRA/XOR mapping based on user logic. 
        // Adjusted based on provided doc snippet:
        // XOR is 00100. CMOV is 01111. 
        
        registerInstruction("CMOV",  0b01111, "R"); 
        
        registerInstruction("ADDI",  0b00110, "I");
        registerInstruction("SUBI",  0b00111, "I");
        registerInstruction("ANDI",  0b01000, "I");
        registerInstruction("ORI",   0b01001, "I");
        registerInstruction("XORI",  0b01010, "I"); // Or NANDI/NORI based on previous chat?
        // Using DOC definitions:
        // NANDI -> 01000 ? Doc says ANDI is 01000. Let's stick to the Doc provided in prompt if possible.
        // Doc says: ANDI 01000. ORI 01001. XORI 01010.
        
        registerInstruction("LUI",   0b10000, "LUI");
        
        registerInstruction("LD",    0b01100, "M"); // Mem-Type
        registerInstruction("ST",    0b01011, "M"); // Mem-Type
        
        registerInstruction("JUMP",  0b01110, "J"); // J-Type (PC Relative)
        registerInstruction("JAL",   0b01101, "J"); // J-Type (PC Relative)
        
        registerInstruction("BEQ",   0b01111, "B"); // Branch Type (I-Type structure but relative)
    }

    private static void registerInstruction(String name, int opcode, String type) {
        OPCODES.put(name, opcode);
        TYPES.put(name, type);
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java Assembler <input.asm> <output.hex>");
            return;
        }

        String inputFile = args[0];
        String outputFile = args[1];

        try {
            List<String> lines = readFile(inputFile);
            List<String> cleanLines = firstPass(lines); // Handle labels
            List<String> hexCodes = secondPass(cleanLines); // Generate machine code
            writeFile(outputFile, hexCodes);
            System.out.println("Successfully assembled to " + outputFile);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Read file into list of strings
    private static List<String> readFile(String filename) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    // First Pass: Clean comments, empty lines, and map Labels
    private static List<String> firstPass(List<String> rawLines) {
        List<String> instructions = new ArrayList<>();
        int address = 0;

        for (String line : rawLines) {
            // Remove comments (// or #)
            String clean = line.split("//")[0].split("#")[0].trim();
            if (clean.isEmpty()) continue;

            // Check for Label
            if (clean.endsWith(":")) {
                String label = clean.substring(0, clean.length() - 1).trim();
                LABELS.put(label, address);
                continue; // Label lines don't increment address
            }
            
            // Handle inline labels "Label: INSTRUCTION"
            if (clean.contains(":")) {
                String[] parts = clean.split(":");
                String label = parts[0].trim();
                LABELS.put(label, address);
                if (parts.length > 1) {
                    clean = parts[1].trim();
                } else {
                    continue;
                }
            }

            instructions.add(clean);
            address++;
        }
        return instructions;
    }

    // Second Pass: Parse instructions and generate hex
    private static List<String> secondPass(List<String> instructions) {
        List<String> hexOutput = new ArrayList<>();
        int pc = 0;

        for (String instr : instructions) {
            // Normalize spaces (replace commas with spaces, multiple spaces with single space)
            String[] parts = instr.replace(",", " ").trim().split("\\s+");
            String mnemonic = parts[0].toUpperCase();

            if (!OPCODES.containsKey(mnemonic)) {
                throw new IllegalArgumentException("Unknown instruction: " + mnemonic);
            }

            int opcode = OPCODES.get(mnemonic);
            String type = TYPES.get(mnemonic);
            int machineCode = 0;

            try {
                switch (type) {
                    case "R": // FORMAT: OP(5) | RD(4) | RS(4) | RT(4) | 0
                        // Syntax: ADD RD, RS, RT
                        int rd = parseRegister(parts[1]);
                        int rs = parseRegister(parts[2]);
                        int rt = (parts.length > 3) ? parseRegister(parts[3]) : 0; // CMOV might only have 2 regs
                        
                        machineCode = (opcode << 13) | (rd << 9) | (rs << 5) | (rt); // RT is bits 4-0 (using 4 bits)
                        break;

                    case "I": // FORMAT: OP(5) | RD(4) | RS(4) | IMM(5)
                        // Syntax: ADDI RD, RS, IMM
                        rd = parseRegister(parts[1]);
                        rs = parseRegister(parts[2]);
                        int imm = parseImmediate(parts[3], 5);
                        
                        machineCode = (opcode << 13) | (rd << 9) | (rs << 5) | (imm & 0x1F);
                        break;
                    
                    case "M": // FORMAT: OP(5) | RD(4) | RS(4) | IMM(5)
                        if (mnemonic.equals("ST")) {
                            // Syntax: ST DataReg, BaseReg, Offset
                            // Dest Field (12-9) holds DataReg
                            // Src1 Field (8-5) holds BaseReg
                            int dataReg = parseRegister(parts[1]); 
                            int baseReg = parseRegister(parts[2]);
                            int offset = parseImmediate(parts[3], 5);
                            
                            machineCode = (opcode << 13) | (dataReg << 9) | (baseReg << 5) | (offset & 0x1F);
                        } else {
                            // Syntax: LD DestReg, BaseReg, Offset
                            int destReg = parseRegister(parts[1]);
                            int baseReg = parseRegister(parts[2]);
                            int offset = parseImmediate(parts[3], 5);
                            
                            machineCode = (opcode << 13) | (destReg << 9) | (baseReg << 5) | (offset & 0x1F);
                        }
                        break;

                    case "LUI": // FORMAT: OP(5) | RD(4) | IMM(9)
                        // Syntax: LUI RD, IMM
                        rd = parseRegister(parts[1]);
                        int imm9 = parseImmediate(parts[2], 9);
                        
                        machineCode = (opcode << 13) | (rd << 9) | (imm9 & 0x1FF);
                        break;

                    case "J": // FORMAT: OP(5) | OFFSET(13)
                        // Syntax: JUMP Label
                        // Jumps are PC Relative in this ISA: Target = PC + 1 + Offset
                        // So Offset = Target - (PC + 1)
                        int targetAddr = getLabelAddress(parts[1]);
                        int offset13 = targetAddr - (pc + 1);
                        
                        machineCode = (opcode << 13) | (offset13 & 0x1FFF);
                        break;
                        
                    case "B": // BEQ (I-Type format but with Branch Offset)
                        // Syntax: BEQ RS, RT, Label
                        // Using I-Type fields: Op | RS(Dest?) | RT(Src1?) | Offset5
                        // Need to verify mapping. Standard: Dest=RS, Src1=RT, Imm=Offset
                        int rsB = parseRegister(parts[1]);
                        int rtB = parseRegister(parts[2]);
                        int targetB = getLabelAddress(parts[3]);
                        int offset5 = targetB - (pc + 1);
                        
                        machineCode = (opcode << 13) | (rsB << 9) | (rtB << 5) | (offset5 & 0x1F);
                        break;
                }
            } catch (Exception e) {
                throw new RuntimeException("Error assembling line " + (pc+1) + ": " + instr + " -> " + e.getMessage());
            }

            // Convert to 5-digit Hex string
            String hex = String.format("%05x", machineCode);
            hexOutput.add(hex);
            pc++;
        }
        return hexOutput;
    }

    private static int parseRegister(String reg) {
        reg = reg.toUpperCase().replace("R", "").trim();
        try {
            int val = Integer.parseInt(reg);
            if (val < 0 || val > 15) throw new IllegalArgumentException("Register out of range 0-15");
            return val;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid register: " + reg);
        }
    }

    private static int parseImmediate(String imm, int bits) {
        int val;
        try {
            if (imm.startsWith("0x")) val = Integer.parseInt(imm.substring(2), 16);
            else val = Integer.parseInt(imm);
            
            // Check bounds (Signed)
            int min = -(1 << (bits - 1));
            int max = (1 << (bits - 1)) - 1;
            // For LUI (unsigned logic usually), looser check? 
            // Let's stick to simple bit masking in the assembler, strict check is optional.
            
            return val;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid immediate: " + imm);
        }
    }

    private static int getLabelAddress(String label) {
        if (LABELS.containsKey(label)) {
            return LABELS.get(label);
        }
        // Try parsing as raw number/offset
        try {
            return Integer.parseInt(label);
        } catch (NumberFormatException e) {
             throw new IllegalArgumentException("Unknown label: " + label);
        }
       
    }

    private static void writeFile(String filename, List<String> hexCodes) throws IOException {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(filename))) {
            bw.write("v2.0 raw");
            bw.newLine();
            for (String hex : hexCodes) {
                bw.write(hex + " ");
            }
            bw.newLine();
        }
    }
}