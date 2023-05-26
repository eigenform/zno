
use crate::sim::*;
use crate::riscv::rv32i::*;

#[derive(Clone, Copy, Debug)]
pub struct FetchTarget {
    pub pc: u32,
}
impl FetchTarget { 
    pub fn new(pc: u32) -> Self { 
        assert!((pc & 0x0000_0003) == 0);
        Self { pc }
    }
    pub fn aligned_addr(&self) -> u32 { 
        self.pc & !0x0000_001f
    }
}
impl Default for FetchTarget { 
    fn default() -> Self { 
        Self { pc: 0xdeadc0de }
    }
}

pub struct FetchBlock {
    addr: u32,
    data: [u8; 32],
}

impl FetchBlock {
    pub fn new(addr: u32, data: [u8; 32]) -> Self {
        assert!((addr & 0x0000_001f) == 0);
        Self { addr, data }
    }
    pub fn iter_words(&self) -> impl Iterator<Item=&u32> {
        let data: &[u32; 8] = unsafe { 
            std::mem::transmute(&self.data)
        };
        data.iter()
    }

}





