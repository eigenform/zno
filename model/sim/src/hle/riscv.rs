
/// RV32I instruction formats.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum InstFormat { R, I, S, B, U, J }

/// RISC-V opcodes.
#[repr(usize)]
#[allow(non_camel_case_types)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Opcode {
    LOAD       = 0b00000, // [lb, lh, lw, lbu, lhu]
    LOAD_FP    = 0b00001,
    CUSTOM_0   = 0b00010,
    MISC_MEM   = 0b00011, // [fence, fence.i]
    OP_IMM     = 0b00100, // [addi, slti, sltiu, xori, ori, andi]
    AUIPC      = 0b00101, 
    OP_IMM_32  = 0b00110,
    STORE      = 0b01000, // [sb, sh, sw]
    STORE_FP   = 0b01001,
    CUSTOM_1   = 0b01010,
    AMO        = 0b01011,
    OP         = 0b01100, // [add, sub, sll, slt, sltu, xor, srl, sra, or, and]
    LUI        = 0b01101,
    OP_32      = 0b01110,
    MADD       = 0b10000,
    MSUB       = 0b10001,
    NMSUB      = 0b10010,
    NMADD      = 0b10011,
    OP_FP      = 0b10100,
    RES_0      = 0b10101,
    CUSTOM_2   = 0b10110,
    BRANCH     = 0b11000, // [beq, bne, blt, bge, bltu, bgeu]
    JALR       = 0b11001,
    RES_1      = 0b11010,
    JAL        = 0b11011,
    SYSTEM     = 0b11100,
    RES_2      = 0b11101,
    CUSTOM_3   = 0b11110,
}
impl From<u32> for Opcode {
    fn from(x: u32) -> Self {
        match x {
         0b00000 => Self::LOAD,
         0b00001 => Self::LOAD_FP,
         0b00010 => Self::CUSTOM_0,
         0b00011 => Self::MISC_MEM,
         0b00100 => Self::OP_IMM,
         0b00101 => Self::AUIPC,
         0b00110 => Self::OP_IMM_32,
         0b01000 => Self::STORE,
         0b01001 => Self::STORE_FP,
         0b01010 => Self::CUSTOM_1,
         0b01011 => Self::AMO,
         0b01100 => Self::OP,
         0b01101 => Self::LUI,
         0b01110 => Self::OP_32,
         0b10000 => Self::MADD,
         0b10001 => Self::MSUB,
         0b10010 => Self::NMSUB,
         0b10011 => Self::NMADD,
         0b10100 => Self::OP_FP,
         0b10101 => Self::RES_0,
         0b10110 => Self::CUSTOM_2,
         0b11000 => Self::BRANCH,
         0b11001 => Self::JALR,
         0b11010 => Self::RES_1,
         0b11011 => Self::JAL,
         0b11100 => Self::SYSTEM,
         0b11101 => Self::RES_2,
         0b11110 => Self::CUSTOM_3,
         _ => unimplemented!(),
        }
    }
}

/// ALU opcodes for I-type encodings.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RvALUOpImm { Addi, Slti, Sltiu, Xori, Ori, Andi, Slli, Srli, Srai }
impl From<(u32, u32)> for RvALUOpImm {
    fn from(x: (u32, u32)) -> Self {
        match x {
            (0b000, _) => Self::Addi,
            (0b001, 0b0000000) => Self::Slli,
            (0b010, _) => Self::Slti,
            (0b011, _) => Self::Sltiu,
            (0b100, _) => Self::Xori,
            (0b101, 0b0000000) => Self::Srli,
            (0b101, 0b0100000) => Self::Srai,
            (0b110, _) => Self::Ori,
            (0b111, _) => Self::Andi,
            _ => unimplemented!("ALU op f3={:03b} f7={:07b}", x.0, x.1),
        }
    }
}
impl std::fmt::Display for RvALUOpImm {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            Self::Addi  => "addi",
            Self::Slti  => "slti",
            Self::Sltiu => "sltiu",
            Self::Xori  => "xori",
            Self::Ori   => "ori",
            Self::Andi  => "andi",
            Self::Slli  => "slli",
            Self::Srli  => "srli",
            Self::Srai  => "srai",
        };
        write!(f, "{}", s)
    }
}



