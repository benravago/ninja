
JDK = "/opt/jdk15"
JAVAC = "$(JDK)/bin/javac"

bin: ../codegen/bin
	mkdir -p bin
	$(JAVAC) -d bin -sourcepath src -cp ../codegen/bin $(shell find src -name '*.java')
	pushd src; find . -type f -not -name '*.java' -exec cp -v --parents {} ../bin ';' ; popd

../codegen/bin:
	$(eval URL := "$(shell dirname $$(git config --get remote.origin.url))")
	pushd .. ; git clone $(URL)/codegen ; cd codegen ; make ; popd

clean:
	rm -fr bin

