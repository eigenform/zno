//! Simple pipelined RV32I model

#![allow(unused_variables)]
#![allow(unused_parens)]
#![allow(unused_imports)]
#![allow(unused_mut)]
#![allow(dead_code)]
#![allow(unreachable_patterns)]

use sim::hle::mem::*;
use sim::hle::riscv::*;
use std::ops::{BitAnd, BitOr, BitXor, Shl, Shr};

pub struct ValidReg<T> {
    data: Option<T>,
}
impl <T> ValidReg<T> {
    pub fn new_valid(init: T) -> Self { 
        Self { data: Some(init) }
    }
    pub fn new_invalid() -> Self { 
        Self { data: None }
    }
    pub fn read(&self) -> &Option<T> { &self.data }
    pub fn write(&mut self, val: T) { self.data = Some(val) }
    pub fn invalidate(&mut self) { self.data = None }
}

/// Decode stage registers
pub struct DecoderStage { 
    data: [u8; 4],
    pc: usize,
}

/// Execute stage registers
pub struct ExecStage {
    inst: Instr,
    pc: usize,
}

/// Register file
pub struct RegisterFile { 
    data: [u32; 32],
}
impl RegisterFile { 
    pub fn write(&mut self, arn: ArchReg, val: u32) {
        if arn == ArchReg(0) { return; }
        let idx = arn.0 as usize;
        self.data[idx] = val;
    }
    pub fn read(&self, arn: ArchReg) -> u32 { 
        if arn == ArchReg(0) { return 0; }
        let idx = arn.0 as usize;
        self.data[idx]
    }
    pub fn new() -> Self {
        Self { data: [0; 32] }
    }
    pub fn dump(&self) {
        let rf = self.data;
        println!("{:08x} {:08x} {:08x} {:08x} {:08x} {:08x} {:08x} {:08x}",
                 rf[0], rf[1], rf[2], rf[3], rf[4], rf[5], rf[6], rf[7]);
        println!("{:08x} {:08x} {:08x} {:08x} {:08x} {:08x} {:08x} {:08x}",
                 rf[8], rf[9], rf[10], rf[11], rf[12], rf[13], rf[14], rf[15]);
        println!("{:08x} {:08x} {:08x} {:08x} {:08x} {:08x} {:08x} {:08x}",
                 rf[16], rf[17], rf[18], rf[19], rf[20], rf[21], rf[22], rf[23]);
        println!("{:08x} {:08x} {:08x} {:08x} {:08x} {:08x} {:08x} {:08x}",
                 rf[24], rf[25], rf[26], rf[27], rf[28], rf[29], rf[30], rf[31]);
    }
}