/// ALU opcodes for R-type encodings.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RvALUOp { Add, Sub, Sll, Slt, Sltu, Xor, Srl, Sra, Or, And }
impl From<(u32, u32)> for RvALUOp {
    fn from(x: (u32, u32)) -> Self {
        match x {
            (0b000, 0b0000000) => Self::Add,
            (0b000, 0b0100000) => Self::Sub,
            (0b001, 0b0000000) => Self::Sll,
            (0b010, 0b0000000) => Self::Slt,
            (0b011, 0b0000000) => Self::Sltu,
            (0b100, 0b0000000) => Self::Xor,
            (0b101, 0b0000000) => Self::Srl,
            (0b101, 0b0100000) => Self::Sra,
            (0b110, 0b0000000) => Self::Or,
            (0b111, 0b0000000) => Self::And,
            _ => unimplemented!("ALU op f3={:03b} f7[1]={}", x.0, x.1),
        }
    }
}
impl std::fmt::Display for RvALUOp {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            Self::Add  => "add",
            Self::Sub  => "sub",
            Self::Sll  => "sll",
            Self::Slt  => "slt",
            Self::Sltu => "sltu",
            Self::Xor  => "xor",
            Self::Srl  => "srl",
            Self::Sra  => "sra",
            Self::Or   => "or",
            Self::And  => "and",
        };
        write!(f, "{}", s)
    }
}


/// RV32I load/store width encodings.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RvWidth { Byte, Half, Word, ByteUnsigned, HalfUnsigned }
impl From<u32> for RvWidth {
    fn from(x: u32) -> Self {
        match x {
            0b000 => Self::Byte,
            0b001 => Self::Half,
            0b010 => Self::Word,
            0b100 => Self::ByteUnsigned,
            0b101 => Self::HalfUnsigned,
            _ => unimplemented!(),
        }
    }
}
impl std::fmt::Display for RvWidth {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            Self::Byte => "b",
            Self::Half => "h",
            Self::Word => "w",
            Self::ByteUnsigned => "bu",
            Self::HalfUnsigned => "hu",
        };
        write!(f, "{}", s)
    }
}


/// RV32I branch opcodes.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RvBranchOp { Eq, Ne, Lt, Ge, Ltu, Geu }
impl From<u32> for RvBranchOp {
    fn from(x: u32) -> Self {
        match x {
            0b000 => Self::Eq,
            0b001 => Self::Ne,
            0b010 => unimplemented!(),
            0b011 => unimplemented!(),
            0b100 => Self::Lt,
            0b101 => Self::Ge,
            0b110 => Self::Ltu,
            0b111 => Self::Geu,
            _ => unimplemented!(),
        }
    }
}
impl std::fmt::Display for RvBranchOp {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            Self::Eq => "eq",
            Self::Ne => "ne",
            Self::Lt => "lt",
            Self::Ge => "ge",
            Self::Ltu => "ltu",
            Self::Geu => "geu",
            _ => unimplemented!(),
        };
        write!(f, "{}", s)
    }
}


/// An architectural register index. 
#[repr(transparent)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ArchReg(pub u32);
impl ArchReg {
    pub fn new(idx: u32) -> Self {
        assert!(idx < 32);
        Self(idx)
    }
    pub fn is_zero(&self) -> bool {
        self.0 == 0
    }
    pub fn as_usize(&self) -> usize { self.0 as usize }
}
impl std::fmt::Display for ArchReg {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "x{}", self.0)
    }
}


/// Representing some encoding of a RISC-V instruction.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Instr {
    /// ALU operation
    Op { rd: ArchReg, rs1: ArchReg, rs2: ArchReg, alu_op: RvALUOp },

    /// ALU operation with immediate
    OpImm { rd: ArchReg, rs1: ArchReg, simm: i32, alu_op: RvALUOpImm },

    /// Memory load
    Load { rd: ArchReg, rs1: ArchReg, simm: i32, width: RvWidth },

    /// Jump-and-link register
    Jalr { rd: ArchReg, rs1: ArchReg, simm: i32 },

    /// Add upper immediate to program counter
    AuiPc { rd: ArchReg, uimm: u32 },

    /// Load upper immediate
    Lui { rd: ArchReg, uimm: u32 },

    /// Memory store
    Store { rs1: ArchReg, rs2: ArchReg, simm: i32, width: RvWidth },

    /// Jump-and-link
    Jal { rd: ArchReg, simm: i32 },

    /// Conditional branch
    Branch { rs1: ArchReg, rs2: ArchReg, simm: i32, brn_op: RvBranchOp },

    Ecall { prv: u32 },
    Ebreak { prv: u32 },

    /// Illegal instruction
    Illegal(u32),
}

