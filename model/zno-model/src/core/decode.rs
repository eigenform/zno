
use crate::riscv::rv32i::*;
use crate::core::uarch::*;


impl MacroOp {
    /// Decode an array of macro-ops.
    pub fn decode_arr<const SZ: usize>(enc: &[u32; SZ], info: &[ImmediateInfo; SZ]) 
        -> [Self; SZ] 
    {
        let mut res = [Self::default(); SZ];
        for idx in 0..SZ {
            res[idx] = Self::decode(enc[idx], info[idx]);
        }
        res
    }

    /// Decode a single macro-op.
    pub fn decode(enc: u32, imm: ImmediateInfo) -> Self {
        let op  = (enc & Rv32::MASK_OP_2)   >>  2;
        let rd  = (enc & Rv32::MASK_RD_7)   >>  7;
        let f3  = (enc & Rv32::MASK_F3_12)  >> 12;
        let rs1 = (enc & Rv32::MASK_RS1_15) >> 15;
        let rs2 = (enc & Rv32::MASK_RS2_20) >> 20;
        let f7  = (enc & Rv32::MASK_F7_25)  >> 25;

        let rd  = ArchReg::new(rd);
        let rs1 = ArchReg::new(rs1);
        let rs2 = ArchReg::new(rs2);

        let mut res = Self {
            enc,
            mov: MovCtl::None,
            rr: false,
            kind: MacroOpKind::None,
            rd,
            rs1,
            rs2,
            op1: Operand::None,
            op2: Operand::None,
            imm,
            pd: PhysRegDst::None,
            ps1: PhysRegSrc::None,
            ps2: PhysRegSrc::None,
        };

        match Opcode::from(op) {
            // R-type formats
            Opcode::OP     => {
                res.kind = MacroOpKind::Alu(AluOp::from_op(f3, f7));
                res.rr = true;
                res.op1 = Operand::Reg;
                res.op2 = Operand::Reg;
            },

            // I-type formats
            Opcode::SYSTEM   => {
                let f12 = (enc & Rv32::MASK_I_IMM12_20_31) >> 20;
                match (f12, rs1, rd) { 
                    (0b0000_0000_0000, ArchReg(0), ArchReg(0)) => {
                        res.kind = MacroOpKind::Sys(SysOp::Ecall(f3));
                    },
                    (0b0000_0000_0001, ArchReg(0), ArchReg(0)) => {
                        res.kind = MacroOpKind::Sys(SysOp::Ebreak(f3));
                    },
                    (_, _, _) => {
                        res.kind = MacroOpKind::Illegal;
                    },
                }
            },
            Opcode::OP_IMM   => {
                res.kind = MacroOpKind::Alu(AluOp::from_opimm(f3, f7));
                res.rr = true;
                res.op1 = Operand::Reg;
                res.op2 = Operand::Imm;
            },
            Opcode::JALR     => {
                let rd_lr = res.rd == ArchReg(1) || res.rd == ArchReg(5);
                let rs1_lr = res.rs1 == ArchReg(1) || res.rs1 == ArchReg(5);

                res.kind = MacroOpKind::Jmp(JmpOp::JmpIndirect);
                res.rr = true;
                res.op1 = Operand::Reg;
                res.op2 = Operand::Imm;
            },
            Opcode::LOAD => {
                res.kind = MacroOpKind::Ld(RvWidth::from(f3));
                res.rr = true;
                res.op1 = Operand::Reg;
                res.op2 = Operand::Imm;
            },

            // S-type formats
            Opcode::STORE  => {
                res.kind = MacroOpKind::St(RvWidth::from(f3));
                res.op1 = Operand::Reg;
                res.op2 = Operand::Imm;
            },

            // B-type formats
            Opcode::BRANCH => {
                res.kind = MacroOpKind::Brn(BrnOp::from(f3));
                res.op1 = Operand::Reg;
                res.op2 = Operand::Reg;
            },

            // U-type formats
            Opcode::AUIPC  => {
                res.kind = MacroOpKind::Alu(AluOp::Add);
                res.rr = true;
                res.op1 = Operand::Pc;
                res.op2 = Operand::Imm;
            },
            Opcode::LUI  => {
                res.kind = MacroOpKind::Alu(AluOp::Add);
                res.rr = true;
                res.op1 = Operand::Imm;
                res.op2 = Operand::Zero;
            },

            // J-type formats
            Opcode::JAL    => {
                res.kind = MacroOpKind::Jmp(JmpOp::JmpRelative);
                res.rr  = true;
                res.op1 = Operand::Pc;
                res.op2 = Operand::Imm;
            },
            _ => {
                res.kind = MacroOpKind::Illegal;
            },
        }

        res
    }

}
