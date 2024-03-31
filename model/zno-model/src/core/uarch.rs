
use crate::riscv::rv32i::*;
use crate::common::*;

/// Immediate storage strategy. 
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ImmStorage { 
    /// Indicates the lack of an immediate value (nothing to store).
    None, 
    /// Indicates that an immediate value is zero (requires no storage).
    Zero, 
    /// Indicates that an immediate value is small enough to alias with 
    /// a physical register name (physical design optimization).
    //Inline, 
    /// Indicates that storage for an immediate value must be allocated.
    Alloc,
}
impl Default for ImmStorage {
    fn default() -> Self { Self::None }
}
impl ImmStorage {
    pub fn from_imm19(imm19: u32) -> Self {
        if imm19 == 0 {
            Self::Zero
        } else {
            Self::Alloc
        }
    }
}

/// Immediate control bits.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct ImmCtl {
    /// Indicates how immediate data is stored in the pipeline.
    pub storage: ImmStorage,
    /// Indicates how immediate data should be expanded into a 32-bit value.
    pub fmt: ImmFormat,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ControlFlowEvent {
    pub redirect: bool,
    pub spec: bool,
    pub npc: usize,
}

pub struct FetchBlock {
    pub start: usize,
    pub addr: usize,
    pub data: [u8; 0x20],
}
impl FetchBlock {
    pub fn as_words(&self) -> &[u32; 8] {
        unsafe { std::mem::transmute(&self.data) }
    }