impl Instr { 
    /// Returns true if this is an illegal instruction.
    pub fn is_illegal(&self) -> bool { matches!(self, Self::Illegal(_)) }

    /// Returns true if this instruction has no effective architectural
    /// side-effects.
    ///
    /// The RISC-V ISA defines an explicit NOP encoding (`addi x0, x0, 0`).
    pub fn is_nop(&self) -> bool {
        match self { 
            Self::Op { rd, rs1, rs2, alu_op } => {
                *rd == ArchReg(0) && 
                *rs1 == ArchReg(0) && 
                *rs2 == ArchReg(0) && 
                *alu_op == RvALUOp::Add
            },
            _ => false,
        }
    }

    pub fn is_controlflow(&self) -> bool {
        match self {
            Self::Jalr { .. } |
            Self::Jal { .. } => true,
            _ => false,
        }
    }

    /// Returns the architectural destination register specified by this
    /// instruction (if one exists). 
    pub fn rd(&self) -> Option<ArchReg> {
        match self { 
            Self::Op { rd, .. } 
            | Self::OpImm { rd, .. }
            | Self::Load { rd, .. }
            | Self::Jalr { rd, .. }
            | Self::AuiPc { rd, .. }
            | Self::Lui { rd, .. }
            | Self::Jal { rd, .. } => Some(*rd),
            _ => None,
        }
    }

    pub fn rs1(&self) -> Option<ArchReg> {
        match self {
            Self::Op { rs1, .. } 
            | Self::OpImm { rs1, .. }
            | Self::Load { rs1, .. }
            | Self::Jalr { rs1, .. }
            | Self::Store { rs1, .. }
            | Self::Branch { rs1, .. } => Some(*rs1),
            _ => None,
        }
    }

    pub fn rs2(&self) -> Option<ArchReg> {
        match self {
            Self::Op { rs2, .. } 
            | Self::Store { rs2, .. }
            | Self::Branch { rs2, .. } => Some(*rs2),
            _ => None,
        }
    }


}

impl Default for Instr { 
    fn default() -> Self { 
        Self::Illegal(0xdeadc0de)
    }
}

//impl std::fmt::Debug for Instr {
//    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
//        write!(f, "{}", self)
//    }
//}
impl std::fmt::Display for Instr {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Op { rd, rs1, rs2, alu_op } => {
                let alu_op = format!("{}", alu_op);
                write!(f, "{:6} {}, {}, {}", alu_op, rd, rs1, rs2)
            },
            Self::OpImm { rd, rs1, simm, alu_op } => {
                let alu_op = format!("{}", alu_op);
                write!(f, "{:6} {}, {}, {}", alu_op, rd, rs1, simm)
            },
            Self::Load { rd, rs1, simm, width } => {
                let inst = format!("l{}", width);
                write!(f, "{:6} {}, {}({})", inst, rd, simm, rs1)
            },
            Self::Jalr { rd, rs1, simm } => {
                write!(f, "{:6} {}, {}({})", "jalr", rd, simm, rs1)
            },
            Self::AuiPc { rd, uimm } => {
                write!(f, "{:6} {}, 0x{:08x}", "auipc", rd, uimm)
            },
            Self::Lui { rd, uimm } => {
                write!(f, "{:6} {}, 0x{:08x}", "lui", rd, uimm)
            },
            Self::Store { rs1, rs2, simm, width } => {
                let inst = format!("s{}", width);
                write!(f, "{:6} {}, {}({})", inst, rs2, simm, rs1)
            },
            Self::Jal { rd, simm } => {
                write!(f, "{:6} {}, {}", "jal", rd, simm)
            },
            Self::Branch { rs1, rs2, simm, brn_op } => {
                let inst = format!("b{}", brn_op);
                write!(f, "{:6} {}, {}, {}", inst, rs1, rs2, simm)
            },
            Self::Ecall { prv } => {
                let inst = format!("ecall");
                write!(f, "{}", inst)
            }
            Self::Ebreak { prv } => {
                let inst = format!("ebreak");
                write!(f, "{}", inst)
            }
            Self::Illegal(enc) => {
                write!(f, "{:6} {:08x}", "ill", enc)
            },
        }
    }
}



