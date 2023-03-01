
_blk0:
	jal x0, _blk1
.balign 32
_blk1:
	jal x0, _blk2
.balign 32
_blk2:
	jal x0, _blk3
.balign 32
_blk3:
	jal x0, _blk0
.balign 32
