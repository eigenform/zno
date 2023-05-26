//! Abstractions for representing/simulating clocked state in a design. 
//!
//! [ClockedState] is a container used to used to synchronize updates to 
//! simulated clocked components. All clocked components must implement
//! [Clocked], which specifies how the internal state of an object should
//! change at clock edges.
//!
//! Modeling Sequential Logic
//! =========================
//!
//! The idea is to *approximate* the semantics of a behavioral RTL so that 
//! it's a little bit easier to write Rust programs that *look* a something 
//! like a hardware model.
//!
//! Examples
//! ========
//!
//! In general, usage will probably look like this:
//!
//! //```
//! //let mut d = ClockedState::new();
//! //let reg: StateRef<Register<u8>> = d.track(
//! //    Register::<u8>::new("reg", 0)
//! //);
//!
//! //// Simulate some number of clock cycles
//! //for cycle in 0..8 
//! //{
//! //    // Sample the output from a register
//! //    let x = reg.borrow().output();
//!
//! //    // Drive input to a register
//! //    reg.borrow_mut().assign(x.wrapping_add(1));
//!
//! //    // Propagate clock edge to all components in this domain
//! //    d.update(); 
//! //}
//!
//! ```
//!

use std::fmt::Debug;
use std::cell::*;
use std::rc::*;

/// Interface to clocked components. 
pub trait Clocked { 
    /// Returns the unique name of this component. 
    fn name(&self) -> &str;
    /// Simulate a clock edge, mutating some internal state. 
    fn update(&mut self);
}

/// A shared mutable reference to some clocked component.
pub type StateRef<T> = Rc<RefCell<T>>;

/// A container for components sharing the same clock signal. 
pub struct ClockedState {
    cycle: usize,
    name: String,
    /// The set of clocked components being tracked.
    components: Vec<StateRef<dyn Clocked>>,
}
impl ClockedState { 
    /// Create a new clock domain. 
    pub fn new(name: impl ToString) -> Self { 
        Self { 
            cycle: 0,
            name: name.to_string(),
            components: Vec::new(),
        }
    }

    /// Move an object implementing [Clocked] into the container an return 
    /// the resulting [StateRef].
    pub fn track<T>(&mut self, obj: &StateRef<T>)
        where T: Clocked + 'static
    {
        self.components.push(obj.clone());
    }

    pub fn cycle(&self) -> usize {
        self.cycle
    }
}

impl Clocked for ClockedState {
    fn name(&self) -> &str { &self.name }
    fn update(&mut self) {
        for entry in self.components.iter() {
            entry.borrow_mut().update()
        }
        self.cycle += 1;
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
        Self { name: name.to_string(), data: init, input: None }
    }

    /// Returns the current output from this register.
    pub fn output(&self) -> T {
        self.data
    }

    /// Latch some value into this register.
    pub fn assign(&mut self, data: T) {
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
        }
    }
}


#[cfg(test)]
mod test {
    use super::*;

    pub struct MyModule {
        state: ClockedState,
        reg: StateRef<Register<u32>>,
    }
    impl MyModule {
        pub fn new() -> Self {
            let reg = Rc::new(RefCell::new(Register::new_init("reg", 0)));
            let mut state = ClockedState::new("mymodule");
            state.track(&reg);
            let mut res = Self { 
                state,
                reg,
            };
            res
        }
    }
    impl Clocked for MyModule {
        fn name(&self) -> &str { "MyModule" }
        fn update(&mut self) {
            self.state.update();
        }
    }

    #[test]
    fn test() {
        let mut d = ClockedState::new("test");
        let myreg = Rc::new(RefCell::new(
            Register::<u32>::new_init("reg", 0)
        ));
        d.track(&myreg);

        for _ in 0..8 { 
            let x = myreg.borrow().output();
            println!("{}", x);
            myreg.borrow_mut().assign(x + 1);
            d.update();
        }
    }

}