/// Wrapper type for methods implementing an RV32I instruction decoder.
pub struct Rv32;
impl Rv32 {
    // Bitmasks for fixed fields
    pub const MASK_OP_2:   u32 = 0b0000000_00000_00000_000_00000_1111100;
    pub const MASK_RD_7:   u32 = 0b0000000_00000_00000_000_11111_0000000;
    pub const MASK_F3_12:  u32 = 0b0000000_00000_00000_111_00000_0000000;
    pub const MASK_RS1_15: u32 = 0b0000000_00000_11111_000_00000_0000000;
    pub const MASK_RS2_20: u32 = 0b0000000_11111_00000_000_00000_0000000;
    pub const MASK_F7_25:  u32 = 0b1111111_00000_00000_000_00000_0000000;

    // I-type immediate bitmask
    pub const MASK_I_IMM12_20_31: u32 = 0b1111111_11111_00000_000_00000_0000000;

    // S-type immediate bitmasks
    pub const MASK_S_IMM5_07_11: u32  = 0b0000000_00000_00000_000_11111_0000000;
    pub const MASK_S_IMM7_25_31: u32  = 0b1111111_00000_00000_000_00000_0000000;

    // B-type immediate bitmasks
    pub const MASK_B_IMM1_07_07: u32  = 0b0000000_00000_00000_000_00001_0000000;
    pub const MASK_B_IMM4_08_11: u32  = 0b0000000_00000_00000_000_11110_0000000;
    pub const MASK_B_IMM6_25_30: u32  = 0b0111111_00000_00000_000_00000_0000000;
    pub const MASK_B_IMM1_31_31: u32  = 0b1000000_00000_00000_000_00000_0000000;

    // U-type immediate bitmask
    pub const MASK_U_IMM20_12_31: u32 = 0b1111111_11111_11111_111_00000_0000000;

    // J-type immediate bitmasks
    pub const MASK_J_IMM8_12_19: u32  = 0b0000000_00000_11111_111_00000_0000000;
    pub const MASK_J_IMM1_20_20: u32  = 0b0000000_00001_00000_000_00000_0000000;
    pub const MASK_J_IMM4_21_24: u32  = 0b0000000_11110_00000_000_00000_0000000;
    pub const MASK_J_IMM6_25_30: u32  = 0b0111111_00000_00000_000_00000_0000000;
    pub const MASK_J_IMM1_31_31: u32  = 0b1000000_00000_00000_000_00000_0000000;

    /// Sign-extend some 32-bit number to 'bits'.
    fn sext32(x: u32, bits: u32) -> i32 {
        ((x << (32 - bits)) as i32) >> (32 - bits)
    }

    /// Build an immediate for I-type encodings.
    fn build_i_imm(enc: u32) -> i32 {
        let imm = (enc & Self::MASK_I_IMM12_20_31) >> 20;
        Self::sext32(imm, 12)
    }

    /// Build an immediate for S-type encodings.
    fn build_s_imm(enc: u32) -> i32 {
        let imm = (
               ((enc & Self::MASK_S_IMM5_07_11) >>  7) 
            | (((enc & Self::MASK_S_IMM7_25_31) >> 25) << 5)
        );
        Self::sext32(imm, 12)
    }

    /// Build an immediate for B-type encodings.
    fn build_b_imm(enc: u32) -> i32 {
        let imm = (
               ((enc & Self::MASK_B_IMM4_08_11) >>  8) 
            | (((enc & Self::MASK_B_IMM6_25_30) >> 25) <<  4) 
            | (((enc & Self::MASK_B_IMM1_07_07) >>  7) << 10)
            | (((enc & Self::MASK_B_IMM1_31_31) >> 31) << 11)
        );
        Self::sext32(imm, 12) << 1
    }

    /// Build an immediate for U-type encodings.
    fn build_u_imm(enc: u32) -> u32 {
        enc & Self::MASK_U_IMM20_12_31
    }

