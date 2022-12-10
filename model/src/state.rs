//! Abstractions for representing clocked state in the design. 
//!

use std::ops::{ Index, IndexMut };
use std::collections::*;
use std::fmt::Debug;

use std::any::*;
use std::cell::*;
use std::rc::*;

/// Interface to clocked components. 
pub trait Clocked { 
    /// Returns the unique name of this component. 
    fn name(&self) -> &str;
    /// Simulate a clock edge.
    fn update(&mut self);
}

/// Shared mutable reference to a clocked component.
pub type StateRef<T> = Rc<RefCell<T>>;

/// A container for clocked components. 
pub struct ClockDomain {
    /// A name for this clock domain.
    name: String,
    /// The set of clocked components being tracked.
    components: Vec<StateRef<dyn Clocked>>,
}
impl ClockDomain { 
    pub fn new(name: impl ToString) -> Self { 
        Self { 
            name: name.to_string(),
            components: Vec::new(),
        }
    }

    pub fn register<T>(&mut self, name: &'static str, init: T) 
        -> StateRef<Register<T>> where T: Copy + Default + Debug + 'static
    {
        let reg = Register::new_init(name, init);
        let rc  = Rc::new(RefCell::new(reg));
        self.components.push(rc.clone());
        rc
    }
}
impl Clocked for ClockDomain {
    fn name(&self) -> &str { &self.name }
    fn update(&mut self) {
        for entry in self.components.iter() {
            entry.borrow_mut().update()
        }
    }
}

/// Stateful element with emulated sequential logic semantics.
pub struct Register<T> where T: Copy + Default + Debug
{
    name: String,
    data: T,
    input: Option<T>,
}
impl <T: Copy + Default + Debug> Register<T> 
    where T: Copy + Default + Debug
{
    pub fn new_init(name: &'static str, init: T) -> Self
    {
        Self { 
            name: name.to_string(), data: init, input: None
        }
    }

    /// Returns the current output from this register.
    pub fn output(&self) -> T {
        self.data
    }

    /// Latch some value into this register.
    pub fn submit(&mut self, data: T) {
        if self.input.is_some() {
            println!("[!] Register '{}': driver-to-driver conflict", self.name);
            println!("    Did you forget to call .update()?");
            panic!("");
        }
        self.input = Some(data);
    }
}
impl <T> Default for Register<T> where T: Copy + Default + Debug {
    fn default() -> Self {
        Self { name: "unk".to_string(), data: T::default(), input: None, }
    }
}
impl <T> Clocked for Register<T> where T: Copy + Default + Debug {
    fn update(&mut self) {
        if let Some(data) = self.input {
            self.data = data;
            self.input = None;
        }
    }
    fn name(&self) -> &str { 
        &self.name
    }
}

pub struct RegisterFile<T: Copy + Default + Debug, const SIZE: usize> 
    where T: Copy + Default + Debug
{
    name:  String,
    data:  [T; SIZE],
    input: [Option<T>; SIZE],
}
impl <T, const SIZE: usize> RegisterFile<T, SIZE>
    where T: Copy + Default + Debug
{
    pub fn new_init(name: impl ToString, init: T) -> Self { 
        Self { 
            name: name.to_string(),
            data:  [init; SIZE],
            input: [None; SIZE],
        }
    }
    pub fn read(&self, idx: usize) -> T {
        self.data[idx]
    }
    pub fn write(&mut self, idx: usize, val: T) {
        self.input[idx] = Some(val);
    }
}
impl <T, const SIZE: usize> Clocked for RegisterFile<T, SIZE> 
    where T: Copy + Default + Debug
{
    fn name(&self) -> &str { 
        &self.name 
    }
    fn update(&mut self) {
        for idx in 0..SIZE { 
            if let Some(val) = self.input[idx].take() {
                self.data[idx] = val;
            }
            assert!(self.input[idx].is_none());
        }
    }
}



/// Representing a packet of data (moving through the pipeline).
#[derive(Debug)]
pub struct Packet<T, const SIZE: usize> 
    where T: Copy + Default + Debug
{
    data: [Option<T>; SIZE]
}
impl <T, const SIZE: usize> Packet<T, SIZE> 
    where T: Copy + Default + Debug
{
    pub fn new() -> Self { 
        Self { data: [None; SIZE] }
    }

    /// Return the maximum width (number of entries) for this packet.
    pub fn capacity(&self) -> usize { 
        SIZE
    }

    /// Returns true if this packet has no valid entries.
    pub fn empty(&self) -> bool { 
        self.data.iter().all(|x| x.is_none())
    }

    /// Returns true if this packet is filled with valid entries.
    pub fn full(&self) -> bool { 
        self.data.iter().all(|x| x.is_some())
    }

    /// Return the number of valid entries in this packet.
    pub fn len(&self) -> usize { 
        self.data.iter().filter(|x| x.is_some()).count()
    }

    /// Return an iterator over all valid entries in this packet.
    pub fn iter_valid(&self) -> impl Iterator<Item=&T> + '_ {
        self.data.iter().flat_map(|x| x)
    }
}
impl <T, const SIZE: usize> Index<usize> for Packet<T, SIZE> 
    where T: Copy + Default + Debug
{
    type Output = T;
    fn index(&self, idx: usize) -> &Self::Output {
        assert!(idx < SIZE);
        self.data[idx].as_ref().unwrap()
    }
}
impl <T, const SIZE: usize> IndexMut<usize> for Packet<T, SIZE> 
    where T: Copy + Default + Debug
{
    fn index_mut(&mut self, idx: usize) -> &mut Self::Output {
        if self.data[idx].is_none() {
            self.data[idx].insert(T::default())
        } else { 
            self.data[idx].as_mut().unwrap()
        }
    }
}
impl <T, const SIZE: usize> Default for Packet<T, SIZE> 
    where T: Copy + Default + Debug
{
    fn default() -> Self { 
        Self { data: [None; SIZE] }
    }
}

/// A FIFO queue with emulated sequential semantics.
pub struct Queue<T, const CAP: usize> 
    where T: Copy + Default + Debug
{
    data: VecDeque<T>,
    input: Option<T>,
    take: Option<()>,
}
impl <T, const CAP: usize> Queue<T, CAP> 
    where T: Copy + Default + Debug
{
    pub fn new() -> Self {
        Self { 
            data: VecDeque::with_capacity(CAP),
            input: None,
            take: None,
        }
    }

    pub fn dbg_push(&mut self, e: T) {
        self.data.push_back(e);
        self.data.make_contiguous();
    }

    /// Returns the current number of elements in the queue
    pub fn len(&self) -> usize { 
        self.data.len()
    }

    /// Try to return the oldest element in the queue.
    pub fn output(&self) -> Option<&T> {
        self.data.front()
    }

    /// Send a value to-be-enqueued on the next clock edge.
    pub fn submit(&mut self, input: T) {
        self.input = Some(input);
    }

    /// Indicate that the current output has been consumed this cycle,
    /// dequeuing the oldest element on the next clock edge.
    pub fn consume(&mut self) {
        assert!(self.data.len() != 0);
        self.take = Some(());
    }

    /// Send a clock edge to the queue, updating the state.
    pub fn update(&mut self) {
        // Take an entry off the queue
        if self.take.is_some() {
            self.data.pop_front().unwrap();
            self.take = None;
        }

        // Put a new entry onto the queue 
        if let Some(input) = &self.input {  
            assert!(self.data.len() + 1 < CAP);
            self.data.push_back(*input);
            self.input = None;
        }
        self.data.make_contiguous();
    }
}

/// FIFO for [Packet] transactions, with emulated sequential semantics.
///
/// `CAP` indicates the capacity of the queue.
/// `PSIZE` indicates the packet width (consumer *and* producer side).
///
/// TODO: Separate the consumer/producer widths?
///
pub struct PacketQueue<T, const CAP: usize, const PSIZE: usize>
    where T: Copy + Default + Debug
{
    data: VecDeque<T>,
    input: Option<Packet<T, PSIZE>>,
    take: Option<usize>,
}
impl <T, const CAP: usize, const PSIZE: usize> PacketQueue<T, CAP, PSIZE> 
    where T: Copy + Default + Debug
{
    pub fn new() -> Self {
        Self { 
            data: VecDeque::with_capacity(CAP),
            input: None,
            take: None,
        }
    }

    /// Return the number of elements in the queue.
    pub fn len(&self) -> usize { 
        self.data.len()
    }

    /// Returns the current output (the `PSIZE` oldest entries in the queue).
    pub fn output(&self) -> Packet<T, PSIZE> {
        let mut res = Packet::new();
        if self.data.len() >= PSIZE { 
            for idx in 0..PSIZE {
                res[idx] = *self.data.get(idx).unwrap();
            }
        } else {
            for idx in 0..self.data.len() {
                res[idx] = *self.data.get(idx).unwrap();
            }
        }
        res
    }

    /// Producer interface: submit a packet to the queue, to-be-latched on 
    /// the next clock edge.
    pub fn submit(&mut self, input: Packet<T, PSIZE>) {
        self.input = Some(input);
    }

    /// Consumer interface: indicate how many entries have been consumed
    /// from the output for this cycle. Consumed entries are dequeued on
    /// the next clock edge. 
    pub fn consume(&mut self, take: usize) {
        assert!(take < PSIZE);
        assert!(take < self.data.len());
        self.take = Some(take);
    }

    /// Send a clock edge to the queue, updating the state.
    pub fn update(&mut self) {
        // Consume entries from the queue
        if let Some(take) = self.take {
            for _ in 0..take {
                self.data.pop_front().unwrap();
            }
            self.take = None;
        }

        // Push new entries onto the queue
        if let Some(input) = &self.input {  
            assert!(input.len() + self.data.len() < CAP);
            for idx in 0..input.len() { 
                self.data.push_back(input[idx]);
            }
            self.input = None;
        } else { 
            unreachable!("PacketQueue had no input driven this cycle?");
        }

        self.data.make_contiguous();
    }
}

#[cfg(test)]
mod test {
    use super::*;

    // This works fine, but using RefCell makes it ugly :<
    #[test]
    fn clock_domain_test() {
        let mut d = ClockDomain::new("test");
        let myreg: StateRef<Register<u32>> = d.register("reg", 0);
        for _ in 0..8 { 
            let x = myreg.borrow().output();
            println!("{}", x);
            myreg.borrow_mut().submit(x + 1);
            d.update();
        }
    }
}

