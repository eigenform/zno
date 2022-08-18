.PHONY: clean rtl
clean:
	rm rtl/*
rtl:
	sbt 'runMain zno.elab.VerilogEmitter'
test:
	sbt test