    /// Build an immediate for J-type encodings.
    fn build_j_imm(enc: u32) -> i32 {
        let imm = (
               ((enc & Self::MASK_J_IMM4_21_24) >> 21)
            | (((enc & Self::MASK_J_IMM6_25_30) >> 25) << 4)
            | (((enc & Self::MASK_J_IMM1_20_20) >> 20) << 10)
            | (((enc & Self::MASK_J_IMM8_12_19) >> 12) << 11)
            | (((enc & Self::MASK_J_IMM1_31_31) >> 31) << 19)
        );
        Self::sext32(imm, 20) << 1
    }

    /// Decode an array of instructions.
    pub fn disas_arr<const sz: usize>(enc: &[u32; sz]) -> [Instr; sz] {
        let mut res = [Instr::Illegal(0xdeadc0de); sz];
        for idx in 0..sz {
            res[idx] = Self::disas(enc[idx]);
        }
        res
    }

    /// Decode an RV32I instruction. 
    /// 
    /// NOTE: This is suitable for implementing a simple disassembler, but
    /// it doesn't necessarily reflect anything useful about a simulated 
    /// implementation of a decoder in hardware. 
    ///
    pub fn disas(enc: u32) -> Instr {
        // The positions of these fields are always fixed.
        let op  = (enc & Rv32::MASK_OP_2)   >>  2;
        let rd  = (enc & Rv32::MASK_RD_7)   >>  7;
        let f3  = (enc & Rv32::MASK_F3_12)  >> 12;
        let rs1 = (enc & Rv32::MASK_RS1_15) >> 15;
        let rs2 = (enc & Rv32::MASK_RS2_20) >> 20;
        let f7  = (enc & Rv32::MASK_F7_25)  >> 25;

        let rd  = ArchReg::new(rd);
        let rs1 = ArchReg::new(rs1);
        let rs2 = ArchReg::new(rs2);

        match Opcode::from(op) {
            // R-type formats
            Opcode::OP     => {
                let alu_op = RvALUOp::from((f3, f7));
                Instr::Op { rd, rs1, rs2, alu_op }
            },

            // I-type formats
            Opcode::MISC_MEM => unimplemented!("MISC_MEM encoding"),
            Opcode::SYSTEM   => {
                let f12 = (enc & Rv32::MASK_I_IMM12_20_31) >> 20;
                match (f12, rs1, rd) { 
                    (0b0000_0000_0000, ArchReg(0), ArchReg(0)) => 
                        Instr::Ecall { prv: f3 },
                    (0b0000_0000_0001, ArchReg(0), ArchReg(0)) => 
                        Instr::Ebreak { prv: f3 },
                    (_, _, _) => Instr::Illegal(op),
                }
            },
            Opcode::OP_IMM   => {
                let simm   = Rv32::build_i_imm(enc);
                let alu_op = RvALUOpImm::from((f3, f7));
                Instr::OpImm { rd, rs1, simm, alu_op }
            },
            Opcode::JALR     => {
                let simm   = Rv32::build_i_imm(enc);
                Instr::Jalr { rd, rs1, simm }
            },
            Opcode::LOAD => {
                let simm   = Rv32::build_i_imm(enc);
                let width  = RvWidth::from(f3);
                Instr::Load { rd, rs1, simm, width }
            },

            // S-type formats
            Opcode::STORE  => {
                let simm   = Rv32::build_s_imm(enc);
                let width  = RvWidth::from(f3);
                Instr::Store { rs1, rs2, simm, width }
            },

            // B-type formats
            Opcode::BRANCH => {
                let simm   = Rv32::build_b_imm(enc);
                let brn_op = RvBranchOp::from(f3);
                Instr::Branch { rs1, rs2, simm, brn_op }
            },

            // U-type formats
            Opcode::AUIPC  => {
                let uimm   = Rv32::build_u_imm(enc);
                Instr::AuiPc { rd, uimm }
            },
            Opcode::LUI  => {
                let uimm   = Rv32::build_u_imm(enc);
                Instr::Lui { rd, uimm }
            },

            // J-type formats
            Opcode::JAL    => {
                let simm  = Rv32::build_j_imm(enc);
                Instr::Jal { rd, simm }
            },
            _ => Instr::Illegal(op),
        }
    }
}