fn main() {
    const RAM_SIZE: usize = 0x0200_0000;
    let mut ram = Ram::new(RAM_SIZE);
    let entry   = read_prog(&mut ram, "programs/test.elf") as u32;

    let mut cycle = 0;
    let mut r_pc   = ValidReg::<usize>::new_valid(entry as usize);
    let mut r_dstage = ValidReg::<DecoderStage>::new_invalid();
    let mut r_estage = ValidReg::<ExecStage>::new_invalid();

    let mut rf = RegisterFile::new();

    // Initialize the stack pointer; see '__stack_top' in rv.ld
    rf.write(ArchReg(2), RAM_SIZE as u32 - 0x1000);

    loop { 

        // Assume the next PC is always the next sequential instruction.
        // This value may change if a branch has been taken.
        let mut npc = r_pc.read().unwrap().wrapping_add(4);
        let mut taken_branch = false;

        println!("================= Cycle {} ==============", cycle);
        rf.dump();

        // -----------------------------------------------
        // Execute stage

        if let Some(estage) = r_estage.read() {
            println!("Executing @ {:08x}: {:?}", estage.pc, estage.inst);
            match estage.inst {
                Instr::AuiPc { rd, uimm } => {
                    rf.write(rd, (estage.pc as u32).wrapping_add(uimm));
                },
                Instr::OpImm { rd, rs1, simm, alu_op } => {
                    let rs1 = rf.read(rs1);
                    let res = match alu_op {
                        RvALUOpImm::Addi => rs1.wrapping_add(simm as u32),
                        RvALUOpImm::Andi => rs1.bitand(simm as u32),
                        RvALUOpImm::Slli => rs1.wrapping_shl(simm as u32),
                        RvALUOpImm::Srai => {
                            (rs1 as i32).wrapping_shr(simm as u32) as u32
                        },
                        _ => unimplemented!("{:?}", alu_op),
                    };
                    println!("{:08x} {:?} {:08x} = {:08x}", rs1, alu_op, simm, res);
                    rf.write(rd, res);
                }
                Instr::Op { rd, rs1, rs2, alu_op } => {
                    let rs1 = rf.read(rs1);
                    let rs2 = rf.read(rs2);
                    let res = match alu_op { 
                        RvALUOp::Sub => rs1.wrapping_sub(rs2),
                        RvALUOp::Add => rs1.wrapping_add(rs2),
                        RvALUOp::And => rs1.bitand(rs2),
                        RvALUOp::Sll => rs1.wrapping_shl(rs2 & 0b11111),
                        _ => unimplemented!("{:?}", alu_op),
                    };
                    println!("{:08x} {:?} {:08x} = {:08x}", rs1, alu_op, rs2, res);
                    rf.write(rd, res);
                },
                Instr::Lui { rd, uimm } => {
                    rf.write(rd, uimm);
                },
                Instr::Jal { rd, simm } => {
                    let tgt_pc = (estage.pc as u32).wrapping_add(simm as u32);
                    let ret_addr  = (estage.pc as u32).wrapping_add(4);
                    rf.write(rd, ret_addr);
                    npc = tgt_pc as usize;
                    taken_branch = true;
                },
                Instr::Jalr { rd, rs1, simm } => {
                    let rs1 = rf.read(rs1);
                    let tgt_pc = rs1.wrapping_add(simm as u32);
                    let ret_addr  = (estage.pc as u32).wrapping_add(4);
                    rf.write(rd, ret_addr);
                    npc = tgt_pc as usize;
                    taken_branch = true;
                },

                Instr::Branch { rs1, rs2, simm, brn_op } => {
                    let tgt_pc = (estage.pc as u32).wrapping_add(simm as u32);
                    let rs1 = rf.read(rs1);
                    let rs2 = rf.read(rs2);
                    let taken = match brn_op { 
                        RvBranchOp::Geu => rs1 >= rs2,
                        RvBranchOp::Ge  => (rs1 as i32) >= (rs2 as i32),
                        RvBranchOp::Ltu => rs1 < rs2,
                        RvBranchOp::Lt  => (rs1 as i32) < (rs2 as i32),
                        RvBranchOp::Ne  => rs1 != rs2,
                        RvBranchOp::Eq  => rs1 == rs2,
                        _ => unimplemented!("{:?}", brn_op),
                    };
                    if taken { 
                        taken_branch = true;
                        npc = tgt_pc as usize;
                    }
                },
                Instr::Store { rs1, rs2, simm, width } => {
                    let base = rf.read(rs1);
                    let addr = base.wrapping_add(simm as u32);

                    let rs2_val = rf.read(rs2);
                    println!("Store {:08x} {:?} to {:08x}", rs2_val, width, addr);

                    match width { 
                        RvWidth::Word => {
                            let val = rs2_val;
                            let bytes = u32::to_le_bytes(val);
                            ram.write_bytes(addr as usize, &bytes);
                        },
                        RvWidth::Half => {
                            let val = ((rs2_val & 0xffff) as u16);
                            let bytes = u16::to_le_bytes(val);
                            ram.write_bytes(addr as usize, &bytes);
                        },
                        RvWidth::Byte => {
                            let val = ((rs2_val & 0xff) as u8);
                            let bytes = u8::to_le_bytes(val);
                            ram.write_bytes(addr as usize, &bytes);
                        },
                        _ => unimplemented!("{:?}", width),
                    };
                },
                Instr::Load { rd, rs1, simm, width } => {
                    let base = rf.read(rs1);
                    let addr = base.wrapping_add(simm as u32);
                    let val  = match width { 
                        RvWidth::Word => {
                            let mut bytes = [0u8; 4];
                            ram.read_bytes(addr as usize, &mut bytes);
                            u32::from_le_bytes(bytes)
                        },
                        RvWidth::Half => {
                            let mut bytes = [0u8; 2];
                            ram.read_bytes(addr as usize, &mut bytes);
                            i16::from_le_bytes(bytes) as u32
                        },
                        RvWidth::Byte => {
                            let mut bytes = [0u8; 1];
                            ram.read_bytes(addr as usize, &mut bytes);
                            i8::from_le_bytes(bytes) as u32
                        },
                        RvWidth::HalfUnsigned => {
                            let mut bytes = [0u8; 2];
                            ram.read_bytes(addr as usize, &mut bytes);
                            u16::from_le_bytes(bytes) as u32
                        },
                        RvWidth::ByteUnsigned => {
                            let mut bytes = [0u8; 1];
                            ram.read_bytes(addr as usize, &mut bytes);
                            u8::from_le_bytes(bytes) as u32
                        },
                    };
                    rf.write(rd, val);
                },
                Instr::Ecall { prv } => {
                    // The syscall number is in x17 (a7)
                    let a0 = rf.read(ArchReg(10));
                    let a1 = rf.read(ArchReg(11));
                    let a2 = rf.read(ArchReg(12));
                    let a3 = rf.read(ArchReg(13));
                    let a4 = rf.read(ArchReg(14));
                    let a5 = rf.read(ArchReg(15));
                    let a6 = rf.read(ArchReg(16));
                    let a7 = rf.read(ArchReg(17));
                    let sc = RvPkSyscall::from_u32(a7);
                    
                    println!("ECALL ({:?} a0={:08x} a1={:08x}", sc, a0, a1);
                    break;
                },
                _ => unimplemented!("{:?}", estage.inst),
            }
            r_estage.invalidate();
        } else { 
            println!("[*] No instruction to execute")
        }

        // On a taken branch, we must flush the pipeline and instead begin
        // fetching from the target address on the next cycle. 
        if taken_branch { 
            println!("[*] Taken branch invalidated decode and fetch");
            r_dstage.invalidate();
            r_pc.invalidate();
        }

        // -----------------------------------------------------------
        // Decode stage

        if let Some(dstage) = r_dstage.read() {
            let enc = u32::from_le_bytes(dstage.data);
            let tmp = Rv32::disas(enc);
            println!("[*] Decoding  @ {:08x}: {}", dstage.pc, tmp);
            r_estage.write(ExecStage { inst: tmp, pc: dstage.pc });
        } else {
            println!("[*] No instruction to decode")
        }

        // -----------------------------------------------------------
        // Fetch stage

        if let Some(pc) = r_pc.read() {
            let mut tmp = [0u8; 4];
            ram.read_bytes(*pc, &mut tmp);
            println!("Fetching  @ {:08x}: {:x?}", pc, tmp);
            r_dstage.write(DecoderStage { data: tmp, pc: *pc });
        } else {
            println!("[*] No address to fetch")
        }

        r_pc.write(npc);
        cycle += 1;

    }
}

