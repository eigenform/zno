


pub struct Freelist<const SIZE: usize> {
    data: [bool; SIZE]
}
impl <const SIZE: usize> Freelist<SIZE> {
    pub fn new() -> Self {
        let mut data = [true; SIZE];
        data[0] = false;
        Self { data }
    }

    pub fn num_free(&self) -> usize { 
        self.data.iter().filter(|x| **x).count()
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



