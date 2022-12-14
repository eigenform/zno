
use crate::state::*;
use crate::rv32i::*;

#[derive(Clone, Copy, Debug)]
pub struct FetchRequest {
    /// The next program counter value associated with this request
    pub npc: u32,
}
impl FetchRequest { 
    pub fn new(npc: u32) -> Self { 
        assert!((npc & 0x0000_0003) == 0);
        Self { npc }
    }
    /// Return the L1I-aligned fetch block address for this request.
    pub fn fetch_addr(&self) -> u32 { 
        self.npc & !0x0000_001f
    }

    /// The offset (in bytes) to the first decoded instruction.
    pub fn offset(&self) -> u32 {
        self.npc & 0x0000_001f
    }

}
impl Default for FetchRequest { 
    fn default() -> Self { 
        Self { npc: 0xdeadc0de }
    }
}



