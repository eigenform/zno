
use crate::state::*;
use crate::rv32i::*;
use crate::bpred::*;
use crate::pipeline::Pipeline;

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


/// An entry in the instruction byte buffer.
#[derive(Clone, Copy, Debug)]
pub struct DecodeRequest {
    /// The [FetchRequest] that generated this packet.
    pub req: FetchRequest,
    /// Contents of this packet.
    //pub data: [u32; 8],
    pub bytes: [u8; 32],
}
impl DecodeRequest {
    /// Create a new entry.
    pub fn new(req: FetchRequest, bytes: [u8; 32]) -> Self { 
        //Self { req, data: unsafe { core::mem::transmute(cache_line) } }
        Self { req, bytes }
    }

    pub fn decode(&self) -> Vec<Instr> {
        let mut res = Vec::new();
        let mut cur = self.req.offset() as usize;
        while cur < 32 { 
            let opcd = u32::from_le_bytes(
                self.bytes[cur..cur+4].try_into().unwrap()
            );
            let inst = Rv32::decode(opcd);
            res.push(inst);
            cur += 4;
        }

        res
    }

}
impl Default for DecodeRequest {
    fn default() -> Self { 
        Self { 
            req: FetchRequest::default(), 
            bytes: [0; 32] 
        }
    }
}

// ------------------------------------------------------

/// Instruction fetch stage.
///
/// Responsible for accessing instruction memory, and placing a cacheline
/// onto the IBB.
///
/// TODO: Stall for full IBB
pub fn fetch_stage(p: &mut Pipeline) {
    if let Some(fetch_req) = p.ftq.output() {
        let mut cacheline = [0u8; 32];
        p.ram.read_bytes(fetch_req.fetch_addr() as usize, &mut cacheline);
        let entry = DecodeRequest::new(*fetch_req, cacheline);
        println!("[IF] Fetched {:08x}", fetch_req.fetch_addr());
        p.ibb.submit(entry);
        p.ftq.consume();
    } else { 
        println!("[IF] Stalled for empty FTQ");
    }
}


// ------------------------------------------------------


/// Logic for interacting with the control-flow map during instruction decode.
pub fn decode_cfm_logic(p: &mut Pipeline, window: &Vec<Instr>, addr: u32) 
{
    // Select the first branch in the decode window
    let found_branch = window.iter().enumerate().find(|(idx, inst)| {
        inst.branch_info() != BranchInfo::None
    }).take();

    if let Some(cfm_entry) = p.cfm.get(addr) {
        if let Some((idx, brn)) = found_branch { 
            if cfm_entry.get_instr() == window[cfm_entry.get_offset()] {
                println!("[DE] CFM hit, branch match");
                p.nbp.submit(Some(addr));
            } else {
                println!("[DE] CFM hit, branch mismatch");
                p.cfm.submit(addr, CfmEntry::new(idx, *brn));
                p.nbp.submit(Some(addr));
            }
        } 
        else { 
            println!("[DE] CFM hit, no branch found");
            p.cfm.flush(addr);
            p.nbp.submit(None);
        }
    } 
    else { 
        if let Some((idx, brn)) = found_branch {
            println!("[DE] CFM miss, found branch");
            let brn_pc = (idx as u32 * 4) + addr;
            println!("{:08x} {:?}", brn_pc, brn);
            p.cfm.submit(addr, CfmEntry::new(idx, *brn));
            p.nbp.submit(Some(addr));
        } 
        else {
            println!("[DE] CFM miss, no branch found");
            p.nbp.submit(None);
        }
    }
}

/// Instruction decode stage.
///
/// TODO: Stall for full OPQ
pub fn decode_stage(p: &mut Pipeline) {
    let mut decode_packet = Packet::<Instr, 8>::new();

    if let Some(ibb_ent) = p.ibb.output() {

        let window = ibb_ent.decode();

        //// Drive each of the decoders
        //let mut window = [Instr::default(); 8];
        //for idx in 0..8 {
        //    let inst = Rv32::decode(ibb_ent.data[idx]);
        //    window[idx] = inst;
        //}

        // Access the CFM, and scan the decode window for branches.
        // NOTE: Maybe CFM accesses should be happening at fetch? 
        decode_cfm_logic(p, &window, ibb_ent.req.npc);

        // Place instructions into packet
        for idx in 0..8 {
            decode_packet[idx] = window[idx];
        }

        // Mark the IBB entry as consumed
        p.ibb.consume();

    } else {
        println!("[DE]: Stalled for empty IBB");
    }

    // Write to the op queue
    //for e in decode_packet.iter_valid() { println!("{:?}", e); }
    p.opq.submit(decode_packet);

}


