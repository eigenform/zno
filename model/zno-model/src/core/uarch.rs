
use crate::riscv::rv32i::*;

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
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
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
            let pd = Rv32::decode(enc);
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
            imm_ctl: ImmCtl { storage: ImmStorage::None, fmt: ImmFormat::None },
            imm_data: ImmData { sign: false, imm19: 0, },
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

    /// Returns true if this block is purely sequential (has no branches).
    pub fn is_sequential(&self) -> bool {
        !self.info.iter().any(|info| info.is_branch())
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

pub struct DecodeBlock {
    pub start: usize,
    pub addr: usize,
    pub data: [Instr; 8],
}



