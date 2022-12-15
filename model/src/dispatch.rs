
use crate::state::*;
use crate::packet::*;

pub struct Freelist<const SIZE: usize, const PSIZE: usize> {
    data: [bool; SIZE]
}
impl <const SIZE: usize, const PSIZE: usize> Freelist<SIZE, PSIZE> {
    pub fn new() -> Self {
        let mut data = [true; SIZE];
        data[0] = false;
        Self { data }
    }

    pub fn capacity(&self) -> usize { SIZE }
    pub fn num_free(&self) -> usize { 
        self.data.iter().filter(|x| **x).count()
    }
    pub fn is_full(&self) -> bool { self.data.iter().all(|x| !x) }

    pub fn output(&self) -> Packet<usize, PSIZE> {
        let frl: Vec<usize> = self.data[1..].iter().enumerate().filter_map(|x|
            { if *x.1 { Some(x.0 + 1) } else { None } })
        .collect::<Vec<usize>>();

        let mut res: Packet<usize, PSIZE> = Packet::new();
        let max = if frl.len() >= PSIZE { PSIZE } else { frl.len() };
        for idx in 0..max {
            res[idx] = frl[idx];
        }

        res
    }

    pub fn consume(&mut self, num: usize) {
    }

    pub fn allocate(&mut self, num_req: usize) -> Option<Vec<usize>> {
        if num_req > self.num_free() {
            return None;
        }

        let res: Vec<usize> = self.data.iter_mut().enumerate()
            .filter(|(idx, e)| **e).take(num_req)
            .map(|(idx, e)| { *e = false; idx }).collect();

        Some(res)
    }

}

impl <const SIZE: usize, const PSIZE: usize> Clocked for Freelist<SIZE, PSIZE> 
{
    fn name(&self) -> &str { "freelist" }
    fn update(&mut self) {
    }
}