    pub fn predecode(&self) -> PredecodeBlock {
        let mut info = [PredecodeInfo::default(); 8];
        let words = self.as_words();
        for idx in 0..8 {
            let enc = words[idx];
            let pd = Rv32::disas(enc);
            let (imm_fmt, imm_data) = Rv32::decode_imm(enc);
            if let Instr::Jalr { rd, rs1, simm } = pd {
                info[idx].rs1 = Some(rs1);
            } else {
                info[idx].rs1 = None;
            }
            info[idx].illegal  = pd.is_illegal();
            info[idx].brn_kind = pd.branch_kind();
            info[idx].imm_data = imm_data;
            info[idx].imm_ctl  = ImmCtl {
                storage: ImmStorage::from_imm19(imm_data.imm19),
                fmt: imm_fmt,
            };
        }

        PredecodeBlock {
            start: self.start,
            addr: self.addr,
            data: self.data,
            info
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct ImmediateInfo {
    pub ctl: ImmCtl,
    pub data: ImmData,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PredecodeInfo {
    pub illegal: bool,
    pub imm_ctl: ImmCtl,
    pub imm_data: ImmData,
    pub brn_kind: Option<BranchKind>,
    pub rs1: Option<ArchReg>,
}
impl PredecodeInfo {
    pub fn is_branch(&self) -> bool {
        !self.illegal && self.brn_kind.is_some()
    }
}
impl Default for PredecodeInfo {
    fn default() -> Self { 
        Self {
            illegal: true,
            imm_ctl: ImmCtl { 
                storage: ImmStorage::None, 
                fmt: ImmFormat::None
            },
            imm_data: ImmData { 
                sign: false, 
                imm19: 0, 
            },
            brn_kind: None,
            rs1: None,
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub enum BranchRecord {
    JmpRelative { tgt: usize },
    CallRelative { tgt: usize },
    BrnRelative { tgt: usize },

    JmpIndirect { rs1: ArchReg },
    CallIndirect { rs1: ArchReg },
    Return,
}

#[derive(Clone, Copy, Debug)]
pub struct CfmEntry {
    kind: CfmEntryKind,
}

#[derive(Clone, Copy, Debug)]
pub enum CfmEntryKind {
    /// This entry 
    Sequential,
    /// This entry has a single unconditional relative jump.
    StaticExit { idx: usize, npc: usize },

    Invalid,
}

#[derive(Clone, Copy, Debug)]
pub enum DecodeBlockExit {
    /// This block has no control-flow instructions
    Sequential,
    /// Expected fault/exception/trap at this index
    Fault(usize),
    /// Expected unconditional jump at this index
    Jmp(usize),
    /// Expected procedure call at this index
    Call(usize),
    /// Expected procedure return at this index
    Ret(usize),
    /// The end of this block must be resolved dynamically
    Dynamic,
}
impl DecodeBlockExit {
    pub fn to_idx(&self) -> usize {
        match self {
            Self::Sequential | Self::Dynamic => 7,
            Self::Fault(idx) |
            Self::Jmp(idx) |
            Self::Call(idx) |
            Self::Ret(idx) => *idx,
        }
    }
}


#[derive(Clone, Copy, Debug)]
pub struct PredecodeBlock {
    pub start: usize,
    pub addr: usize,
    pub data: [u8; 0x20],
    pub info: [PredecodeInfo; 8],
}
impl PredecodeBlock {
    pub fn as_words(&self) -> &[u32; 8] {
        unsafe { std::mem::transmute(&self.data) }
    }

    pub fn get_imm_info(&self) -> [ImmediateInfo; 8] {
        let mut res = [ImmediateInfo::default(); 8];
        for idx in 0..8 {
            res[idx].ctl  = self.info[idx].imm_ctl;
            res[idx].data = self.info[idx].imm_data;
        }
        res
    }

    /// Returns true if this block is purely sequential (has no branches).
    pub fn is_sequential(&self) -> bool {
        !self.info.iter().any(|info| info.is_branch())
    }

    /// Find the first illegal instruction (if it exists).
    pub fn first_illegal_inst(&self) -> Option<usize> {
        self.info.iter().enumerate()
            .skip(self.start)
            .find(|(idx, i)| i.illegal)
            .map(|(idx, i)| idx)
    }
    
    /// Find the first control-flow instruction (if it exists).
    pub fn first_cfi(&self) -> Option<(usize, &PredecodeInfo)> {
        self.info.iter().enumerate()
            .skip(self.start)
            .find(|(idx, i)| i.is_branch())
    }

    /// Return a [Vec] of tuples with the index/info of all control-flow
    /// instructions within this block. 
    pub fn get_branches(&self) -> Vec<(usize, &PredecodeInfo)> {
        self.info.iter()
            .enumerate()
            .skip(self.start)
            .filter(|(idx, info)| info.is_branch())
            .map(|(idx, info)| (idx, info))
            .collect()
    }

    /// Return the index of the terminal instruction in this block. 
    pub fn get_exit(&self) -> DecodeBlockExit {
        if let Some(idx) = self.first_illegal_inst() {
            return DecodeBlockExit::Fault(idx);
        } 

        if self.is_sequential() {
            return DecodeBlockExit::Sequential;
        }

        if let Some((idx, info)) = self.first_cfi() {
            match info.brn_kind.unwrap() {
                BranchKind::Return => return DecodeBlockExit::Ret(idx),
                BranchKind::CallIndirect => return DecodeBlockExit::Call(idx),
                BranchKind::JmpIndirect => return DecodeBlockExit::Jmp(idx),
                BranchKind::CallRelative => return DecodeBlockExit::Call(idx),
                BranchKind::JmpRelative => return DecodeBlockExit::Jmp(idx),
                BranchKind::BrnRelative => return DecodeBlockExit::Dynamic,
            }
        } else {
            unreachable!();
        }
    }


    /// If this block has a single unconditional relative jump, return the 
    /// index of the jump instruction and the fully-resolved target address.
    pub fn get_single_static_exit(&self) -> Option<(usize, usize)> {
        let branches = self.get_branches();
        if branches.len() != 1 { 
            return None; 
        }
        let idx  = branches[0].0;
        let info = branches[0].1;
        let brn_kind = info.brn_kind.unwrap();
        if brn_kind.is_relative() || brn_kind.is_unconditional() {
            let pc = self.addr.wrapping_add(idx << 2) as u32;
            let imm = info.imm_data.sext32(info.imm_ctl.fmt).unwrap();
            let npc = pc.wrapping_add(imm as u32);
            Some((idx, npc as usize))
        } else {
            None
        }
    }

    pub fn to_cfm_entry(&self) -> CfmEntry {
        //for (idx, info) in self.info.iter().enumerate() {
        //    let pc = self.addr + (idx*4);
        //    println!("{:08x}: {:x?}", pc, info);
        //}

        // This predecode block contains no branches
        if !self.info.iter().any(|info| info.is_branch()) {
            return CfmEntry {
                kind: CfmEntryKind::Sequential,
            };
        }

        // This predecode block contains a single unconditional relative jmp.
        if let Some((idx, pc)) = self.get_single_static_exit() {
            return CfmEntry {
                kind: CfmEntryKind::StaticExit { idx, npc: pc },
            };
        }

        // The predecode block contains an illegal instruction.
        let ill_idx = self.info.iter().enumerate()
            .find(|(idx, info)| info.illegal)
            .map(|(idx, info)| idx);
        if let Some(idx) = ill_idx {
            if idx == 0 {
                unimplemented!("The first instruction in this block is illegal");
            }
            unimplemented!("This block has an illegal instruction");
        }

        let branches: Vec<(usize, &PredecodeInfo)> = self.info.iter()
            .enumerate().filter(|(idx, info)| info.is_branch())
            .map(|(idx, info)| (idx, info))
            .collect();

        let mut branch_iter = branches.iter();
        let mut branch_tgts = Vec::new();

       while let Some((idx, info)) = branch_iter.next() {
            let pc = self.addr.wrapping_add(idx << 2) as u32;
            let brn_kind = info.brn_kind.unwrap();

            // We always want to "statically" compute the target address of 
            // discovered branches. 
            let brn_tgt = if brn_kind.is_relative() {
                let imm = info.imm_data.sext32(info.imm_ctl.fmt).unwrap();
                let tgt = pc.wrapping_add(imm as u32);
                Some(tgt)
            } else {
                None
            };
            branch_tgts.push(brn_tgt);

            println!("{:08x?}", brn_tgt);
        }



        for (idx, info) in branches {
            println!("  {} {:?}", idx, info);
        }

        CfmEntry {
            kind: CfmEntryKind::Invalid,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MacroOpKind {
    None,
    Alu(AluOp),
    Ld(RvWidth),
    St(RvWidth),
    Sys(SysOp),
    Brn(BrnOp),
    Jmp(JmpOp),
    Illegal,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AluOp { None, Add, Sub, Sll, Slt, Sltu, Xor, Srl, Sra, Or, And }
impl std::fmt::Display for AluOp {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> Result<(), std::fmt::Error> {
        let name = match self {
            Self::None => "alu_none",
            Self::Add =>  "add",
            Self::Sub =>  "sub",
            Self::Sll =>  "sll",
            Self::Slt =>  "slt",
            Self::Sltu => "sltu",
            Self::Xor =>  "xor",
            Self::Srl =>  "srl",
            Self::Sra =>  "sra",
            Self::Or =>   "or",
            Self::And =>  "and",
        };
        write!(f, "{}", name)
    }
}
impl AluOp {
    pub fn from_op(f3: u32, f7: u32) -> Self {
        match (f3, f7) {
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
            _ => unimplemented!("ALU op f3={:03b} f7[1]={}", f3, f7),
        }
    }
    pub fn from_opimm(f3: u32, f7: u32) -> Self {
        match (f3, f7) {
            (0b000, _) => Self::Add,
            (0b001, 0b0000000) => Self::Sll,
            (0b010, _) => Self::Slt,
            (0b011, _) => Self::Sltu,
            (0b100, _) => Self::Xor,
            (0b101, 0b0000000) => Self::Srl,
            (0b101, 0b0100000) => Self::Sra,
            (0b110, _) => Self::Or,
            (0b111, _) => Self::And,
            _ => unimplemented!("ALU op f3={:03b} f7={:07b}", f3, f7),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BrnOp { None, Eq, Ne, Lt, Ge, Ltu, Geu }
impl std::fmt::Display for BrnOp {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> Result<(), std::fmt::Error> {
        let name = match self {
            Self::None => "brn_none",
            Self::Eq =>  "beq",
            Self::Ne =>  "bne",
            Self::Lt =>  "blt",
            Self::Ge =>  "bge",
            Self::Ltu => "bltu",
            Self::Geu =>  "bgeu",
        };
        write!(f, "{}", name)
    }
}

impl BrnOp {
    pub fn from(f3: u32) -> Self {
        match f3 {
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

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum JmpOp {
    JmpRelative,
    JmpIndirect,
    CallRelative,
    CallIndirect,
    Return,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SysOp { None, Ecall(u32), Ebreak(u32) }
impl std::fmt::Display for SysOp {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> Result<(), std::fmt::Error> {
        let name = match self {
            Self::None => "sys_none",
            Self::Ecall(x) =>  "ecall",
            Self::Ebreak(x) =>  "ebreak",
        };
        write!(f, "{}", name)
    }
}



#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Operand { None, Zero, Reg, Imm, Pc, }


#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MovCtl { None, Op1, Op2, Zero }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PhysRegSrc {
    None,
    Local(usize),
    Global(usize),
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PhysRegDst {
    None,
    Allocated(usize),
}


#[derive(Clone, Copy, Debug)]
pub struct MacroOp {
    pub enc: u32,
    pub kind: MacroOpKind,
    pub rr: bool,
    pub rd: ArchReg,
    pub pd: PhysRegDst,
    pub ps1: PhysRegSrc,
    pub ps2: PhysRegSrc,
    pub rs1: ArchReg,
    pub rs2: ArchReg,
    pub op1: Operand,
    pub op2: Operand,
    pub imm: ImmediateInfo,
    pub mov: MovCtl,
}
impl std::fmt::Display for MacroOp {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> Result<(), std::fmt::Error> {
        let op1_name = match self.op1 {
            Operand::Pc => "pc".to_string(),
            Operand::Imm => "#imm".to_string(),
            Operand::Reg => {
                match self.ps1 {
                    PhysRegSrc::None => format!("{}", self.rs1),
                    PhysRegSrc::Local(prn) |
                    PhysRegSrc::Global(prn) => format!("p{}", prn),
                }
            },
            Operand::Zero => "0".to_string(),
            Operand::None => "".to_string(),
        };
        let op2_name = match self.op2 {
            Operand::Pc => "pc".to_string(),
            Operand::Imm => "#imm".to_string(),
            Operand::Reg => {
                match self.ps2 {
                    PhysRegSrc::None => format!("{}", self.rs2),
                    PhysRegSrc::Local(prn) |
                    PhysRegSrc::Global(prn) => format!("p{}", prn),
                }
            },
            Operand::Zero => "0".to_string(),
            Operand::None => "".to_string(),
        };

        let dst_name = match self.pd {
            PhysRegDst::None => format!("{}", self.rd),
            PhysRegDst::Allocated(prn) => format!("p{}", prn),
        };


        match self.mov {
            MovCtl::None => {},
            MovCtl::Zero => {
                return write!(f, "mov {}, 0", dst_name);
            },
            MovCtl::Op1 => {
                return write!(f, "mov {}, {}", dst_name, op1_name);
            },
            MovCtl::Op2 => {
                return write!(f, "mov {}, {}", dst_name, op2_name);
            },
        }


        match self.kind {
            MacroOpKind::None => {
                write!(f, "mop_none")
            },
            MacroOpKind::Jmp(op) => {
                write!(f, "jmp {}, {}, {}", dst_name, op1_name, op2_name)
            },
            MacroOpKind::Brn(op) => {
                write!(f, "{} {}, {}", op, op1_name, op2_name)
            },
            MacroOpKind::Alu(op) => {
                write!(f, "{} {}, {}, {}", op, dst_name, op1_name, op2_name)
            },
            MacroOpKind::Sys(op) => {
                write!(f, "{} {}, {}", op, op1_name, op2_name)
            },
            MacroOpKind::Ld(op) => {
                write!(f, "load {}, {}, {}, {}", op, dst_name, op1_name, op2_name)
            },
            MacroOpKind::St(op) => {
                write!(f, "store {}, {}, {}, {}", op, self.rs2, op1_name, op2_name)
            },
            MacroOpKind::Illegal => {
                write!(f, "ill {:08x}", self.enc)
            },
        }
    }
}


impl Default for MacroOp {
    fn default() -> Self {
        Self {
            enc: 0xdeadc0de,
            mov: MovCtl::None,
            rr: false,
            kind: MacroOpKind::None,
            rd: ArchReg(0),
            pd: PhysRegDst::None,
            ps1: PhysRegSrc::None,
            ps2: PhysRegSrc::None,
            rs1: ArchReg(0),
            rs2: ArchReg(0),
            op1: Operand::None,
            op2: Operand::None,
            imm: ImmediateInfo::default(),
        }
    }
}
impl MacroOp {

    /// This op is non-schedulable (ie. a 'nop', 'mov', or something which
    /// is otherwise (a) already resolved, or (b) can be trivially resolved
    /// at retire. 
    pub fn is_nonsched(&self) -> bool {
        let is_mov = self.mov != MovCtl::None;

        //let is_jal = self.kind == MacroOpKind::Jmp(JmpOp::JmpRelative);

        is_mov
    }

    /// This op has a valid register result 
    pub fn has_rr(&self) -> bool { 
        self.rr && self.rd != ArchReg(0)
    }
    /// This op allocates a new physical register
    pub fn has_rr_alc(&self) -> bool {
        self.has_rr() && self.mov == MovCtl::None
    }

    pub fn get_pd(&self) -> Option<usize> {
        match self.pd {
            PhysRegDst::None => None,
            PhysRegDst::Allocated(prn) => Some(prn),
        }
    }
    pub fn get_ps1(&self) -> Option<usize> {
        match self.ps1 {
            PhysRegSrc::None => None,
            PhysRegSrc::Local(prn) | PhysRegSrc::Global(prn) => Some(prn),
        }
    }
    pub fn get_ps2(&self) -> Option<usize> {
        match self.ps2 {
            PhysRegSrc::None => None,
            PhysRegSrc::Local(prn) | PhysRegSrc::Global(prn) => Some(prn),
        }
    }

}


#[derive(Clone, Copy)]
pub struct DecodeBlock {
    pub start: usize,
    pub exit: DecodeBlockExit,
    pub addr: usize,
    pub data: [MacroOp; 8],
}
impl DecodeBlock {
    pub fn print(&self) {
        for idx in 0..8 {
            let pc = self.start.wrapping_add(idx << 2);
            if idx < self.start || idx > self.exit.to_idx() {
                println!("{:08x}: X {}", pc, Rv32::disas(self.data[idx].enc));
            } else {
                println!("{:08x}:   {}", pc, Rv32::disas(self.data[idx].enc));
            }
        }
    }

    pub fn num_preg_allocs(&self) -> usize {
        self.data.iter().skip(self.start).take(self.exit_idx()+1)
            .filter(|mop| mop.has_rr_alc())
            .count()
    }

    pub fn exit_idx(&self) -> usize { self.exit.to_idx() }

    pub fn iter_seq(&self) 
        -> impl Iterator<Item=(usize, &MacroOp)> 
    {
        self.data.iter().enumerate().skip(self.start)
            .take_while(|(idx, _)| *idx <= self.exit_idx())
    }

    pub fn iter_seq_mut(&mut self) 
        -> impl Iterator<Item=(usize, &mut MacroOp)> 
    {
        self.data.iter_mut().enumerate().skip(self.start)
            .take_while(|(idx, mop)| idx <= &mut self.exit.to_idx())
    }


    /// Return the index of the first provider of an architectural register
    /// within the block.
    pub fn find_first_def(&self, arn: ArchReg) -> Option<usize> {
        self.iter_seq().find(|(idx, mop)| mop.has_rr() && mop.rd == arn)
            .map(|(idx, _)| idx)
    }

    /// Given the index of a mop, return the index of *most-recent* previous
    /// provider for a particular architectural register. 
    pub fn find_provider(&self, sink_idx: usize, arn: ArchReg) -> Option<usize> {
        assert!(sink_idx >= self.start);
        assert!(sink_idx <= self.exit_idx());

        for pidx in (self.start..sink_idx).rev() {
            let provider = self.data[pidx];
            if provider.has_rr() && provider.rd == arn {
                return Some(pidx);
            }
        }
        None

    }


    pub fn calc_local_deps(&self) -> Vec<(usize, Option<usize>, Option<usize>)> {
        let mut res = Vec::new();

        for sidx in self.start+1..=self.exit_idx() {
            let sink = self.data[sidx];
            // Skip ops without any source register operands
            if sink.op1 != Operand::Reg && sink.op2 != Operand::Reg {
                continue;
            }

            let rs1_match = if sink.op1 == Operand::Reg {
                self.find_provider(sidx, sink.rs1)
            } else { 
                None 
            };
            let rs2_match = if sink.op2 == Operand::Reg { 
                self.find_provider(sidx, sink.rs2)
            } else { 
                None 
            };
            res.push((sidx, rs1_match, rs2_match));
        }
        res
    }

    // 1. If a provider moves zero, we can propagate it. 
    //
    // 2. Otherwise, the existence of a provider means that we cannot
    //    treat the source register as zero. 
    //
    // 3. When no provider exists, we can respect the zero bits
    //    indicated by the register map
    pub fn rewrite_dyn_zero_operands(&mut self, zeroes: [bool; 32]) -> usize {
        let mut num_rewritten = 0;

        for idx in self.start..=self.exit_idx() {
            let op = self.data[idx];

            if op.op1 == Operand::Reg {
                if let Some(pidx) = self.find_provider(idx, op.rs1) {
                    if self.data[pidx].mov == MovCtl::Zero {
                        self.data[idx].op1 = Operand::Zero;
                        num_rewritten += 1;
                    }
                } 
                else {
                    if zeroes[op.rs1.as_usize()] {
                        self.data[idx].op1 = Operand::Zero;
                        num_rewritten += 1;
                    }
                }
            }

            if op.op2 == Operand::Reg {
                if let Some(pidx) = self.find_provider(idx, op.rs2) {
                    if self.data[pidx].mov == MovCtl::Zero {
                        self.data[idx].op2 = Operand::Zero;
                        num_rewritten += 1;
                    }
                } 
                else {
                    if zeroes[op.rs2.as_usize()] {
                        self.data[idx].op2 = Operand::Zero;
                        num_rewritten += 1;
                    }
                }
            }
        }
        num_rewritten

    }

    pub fn rewrite_static_zero_operands(&mut self) -> usize {
        let mut num_rewritten = 0;
        for mop in self.data.iter_mut() {
            match mop.op1 {
                // NOTE: op1 always corresponds to rs1 here
                Operand::Reg => if mop.rs1.is_zero() {
                    mop.op1 = Operand::Zero;
                    num_rewritten += 1;
                },
                Operand::Imm => if mop.imm.ctl.storage == ImmStorage::Zero {
                    mop.op1 = Operand::Zero;
                    num_rewritten += 1;
                },
                _ => {},
            }
            match mop.op2 {
                // NOTE: op2 always corresponds to rs2 here
                Operand::Reg => if mop.rs2.is_zero() {
                    mop.op2 = Operand::Zero;
                    num_rewritten += 1;
                },
                Operand::Imm => if mop.imm.ctl.storage == ImmStorage::Zero {
                    mop.op2 = Operand::Zero;
                    num_rewritten += 1;
                },
                _ => {},
            }
        }
        num_rewritten
    }

    pub fn rewrite_mov_ops(&mut self) -> usize {
        let mut num_rewritten = 0;
        for idx in self.start..=self.exit_idx() {
            let mop = self.data[idx];
            if mop.mov != MovCtl::None {
                continue;
            }

            let op1_zero = mop.op1 == Operand::Zero;
            let op2_zero = mop.op2 == Operand::Zero;

            let regop = mop.op1 == Operand::Reg && mop.op2 == Operand::Reg;
            let regeq = mop.rs1 == mop.rs2;

            let mov_op1 = op2_zero && 
                (mop.kind == MacroOpKind::Alu(AluOp::Add) ||
                mop.kind == MacroOpKind::Alu(AluOp::Or) ||
                mop.kind == MacroOpKind::Alu(AluOp::Sub));

            let mov_op2 = op1_zero && 
                (mop.kind == MacroOpKind::Alu(AluOp::Add) ||
                mop.kind == MacroOpKind::Alu(AluOp::Or));

            let mov_zero_xs = regop && regeq &&
                (mop.kind == MacroOpKind::Alu(AluOp::Sub) ||
                mop.kind == MacroOpKind::Alu(AluOp::Xor));
            let mov_zero_and = (op1_zero || op2_zero) &&
                mop.kind == MacroOpKind::Alu(AluOp::And);

            // Zero idioms
            if mov_zero_xs || mov_zero_and {
                self.data[idx].mov = MovCtl::Zero;
                num_rewritten += 1;
            } 
            // Move the first operand
            else if mov_op1 {
                self.data[idx].mov = if op1_zero { 
                    MovCtl::Zero 
                } else { 
                    MovCtl::Op1 
                };
                num_rewritten += 1;
            } 
            // Move the second operand
            else if mov_op2 {
                self.data[idx].mov = if op2_zero { 
                    MovCtl::Zero 
                } else { 
                    MovCtl::Op2 
                };
                num_rewritten += 1;
            }
        }
        num_rewritten
    }

}

#[derive(Clone, Copy)]
pub struct MicroOp {
    kind: MicroOpKind,
    pd: Option<usize>,
    ps1: Option<usize>,
    ps2: Option<usize>,
    op1: Operand,
    op2: Operand,
}
impl Default for MicroOp {
    fn default() -> Self {
        Self {
            kind: MicroOpKind::None,
            pd: None,
            ps1: None,
            ps2: None,
            op1: Operand::None,
            op2: Operand::None,
        }
    }
}
impl MicroOp {
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MicroOpKind {
    None,
    Alu(AluOp),
}


pub struct Freelist<const SZ: usize> {
    wp_allocated: Option<Vec<usize>>,
    arr: [bool; SZ],
}
impl <const SZ: usize> Freelist<SZ> {
    pub fn new() -> Self {
        let mut res = Self {
            wp_allocated: None,
            arr: [true; SZ],
        };
        res.arr[0] = false;
        res
    }
    pub fn num_free(&self) -> usize {
        self.arr.iter().filter(|s| **s == true).count()
    }
    pub fn sample_alcs(&self, n: usize) -> Option<Vec<usize>> {
        if n > self.num_free() {
            return None;
        }
        let res: Vec<usize> = self.arr.iter()
            .enumerate()
            .filter(|(prn,s)| **s == true)
            .map(|(idx, s)| idx)
            .take(n).collect();
        Some(res)
    }

    pub fn drive_allocated(&mut self, alcs: Vec<usize>) {
        self.wp_allocated = Some(alcs);
    }
}
impl <const SZ: usize> Clocked for Freelist<SZ> {
    fn update(&mut self) {
        if let Some(alcs) = self.wp_allocated.take() {
            for prn in alcs {
                assert!(self.arr[prn] == true);
                self.arr[prn] = false;
            }
        }
    }
}


pub struct RegisterMap {
    wp_pending: Vec<(ArchReg, usize)>,
    data: [usize; 32],
    zero: [bool; 32],
}
impl RegisterMap {
    pub fn new() -> Self { 
        let mut zero = [false; 32];
        zero[0] = true;
        let mut data = [ 
             0,  1,  2,  3,  4,  5,  6,  7,
             8,  9, 10, 11, 12, 13, 14, 15, 
            16, 17, 18, 19, 20, 21, 22, 23, 
            24, 25, 26, 27, 28, 29, 30, 31,
        ];
        Self { 
            zero, 
            data,
            wp_pending: Vec::new(),
        }
    }
    pub fn sample_rp(&self, arn: ArchReg) -> (usize, bool) {
        let idx = arn.as_usize();
        (self.data[idx], self.zero[idx])
    }
    pub fn sample_zeroes(&self) -> [bool; 32] {
        self.zero
    }

    pub fn drive_wp(&mut self, arn: ArchReg, prn: usize) {
        assert!(arn != ArchReg(0));
        self.wp_pending.push((arn, prn));
    }
    pub fn print(&self) {

        println!("[MAP] Register map state:");
        for idx in (0..32).step_by(4) {
            println!(" {:2} => {:3} | {:2} => {:3} | {:2} => {:3} | {:2} => {:3}",
            idx, self.data[idx], 
            idx+1, self.data[idx+1], 
            idx+2, self.data[idx+2], 
            idx+3, self.data[idx+3], 
            );
        }
    }

}

impl Clocked for RegisterMap {
    fn update(&mut self) {
        while let Some((arn, prn)) = self.wp_pending.pop() {
            self.data[arn.as_usize()] = prn;
            self.zero[arn.as_usize()] = prn == 0;
        }
    }
}


pub struct PhysicalRegisterFile<const SIZE: usize> {
    data: [u32; SIZE],
}
impl <const SIZE: usize> PhysicalRegisterFile<SIZE> {
    pub fn new() -> Self {
        Self {
            data: [0; SIZE],
        }
    }
}
impl <const SIZE: usize> Clocked for PhysicalRegisterFile<SIZE> {
    fn update(&mut self) {
    }
}


