
extern crate goblin;
use goblin::*;
use std::collections::*;
use std::ops::*;

use zno_model::mem::*;
use zno_model::rv32i::*;
use zno_model::state::*;
use zno_model::bpred::*;
use zno_model::front::*;
use zno_model::dispatch::*;

fn read_prog(ram: &mut Ram, filename: &'static str) -> usize { 
    let buffer = std::fs::read("programs/test.elf").unwrap();
    let elf = elf::Elf::parse(&buffer).unwrap();
    let entry = elf.entry as usize;
    println!("entry {:08x}", entry);
    for hdr in elf.program_headers {
        if hdr.p_type == elf64::program_header::PT_LOAD {
            let off = hdr.p_offset as usize;
            let dst = hdr.p_paddr as usize;
            let sz  = hdr.p_filesz as usize;
            ram.write_bytes(dst, &buffer[off..off+sz]);
            println!("{:?}", hdr);
        }
    }
    entry
}

#[derive(Copy, Clone, Debug)]
pub struct DataPacket<T: Clone + Copy + std::fmt::Debug> { 
    pc: usize,
    data: T,
}
impl <T: Clone + Copy + std::fmt::Debug> DataPacket<T> {
    pub fn new(pc: usize, data: T) -> Self { 
        Self { pc, data }
    }
}

#[derive(Copy, Clone, Debug)]
pub enum ResultPacket {
    RfWrite(ArchReg, u32),
    BranchLink(ArchReg, u32, u32),
}


fn main() {

    let mut clk = ClockDomain::new("core");

    let mut rf  = RegisterFile::<u32, 32>::new_init("rf", 0);
    let mut ram = Ram::new(0x0200_0000);
    let entry   = read_prog(&mut ram, "programs/test.elf");

    // Pipeline registers
    let mut fetch_pc = Register::new_init("fpc", entry);
    let mut fetch_op: Register<Option<DataPacket<u32>>> = 
        Register::new_init("opcd", None);
    let mut dec_inst: Register<Option<DataPacket<Instr>>> = 
        Register::new_init("inst", None);
    let mut result: Register<Option<DataPacket<ResultPacket>>> = 
        Register::new_init("res", None);

    for cyc in 0..32 {
        println!("Cycle {:08x}", cyc);

        // Fetch
        let mut opcd = [0u8; 4];
        let fpc = fetch_pc.output();
        ram.read_bytes(fpc, &mut opcd);
        let opcd = u32::from_le_bytes(opcd);
        println!("[IF] {:08x}: Fetched {:08x}", fpc, opcd);
        fetch_op.submit(Some(DataPacket::new(fpc, opcd)));

        // Decode
        if let Some(opcd) = fetch_op.output() {
            let inst = Rv32::decode(opcd.data);
            dec_inst.submit(Some(DataPacket::new(opcd.pc, inst)));
            println!("[ID] {:08x}: Decoded {:08x}", opcd.pc, opcd.data);
        } else { 
            println!("[ID] Stalled");
        }

        println!("[EX] Register file: ");
        for i in 0..8 { 
            println!("     x{:02}={:08x} x{:02}={:08x} \
            x{:02}={:08x} x{:02}={:08x}", 
            (i*4)+0, rf.read((i*4)+0),
            (i*4)+1, rf.read((i*4)+1),
            (i*4)+2, rf.read((i*4)+2),
            (i*4)+3, rf.read((i*4)+3),
            );
        }

        // Execute
        if let Some(inst) = dec_inst.output() {
            println!("[EX] {:08x}: {:?}", inst.pc, inst.data);
            match inst.data {
                Instr::AuiPc { rd, uimm } => { 
                    let res = (inst.pc as u32).wrapping_add(uimm);
                    result.submit(Some(DataPacket::new(inst.pc, 
                        ResultPacket::RfWrite(rd, res))));
                },
                Instr::OpImm { rd, rs1, simm, alu_op } => { 
                    let rs1 = rf.read(rs1.0 as usize);
                    let res = match alu_op { 
                        RvALUOpImm::Addi => rs1.wrapping_add(simm as u32),
                        RvALUOpImm::Slti => ((rs1 as i32) < simm) as u32,
                        RvALUOpImm::Sltiu => (rs1 < (simm as u32)) as u32,
                        RvALUOpImm::Xori => rs1.bitxor(simm as u32),
                        RvALUOpImm::Ori => rs1.bitor(simm as u32),
                        RvALUOpImm::Andi => rs1.bitand(simm as u32),
                        RvALUOpImm::Slli => unimplemented!(),
                        RvALUOpImm::Srli => unimplemented!(),
                        RvALUOpImm::Srai => unimplemented!(),
                        _ => unimplemented!(),
                    };
                    result.submit(Some(DataPacket::new(inst.pc, 
                        ResultPacket::RfWrite(rd, res))));
                },
                Instr::Op { rd, rs1, rs2, alu_op } => {
                    let rs1 = rf.read(rs1.0 as usize);
                    let rs2 = rf.read(rs2.0 as usize);
                    let res = match alu_op {
                        RvALUOp::Add  => rs1.wrapping_add(rs2),
                        RvALUOp::Sub  => rs1.wrapping_sub(rs2),
                        RvALUOp::Sll  => unimplemented!(),
                        RvALUOp::Slt  => ((rs1 as i32) < (rs2 as i32)) as u32,
                        RvALUOp::Sltu => (rs1 < rs2) as u32,
                        RvALUOp::Xor  => rs1.bitxor(rs2),
                        RvALUOp::Srl  => unimplemented!(),
                        RvALUOp::Sra  => unimplemented!(),
                        RvALUOp::Or   => rs1.bitor(rs2),
                        RvALUOp::And  => rs1.bitand(rs2),
                        _ => unimplemented!(),
                    };
                    result.submit(Some(DataPacket::new(inst.pc, 
                        ResultPacket::RfWrite(rd, res))));
                },
                Instr::Jal { rd, simm } => { 
                    let pc = inst.pc as u32;
                    let link = pc.wrapping_add(4);
                    let tgt  = pc.wrapping_add(simm as u32);
                    result.submit(Some(DataPacket::new(inst.pc, 
                        ResultPacket::BranchLink(rd, link, tgt))));
                },
                _ => unimplemented!(),
            }
        } else {
            println!("[EX] Stalled");
        }

        if let Some(res) = result.output() {
            println!("[WB] {:08x}: {:x?}", res.pc, res.data);
            match res.data { 
                ResultPacket::RfWrite(rd, val) => {
                    if rd.0 != 0 { 
                        rf.write(rd.0 as usize, val);
                    }
                    fetch_pc.submit(fetch_pc.output().wrapping_add(4));
                },
                ResultPacket::BranchLink(rd, link, tgt) => {
                    if rd.0 != 0 {
                        rf.write(rd.0 as usize, link);
                    }
                    fetch_pc.submit(tgt as usize);
                },
            }
        } else {
            println!("[WB] Stalled");
            fetch_pc.submit(fetch_pc.output().wrapping_add(4));
        }


        println!("================================");
        fetch_pc.update();
        fetch_op.update();
        dec_inst.update();
        result.update();
        rf.update();
        clk.update();
    }
}


