
use std::collections::*;
use crate::state::*;
use crate::packet::*;
use crate::uarch::*;
use crate::prim::*;

#[derive(Clone, Copy, Debug, Default)]
pub struct RobEntry {
}

pub struct ReorderBuffer<const CAP: usize, const ISIZE: usize> {
    data: VecDeque<RobEntry>,
    input: Option<Packet<RobEntry, ISIZE>>,
}
impl <const CAP: usize, const ISIZE: usize> ReorderBuffer<CAP, ISIZE> {
    pub fn new() -> Self { 
        Self { 
            data: VecDeque::new(),
            input: None,
        }
    }

    pub fn push(&mut self, p: Packet<RobEntry, ISIZE>) {
        self.input = Some(p);
    }

}

impl <const CAP: usize, const ISIZE: usize> Storage<CAP, ISIZE>
    for ReorderBuffer<CAP, ISIZE> 
{
    fn num_used(&self) -> usize {
        self.data.len()
    }
}


impl <const CAP: usize, const ISIZE: usize> Clocked 
    for ReorderBuffer<CAP, ISIZE> 
{
    fn name(&self) -> &str { "rob" }
    fn update(&mut self) {
        if let Some(input) = &self.input {  
            assert!(self.num_free() >= input.valid_len());
            for idx in 0..input.valid_len() { 
                self.data.push_back(input[idx]);
            }
            self.input = None;
        } else { 
            unreachable!("ReorderBuffer had no input driven this cycle?");
        }
        self.data.make_contiguous();

    }
}