/// RV32I immediate formats
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ImmFormat { None, I, S, B, U, J }
impl Default for ImmFormat {
    fn default() -> Self { Self::None }
}

/// RV32I encoded immediate data bits.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ImmData {
    /// The sign bit
    pub sign: bool,
    /// 19-bit immediate data
    pub imm19: u32,
}
impl Default for ImmData {
    fn default() -> Self {
        Self { sign: false, imm19: 0 }
    }
}

impl ImmData {
    /// Concatenate the sign bit and shift the immediate data if necessary,
    /// forming a 32-bit value. 
    fn gen(&self, fmt: ImmFormat) -> u32 {
        match fmt {
            ImmFormat::None => 0,
            ImmFormat::I => ((self.sign as u32) << 11) | self.imm19,
            ImmFormat::S => ((self.sign as u32) << 11) | self.imm19,
            ImmFormat::B => (((self.sign as u32) << 11) | self.imm19) << 1,
            ImmFormat::U => (((self.sign as u32) << 19) | self.imm19) << 12,
            ImmFormat::J => (((self.sign as u32) << 19) | self.imm19) << 1,
        }
    }

    /// Expand into the sign-extended 32-bit immediate
    pub fn sext32(&self, fmt: ImmFormat) -> Option<i32> {
        match fmt {
            ImmFormat::I => Some(Rv32::sext32(self.gen(fmt), 12)),
            ImmFormat::S => Some(Rv32::sext32(self.gen(fmt), 12)),
            ImmFormat::B => Some(Rv32::sext32(self.gen(fmt), 12)),
            ImmFormat::J => Some(Rv32::sext32(self.gen(fmt), 20)),
            ImmFormat::U => None,
            ImmFormat::None => None,
        }
    }

    /// Generate the appropriate 32-bit value.
    pub fn expand(&self, fmt: ImmFormat) -> Option<u32> {
        match fmt {
            ImmFormat::I => Some(self.sext32(fmt).unwrap() as u32),
            ImmFormat::S => Some(self.sext32(fmt).unwrap() as u32),
            ImmFormat::B => Some(self.sext32(fmt).unwrap() as u32),
            ImmFormat::J => Some(self.sext32(fmt).unwrap() as u32),
            ImmFormat::U => Some(self.gen(fmt)),
            ImmFormat::None => None,
        }
    }
}

#[derive(Copy, Clone)]
pub struct Rv32Imm {
    pub fmt: ImmFormat,
    pub data: ImmData,
}
impl Rv32Imm {
    pub fn new(fmt: ImmFormat, data: ImmData) -> Self {
        Self { fmt, data }
    }
    pub fn expand(&self) -> Option<u32> {
        self.data.expand(self.fmt)
    }
    pub fn is_valid(&self) -> bool {
        self.fmt != ImmFormat::None
    }
}
impl Default for Rv32Imm {
    fn default() -> Self {
        Self { fmt: ImmFormat::None, data: ImmData::default() }
    }
}

impl Rv32 {
    pub fn decode_imm(enc: u32) -> Rv32Imm {
        let op  = (enc & Rv32::MASK_OP_2)   >>  2;
        let fmt = match Opcode::from(op) {
            Opcode::OP => ImmFormat::None,
            Opcode::SYSTEM |
            Opcode::OP_IMM |
            Opcode::JALR   |
            Opcode::LOAD => ImmFormat::I,
            Opcode::STORE => ImmFormat::S,
            Opcode::BRANCH => ImmFormat::B,
            Opcode::AUIPC => ImmFormat::U,
            Opcode::LUI => ImmFormat::U,
            Opcode::JAL => ImmFormat::J,
            _ => unimplemented!("{:?}", op),
        };
        let sign_bit = (enc & 0x8000_0000) != 0;
        let menc = enc & 0x7fff_ffff;
        let imm = match fmt {
            ImmFormat::None => 0,
            ImmFormat::I => { 
                (menc & Self::MASK_I_IMM12_20_31) >> 20
            },
            ImmFormat::S => {
                   (((menc & Self::MASK_S_IMM5_07_11) >>  7) 
                 | (((menc & Self::MASK_S_IMM7_25_31) >> 25) << 5))
            },
            ImmFormat::B => {
                   (((menc & Self::MASK_B_IMM4_08_11) >>  8) 
                 | (((menc & Self::MASK_B_IMM6_25_30) >> 25) <<  4) 
                 | (((menc & Self::MASK_B_IMM1_07_07) >>  7) << 10) 
                 | (((menc & Self::MASK_B_IMM1_31_31) >> 31) << 11))
            },
            ImmFormat::U => {
                (menc & Self::MASK_U_IMM20_12_31) >> 12
            },
            ImmFormat::J => {
                   (((menc & Self::MASK_J_IMM4_21_24) >> 21) 
                 | (((menc & Self::MASK_J_IMM6_25_30) >> 25) << 4) 
                 | (((menc & Self::MASK_J_IMM1_20_20) >> 20) << 10) 
                 | (((menc & Self::MASK_J_IMM8_12_19) >> 12) << 11) 
                 | (((menc & Self::MASK_J_IMM1_31_31) >> 31) << 19))
            },
        };
        let data = ImmData { sign: sign_bit, imm19: imm };
        Rv32Imm::new(fmt, data)
    }
}


