.PHONY: clean rtl
clean:
	rm rtl/*
rtl:
	sbt 'runMain zno.elab.Elaborate'
test:
	sbt "testOnly RvSpec"
	#sbt test
