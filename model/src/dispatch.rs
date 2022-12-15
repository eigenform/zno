
use crate::state::*;
use crate::packet::*;
use crate::prim::*;

pub struct Freelist<const SIZE: usize, const PSIZE: usize> {
    data: [bool; SIZE],
    take: Option<usize>,
}
impl <const SIZE: usize, const PSIZE: usize> Storage<SIZE, PSIZE>
    for Freelist<SIZE, PSIZE> 
{
    fn num_used(&self) -> usize { 
        self.data.iter().filter(|x| !*x).count()
    }
}

impl <const SIZE: usize, const PSIZE: usize> Freelist<SIZE, PSIZE> {
    pub fn new() -> Self {
        let mut data = [true; SIZE];
        data[0] = false;
        Self { 
            data,
            take: None,
        }
    }

    /// Returns a [Vec] of *all* free physical register indicies.
    fn get_free(&self) -> Vec<usize> {
        self.data[1..].iter().enumerate().filter_map(|x| { 
            if *x.1 { Some(x.0 + 1) } else { None } }
        ).collect()
    }

    pub fn output(&self) -> Packet<usize, PSIZE> {
        let free = self.get_free();
        let mut res: Packet<usize, PSIZE> = Packet::new();
        let max = if free.len() >= PSIZE { PSIZE } else { free.len() };
        for idx in 0..max {
            res[idx] = free[idx];
        }

        res
    }

    pub fn allocate(&mut self, num: usize) {
        assert!(num <= PSIZE);
        self.take = Some(num);
    }

}

impl <const SIZE: usize, const PSIZE: usize> Clocked for Freelist<SIZE, PSIZE> 
{
    fn name(&self) -> &str { "freelist" }
    fn update(&mut self) {
        if let Some(num) = self.take {
        }
    }
}