/// Branch information. 
///
/// For JAL:
///     - UncondDirect when (rd == x0)
///     - CallDirect when rd == (x1 | x5)
///
/// For JALR:
///     - UncondIndirect when (rd == x0)
///     - CallIndirect when rd == (x1 | x5)
///     - Return when rs1 == (x1 | x5)
///
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum BranchInfo {
    /// An unconditional direct branch with PC-relative addressing.
    UncondDirect { simm: i32 },

    /// An unconditional direct procedure call with PC-relative addressing. 
    CallDirect { lr: ArchReg, simm: i32 },

    /// An unconditional indirect branch. 
    UncondIndirect { base: ArchReg, simm: i32 },

    /// An unconditional indirect procedure call. 
    CallIndirect { lr: ArchReg, base: ArchReg, simm: i32 },

    /// A conditional direct branch with PC-relative addressing.
    CondDirect { simm: i32 },

    /// An unconditional indirect procedure return. 
    Return { lr: ArchReg },

    /// An illegal instruction
    Illegal(u32),
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum BranchKind {
    None,
    Return(ArchReg),
    CallIndirect(ArchReg),
    JmpIndirect(ArchReg),
    CallRelative,
    JmpRelative,
    BrnRelative,
}
impl BranchKind {
    pub fn is_conditional(&self) -> bool {
        matches!(self, Self::BrnRelative)
    }
    pub fn is_unconditional(&self) -> bool {
        !self.is_conditional()
    }
    pub fn is_indirect(&self) -> bool {
        match self {
            Self::Return(_) | 
            Self::CallIndirect(_) | 
            Self::JmpIndirect(_) => true,
            _ => false,
        }
    }
    pub fn is_relative(&self) -> bool {
        match self {
            Self::CallRelative | 
            Self::JmpRelative | 
            Self::BrnRelative => true,
            _ => false,
        }
    }

}

impl Instr {
    pub fn branch_kind(&self) -> BranchKind {
        match self {
            Self::Jalr { rd, rs1, simm } => {
                match (rd, rs1) {
                    (ArchReg(0), ArchReg(1)) => BranchKind::Return(*rs1),
                    (ArchReg(0), ArchReg(5)) => BranchKind::Return(*rs1),
                    (ArchReg(1), _) => BranchKind::CallIndirect(*rs1),
                    (ArchReg(5), _) => BranchKind::CallIndirect(*rs1),
                    (_, _) => BranchKind::JmpIndirect(*rs1),
                }
            },
            Self::Jal { rd, .. } => {
                match rd {
                    ArchReg(1) | ArchReg(5) => BranchKind::CallRelative,
                    _ => BranchKind::JmpRelative,
                }
            },
            Self::Branch { .. } => BranchKind::BrnRelative,
            _ => BranchKind::None,
        }
    }
}



#[derive(Debug)]
pub enum RvPkSyscall {
    Exit,
}
impl RvPkSyscall {
    pub fn from_u32(x: u32) -> Self { 
        match x { 
            93 => Self::Exit,
            _ => unimplemented!("syscall {}", x),
        }
    }
}


