
use std::fmt::Debug;
use std::cell::*;
use std::rc::*;

use crate::lle::*;

/// A register. 
#[derive(Clone, Copy)]
pub struct Reg<T: Copy + Default> {
    /// The [instantaneous] value of this register.
    data: T,
    /// The next value of this register (effective at the next clock edge).
    next: Option<T>,
}
impl <T: Copy + Default> Reg<T> {
    /// Create a new register. 
    pub fn new(init: T) -> Self { 
        Self { next: None, data: init }
    }
    pub fn next_as_mut(&mut self) -> &mut T {
        self.next.get_or_insert(T::default())
    }
    /// Drive input to this register.
    pub fn drive(&mut self, val: T) { self.next = Some(val) }
    /// Sample the current value of this register.
    pub fn sample(&self) -> T { self.data }
    /// Sample the current value of this register (as a reference).
    pub fn sample_ref(&self) -> &T { &self.data }
}
impl <T: Copy + Default> Default for Reg<T> {
    fn default() -> Self {
        Self { 
            next: None,
            data: T::default(),
        }
    }
}
impl <T: Copy + Default> Clocked for Reg<T> {
    fn update(&mut self) {
        if let Some(next) = self.next.take() {
            self.data = next;
        }
    }
}




#[cfg(test)]
mod test {
    use super::*;

    pub struct MyModule {
        state: ClockedState,
        reg: StateRef<Reg<u32>>,
    }
    impl MyModule {
        pub fn new() -> Self {
            let reg = Rc::new(RefCell::new(Reg::new(0)));
            let mut state = ClockedState::new();
            state.track(&reg);
            let mut res = Self { 
                state,
                reg,
            };
            res
        }
    }
    impl Clocked for MyModule {
        fn update(&mut self) {
            self.state.update();
        }
    }

    #[test]
    fn reg_increment() {
        let mut r = Reg::new(0u32);
        for i in 0..8 {
            let x = r.sample();
            r.drive(x + 1);
            assert_eq!(x, i);
            assert_eq!(r.sample(), i);
            r.update();
        }
        assert_eq!(r.sample(), 8);
    }


    #[test]
    fn reg_clockedstate() {
        let mut d = ClockedState::new();
        let myreg = Rc::new(RefCell::new(Reg::<u32>::new(0)));
        d.track(&myreg);

        for i in 0..8 { 
            let x = myreg.borrow().sample();
            assert_eq!(x, i);
            myreg.borrow_mut().drive(x + 1);
            d.update();
        }
    }

}

