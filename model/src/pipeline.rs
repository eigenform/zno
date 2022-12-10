
use crate::state::*;
use crate::mem::*;
use crate::bpred::*;
use crate::front::*;
use crate::dispatch::*;
use crate::rv32i::*;

pub const RAM_SIZE: usize = 0x0200_0000;

pub struct Pipeline { 
    /// Cycle counter
    cyc: usize,
    npc: u32,

    pub nbp: Register<Option<u32>>,

    /// Generic memory device
    pub ram: Ram,
    /// Control-flow Map
    pub cfm: ControlFlowMap,
    /// Fetch target queue
    pub ftq: Queue<FetchRequest, 8>,
    /// Instruction byte buffer
    pub ibb: Queue<DecodeRequest, 8>,
    /// Instruction/micro-op queue
    pub opq: PacketQueue<Instr, 128, 8>,
    /// Physical register freelist
    pub frl: Freelist<256>,
}
impl Pipeline {
    pub fn new() -> Self { 
        let mut res = Self {
            cyc: 0,
            npc: 0,
            nbp: Register::default(),
            ram: Ram::new(RAM_SIZE),
            cfm: ControlFlowMap::new(),
            ftq: Queue::new(),
            ibb: Queue::new(),
            opq: PacketQueue::new(),
            frl: Freelist::new(),
        };
        res
    }

    pub fn init_pc(&mut self, init_pc: u32) {
        self.ftq.dbg_push(FetchRequest::new(init_pc));
    }

    pub fn step(&mut self) {
        println!("-------- cycle {:08} --------", self.cyc);
        println!("IBB {} entries", self.ibb.len());
        println!("OPQ {} entries", self.opq.len());

        bpu_stage(self);
        fetch_stage(self);
        decode_stage(self);

        self.nbp.update();
        self.ftq.update();
        self.ibb.update();
        self.opq.update();
        self.cfm.update();
        self.cyc += 1;

    }
}



